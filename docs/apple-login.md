# Sign in with Apple

Apple login is brokered by Keycloak through the `apple` identity provider that
ships in the tessera provider JAR (`plugins/tessera-otp/`, package
`no.losol.keycloak.tessera.apple`). Applications keep talking to Keycloak as
before; Apple is one more button on the Ratio login page, like Google.

The provider is Keycloak's own OIDC broker plus the three things Apple does
differently:

| Apple deviation | What the provider does |
| --- | --- |
| Returns the authorization response as a cross-site **POST** (`response_mode=form_post`) when the name/email scope is requested | Adds a `@POST` handler to Keycloak's broker callback and hands the same parameters to Keycloak's GET handler |
| Client secret is a short-lived **JWT signed with the team's ES256 key**, not a static string | Signs one per token request from the key in the "Client secret" field |
| The user's **name** is only sent once, on first consent, as a `user` form field | Copies first/last name from it onto the brokered identity |

Everything else (nonce, signature via Apple's JWKS, issuer/audience checks,
first-broker-login, account linking, mappers) is stock Keycloak. The endpoints
and issuer are pinned in code to `https://appleid.apple.com`.

## Apple Developer setup

In the Apple Developer account ([Apple's guide](https://developer.apple.com/help/account/capabilities/configure-sign-in-with-apple-for-the-web/)):

1. **App ID** (primary): enable *Sign in with Apple*. The grouping under this
   App ID is what keeps Apple's `sub` stable across your apps and services.
2. **Services ID** for the web login, with *Sign in with Apple* enabled and
   grouped with the App ID. Register the Tessera domain and this return URL:

   ```text
   https://<tessera-host>/realms/<realm>/broker/apple/endpoint
   ```

   Apple requires HTTPS and an exact match, so if Keycloak runs behind a
   path prefix or a different public hostname, copy the redirect URI the
   admin console shows for the provider instead of typing it. Apple does not
   accept `localhost`.
3. **Key** with *Sign in with Apple* enabled, associated with the App ID.
   Download the `.p8` file (Apple shows it once) and note its **Key ID**.
4. Note the **Team ID** (top right in the developer account).

The Services ID is the client id. The key is the only secret.

## Keycloak configuration

Add an identity provider of type **Apple** (provider id `apple`). Use the alias
`apple`: it is part of the callback URL registered with Apple and of the stored
federated identity, so it is permanent once users have logged in.

| Field | Value |
| --- | --- |
| Client ID | the Services ID, e.g. `no.losol.tessera.web` |
| Client secret | the contents of the `.p8` file (PEM; line breaks may be dropped when pasting), or a `${vault.…}` reference |
| Team ID | Apple Team ID |
| Key ID | Key ID of the `.p8` key |
| Trust email | on, see [social-login.md](social-login.md) |
| First login flow | the shared broker flow from [social-login.md](social-login.md) |

Keycloak masks the "Client secret" field in the admin API and console, which
is why the key lives there rather than in a custom field. With Team ID and
Key ID set, Keycloak signs a five-minute client secret from the key on every
token request, so there is no client secret to rotate. Rotating the key itself
means creating a new key at Apple, replacing the Client secret and Key ID, and
revoking the old key; existing logins are unaffected because the identity is
the Apple `sub`, not the key.

If you would rather not store the key in Keycloak, leave Team ID and Key ID
blank and put a ready-made Apple client secret JWT (valid up to six months) in
the Client secret field. It is then sent as-is and must be replaced before it
expires.

The default scope is `name email`. Only the ID token's `email` is used; the
email in the first-consent `user` field is ignored.

## Private relay addresses

Users who choose *Hide my email* log in with an address under
`privaterelay.appleid.com`. Apple relays mail to it, it is verified, and it is
stored as the user's email like any other. It is a different address from the
one the person may have used for email-code login earlier, so such a login
creates a separate account rather than joining the existing one; see the
linking notes in [social-login.md](social-login.md).

## Verifying a deployment

- The login page shows the Apple button; clicking it lands on Apple's consent
  page with the Services ID in the URL.
- After consent, Apple POSTs to `/realms/<realm>/broker/apple/endpoint`. A
  405 here means the provider JAR was built without `META-INF/beans.xml`
  (Quarkus then does not see the POST handler). A 400 "invalid state" means the
  browser did not send Keycloak's auth-session cookie on the cross-site POST,
  which points at a proxy or cookie setting, not at Apple.
- `Unexpected response from token endpoint` with `invalid_client` in the log
  means Apple rejected the client secret: check Team ID, Key ID, the key, and
  that the Services ID is grouped with the App ID the key belongs to.

The provider was verified against Keycloak 26.8.2 with a stand-in Apple
(form_post callback, ES256 client secret, first-consent name, returning login
by `sub` without an email claim, cancel, auto-link to an existing account,
relay address, an ID token without `exp` rejected, and the pre-made client
secret variant).
