# Build stage — Gradle needs a JDK; the wrapper pins the Gradle version.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon installDist

# Runtime stage — JRE only; ciphertext-on-disk workloads want nothing fancier.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/install/knit-spool/ /app/
EXPOSE 9470
USER 65532:65532
ENTRYPOINT ["/app/bin/knit-spool"]
