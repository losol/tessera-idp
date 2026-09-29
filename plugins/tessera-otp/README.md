# Keycloak Tessera OTP Authenticator

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

## Flow

The authenticator handles both steps:

1. **Email form** — user enters email address
2. **OTP form** — user enters code received via email

If the email doesn't exist in Keycloak, a new user is automatically created. Email is verified on successful OTP entry.

Requires realm SMTP to be configured and brute force protection enabled.
