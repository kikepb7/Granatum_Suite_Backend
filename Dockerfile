# syntax=docker/dockerfile:1.7
#
# Granatum Suite Backend - application image (feature 006, research.md D-009).
#
# Two stages: a JDK builds the boot jar and splits it into layers; a JRE-only
# Alpine image runs it as an unprivileged user. Tests do not run here: CI runs
# them before building the image, with the Postgres and Docker daemon that
# Testcontainers needs and a `docker build` does not have.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Build scripts first: the Gradle cache mount below keeps dependencies between
# builds, and this order keeps the layer cache useful when only code changes.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY build-logic build-logic
COPY app/build.gradle.kts app/
COPY common/build.gradle.kts common/
COPY features/inventory/build.gradle.kts features/inventory/
COPY features/timetracking/build.gradle.kts features/timetracking/
COPY features/auth/build.gradle.kts features/auth/
COPY features/invoices/build.gradle.kts features/invoices/

COPY app/src app/src
COPY common/src common/src
COPY features/inventory/src features/inventory/src
COPY features/timetracking/src features/timetracking/src
COPY features/auth/src features/auth/src
COPY features/invoices/src features/invoices/src

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :app:bootJar -x test --no-daemon --quiet \
 && cp "$(find app/build/libs -name '*.jar' ! -name '*-plain.jar' | head -1)" /src/app.jar \
 && java -Djarmode=tools -jar /src/app.jar extract --layers --launcher --destination /src/extracted

FROM eclipse-temurin:21-jre-alpine

# FR-014: never root. A fixed uid/gid so volume permissions are predictable.
RUN addgroup -S -g 10001 granatum && adduser -S -u 10001 -G granatum -H -s /sbin/nologin granatum

WORKDIR /app
# Least to most frequently changing, so a code-only change rebuilds one layer.
COPY --from=build --chown=granatum:granatum /src/extracted/dependencies/ ./
COPY --from=build --chown=granatum:granatum /src/extracted/spring-boot-loader/ ./
COPY --from=build --chown=granatum:granatum /src/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=granatum:granatum /src/extracted/application/ ./

USER granatum

# prod explicitly, even though it is also the application's default (D-004).
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

# /actuator/health is outside /api, so the rate limits never apply to it.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=4 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
