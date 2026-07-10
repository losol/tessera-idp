#!/usr/bin/env bash
# tessera-idp · SPDX-License-Identifier: MPL-2.0
#
# Guard: the Keycloak version must be identical across every build file.
#
# It lives in two ecosystems that cannot share a variable:
#   - Dockerfile                    ARG KEYCLOAK_VERSION=<v>   (builder + runtime image)
#   - plugins/tessera-otp/pom.xml   <keycloak.version>         (compile-time provided deps)
#
# If they drift, the provider compiles against one Keycloak but runs on another —
# a NoSuchMethodError at login time instead of a build failure. This script turns
# that drift back into a build failure. Run it locally or from CI, at any cwd.
#
# Exit: 0 = all versions match · 1 = mismatch, or a version could not be located.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dockerfile="$repo_root/Dockerfile"
pom="$repo_root/plugins/tessera-otp/pom.xml"

fail() { printf 'check-keycloak-version: ERROR: %s\n' "$1" >&2; exit 1; }

[ -f "$dockerfile" ] || fail "Dockerfile not found at $dockerfile"
[ -f "$pom" ]        || fail "pom.xml not found at $pom"

docker_ver="$(grep -E '^ARG[[:space:]]+KEYCLOAK_VERSION=' "$dockerfile" \
  | head -1 | cut -d= -f2 | tr -d '[:space:]')"
pom_ver="$(grep -oE '<keycloak\.version>[^<]+</keycloak\.version>' "$pom" \
  | head -1 | sed -E 's|.*<keycloak\.version>([^<]+)</keycloak\.version>.*|\1|')"

[ -n "$docker_ver" ] || fail "could not find 'ARG KEYCLOAK_VERSION=' in Dockerfile"
[ -n "$pom_ver" ]    || fail "could not find '<keycloak.version>' in pom.xml"

printf 'Dockerfile  KEYCLOAK_VERSION  : %s\n' "$docker_ver"
printf 'pom.xml     keycloak.version  : %s\n' "$pom_ver"

if [ "$docker_ver" != "$pom_ver" ]; then
  fail "Keycloak version mismatch ($docker_ver != $pom_ver) — align Dockerfile and pom.xml."
fi

printf 'OK — Keycloak version is consistent (%s).\n' "$docker_ver"
