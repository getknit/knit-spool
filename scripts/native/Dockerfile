# The toolchain the aarch64 musl native binary is built with (ADR 2026-11.bxce).
#
# BellSoft's Liberica NIK on Alpaquita (musl) is the one native-image distribution that runs on a
# musl aarch64 host, and native-image cannot cross-compile, so this is the only place that binary can
# come from. The image is built locally by scripts/native/build.sh on every run (seconds; the layer
# is tiny) and never published: a FROM line here is what Dependabot's docker ecosystem can bump, where
# an image reference inside a shell script is not.
#
# Pin by digest when bumping, with the tag kept beside it for the reader; the tag moves under you and
# a native build is only as reproducible as its toolchain.
FROM bellsoft/liberica-native-image-kit-container:jdk-21-nik-23.1.12.1-musl@sha256:7e1ee134f399792b726caaf352713c27cccf7a2277d4ea821fbe74dba6ef91fa

# What native-image links with, and what the acceptance script's static checks read. `apk` is a
# no-op for anything the base image already carries.
RUN apk add --no-cache bash binutils gcc linux-headers musl-dev zlib-dev
