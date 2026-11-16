# Passkeys

A passkey lets a returning user sign in with the fingerprint, face or PIN of
their device, without waiting for a code by email. It is Keycloak's WebAuthn
passwordless login; the Ratio theme gives it pages that match the rest of the
login, and the email code stays as the way in for everyone else and as the
fallback when a passkey is lost.

Nothing in this repository has to be enabled for it: the theme renders the
pages whenever a realm's flow uses the step. This page is the contract for
the realm setup, which belongs in eventuras-infra.

## How the flow has to look

Keycloak lets a page switch to another step ("try another way") only among
the steps it allows at that point, and **before the user is identified it
does not allow the passkey step**: that step is bound to a user's
credentials, and there is no user yet. The email-code step, on the other
hand, needs no user and is always allowed.

So the passkey step has to come **first**, and the email code second:

```text
browser (copy of the realm's browser flow)
├─ Cookie                                   ALTERNATIVE
├─ Identity Provider Redirector             ALTERNATIVE
└─ forms                                    ALTERNATIVE  (sub-flow)
   ├─ WebAuthn Passwordless Authenticator   ALTERNATIVE   ← first
   └─ Tessera OTP                           ALTERNATIVE
```

Keycloak then renders the passkey step's page as the first thing the user
sees, and the theme renders that page as **the start page**: the passkey
button, the realm's identity providers, and the email code, which switches to
the Tessera OTP step. A realm without passkeys keeps Tessera OTP alone, and
nothing changes for it.

The other way round (Tessera OTP first, passkey second) does not work: the
email-code page would have no way to offer the passkey.

## Realm setup

1. **WebAuthn Passwordless Policy** (Authentication → Policies):

   | Setting | Value | Why |
   | --- | --- | --- |
   | Relying party entity name | the realm's display name | shown by the device when creating the passkey |
   | Relying party ID | the Tessera host, e.g. `id.example.org` | passkeys are bound to it; changing it invalidates every passkey |
   | Require discoverable credential | **Yes** | the step runs before the user is identified, so the device must find the passkey by itself |
   | User verification requirement | **required** | fingerprint, face or PIN, not just possession |
   | Authenticator attachment | not specified | platform passkeys and security keys alike |
   | Signature algorithms | ES256, RS256 | |

2. **Browser flow**: as above, bound to the realm (or to the clients that
   should have passkeys, as a client flow override).

3. **Required action** *Webauthn Register Passwordless*: enabled, **not**
   default. Creating a passkey is offered, never forced (next section).

4. Nothing on the theme. `tessera.login-methods` (per client, see
   [themes/ratio/README.md](../themes/ratio/README.md)) takes `passkey` as
   an entry to order or emphasise it; without the attribute the passkey
   leads and every provider follows.

## Offering a passkey

Keycloak has no "optional" required action, so the offer comes from the
application, at a moment of its choosing (after the first sign-in, after
something the user did that needed signing in), with Keycloak's
application-initiated action:

```text
https://<tessera-host>/realms/<realm>/protocol/openid-connect/auth
    ?client_id=…&redirect_uri=…&response_type=code&scope=openid
    &kc_action=webauthn-register-passwordless
```

The user is sent to the "Sign in faster next time?" page with *Create
passkey* and *Not now*, and comes back to the app either way. Users can also
create or remove passkeys under *Signing in* in the account console. Setting
the required action on a user from the admin console forces it at the next
sign-in, without *Not now*; keep that for cases that call for it.

## What the user sees

| Page | When |
| --- | --- |
| Start page with *Sign in with passkey* | the realm's flow starts with the passkey step |
| The device's own passkey dialog | after the button; Keycloak's own WebAuthn script, unchanged |
| *That did not work with the passkey* | cancelled, no passkey on the device, or a rejected response: *Get a code by email* first, *Try the passkey again* second |
| *How do you want to sign in?* | Keycloak's chooser, only when a step has several alternatives besides the passkey |
| *Sign in faster next time?* | creating a passkey, with *Not now* when the app asked for it |

Keycloak's script asks for a name for the new passkey with a browser
`prompt()`; the theme answers it with the default label (`Passkey`, or
`Passnøkkel` on an nb realm), so the user sees the device's dialog only.

## Limits

- **The passkey is bound to the Tessera host**, not the application's. That
  is right, since the sign-in happens there, and it works from a mobile app
  through the system browser. Moving Tessera to another host invalidates
  every passkey.
- **The email code remains.** A passkey makes signing in faster and
  phishing-resistant for that user, but the account is still reachable with
  a code to its email address.
- **Autofill (conditional UI)**, where the browser suggests the passkey in
  the email field with no button at all, is Keycloak's `passkeys` feature.
  It is a later step, once that feature is stable in the Keycloak version
  this image runs.
- Verified in the theme's smoke suite (`themes/ratio`, `npm run test:smoke`)
  against keycloakify's page mocks. A run against a live Keycloak with a
  real passkey is still needed before enabling it on a realm.
