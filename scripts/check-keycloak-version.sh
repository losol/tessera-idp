#!/usr/bin/env bash
# tessera-idp · SPDX-License-Identifier: MPL-2.0
#
# Guard: the Keycloak version must be identical across every build file.
#
# It lives in three places that cannot share a variable:
#   - Dockerfile                    FROM quay.io/keycloak/keycloak:<v>  (builder stage)
#   - Dockerfile                    FROM quay.io/keycloak/keycloak:<v>  (runtime stage)
#   - plugins/tessera-otp/pom.xml   <keycloak.version>  (compile-time provided deps)
#
# The tag is deliberately written out in both FROM lines (no ARG): Dependabot
# cannot resolve ARG-in-FROM, but updates identical image:tag lines together.
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

docker_vers="$(grep -E '^FROM[[:space:]]+quay\.io/keycloak/keycloak:' "$dockerfile" \
  | sed -E 's|^FROM[[:space:]]+quay\.io/keycloak/keycloak:([^[:space:]@]+).*|\1|')"
pom_ver="$(grep -oE '<keycloak\.version>[^<]+</keycloak\.version>' "$pom" \
  | head -1 | sed -E 's|.*<keycloak\.version>([^<]+)</keycloak\.version>.*|\1|')"

[ -n "$docker_vers" ] || fail "could not find 'FROM quay.io/keycloak/keycloak:<tag>' in Dockerfile"
[ -n "$pom_ver" ]     || fail "could not find '<keycloak.version>' in pom.xml"

if [ "$(printf '%s\n' "$docker_vers" | sort -u | wc -l | tr -d '[:space:]')" != "1" ]; then
  fail "Dockerfile FROM lines disagree on the Keycloak tag: $(printf '%s ' $docker_vers)"
fi
docker_ver="$(printf '%s\n' "$docker_vers" | head -1)"

printf 'Dockerfile  keycloak FROM tag : %s\n' "$docker_ver"
printf 'pom.xml     keycloak.version  : %s\n' "$pom_ver"

if [ "$docker_ver" != "$pom_ver" ]; then
  fail "Keycloak version mismatch ($docker_ver != $pom_ver) — align Dockerfile and pom.xml."
fi

printf 'OK — Keycloak version is consistent (%s).\n' "$docker_ver"
