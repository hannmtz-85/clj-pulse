# syntax=docker/dockerfile:1.7
#
# The api, the scheduler and the portal (ADR-0030): a jar each, compiled ahead of time, on a JRE image. They differ only in the name
# of the jar and in the namespace it starts, so there is one file for the three:
#
#   docker build -f infra/docker/jvm.Dockerfile --build-arg APP=api -t pulse-api .
#
# The context is the root of the repository; .dockerignore keeps out what must not be in a layer (.env, the history, installed packages).
# The image is made by `docker compose --profile apps build` (bb apps), which gives APP.

# --- the jar --------------------------------------------------------------------------------------------------------------------
FROM clojure:temurin-21-tools-deps-1.12.6.1673-bookworm-slim AS build
ARG APP
RUN test -n "${APP}" || { echo "Say which app: --build-arg APP=api | scheduler | web"; exit 1; }
WORKDIR /src

# What a jar is made of: the build (tools/build.clj and the plan it reads), the libraries of the monorepo and the app. The Maven cache
# is a cache mount, so a change in one app does not download the world again.
COPY deps.edn ./
COPY tools/build.clj tools/build_plan.clj tools/
COPY libs libs
COPY apps/${APP} apps/${APP}
RUN --mount=type=cache,target=/root/.m2 \
    clojure -T:build uber :app :${APP}

# --- the image ------------------------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21.0.13_9-jre-alpine-3.24
ARG APP
LABEL org.opencontainers.image.title="pulse-${APP}" \
      org.opencontainers.image.description="Pulse ${APP}: remote patient monitoring on FHIR R4 with Keycloak single sign-on (synthetic data only)" \
      org.opencontainers.image.licenses="MIT"

# Not root, and with a fixed id so that a policy can name it
RUN addgroup -S -g 10001 pulse && adduser -S -u 10001 -G pulse -H -s /sbin/nologin pulse
WORKDIR /app
COPY --from=build /src/target/${APP}.jar /app/app.jar

# JAVA_FLAGS are the image's: a serial collector and the C1 compiler alone, which a small container and a quick start want (ADR-0030),
# no file of statistics (the file system is read-only), and an exit when the heap is out, which compose restarts. JAVA_OPTS are the
# service's own, such as the size of its heap.
ENV MAIN=pulse.${APP}.main \
    JAVA_FLAGS="-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UsePerfData -XX:+ExitOnOutOfMemoryError" \
    JAVA_OPTS=""
USER pulse

# exec: the JVM is PID 1, so it gets the SIGTERM of `docker stop` and the shutdown hook of the app runs
ENTRYPOINT ["sh", "-c", "exec java $JAVA_FLAGS $JAVA_OPTS -jar /app/app.jar -m $MAIN"]
