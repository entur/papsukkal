# Runtime image for Papsukkal (run-once batch CronJob).
#
# The Spring Boot fat jar is built by Maven in CI (`mvn -B -ntp package`), which has access to
# Entur's Maven repository for the `superpom` parent. This image just packages the pre-built jar —
# it does not build, so no Maven credentials are needed at image-build time.
# Pinned by digest for reproducible builds; bump deliberately (e.g. via Renovate/Dependabot).
FROM eclipse-temurin:25-jre@sha256:5cf92df78f6dba978777d5cffa3c856e583f86814fde82a6c3534ccdfd794f2f

# JVM options (heap, file encoding, spring.config.additional-location) are set by the Helm chart
# via JDK_JAVA_OPTIONS at deploy time.

WORKDIR /app
COPY target/papsukkal.jar /app/papsukkal.jar

# The eclipse-temurin (Ubuntu 24.04) base already ships a non-root uid/gid 1000 user, so we run as
# it rather than creating another at 1000 (which collides — groupadd exits 4 on a duplicate GID).
# Matches the Helm chart's runAsUser/runAsGroup/fsGroup = 1000.
USER 1000:1000
ENTRYPOINT ["java", "-jar", "/app/papsukkal.jar"]
