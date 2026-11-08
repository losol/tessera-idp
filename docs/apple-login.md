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
| Store tokens | **on** (`storeToken: true`): retains only the refresh token for account-deletion revocation |
| Stored tokens readable | **off** (`addReadTokenRoleOnCreate: false`); the provider also denies broker token retrieval |

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

## Account deletion and Apple token revocation

On a successful Apple sign-in, with **Store tokens on**, the provider keeps
`refresh_token` as the only credential in the user's federated identity token
field in Keycloak's database. Access tokens, ID tokens and profile data are
excluded from that field. Keycloak preserves the previous
refresh token on returning login if Apple omits a replacement. The refresh
token is a long-lived credential: protect the database and its backups, and
restrict realm administration. This storage does not add encryption at rest.
Keycloak's returning-login merge may also serialize default numeric response
fields. The credential is not a user profile attribute. Both broker token
retrieval endpoints return
403, even for holders of the broker client's `read-token` role, and external
token exchange is disabled for this provider.

When an admin deletes the user or the user completes Keycloak's `delete_account`
action, the provider snapshots the Apple link before removal. Only after
successful removal **and database commit** does it make one form-encoded
`POST https://appleid.apple.com/auth/revoke` with:

| Parameter | Value |
| --- | --- |
| `client_id` | The same Services ID that issued the token, e.g. `no.losol.tessera.web`, not the native App ID or Team ID |
| `client_secret` | A fresh five-minute ES256 JWT signed by `AppleClientSecret`, with the same vault/key handling as login; or the configured pre-made JWT |
| `token` | The stored Apple refresh token |
| `token_type_hint` | `refresh_token` |

Apple documents these fields in [Token revocation](https://developer.apple.com/documentation/signinwithapplerestapi/revoke-tokens).
HTTP 200 means revoked or already invalid. A non-200, network error, missing
token or credential configuration failure is logged without tokens, keys,
client-secret JWTs, response bodies or exception messages. Deletion still
completes. The request runs after commit, with five-second connection/pool/read
timeouts and redirects disabled. There is no retry queue; a server crash after
commit can lose the revocation attempt.

With **Store tokens off**, stock Keycloak does not persist Apple's access or
refresh tokens on the federated identity. In **26.8.2**, its OIDC broker still
copies the access, refresh and ID tokens to user-session notes by default:
the [OIDC broker's `authenticationFinished`](https://github.com/keycloak/keycloak/blob/26.8.2/services/src/main/java/org/keycloak/broker/oidc/OIDCIdentityProvider.java)
uses [`IdentityProviderModel.isStoreTokenInSession()`](https://github.com/keycloak/keycloak/blob/26.8.2/server-spi/src/main/java/org/keycloak/models/IdentityProviderModel.java),
whose default is true.
Thus a usable Apple token can remain in an active session even with Store
tokens off. That session can expire or be logged out before deletion, so it
cannot guarantee later revocation. Stock session retention also applies with
Store tokens on; the refresh-token-only reduction described above applies to
the federated identity field. An ID token, Apple `sub`, or Keycloak token cannot
be used at Apple's revoke endpoint. This listener deliberately reads only the
federated identity, without a session fallback. Users who signed in before
storage was enabled have nothing to revoke until they next sign in through Apple. Their
account deletion must still be honored; they can manually remove the app under
their Apple Account's *Sign in with Apple* settings. See Apple's
[account-deletion guidance](https://developer.apple.com/documentation/technotes/tn3194-handling-account-deletions-and-revoking-tokens-for-sign-in-with-apple).
Turning storage off later does not erase already stored tokens; deletion can
still revoke those tokens. Unlinking Apple, deleting a realm or the identity
provider, and deleting a civitas account alone do not trigger this listener.

### eventuras-infra handoff

For the `losol` realm (test in `losol-test` first):

- In `upsertAppleIDP`, change Apple to `storeToken: true` and keep
  `addReadTokenRoleOnCreate: false`. Google stays with Store tokens off.
- Keep provider id and alias `apple`, the original Services ID, `teamId`,
  `keyId`, and the `.p8` signing key in `clientSecret` (or its vault reference).
  The existing login secrets are sufficient; no extra Apple secret is needed.
  A pre-made client-secret JWT must remain unexpired at deletion time too.
- Enable the `delete_account` required action with `defaultAction: false`
  and add the `account` client's `delete-account` role to the realm's default
  role composites, as described in the separate account-deletion infra brief.
- No realm event-listener setting or user/admin event persistence is required.
  The service-loaded Apple factory registers its model-event listener at boot.

The hook is verified against the pinned **Keycloak 26.8.2** sources:
[UserStorageManager](https://github.com/keycloak/keycloak/blob/26.8.2/model/storage-private/src/main/java/org/keycloak/storage/UserStorageManager.java)
publishes `UserPreRemovedEvent` before storage cleanup, and
[UserManager](https://github.com/keycloak/keycloak/blob/26.8.2/server-spi/src/main/java/org/keycloak/models/UserManager.java)
publishes `UserRemovedEvent` only after `removeUser` succeeds. Both the
[admin user resource](https://github.com/keycloak/keycloak/blob/26.8.2/services/src/main/java/org/keycloak/services/resources/admin/UserResource.java)
and [DeleteAccount action](https://github.com/keycloak/keycloak/blob/26.8.2/services/src/main/java/org/keycloak/authentication/requiredactions/DeleteAccount.java)
use that manager. The listener reads the link at the pre-removal event, then
enlists an after-completion transaction on the successful-removal event;
rollback never revokes Apple's authorization. Recheck this ordering on
Keycloak upgrades.

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

Revocation was checked in a disposable Keycloak 26.8.2 runtime with a synthetic
Apple and test-only endpoint/HTTP transport overrides: refresh-token-only
credential storage, returning login without a replacement refresh token, admin
deletion after logout, a 500 response with deletion still committed, tokens-off
session notes with no federated token, and the self-service `delete_account`
confirmation flow. This used an HTTP harness, not manual console UI testing.

For the revocation rollout, verify in a test realm with a stand-in Apple:

- Sign in with Store tokens on; the federated identity token must contain only
  the refresh token. A returning sign-in without a new refresh token must keep
  the previous one. Both broker token endpoints must deny retrieval.
- Delete through the admin console, then through the account console with
  `delete_account` enabled: each committed deletion sends one `/auth/revoke`
  with the stored token, the Services ID and a verifiable client-secret JWT.
- Return 500 from the stand-in: the user is still gone, with one warning and
  no credentials in logs. Deleting an old user without a stored token must
  also complete, with a missing-token warning and no outbound request.
- Finally, verify once against real Apple: sign in, delete the Los ID account,
  and confirm the app is removed from the Apple Account's *Sign in with Apple*
  list. Stand-in tests do not replace this production check.
