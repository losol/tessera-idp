# Apple login through Tessera

Apple terminates at Tessera; applications continue to use the existing Keycloak
OIDC client. Civitas needs no Apple SDK, signing key, callback or code change.
The existing Ratio theme displays the enabled provider with alias `apple`.
Use client attribute `tessera.login-methods=apple,email` to put Apple first, or
leave the attribute unset to show the configured providers normally.

## Broker selection and maintenance

Tessera integrates the Apache-2.0 [klausbetz Apple broker](https://github.com/klausbetz/apple-identity-provider-keycloak),
release [1.17.0](https://github.com/klausbetz/apple-identity-provider-keycloak/releases/tag/1.17.0),
source commit `665011f7ef021850ce5e7a92e33a8bf969c9a29f`. The release upgraded
to Keycloak 26.5; upstream documents compatibility with 26.5 and later. Tessera
targets **26.8.2** (image `26.8.2-0`, already on main through #91).
The older [BenjaminFavre provider](https://github.com/BenjaminFavre/keycloak-apple-social-identity-provider)
was considered; its older Keycloak integration does not establish current SPI
compatibility. Generic OIDC configuration alone does not provide Apple's POST
callback and first-consent `user` handling.

Five upstream source files are vendored under `at/klausbetz/provider`; the
Apache license and exact provenance are included in the provider JAR under
`META-INF/licenses/apple-identity-provider-*` and in the repository NOTICE.
There is no downloaded executable dependency during the build. The upstream
release JAR SHA256 was independently verified as
`4091dee2a1ec9e0771bef4bd46005197d86b0a2b1f25198c41738476b1d102bb`.
Only `TesseraAppleIdentityProviderFactory` is registered, as **`tessera-apple`**.
The upstream `apple` factory and claim mappers are not installed.

One compatibility patch removes the obsolete `@Override` annotation from
`exchangeExternalTokenV1Impl`: Keycloak 26.8 renamed that SPI. The patch is
marked in the source and provenance. The callback also adds `@Provider` and
retains upstream's empty `META-INF/beans.xml` for Quarkus REST resource
discovery; without discovery the actual POST returns 405. These requirements
are described in the [Keycloak developer guide](https://www.keycloak.org/docs/26.8.0/server_development/#_extensions_rest).
The other three source files are unchanged.
`@Vetoed` preserves request-specific callback construction by the broker;
CDI cannot inject its authentication callback and event constructor arguments.
The wrapper blocks both the old method and the current exchange SPI.

The Tessera wrapper requires Keycloak's full ID-token validation, including the
configured Services ID audience and authorized party. Upstream 1.17.0's
`validateToken(String)` explicitly ignores audience; changing its configuration
cannot repair that override. Signature/JWKS and the Apple issuer/endpoints are
pinned. Nonce cannot be disabled: Keycloak 26.8.2 stores the token nonce and
validates it in `preprocessFederatedIdentity`. The upstream POST callback
verifies the authentication session and state before code exchange. Legacy
external token exchange is unsupported, so it cannot bypass Tessera's first
and post broker flows. Native account-console authenticated linking uses the
browser flow and remains available.

On every Keycloak/upstream update, review the source diff and rerun the broker
tests and browser matrix below. Upstream's compatibility table alone is not
acceptance evidence. Tessera owns maintenance of its wrapper and any documented
compatibility patches.

## Apple Developer configuration

1. Enable Sign in with Apple on a primary **App ID** in the Apple Developer
   team. Associate the web login with that App ID; retain the grouping when
   rotating credentials to preserve the stable Apple subject.
2. Register a **Services ID** for the website, enable Sign in with Apple, and
   associate it with that primary App ID. The Services ID is the Keycloak
   provider's `clientId`; it is not the App ID/bundle identifier.
3. Register the public Tessera domain and the exact return URL displayed by
   Keycloak for alias `apple`:

   ```text
   https://<public-idp-host>/realms/<realm>/broker/apple/endpoint
   ```

   Include any actual public context-path prefix. Use HTTPS and an exact host,
   path, realm and alias; no localhost, wildcard, application callback, or
   guessed `/auth` prefix. Check proxy/hostname configuration so Keycloak emits
   this same public URL in `redirect_uri`. Allow Apple's cross-site
   `application/x-www-form-urlencoded` POST through the proxy/WAF. Do not
   disable broker state/nonce checks to fix a proxy or cookie problem.
4. Create a **Sign in with Apple signing key** associated with the primary App
   ID. Record its Key ID and the Team ID and securely download its `.p8` key.
   Store the key only in the IdP's managed secret/configuration system. Never
   put it in a client, realm fixture, Git, browser, ticket, log, or this guide.

See Apple's [web setup](https://developer.apple.com/help/account/capabilities/configure-sign-in-with-apple-for-the-web/)
and [private key setup](https://developer.apple.com/help/account/capabilities/create-a-sign-in-with-apple-private-key/).

## Keycloak configuration contract

Install the image and select **Apple (Tessera)** (`providerId=tessera-apple`),
alias **`apple`**, display name **Apple**, enabled and visible on the login
page. Bind the first and post login flows from [the common broker contract](broker-linking.md)
(`tessera-broker-linking` and `tessera-broker-policy`). Do not use the stock
automatic email-link authenticator as a substitute. Set `syncMode=IMPORT`;
set `trustEmail=false` (verification comes from the validated signed claim).
Attach the shared **Validated broker identity guard** mapper
(`tessera-broker-claims`, `syncMode=IMPORT`) to Apple; it validates the signed
identity and configuration before Keycloak can mutate or natively link users.
the wrapper enforces it so a subsequent Apple login cannot overwrite an
existing email/profile or citizen association. Keep the alias/issuer stable:
returning users resolve by the broker's fixed issuer plus Apple `sub`, not
email. Do not delete/recreate links or migrate aliases by guessing email.

Configure `clientId` as the Services ID, `teamId` and `keyId` as the Apple
identifiers, and `clientSecret` as the raw PKCS#8 `.p8` private key content.
For imported/API configuration, explicitly set `validateSignature=true`,
`useJwksUrl=true`, `jwksUrl=https://appleid.apple.com/auth/keys` and
`disableNonce=false`: Keycloak validates the persisted model before the
provider constructor pins its effective settings.
The inherited broker signs an ES256 client-secret JWT for the token endpoint;
its `iss` is the Team ID, `sub` the Services ID, `aud` Apple's issuer and `kid`
the Key ID. The wrapper keeps generated secrets request-local. Scopes default
to `name email`; the authorization request uses `response_mode=form_post`.
Do not expose the upstream exchange auto-link switch or enable legacy external
token exchange for Apple. Provider token storage is disabled.

The production endpoints are fixed to `https://appleid.apple.com/auth/authorize`,
`/auth/token` and `/auth/keys`; the accepted issuer is
`https://appleid.apple.com`. There is no production fixture-endpoint override.
Deployment declarations and secret injection belong in **eventuras-infra**;
this repository supplies the image/provider and its contract only.

## Email, first consent and existing accounts

Trust only email from the validated ID token with `email_verified` boolean
`true` or exact string `"true"`. A callback's unsigned `user.email`, a browser
field, or `is_private_email` cannot establish verification. Verified Apple
relay addresses, including `privaterelay.appleid.com` and `private.icloud.com`,
are accepted unchanged. Never infer or substitute the underlying address.

The shared policy can automatically link exactly one matching previously
verified ordinary account with no conflicting identity or stronger security
requirement. An unverified existing account, ambiguous match, credentials,
required actions, strict policy or identity conflict cannot be bypassed.
Realm/client/user `tessera.broker.security=strict` keeps the common stricter
policy; any strict requirement wins and unknown values fail closed. Linking a
relay identity to an account under a different address requires authenticated
access to that account through native account linking, including its normal
stronger authentication. No relay-to-original-address matching is attempted.

Apple supplies the POST `user` profile only on first consent. Names from that
profile are optional display data, never identity or authorization evidence;
the email authority remains the signed ID token. The broker reads names after
token validation. Returning login must succeed for the same subject without
`user`, name or email; it preserves the local user ID, email and citizen
association. Missing email on a first attachment cannot prove email ownership.

## Client-secret and signing-key rotation

The broker creates an ES256 client-secret JWT from the configured private key
when exchanging a code; the upstream implementation gives it a 180-day lifetime.
Apple permits a maximum lifetime of six months. Do not operate with a manually
pasted secret close to expiry. Prefer the managed `.p8` signing-key contract,
monitor token endpoint `invalid_client` errors without recording request/response
secrets, and test generation/rotation in a nonproduction realm.

Create a replacement Apple key, update the IdP's secret plus matching Key ID
together, and verify first and returning login using the unchanged Services
ID/App ID grouping. Roll the IdP configuration as required by the deployment
secret mechanism, then revoke the old key after the new one is proven and the
rollback window closes. Schedule expiry alerts if operators intentionally use
pre-generated client-secret JWTs; replace them before their `exp`. Never log a
key, client-secret JWT, authorization code, ID/access/refresh token or full
callback body while diagnosing rotation.

## Private relay delivery

Register all outgoing source domains/addresses in Apple's Sign in with Apple
Email Communication settings. Configure SPF for the exact envelope-sender
domain and DKIM with the registered source domain as Apple requires. Include
the IdP SMTP provider's actual MAIL FROM/bounce domain; registering the visible
From address alone may be insufficient. See Apple's
[relay setup and authentication requirements](https://developer.apple.com/help/account/capabilities/configure-private-email-relay-service/).

Before rollout, send a real IdP message to an Apple relay identity, confirm it
arrives in the user's private inbox, inspect delivery/bounce events without
exposing addresses/tokens, and test disabled-forwarding/failure handling. Test
the configured SMTP source after every sending-domain/provider change. Accepting
a signed relay email in a local fixture does **not** prove Apple mail delivery.

## Acceptance evidence and rollout gate

The automated provider tests and shared deterministic fixture use actual
Keycloak 26.8.2 and synthetic signing keys. Browser coverage must include:

| Case | Required observation |
| --- | --- |
| First consent | Cross-site `form_post` accepted; optional names imported; signed verified email used; no redundant code for trusted email. |
| Returning consent | Same issuer/subject resolves same local account without profile/email; email/citizen association unchanged. |
| Matching account | Only a unique previously verified ordinary account auto-links; conflicting/ambiguous/unverified targets rejected. |
| Relay | Both relay domains accepted as opaque verified addresses; different-address linking requires native authenticated access. |
| Strict/protected | Realm/client/user strict and credentials/required actions cannot be downgraded; normal authenticated linking remains usable. |
| Cancellation | `access_denied` and `user_cancelled_authorize` cancel safely without creating an account/link. |
| Validation failures | Wrong audience/issuer/signature, expired/missing-exp token, missing/wrong nonce, missing/mutated/replayed state and token endpoint errors fail before account/link side effects. |
| Profile failures | Missing optional names works; malformed first-consent profile fails safely; unsigned profile email cannot create a verified link. |
| Exchange | ID-token/code exchange variants fail closed; native authenticated browser linking still works. |

Run provider regressions with `mvn -f plugins/tessera-otp/pom.xml clean package`.
The JUnit cases sign real synthetic RSA ID tokens, exercise inherited nonce
validation and verify ES256 client-secret generation/rotation using synthetic
P8 keys. They do not contact Apple.

The Apple-specific [browser runner](../scripts/apple-broker-browser.py) imports
the shared `broker-browser.py` helper from #92. Start the shared loopback
provider/SMTP fixture and a disposable `apple-fixture` Keycloak 26.8.2 realm
from `broker-realm.py --provider apple`, with the test-only Apple factory JAR,
the Ratio theme and the required claims mapper. Use complete seed names and
admin-visible `citizen_id` attributes as defined by the common fixture. The
fixture factory must never be packaged in the production image.

With `KEYCLOAK_DIST`, `JAVA_HOME` and Maven configured as in
[the common test guide](broker-testing.md), run the fixture and Apple runtime
in separate terminals:

```bash
node scripts/broker-fixture.mjs

BROKER_PROVIDER=apple BROKER_REALM=apple-fixture BROKER_PORT=18083 \
BROKER_FIXTURE_FACTORY=no.losol.keycloak.tessera.apple.AppleFixtureIdentityProviderFactory \
bash scripts/broker-runtime.sh
```

```bash
BROKER_KEYCLOAK_URL=http://127.0.0.1:18083 \
BROKER_REALM=apple-fixture \
python3 scripts/apple-broker-browser.py
```

The runner accepts only loopback fixture realms and uses disposable fixture
admin credentials (override with `BROKER_ADMIN_USERNAME` and
`BROKER_ADMIN_PASSWORD` for that local test realm). It compares account IDs,
emails, verification, profile, required actions, attributes and federated
links before/after each rejection and checks immutable identity on returning
login/auto-link. Never supply production credentials to this test harness.

Synthetic consent uses an explicit fixture-only automatic selector. The real
AO browser navigates through Keycloak and submits Apple's normal `form_post`;
the production broker still validates signature, issuer, audience, state and
nonce. This is deterministic **broker/runtime** evidence. It does not prove
provider-button clicks, keyboard navigation, OTP entry/fallback, or interactive
native account linking when AO input dispatch is unavailable. Keep those
interactive checks open separately; never infer success from input-dispatched
messages or an error page without checking account/link side effects.

For the authenticated native-link **confirmation boundary**, start the disposable
runtime with the additional Google test factory by setting
`BROKER_EXTRA_FIXTURE_FACTORY=no.losol.keycloak.tessera.fixture.FixtureGoogleIdentityProviderFactory`.
Set the same variable when invoking the Apple runner with
`--native-link-boundary`. It authenticates a synthetic target with the Google
broker, requests Keycloak's native `idp_link:apple` action and verifies the
**Linking Apple** confirmation page without account mutation. It deliberately
stops there: Continue/Cancel interactions and completed different-address
linking remain acceptance checks requiring working browser input.

To finish that blocked check manually in the disposable dual-provider realm:

1. Run `--native-link-boundary` to authenticate the target and stop on
   **Linking Apple**. Click **Continue** with working browser input, then choose
   `apple-different` on the synthetic Apple consent page.
2. Verify the existing local user ID, original email and `citizen_id` remain
   unchanged and exactly one Apple link with subject `apple-different` is added
   to that authenticated account. Confirm the RP authenticates that same user.
3. Repeat with a fresh unlinked fixture target and cancel at confirmation or
   Apple consent; verify its account, citizen data and links remain unchanged.
   Repeat against any configured stronger account policy using its normal
   authentication; do not remove required actions or credentials to pass.

For a genuinely cross-site run, keep the synthetic Apple page at
`http://127.0.0.1:18089` and address Keycloak as `http://localhost:18086` (a
distinct site), with `BROKER_KEYCLOAK_URL=http://localhost:18086` and
`--cross-site`. The test-only factory then requires the browser's actual
`Sec-Fetch-Site: cross-site` header on the POST before code exchange. Different
ports on `127.0.0.1` alone are only different origins and are insufficient.

On 2026-11-07 (Europe/Oslo) the clean canonical launcher and Keycloak 26.8.2 completed
47 Maven tests (21 Apple-specific) and all **26 Apple broker/runtime scenarios**
in this runner. Positive RP code-exchange/userinfo results identified the
expected original local account; rejected callbacks preserved full account,
credential-type, required-action, link and citizen-attribute snapshots.
The native confirmation boundary also passed without account mutation.
These results used shared dependency `5870745` and synthetic keys/accounts.
The complete 26-case matrix also passed on a second clean runtime with Apple
on `http://127.0.0.1:18089` and Keycloak on `http://localhost:18086`, with the
test-only cross-site request-header guard enabled. RP identity and full
account/link preservation assertions passed across these distinct sites.
Interactive provider/keyboard/OTP and completed native linking remain blocked
by AO 0.14.9 input dispatch; live Apple consent and relay delivery are unproven.

Live Apple Developer credentials, public HTTPS callback registration, real
consent and actual relay SMTP delivery are **external rollout checks**. Local
fixtures are not proof of those checks. Record the actual browser/provider
matrix, CI head and outstanding live checks in the PR; do not call the issue
complete based only on a mocked provider or unit tests.
