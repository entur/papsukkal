# Runtime image for Papsukkal (run-once batch CronJob).
#
# The Spring Boot fat jar is built by Maven in CI (`mvn -B -ntp package`), which has access to
# Entur's Maven repository for the `superpom` parent. This image just packages the pre-built jar —
# it does not build, so no Maven credentials are needed at image-build time.
FROM eclipse-temurin:25-jre

# JVM options (heap, file encoding, spring.config.additional-location) are set by the Helm chart
# via JDK_JAVA_OPTIONS at deploy time.

# Run as a non-root user (uid/gid 1000 matches the Helm chart's runAsUser/fsGroup).
RUN groupadd --gid 1000 papsukkal && useradd --uid 1000 --gid 1000 --home-dir /app --no-create-home papsukkal
WORKDIR /app

COPY --chown=papsukkal:papsukkal target/papsukkal.jar /app/papsukkal.jar

USER papsukkal
ENTRYPOINT ["java", "-jar", "/app/papsukkal.jar"]
