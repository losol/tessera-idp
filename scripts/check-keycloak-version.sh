#!/usr/bin/env bash
# tessera-idp · SPDX-License-Identifier: MPL-2.0
#
# Guard: the Keycloak version must agree across every build file.
#
# It lives in three places that cannot share a variable:
#   - Dockerfile                    FROM quay.io/keycloak/keycloak:<v>  (builder stage)
#   - Dockerfile                    FROM quay.io/keycloak/keycloak:<v>  (runtime stage)
#   - plugins/tessera-otp/pom.xml   <keycloak.version>  (compile-time provided deps)
#
# The tag is deliberately written out in both FROM lines (no ARG): Dependabot
# cannot resolve ARG-in-FROM, but updates identical image:tag lines together.
#
# One asymmetry is expected and allowed. Quay republishes a release as
# <version>-<n> when the base image is rebuilt: same Keycloak, new layers
# (26.7.3 and 26.7.3-1 are the same manifest). Maven has no such tag, so
# pom.xml can only ever name the plain version — and Dependabot, which sees
# -1 as newer, will keep proposing it for the Dockerfile. So the rebuild
# suffix is stripped before comparing. A prerelease tag (26.8.0-rc.1) is not
# a rebuild suffix and is left alone.
#
# If they drift, the provider compiles against one Keycloak but runs on another —
# a NoSuchMethodError at login time instead of a build failure. This script turns
# that drift back into a build failure. Run it locally or from CI, at any cwd.
#
# With --print the checks still run, but the only thing written to stdout is the
# agreed-upon upstream version, without any rebuild suffix — so release tooling
# can quote it without re-implementing the parsing this script exists to own.
# Release notes pin the exact image by digest separately; this is just the
# human-readable version printed beside it.
#
# Exit: 0 = all versions match · 1 = mismatch, or a version could not be located.
set -euo pipefail

print_only=false
if [ "$#" -gt 1 ]; then
  printf 'check-keycloak-version: ERROR: too many arguments (only --print)\n' >&2; exit 1
fi
case "${1:-}" in
  --print) print_only=true ;;
  "")      ;;
  *)       printf 'check-keycloak-version: ERROR: unknown argument %s (only --print)\n' "$1" >&2; exit 1 ;;
esac

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
  fail "Dockerfile FROM lines disagree on the Keycloak tag: ${docker_vers//$'\n'/ }"
fi
docker_ver="$(printf '%s\n' "$docker_vers" | head -1)"
docker_upstream="$(printf '%s\n' "$docker_ver" | sed -E 's/-[0-9]+$//')"

if [ "$print_only" = false ]; then
  printf 'Dockerfile  keycloak FROM tag : %s\n' "$docker_ver"
  printf 'pom.xml     keycloak.version  : %s\n' "$pom_ver"
fi

if [ "$docker_upstream" != "$pom_ver" ]; then
  shown="$docker_ver"
  [ "$docker_upstream" = "$docker_ver" ] || shown="$docker_ver (rebuild of $docker_upstream)"
  fail "Keycloak version mismatch: Dockerfile $shown != pom.xml $pom_ver — align Dockerfile and pom.xml."
fi

if [ "$print_only" = true ]; then
  printf '%s\n' "$docker_upstream"
else
  printf 'OK — Keycloak version is consistent (%s).\n' "$docker_upstream"
fi
