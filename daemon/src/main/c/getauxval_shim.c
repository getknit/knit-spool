// SPDX-License-Identifier: AGPL-3.0-or-later
/*
 * `__getauxval` for stock musl (ADR 2026-11.bxce).
 *
 * The JDK's static libraries were compiled with -moutline-atomics, so the aarch64 image carries
 * libgcc's `__aarch64_cas4_acq_rel` and friends, and their constructor reads the LSE bit of
 * AT_HWCAP through `__getauxval`: a glibc-internal name that BellSoft's Alpaquita musl, the one in
 * the NIK build container, exports as well, and stock musl (Alpine, OpenWrt) does not. The binary
 * links in the container and fails to load on the router with "Error relocating knit-spool:
 * __getauxval: symbol not found". This defines it over the public `getauxval`, so the helpers keep
 * choosing between LSE and LL/SC at run time exactly as before. Linked into the aarch64 image only;
 * x86_64 has no outline atomics and no such reference. scripts/native-acceptance.sh checks every
 * undefined symbol of the shipped binary against Alpine's libc and zlib, so the next one is caught
 * before any boot.
 */
#include <sys/auxv.h>

unsigned long __getauxval(unsigned long type) { return getauxval(type); }
