# Google and Apple login

Both providers are plain Keycloak identity providers on the realm: Google is
Keycloak's built-in `google` broker, Apple is the small `apple` broker shipped
in the tessera provider JAR ([apple-login.md](apple-login.md)). No Tessera
code decides who may log in; the realm's first-broker-login flow does, with
Keycloak's own authenticators. The Ratio login page shows every enabled
provider, ordered and emphasised per client with `tessera.login-methods`
(see [themes/ratio/README.md](../themes/ratio/README.md)).

Realm and provider declarations belong in eventuras-infra. This page is the
contract they implement.

## Google

Create an OAuth client of type *Web application* in a Google Cloud project
owned by the IdP operators, with this authorized redirect URI:

```text
https://<tessera-host>/realms/<realm>/broker/google/endpoint
```

Add an identity provider of type **Google** with the alias `google`, the
client id and secret, and the settings below. Leave *Hosted domain* empty so
both Gmail and Workspace accounts can log in. Nothing else is needed; the
built-in provider pins Google's endpoints and verifies the ID token against
Google's JWKS.

## Settings shared by Google and Apple

| Setting | Value | Why |
| --- | --- | --- |
| Alias | `google` / `apple` | In the callback URL and in every stored link. Permanent. |
| Trust email | on | New accounts get `emailVerified` from the provider's `email_verified` claim instead of being left unverified, and matching accounts can be linked without an email round trip. Both providers always send the claim. |
| Sync mode | import | The provider fills the profile on first login and never overwrites what the user or an admin changed later. |
| First login flow | `tessera first broker login` (below) | |
| Store tokens | Google: off. **Apple: on** | Apple retains only the refresh token to revoke its authorization when the Los ID account is deleted; see [apple-login.md](apple-login.md#account-deletion-and-apple-token-revocation). |
| Stored tokens readable | off | Apple denies broker token retrieval; the retained refresh token is private to account deletion. |

## First login flow

Users in Tessera are identified by email: the email one-time-code login
creates an account for a verified address, and a social login must join that
account rather than make a second one. Keycloak's default first-broker-login
flow asks the user to confirm and then emails a verification link; for a
provider that already verified the address that is one round trip too many.
This flow instead links automatically when the address matches, and falls back
to Keycloak's email-link verification when it cannot trust the match:

```text
tessera first broker login                  (top-level flow)
├─ Create User If Unique                     ALTERNATIVE
└─ Existing account                          ALTERNATIVE  (sub-flow)
   ├─ Automatically set existing user        ALTERNATIVE
   └─ Verify existing account by email       ALTERNATIVE
```

- **Create User If Unique** (`idp-create-user-if-unique`) creates the account
  when no user has the email. Email, name and `emailVerified` come from the
  ID token.
- **Automatically set existing user** (`idp-auto-link`) links the brokered
  identity to the one existing user with that email. Keycloak only does this
  when the provider is trusted or the token says `email_verified`, and never
  when the email was edited in a review-profile step.
- **Verify existing account by email** (`idp-email-verification`) is the
  fallback: Keycloak emails a link to the address on the existing account.

There is no *Review Profile* step: the realm's user profile decides whether a
missing name must be filled in (the *Verify Profile* required action), the
same as for users created by the email-code login.

Create it with the admin console, or declaratively:

```json
{
  "authenticationFlows": [
    {
      "alias": "tessera first broker login",
      "providerId": "basic-flow",
      "topLevel": true,
      "builtIn": false,
      "authenticationExecutions": [
        { "authenticator": "idp-create-user-if-unique", "requirement": "ALTERNATIVE", "priority": 10, "authenticatorFlow": false, "userSetupAllowed": false },
        { "flowAlias": "tessera existing account", "requirement": "ALTERNATIVE", "priority": 20, "authenticatorFlow": true, "userSetupAllowed": false }
      ]
    },
    {
      "alias": "tessera existing account",
      "providerId": "basic-flow",
      "topLevel": false,
      "builtIn": false,
      "authenticationExecutions": [
        { "authenticator": "idp-auto-link", "requirement": "ALTERNATIVE", "priority": 10, "authenticatorFlow": false, "userSetupAllowed": false },
        { "authenticator": "idp-email-verification", "requirement": "ALTERNATIVE", "priority": 20, "authenticatorFlow": false, "userSetupAllowed": false }
      ]
    }
  ]
}
```

## What this means for accounts

- **Returning users** are found by provider alias and subject (`sub`). A
  changed or missing email at the provider does not change the account.
- **Same email as an existing account** (typically one created by the email
  code): linked on first social login, no second account, nothing on the
  account is overwritten.
- **New email**: a new account, verified, with the name from the provider.
  Someone who later uses the email code with that address lands in this same
  account, because the email code looks users up by email.
- **Different email for the same person** (an Apple relay address, or a
  Google account under another address): a separate account. Keycloak cannot
  know these belong together. Linking them afterwards is Keycloak's account
  console *Linked accounts* page (or the `idp_link` application-initiated
  action) while logged in to the account to keep.

## Trust assumptions

- Google sets `email_verified` for Gmail and Workspace addresses it hosts and
  for other addresses it verified when the Google account was created. The
  flow trusts the claim as [Google documents it](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token).
  If a realm wants to distinguish hosted (`hd` claim) from external addresses,
  that is a small conditional authenticator in the sub-flow above, not a
  provider change; it is not implemented today.
- Apple verifies every address it asserts, including relay addresses.
- `idp-auto-link` links to an existing account even if that account's own
  email was never verified (for example one created by an admin). Realms where
  that matters should put *Confirm link existing account* before it.
