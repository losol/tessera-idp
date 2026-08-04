# Tessera IDP — custom Keycloak image: tessera-otp provider + Ratio login theme.
# Build context = repo root (reaches both plugins/ and themes/).
#
#   docker build -t ghcr.io/losol/tessera-idp:<tag> .
#
# Consumed by a Keycloak Operator `Keycloak` CR via `spec.image`, so the
# providers (the tessera-otp JAR + the keycloakify theme JAR) are baked in and
# `kc.sh build` runs — the image is "optimized" and needs no runtime mounts.

# Maven's resolver already retries a failed download 3x, but only for the status
# codes in retryHandler.serviceUnavailable — which defaults to 429,503. Maven
# Central answered 502 during the v0.1.0 release build, so the download was not
# retried and ~30 min of emulated multi-arch build died on a momentary blip.
# Widening the set to the whole "try again later" family costs nothing when
# Central is healthy: a real 502 on every attempt still fails the build.
#
# Declared once and consumed by both Maven-using stages, so the two cannot drift.
ARG MAVEN_RETRY_CODES=429,502,503,504

# 1. Build the tessera-otp provider JAR from source.
FROM maven:3-eclipse-temurin-26 AS plugin
ARG MAVEN_RETRY_CODES
WORKDIR /build
COPY plugins/tessera-otp/pom.xml .
COPY plugins/tessera-otp/src ./src
# Tests run here on purpose: AltchaAndroidJsonTest is the build-time guard that
# altcha still works against the shaded android-json org.json implementation.
RUN mvn -q -B -Daether.connector.http.retryHandler.serviceUnavailable="$MAVEN_RETRY_CODES" package

# 2. Build the Ratio login theme (keycloakify → theme JAR).
# keycloakify shells out to `mvn` to package the JAR, so Maven and a JDK must be
# on PATH. They are copied from the same image the plugin stage builds in rather
# than installed with apt, which ships Maven 3.8.7 — old enough to still resolve
# through Wagon, where the retry setting below is silently ignored. A bare stage
# (no RUN) so buildx can still fetch it in parallel with the rest of the build.
FROM maven:3-eclipse-temurin-26 AS maven-dist

FROM node:24 AS theme
ARG MAVEN_RETRY_CODES
COPY --from=maven-dist /usr/share/maven /usr/share/maven
COPY --from=maven-dist /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/usr/share/maven/bin:/opt/java/openjdk/bin:${PATH}"
# keycloakify appends to MAVEN_OPTS rather than replacing it, so this survives
# into the mvn it spawns — there is no command line of ours to put it on.
ENV MAVEN_OPTS="-Daether.connector.http.retryHandler.serviceUnavailable=${MAVEN_RETRY_CODES}"
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
