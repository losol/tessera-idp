# Tessera IDP

Custom [Keycloak](https://www.keycloak.org/) image that bakes two providers into
an optimized runtime:

- **`plugins/tessera-otp/`** — Keycloak SPI providers (Java/Maven): the email
  one-time-code authenticator and the [Sign in with Apple](docs/apple-login.md)
  and [Discord](docs/discord-login.md) identity providers.
- **`themes/ratio/`** — the Ratio login theme, built with [Keycloakify](https://www.keycloakify.dev/).

Google, Apple and Discord login, and how social logins join existing
accounts, are described in [docs/social-login.md](docs/social-login.md);
passkeys in [docs/passkeys.md](docs/passkeys.md). A fixed login code
for an app store reviewer's account, limited to one client and a date, is
described in [docs/review-accounts.md](docs/review-accounts.md).

Grouped under `plugins/` and `themes/` so more providers or themes can be added
without cluttering the repo root.

## Image

```text
ghcr.io/losol/tessera-idp:<tag>
```

The package is public, so it pulls anonymously — no `docker login`, and no
`imagePullSecret` on a cluster that runs it through the Keycloak Operator:

```bash
docker pull ghcr.io/losol/tessera-idp:latest
```

Published for `linux/amd64` and `linux/arm64`.

## Releasing

Releases are made by [release-please](https://github.com/googleapis/release-please)
from the commits on `main`:

1. After every merge to `main`, release-please keeps one **release PR** open
   ("chore(main): release 0.5.0"), with the next version and the changelog
   worked out from the commit messages since the last release.
2. **Merging that PR is the release.** It tags `v0.5.0`, opens the GitHub
   Release with the changelog, and starts the
   [build workflow](.github/workflows/build.yml), which builds and publishes
   the image and adds the baked-in Keycloak version and the image digest at
   the top of the Release:

   ```text
   → ghcr.io/losol/tessera-idp:0.5.0, :0.5, :latest
   → github.com/losol/tessera-idp/releases/tag/v0.5.0
   ```

Nothing ships until someone merges the release PR, so merge feature PRs
freely and release when it suits. The version comes from the commit types
(the PR title, since PRs are squash-merged): `feat` bumps the minor version,
`fix` and the rest the patch; while on `0.x` nothing bumps the major. The
types and what they land under are in
[release-please-config.json](release-please-config.json); `theme:` is this
repo's own type for login-theme work.

If the build fails after a release PR was merged, the Release exists without
an image: fix the cause and re-run the build on the tag from the Actions tab
(*Build & publish image* → *Run workflow* → the tag).

A tag pushed by hand (`git push origin v0.1.0`) still builds and opens a
Release, with GitHub's notes from the merged PRs; a prerelease suffix
(`v0.2.0-rc.1`) marks it as such and does not move `latest`. The manifest
[.release-please-manifest.json](.release-please-manifest.json) says which
version release-please counts from; after a hand-made release, update it.

Version numbers describe *this image*, not the Keycloak inside it — read the
release notes for that.

## Runtime defaults

The image sets two runtime options as environment variables; a deployment can
override either.

| Variable | Default | Why |
| --- | --- | --- |
| `KC_TRACING_SAMPLER_RATIO` | `0.0` | Tracing is built in (`--tracing-enabled`) so every request gets an OpenTelemetry trace id. The id prefixes the request's log lines and the login theme's error page shows it as the support reference. At `0.0` nothing is exported, so no collector is needed; to export traces, raise the ratio and set `KC_TRACING_ENDPOINT`. |
| `KC_SPI_THEME__DEFAULT` | `ratio` | All unset theme types default to ratio, including pages without a realm (e.g. an unknown realm). The Ratio JAR supplies the login UI and parent-only children of stock `keycloak.v3` (account), `keycloak.v2` (admin), and `keycloak` (email). Realms need no explicit theme overrides. |

To find the log lines for a reference someone reports from the error page:

```bash
kubectl logs -n <namespace> <keycloak-pod> | grep 'traceId=<reference>'
```

## Local build

```bash
docker build -t ghcr.io/losol/tessera-idp:dev .
```

Build context is the repo root; the [Dockerfile](Dockerfile) reaches both
`plugins/tessera-otp/` and `themes/ratio/`.

## CI

The Keycloak version must match between the [Dockerfile](Dockerfile) (runtime
image) and [plugins/tessera-otp/pom.xml](plugins/tessera-otp/pom.xml) (compile
target). Both workflows run a guard that fails on drift; run it locally too:

```bash
scripts/check-keycloak-version.sh
```

The image CI job also runs the default-theme regression smoke against the built
image and official Mailpit. To reproduce locally with Docker:

```bash
docker build -t tessera-idp:ci .
bash scripts/smoke-default-themes.sh tessera-idp:ci
```

The smoke creates and removes a disposable realm with all four theme fields
absent, verifies the master admin theme is unset, checks stock account/admin
HTML and inherited JS, Ratio login/unknown-realm pages, JAR metadata/parents,
and sends a real Tessera OTP through `EmailTemplateProvider` to Mailpit, then
redeems its delivered code. No filesystem theme mounts are used. With Docker
unavailable, the same stdlib HTTP suite can target disposable native Keycloak
and official Mailpit processes (Keycloak must use `KC_SPI_THEME__DEFAULT=ratio`
and the built JARs in `providers/`):

```bash
KEYCLOAK_URL=http://127.0.0.1:8080 MAILPIT_URL=http://127.0.0.1:8025 \
SMTP_HOST=127.0.0.1 SMTP_PORT=1025 \
THEME_JAR="$PWD/themes/ratio/dist_keycloak/keycloak-ratio-theme.jar" \
KC_BOOTSTRAP_ADMIN_PASSWORD=local-only \
python3 scripts/smoke-default-themes.py
```
