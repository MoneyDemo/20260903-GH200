# ---- build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Dependencies first so Docker can cache them between source-only changes.
COPY pom.xml .
RUN mvn -B -e dependency:go-offline

COPY src ./src
RUN mvn -B package

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /opt/simpleweb

COPY --from=build /workspace/target/simpleweb.jar ./simpleweb.jar

# Build provenance (SHA/time) is baked into the jar at build time by Maven resource
# filtering and read from the classpath, so it is intentionally NOT an env var here:
# APP_BUILD_SHA/APP_BUILD_TIME can never re-stamp what /api/info reports.
ENV SERVER_PORT=8080 \
    APP_ENVIRONMENT=local

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/simpleweb/simpleweb.jar"]
