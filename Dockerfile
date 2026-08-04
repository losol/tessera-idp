# Tessera IDP — custom Keycloak image: tessera-otp provider + Ratio login theme.
# Build context = repo root (reaches both plugins/ and themes/).
#
#   docker build -t ghcr.io/losol/tessera-idp:<tag> .
#
# Consumed by a Keycloak Operator `Keycloak` CR via `spec.image`, so the
# providers (the tessera-otp JAR + the keycloakify theme JAR) are baked in and
# `kc.sh build` runs — the image is "optimized" and needs no runtime mounts.

# 1. Build the tessera-otp provider JAR from source.
FROM maven:3-eclipse-temurin-26 AS plugin
WORKDIR /build
COPY plugins/tessera-otp/pom.xml .
COPY plugins/tessera-otp/src ./src
# Tests run here on purpose: AltchaAndroidJsonTest is the build-time guard that
# altcha still works against the shaded android-json org.json implementation.
#
# Maven's resolver already retries a failed download 3x, but only for the status
# codes in retryHandler.serviceUnavailable — which defaults to 429,503. Maven
# Central answered 502 during the v0.1.0 release build, so the download was not
# retried and ~30 min of emulated multi-arch build died on a momentary blip.
# Widening the set to the whole "try again later" family costs nothing when
# Central is healthy: a real 502 on every attempt still fails the build.
RUN mvn -q -B -Daether.connector.http.retryHandler.serviceUnavailable=429,502,503,504 package

# 2. Build the Ratio login theme (keycloakify → theme JAR).
# keycloakify packages the JAR with Maven, so install it (+ a JDK) here.
FROM node:24 AS theme
RUN apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /theme
COPY themes/ratio/package.json themes/ratio/package-lock.json ./
RUN npm ci
COPY themes/ratio/ ./
RUN npm run build-keycloak-theme

# 3. Bake providers into Keycloak and run the build step.
# db/health/metrics are build-time options — bake them so the optimized image
# can start with --optimized (the operator requires health for its probes).
FROM quay.io/keycloak/keycloak:26.7.0 AS builder
COPY --from=plugin /build/target/keycloak-tessera-otp.jar /opt/keycloak/providers/
COPY --from=theme /theme/dist_keycloak/keycloak-ratio-theme.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build \
    --db=postgres \
    --health-enabled=true \
    --metrics-enabled=true

# 4. Optimized runtime image.
FROM quay.io/keycloak/keycloak:26.7.0
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
