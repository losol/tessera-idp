# Local broker validation

These fixtures are deliberately unauthenticated **local tests**, never a
deployment configuration. Use only synthetic accounts and disposable Keycloak
data. They bind loopback ports 18082 (Google Keycloak), 18089 (OIDC fixture) and
18090 (SMTP sink). The Apple dependent PR uses Keycloak port 18083 and the same
fixture endpoints. No Docker daemon or live provider credentials are required.

## Reproduce

Requirements: JDK 21+, Maven, Node 20+, Python 3, `rg`, the AO desktop/CLI with
this session's Browser panel, and the official Keycloak **26.8.2** distribution.
Inspect/build from the issue branch, then:

```bash
scripts/check-keycloak-version.sh
mvn -B -f plugins/tessera-otp/pom.xml package
# Build the existing theme, once:
cd themes/ratio
npm ci
npm run build-keycloak-theme
cd ../..

# Terminal 1: synthetic RS256 OIDC provider + in-memory SMTP sink
node scripts/broker-fixture.mjs

# Terminal 2: build provider/test classes and start a new disposable runtime
export KEYCLOAK_DIST=/absolute/path/to/keycloak-26.8.2
export JAVA_HOME=/absolute/path/to/jdk-21
bash scripts/broker-runtime.sh

# Terminal 3, in the AO worker session:
python3 scripts/broker-browser.py
```

The launcher creates a new temporary runtime and database; it does not reuse,
delete or alter deployed data. Synthetic local admin credentials are printed
for test assertions, never for a live realm. Stop both processes after testing.
The production JAR is checked for absence of test fixtures. Only a separate
`fixture-only.jar`, assembled from `target/test-classes`, overrides the Google
factory in this runtime. Never copy that JAR to an image or deployed IdP.

The fixture retains Google's actual broker/token validation, while redirecting
authorization/token/JWKS endpoints to loopback. Tokens keep Google's real
issuer and are signed by a fresh local RSA key. Nonce/audience/state come from
the actual Keycloak browser session. Codes are single use; fixture JWKS key IDs
change on restart to avoid stale cached signing keys. Production source has no
fixture issuer/endpoint override or scenario selector.

The runner selects an explicit scenario through the loopback test fixture and
uses `ao browser open` to the normal Keycloak authorization endpoint with
`kc_idp_hint`. The consent page auto-submits its own test form; Google then
returns through its actual callback, first-login, post-login and required-action
paths. This proves browser broker behavior, **not** an interactive Google button
click. No browser claims are trusted by production code.

The runner uses a synthetic loopback admin to isolate independent login sessions
and declare `citizen_id`/`tessera.broker.security` as admin-only profile attributes.
It compares before/after persisted account and provider-link state, including
citizen attributes, and exchanges the callback authorization code at local
Keycloak to verify the authenticated user ID. It waits for the fixture consent
counter followed by AO's load wait before asserting a new case; repeated Apple
form-post callbacks can legitimately have the same URL. Tokens, credential data
and authorization codes are never printed.
The admin helper refuses remote origins and non-fixture realm names. The launcher
defaults are `fixture-admin`/`fixture-admin`; an existing disposable setup can use
`BROKER_ADMIN_USERNAME`/`BROKER_ADMIN_PASSWORD`. Do not use live credentials.

The dependent Apple suite can set `BROKER_EXTRA_FIXTURE_FACTORY` to include the
Google test factory alongside its own factory for native cross-provider linking
tests. Both remain in the separate fixture-only JAR.

For targeted runs:

```bash
python3 scripts/broker-browser.py --cases gmail,returning,changed-email,missing-email,workspace,link-existing
python3 scripts/broker-browser.py --cases unverified,conflict,protected,strict,bad-audience,bad-issuer,bad-nonce,missing-nonce,bad-signature,expired,bad-state,missing-state,token-error,failure
python3 scripts/broker-browser.py --cases external,external-expired,external-existing
```

First attachment tests require fresh synthetic identities. Start a fresh runtime
to repeat the complete suite. Returning cases intentionally reuse the same
subject. The `changed-email` case presents another existing account's email and
must keep the original identity. External cases `external` and
`external-existing` assert the signed email-link confirmation gate: captured mail
is available only at `http://127.0.0.1:18089/mail?to=<synthetic-address>`.
The dedicated tests prove there is no provider attachment/client login before
proof, open the captured link in the session browser, verify the authenticated
local ID and preserved citizen data, and reject reused links. `external-expired`
temporarily uses a one-second action-token lifespan in the disposable realm and
proves an expired link cannot authenticate or change accounts, then restores the
original lifespan. The full default suite includes these cases, ambiguity,
strict account/client/action cases, and provider cancellation/failure.

## Interactive acceptance and live-provider limits

Verified on 2026-11-06: the canonical launcher imported a clean
`google-final-fixture` realm and started Keycloak 26.8.2 on loopback port 18085.
The default Google suite passed **29 cases** with authorization-result subject
checks and persisted account/link/citizen assertions, including external new and
existing account confirmation, expired/reused signed mail links, ambiguity,
strict account/client/action policy, cancellation and token/state failures.
The provider's **26 unit/SPI tests** passed, including native resend mail count
and proof-completion guards. Ratio built successfully with Node 24. The release
JAR contained no fixture classes. These are local signed-fixture results, not
live-provider or interactive input proof.

In the session-owned AO browser, verify Ratio offers Google plus OTP, provider
button and keyboard navigation, new/returning login, matching-account attachment,
external email confirmation, authenticated linking (including another address),
conflicts/ambiguous identities, strict account/client/action rejection,
cancellation/failures and completion through the OTP fallback. Use before/after
admin snapshots to prove local account ID/citizen attributes/link preservation
and absence of mutations for negative cases. Native linking must require the
target account's existing authentication; it must not use an email posted by the
broker to pick a target.

On 2026-11-06 the AO 0.14.9 session Browser panel displayed real Keycloak pages,
but its input path reported dispatched clicks without DOM changes/navigation.
An independent `https://example.com` link reproduced the same issue; direct
`open` worked. `get text` without a selector also failed against the bundled
agent-browser parser; fresh snapshots/element refs were usable for reads.
This is an explicit blocker for interactive acceptance, not a successful test.
Do not replace it with a claim that auto-consent covers button/keyboard/OTP
interaction. A maintainer must repair the input path and rerun those cases.

No local fixture result proves a configured live Google client, consent audience,
real Google credentials, Workspace behavior or production mail delivery. Those
remain operator validation with credentials held only in the IdP. Test code,
documentation and logs contain no live credentials or provider tokens.
