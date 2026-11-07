#!/usr/bin/env bash
# Run against the actual built image, with no filesystem theme mounts/overrides.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image="${1:-tessera-idp:ci}"
run_id="tessera-theme-smoke-$$"
work="$(mktemp -d)"
network="$run_id"
keycloak="$run_id-keycloak"
mailpit="$run_id-mailpit"
cleanup() {
  result=$?
  if [ "$result" -ne 0 ]; then
    docker logs "$keycloak" >&2 || true
    docker logs "$mailpit" >&2 || true
  fi
  docker rm -f "$keycloak" "$mailpit" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  rm -rf "$work"
  exit "$result"
}
trap cleanup EXIT
docker network create "$network" >/dev/null
docker run -d --name "$mailpit" --network "$network" --network-alias mailpit \
  --label "ao.session=${AO_SESSION_ID:-ci}" \
  -p 127.0.0.1::8025 ghcr.io/axllent/mailpit:v1.33.0 \
  --disable-version-check >/dev/null
export KC_BOOTSTRAP_ADMIN_USERNAME=admin
KC_BOOTSTRAP_ADMIN_PASSWORD="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
export KC_BOOTSTRAP_ADMIN_PASSWORD
# Use the image's KC_SPI_THEME__DEFAULT unchanged, so removing it also fails.
# dev-file isolates storage; start-dev reaugments the postgres-optimized image.
docker run -d --name "$keycloak" --network "$network" \
  --label "ao.session=${AO_SESSION_ID:-ci}" \
  -p 127.0.0.1::8080 -e KC_BOOTSTRAP_ADMIN_USERNAME -e KC_BOOTSTRAP_ADMIN_PASSWORD \
  "$image" start-dev --db=dev-file >/dev/null
docker cp "$keycloak:/opt/keycloak/providers/keycloak-ratio-theme.jar" "$work/ratio.jar"
http_port() {
  docker port "$1" "$2" | head -1 | awk -F: '{print $NF}'
}
export KEYCLOAK_URL="http://127.0.0.1:$(http_port "$keycloak" 8080)"
export MAILPIT_URL="http://127.0.0.1:$(http_port "$mailpit" 8025)"
export SMTP_HOST=mailpit SMTP_PORT=1025 THEME_JAR="$work/ratio.jar"
KEYCLOAK_VERSION="$("$root/scripts/check-keycloak-version.sh" --print)"
export KEYCLOAK_VERSION
python3 "$root/scripts/smoke-default-themes.py"
