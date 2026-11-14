# Login with Discord

Discord login is brokered by Keycloak through the `discord` identity provider
that ships in the tessera provider JAR (`plugins/tessera-otp/`, package
`no.losol.keycloak.tessera.discord`). Applications keep talking to Keycloak
as before; Discord is one more button on the Ratio login page, like Google
and Apple.

Discord is an OAuth 2.0 provider, not an OpenID one: there is no ID token and
no discovery document. The provider is Keycloak's generic OAuth 2.0 broker
with the Discord endpoints pinned in code, and it reads the user from
`https://discord.com/api/users/@me` after the code exchange, the way
Keycloak's own GitHub provider does. It asks for the scopes `identify` (id
and username) and `email`; nothing about servers or connections.

## Discord Developer Portal setup

In the [Discord Developer Portal](https://discord.com/developers/applications),
on an application owned by the IdP operators (a team, not a personal account):

1. **OAuth2 → Redirects**: add exactly

   ```text
   https://<tessera-host>/realms/<realm>/broker/discord/endpoint
   ```

   Copy the redirect URI from the admin console's provider page if Keycloak
   runs behind a path prefix or another public hostname: Discord matches it
   exactly. Discord accepts `http://localhost` redirects for development.
2. Note the **Client ID** and generate a **Client Secret** (shown once).

The application's name and icon are what the user sees on Discord's consent
screen, so name it after the service, e.g. the municipality's citizen portal.

## Keycloak configuration

Add an identity provider of type **Discord** (provider id `discord`). Use the
alias `discord`: it is part of the callback URL registered with Discord and
of every stored link, so it is permanent once users have logged in.

| Field | Value |
| --- | --- |
| Client ID | the application's Client ID |
| Client secret | the Client Secret, or a `${vault.…}` reference |
| Trust email | **off**, see below |
| Sync mode | import |
| First login flow | `tessera first broker login`, see [social-login.md](social-login.md) |
| Store tokens | off; nothing reads them |
| Stored tokens readable | off |

Nothing else is needed: the endpoints and scopes are in the code.

## Email and account linking

Users in Tessera are identified by email, and a social login must join the
account with that email rather than create a second one. For Discord the
one thing to know is that **a Discord email may be unverified**: Discord
reports `verified` next to the address, and a user can sign up without ever
confirming it.

The provider passes the address on as Discord gives it and marks it verified
only when Discord says so. With *Trust email* off, Keycloak's
first-broker-login flow then behaves like this:

| Discord address | Existing account with that email | Result |
| --- | --- | --- |
| verified | yes | linked automatically, nothing on the account changes |
| verified | no | a new account, email verified, name from the display name |
| unverified | yes | Keycloak emails a link to the existing account's address; linked once it is clicked |
| unverified | no | a new account with an unverified email |
| none (scope refused) | — | a new account without an email |

This is why *Trust email* stays off for Discord: with it on, an unverified
address would link to an existing account on Discord's word alone.

A user whose Discord email differs from their account's email gets a separate
account. To join the two, link Discord **from the account they want to keep**
(next section).

## Linking Discord to an account you are logged in to

Two built-in ways, no Tessera code involved:

- **Account console** (`/realms/<realm>/account`, *Linked accounts*): the
  user clicks *Link* next to Discord.
- **From the application**, with a "Connect Discord" button: Keycloak's
  [client-initiated account linking](https://www.keycloak.org/securing-apps/oidc-layers#_client_initiated_account_linking).
  The app sends the logged-in user to

  ```text
  https://<tessera-host>/realms/<realm>/broker/discord/link
      ?client_id=<client-id>&redirect_uri=<back-to-app>&nonce=<random>&hash=<hash>
  ```

  where `hash` is `base64url(sha256(nonce + session_state + client_id + "discord"))`,
  computed from the user's token. The client needs the `manage-account-links`
  role of the `account` client (Keycloak grants it to confidential clients;
  a public client must be given it explicitly) and the redirect URI must be
  one of the client's valid redirect URIs. Keycloak links the Discord
  identity to the current user and sends them back; if that Discord account
  is already linked to someone else, it says so instead.

Either way the link is by Discord's permanent user id, so the user can later
log in with the Discord button even when their Discord email differs from the
account's email.

## What the realm gets

| Discord field | Keycloak | Note |
| --- | --- | --- |
| `id` | the brokered identity | permanent; usernames change |
| `username` | username | the realm's *email as username* setting wins where it applies |
| `global_name` | first and last name | the display name, split at the last space; empty if unset, and the profile step asks |
| `email`, `verified` | email, `emailVerified` | see above |
| `avatar`, `locale`, … | not imported | available to an attribute mapper of type *Attribute Importer* on the provider |

Token exchange from an external Discord token is disabled.

## Verification

`mvn -B package` in `plugins/tessera-otp` runs `DiscordUserTest` (what the
profile becomes) and `DiscordIdentityProviderTest` (the profile call against a
local stand-in for Discord, with the pinned URLs and the bearer token).

Before enabling it on a realm, test in a test realm with a Discord
application of its own: a verified-email login that joins an email-code
account, an unverified-email login that gets the email link, and the link
from the account console.
