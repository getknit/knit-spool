# Build stage — Gradle needs a JDK; the wrapper pins the Gradle version.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon :daemon:installDist

# Runtime stage — JRE only; ciphertext-on-disk workloads want nothing fancier.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/daemon/build/install/knit-spool/ /app/
RUN mkdir /data && chown 65532:65532 /data
ENV SPOOL_DATA_DIR=/data
VOLUME /data
EXPOSE 9470
USER 65532:65532
# temurin-jre has bash but no curl; /dev/tcp keeps the healthcheck dependency-free.
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s \
  CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/9470 && printf 'GET /healthz HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q ' 200 ' <&3"]
ENTRYPOINT ["/app/bin/knit-spool"]
