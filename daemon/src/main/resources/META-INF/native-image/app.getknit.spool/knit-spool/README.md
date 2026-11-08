# native-image metadata for `:daemon`

What GraalVM native-image needs to know about this program that it cannot find by static analysis
(ADR 2026-11.bxce). Picked up automatically from this path when the daemon jar is on the image's
classpath; JSON has no comments, so the reasons live here.

- `resource-config.json`
  - `app/getknit/spool/build-info.properties`: the build stamp `BuildInfo` reads by relative name.
    Without it the native binary reports `version=unknown` at `GET /source` and on `/metrics`, and
    nothing fails. `scripts/native-acceptance.sh` checks the commit at `/source` for this reason.
  - `logback.xml`: logback locates its configuration through the class loader. Without it the native
    binary falls back to logback's basic configurator, which prints at DEBUG and drops the `io.ktor`
    INFO pin that keeps the bearer token out of the log.

The legacy per-file format rather than `reachability-metadata.json`, because the GraalVM-for-JDK-21
builders the Liberica NIK 23.1 container ships predate the combined file.

Everything else, such as logback's appenders and converters, Ktor's unsafe field access and
sqlite-jdbc's JNI surface, comes from the GraalVM reachability metadata repository, which the Gradle
plugin matches to the versions on the classpath. Add a `reflect-config.json` here only for a class of
this program's own, and say why beside it.
