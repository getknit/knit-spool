# Flags LSE atomics in an AArch64 disassembly (`objdump -d --no-show-raw-insn`), except inside GCC's
# outline-atomics helpers (ADR 2026-11.bxce). The OpenWrt One's Cortex-A53 has no LSE: any such
# instruction the image reaches aborts it. The JDK's static libraries were compiled with
# -moutline-atomics, so the image carries `__aarch64_cas4_acq_rel` and friends, whose bodies pick LSE
# or LL/SC at run time behind a HWCAP byte and are safe:
#
#     adrp  x16, <__aarch64_have_lse_atomics page>
#     ldrb  w16, [x16, #off]
#     cbz   w16, <the LL/SC path>
#     casal w0, w1, [x2]          <- the one LSE instruction, reached only when the byte says so
#     ret
#
# native-image deletes local symbols by default, so the helpers' names are gone from the shipped
# binary and the scan recognises that shape instead of the label: `ldrb w16`, `cbz w16`, the LSE
# instruction, `ret`. Prints every other LSE instruction, and the count on stderr.
/^[0-9a-f]+ <.*>:$/ { labelled_helper = ($0 ~ /<__aarch64_/); next }
{
    insn = $2
    if (pending != "") {
        if (insn != "ret") { print pending; n++ }
        pending = ""
    }
    if (!labelled_helper && insn ~ /^(cas|ldadd|ldclr|ldeor|ldset|ldsmax|ldsmin|ldumax|ldumin|swp)[a-z]*$/) {
        if (prev1 == "cbz w16," && prev2 == "ldrb w16,") pending = $0
        else { print; n++ }
    }
    prev2 = prev1
    prev1 = insn " " $3
}
END {
    if (pending != "") { print pending; n++ }
    print n + 0 > "/dev/stderr"
}
