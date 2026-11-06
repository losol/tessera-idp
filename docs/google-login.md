# Google login through Tessera

Google is offered alongside email OTP by Keycloak's **built-in `google` broker**
and the existing Ratio theme. Civitas keeps its current Tessera issuer and
application callback; no Google client secret or provider callback belongs in
Civitas. Complete the [shared broker contract](broker-linking.md) first.

## Google client and consent

In a Google Cloud project owned by the IdP operators, configure Google Auth
Platform branding, audience and data access, then create an OAuth client of
type **Web application**. Configure the public application name, support email,
authorized domains, privacy-policy/terms URLs and developer contact. Use only
`openid email profile`; do not request offline access or Google API scopes for
login. An external testing consent app requires explicit test users; arrange
production publishing/verification where Google requires it. An internal app
restricts access to its Workspace organization, so use an external audience if
the service must accept ordinary Gmail accounts too.

Register exactly this authorized redirect URI, substituting the deployed
Tessera HTTPS origin and realm:

```text
https://<tessera-host>/realms/<realm>/broker/google/endpoint
```

If the IdP has an intentional HTTP relative path, include that prefix before
`/realms`. Copy the redirect URI displayed by Keycloak rather than guessing the
host/proxy path. The alias **`google`** is part of the callback and permanent
identity binding. This is the **broker** callback, not Civitas's callback.

Create the Keycloak identity provider with provider ID `google`, alias `google`,
display name `Google`, the Google client ID and client secret, enabled. Configure
the shared first/post flows and claims mapper. Set default scopes
`openid email profile`, `disableUserInfoService=true` so first attachment uses
the validated ID token email, `validateSignature=true`, `useJwksUrl=true`,
JWKS URL `https://www.googleapis.com/oauth2/v3/certs`, `disableNonce=false`,
`syncMode=IMPORT`, `trustEmail=false`, `storeToken=false` and offline access off.
The built-in provider pins Google's issuer/endpoints. Leave Hosted Domain blank
for both consumer and Workspace login; set it only for an intentional domain
restriction. A browser-requested `hd` hint never proves Workspace membership.

The validated token determines email authority: verified `gmail.com`, or verified
email with a nonempty `hd`, can skip another email code. A Google account for an
external address without `hd` needs fresh local email proof on initial attachment,
even when its token says `email_verified=true`. See
[Google's email trust rules](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token).

## Presentation and operations

Keep the client's existing issuer/callback unchanged. Set the client attribute
`tessera.login-methods=google,otp` to lead with Google and retain the OTP link,
or `otp,google` to lead with email. No theme fork or Google JavaScript SDK is
needed. This attribute only changes presentation; enforce stricter client
requirements through the shared server policy and existing authentication flows.

Store the Google secret only in the IdP's managed secret store/Keycloak provider
configuration, provisioned by **eventuras-infra**. Never commit it into this
repository, client-side code, realm exports, examples or CI logs. Rotate through
the provider console and secret store with an explicit tested cutover; restrict
Google project and Keycloak administration. Avoid raw token/request-body logging.
Back up account and federated-identity associations before operational changes.

Before enabling production traffic, test the exact HTTPS callback, consent
audience, reverse-proxy host settings, SMTP delivery, cancellation, an actual
Gmail and Workspace identity, and an external-address Google account. Verify
the target local account/citizen ID is preserved when linking. Stricter users
must use the existing stronger login and native authenticated linking.

## Evidence boundaries

The repository provides unit/SPI tests plus a signed local broker/SMTP fixture
and an AO browser runner described in [broker testing](broker-testing.md).
Fixture keys, subjects and addresses are synthetic; fixture provider classes
are under `src/test` and absent from the release JAR. They retain the built-in
Google validation implementation while redirecting only test endpoints to
loopback. They are never a deployment option.

Local fixture proof does **not** establish a live Google client/consent setup,
Google network behavior, real Workspace configuration or production mail
delivery. Those checks require operator-managed credentials/configuration.
