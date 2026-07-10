# Tessera IDP

Custom [Keycloak](https://www.keycloak.org/) image that bakes two providers into
an optimized runtime:

- **`plugins/tessera-otp/`** — Keycloak SPI provider (Java/Maven) for OTP.
- **`themes/ratio/`** — the Ratio login theme, built with [Keycloakify](https://www.keycloakify.dev/).

Grouped under `plugins/` and `themes/` so more providers or themes can be added
without cluttering the repo root.

## Image

```text
ghcr.io/losol/tessera-idp:<tag>
```

## Releasing

Releases are **tag-driven**. Push a semver tag and the [build workflow](.github/workflows/build.yml)
builds and publishes the image:

```bash
git tag v1.0.0
git push origin v1.0.0
# → ghcr.io/losol/tessera-idp:1.0.0, :1.0, :latest
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
