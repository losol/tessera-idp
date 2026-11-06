#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Disposable LOCAL TEST runtime only. Never run against a deployed installation.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${KEYCLOAK_DIST:?Set KEYCLOAK_DIST to an unpacked Keycloak 26.8.2 distribution}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 21 or a supported newer JDK}"
theme_jar="${BROKER_THEME_JAR:-themes/ratio/dist_keycloak/keycloak-ratio-theme.jar}"
test -f "$theme_jar" || { echo 'Build the Ratio theme JAR first.' >&2; exit 1; }
"${MVN:-mvn}" -B -f plugins/tessera-otp/pom.xml package
runtime_dir="$(mktemp -d "${TMPDIR:-/tmp}/tessera-broker-runtime.XXXXXX")"
cp -R "$KEYCLOAK_DIST/." "$runtime_dir/"
cp plugins/tessera-otp/target/keycloak-tessera-otp.jar "$runtime_dir/providers/"
cp "$theme_jar" "$runtime_dir/providers/"
if "$JAVA_HOME/bin/jar" tf plugins/tessera-otp/target/keycloak-tessera-otp.jar | rg -q 'Fixture|/fixture/'; then
    echo 'Production JAR contains fixture classes; refusing to start.' >&2; exit 1
fi
fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/tessera-broker-testjar.XXXXXX")"
factory="${BROKER_FIXTURE_FACTORY:-no.losol.keycloak.tessera.fixture.FixtureGoogleIdentityProviderFactory}"
factory_path="${factory//.//}"
mkdir -p "$fixture_dir/$(dirname "$factory_path")" "$fixture_dir/META-INF/services"
cp plugins/tessera-otp/target/test-classes/"${factory_path}"*.class "$fixture_dir/$(dirname "$factory_path")/"
printf '%s\n' "$factory" > "$fixture_dir/META-INF/services/org.keycloak.broker.social.SocialIdentityProviderFactory"
if test -n "${BROKER_EXTRA_FIXTURE_FACTORY:-}"; then
    extra_path="${BROKER_EXTRA_FIXTURE_FACTORY//.//}"
    mkdir -p "$fixture_dir/$(dirname "$extra_path")"
    cp plugins/tessera-otp/target/test-classes/"${extra_path}"*.class "$fixture_dir/$(dirname "$extra_path")/"
    printf '%s\n' "$BROKER_EXTRA_FIXTURE_FACTORY" >> "$fixture_dir/META-INF/services/org.keycloak.broker.social.SocialIdentityProviderFactory"
fi
"$JAVA_HOME/bin/jar" --create --file "$runtime_dir/providers/fixture-only.jar" -C "$fixture_dir" .
rm -rf "$fixture_dir"
mkdir -p "$runtime_dir/data/import"
python3 scripts/broker-realm.py --provider "${BROKER_PROVIDER:-google}" --realm "${BROKER_REALM:-broker-fixture}" > "$runtime_dir/data/import/fixture-realm.json"
echo "Disposable runtime: $runtime_dir"
echo 'Local test admin: fixture-admin / fixture-admin (synthetic, loopback only)'
export KC_BOOTSTRAP_ADMIN_USERNAME=fixture-admin KC_BOOTSTRAP_ADMIN_PASSWORD=fixture-admin
exec "$runtime_dir/bin/kc.sh" start-dev --http-host=127.0.0.1 --http-port="${BROKER_PORT:-18082}" --import-realm
