# Build stage — Gradle needs a JDK; the wrapper pins the Gradle version.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
# The build stamp the daemon reports at runtime (startup log, GET /source, knit_spool_build_info).
# Passed in, not discovered: .dockerignore excludes .git, so there is no repository in this context
# to ask, and an image built without them honestly says "unknown".
#   docker build --build-arg SPOOL_COMMIT="$(git rev-parse HEAD)" -t knit-spool .
ARG SPOOL_VERSION=""
ARG SPOOL_COMMIT=""
# in-process compilation keeps the Kotlin compile off a forked daemon. That daemon writes lock
# files under /tmp and deletes them on exit, which races kaniko's filesystem snapshot in CI
# ("Failed to get file info for /tmp/kotlin-daemon.*.log.lck"). It is also leaner, which suits a
# build stage that is thrown away.
#
# A positional-parameter list so each optional -P is one line: an empty ARG has to vanish from the
# command line entirely, not arrive as -PspoolVersion= and stamp a blank version.
RUN set -- --no-daemon -Pkotlin.compiler.execution.strategy=in-process; \
    if [ -n "$SPOOL_VERSION" ]; then set -- "$@" "-PspoolVersion=$SPOOL_VERSION"; fi; \
    if [ -n "$SPOOL_COMMIT" ]; then set -- "$@" "-PspoolCommit=$SPOOL_COMMIT"; fi; \
    ./gradlew "$@" :daemon:installDist

# Runtime stage — JRE only; ciphertext-on-disk workloads want nothing fancier.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/daemon/build/install/knit-spool/ /app/
# -p, not a bare mkdir: kaniko processes the VOLUME below during stage setup and creates /data
# before this RUN executes, so a bare mkdir fails with "File exists" there while succeeding
# under docker build. -p is idempotent and correct for both.
RUN mkdir -p /data && chown 65532:65532 /data
ENV SPOOL_DATA_DIR=/data
VOLUME /data
EXPOSE 9470
USER 65532:65532
# temurin-jre has bash but no curl; /dev/tcp keeps the healthcheck dependency-free.
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s \
  CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/9470 && printf 'GET /healthz HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q ' 200 ' <&3"]
ENTRYPOINT ["/app/bin/knit-spool"]
