# Keycloak Tessera providers

Two providers in one JAR:

- **`tessera-otp`** — the passwordless one-time-code authenticator described below.
- **`apple`** — a Sign in with Apple identity provider, see
  [docs/apple-login.md](../../docs/apple-login.md). It is Keycloak's OIDC broker
  with Apple's `form_post` callback, signed client secret and first-consent
  name handling added. `META-INF/beans.xml` is there for it: without it Quarkus
  does not index the JAR and the callback's POST handler answers 405.

## Tessera OTP

Passwordless one-time-code authenticator for Keycloak. User enters email, receives a 4-character code, enters it, and is logged in. New users are automatically created. The name is channel-agnostic so additional delivery channels (e.g. SMS) can be added later without renaming.

## Build

```bash
mvn package
```

Produces `target/keycloak-tessera-otp.jar`.

## Deploy

Upload JAR as a ConfigMap and mount in Keycloak:

```bash
kubectl create configmap keycloak-tessera-otp-jar -n kursinord-keycloak-prod \
  --from-file=target/keycloak-tessera-otp.jar \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl delete pod -n kursinord-keycloak-prod keycloak-0
```

## Configuration

Provider ID: `tessera-otp`

| Setting | Default | Description |
|---|---|---|
| code-length | 6 | Characters in OTP code |
| code-lifetime | 300 | Code validity in seconds |
| code-alphabet | `0123456789` | Characters used to generate the code |
| altcha-hmac-key | *(blank)* | HMAC secret for the ALTCHA proof-of-work on the email step. Blank disables the captcha entirely |
| altcha-cost | 5000 | PBKDF2 iterations per ALTCHA attempt. Higher is more work for bots and a slower solve on slow phones; applies to challenges issued after the change |
| review-accounts | *(blank)* | Fixed login codes for prepared review accounts, `email=code` entries separated by commas or newlines; codes at least 8 digits, users must exist. No email is sent to these addresses. Off unless the next two are also set. See [docs/review-accounts.md](../../docs/review-accounts.md) |
| review-client-ids | *(blank)* | The only client IDs the review codes work for (exact match) |
| review-valid-until | *(blank)* | When the review codes stop working: `2026-12-01` (whole day, UTC) or `2026-12-01T12:00:00Z` |

## Flow

The authenticator handles both steps:

1. **Email form** — user enters email address
2. **OTP form** — user enters code received via email

If the email doesn't exist in Keycloak, a new user is automatically created. Email is verified on successful OTP entry.

Requires realm SMTP to be configured and brute force protection enabled.
