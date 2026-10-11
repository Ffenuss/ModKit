# ARM32 JNI and guest session ownership

Implementation checkpoint: `a86175eb93f10a52643278190ffb1d99b4f4b8d2`.
No new production APK has been issued from this checkpoint.

## Implemented behavior

- JNI discovery indexes ARM64 and ARM32 library identities independently. The same export in different ABIs is not an ambiguous binding. Same-ABI ambiguity, overlapping patch ranges and incomplete library indexes fail closed.
- ARM32 accepts bounded, exported ARM-state bodies composed of unconditional MOV/MOVW/MOVT into r0/r1 followed by BX LR. Exact declared JNI types determine the return encoding; long and double use both core registers. The ELF must explicitly identify EABI5 softfp. Thumb, hard-float, unknown conventions, calls, stores and branches do not receive recipes.
- The owned ARM32 fixture supplies six typed constant getters. The independent CPU test covers all eight primitive result types, a long with nonzero upper bits, signed int construction and a nontrivial double.
- Closing a guest fences pending commands and controller publication under one ownership lock. Delayed successful/failed loaders cannot publish into a closed menu. View and callback cleanup runs on the main thread, including when close is called from the worker.
- Selecting another app closes the session before queued actions can enable recipes. Failed restoration retains the closed session instead of silently admitting a replacement guest.
- Successful writes also recheck image identity. Missing modules, wrong process ABI and mismatched image/code have readable reasons. DEX candidates explicitly explain that original Space has no DEX executor and that the existing DEX route requires expert repacking.
- Runtime code lookup rejects virtual addresses wider than the process pointer. Native payload loading checks ELF class as well as machine and byte order.
- Production APK assembly is a separate explicitly requested CI job after ModKit validation. Debug and instrumentation APKs are built internally for tests; no intermediate APK is a release deliverable.

Android softfp convention reference: https://developer.android.com/ndk/guides/abis#armeabi-v7a

## Verification

Space source and device run: https://github.com/Ffenuss/ModKit/actions/runs/37988314195
This run tests `02b4ef7f98bcc9bceb13a34c2250c900d90ac584`; subsequent commit changes only ModKit device assertions.

- Host Java compilation against API35, DEX contract checks and shared engine checks passed.
- Android35 executor: 13 tests, zero failures/errors/skips.
- Android35 guest overlay/panel: 3 tests, zero failures/errors/skips. Includes delayed load/session replacement.
- Downloaded device report ZIP matches GitHub SHA256: `df4b294f984001a780377df8f44ec6154e0f708dffa0cf6500bf664ed2dd1005`.

ModKit run: https://github.com/Ffenuss/ModKit/actions/runs/37988698532
- 450 ModKit unit tests passed, zero failures/errors/skips.
- Lint completed with zero errors (existing warnings remain).
- Independent Unicorn execution: 12 ARM64 and 10 ARM32 vectors passed; memory and preserved registers stayed unchanged.
- Owned Math baseline: 106 checks passed.
- Internal debug/test assembly and payload/alignment/signature checks passed. Final production assembly was skipped deliberately.
- Android29 single APK: 8 tests, zero failures/errors/skips.
- Android35 split APK: 8 tests, zero failures/errors/skips.
- Both device runs confirm six ARM32 and eight ARM64 executable JNI recipes with independent ABI identities; hard-float and missing-convention ELF fixtures yield no recipes.
- DEX overlay scenario completes on both APIs with 11 exact Math prototypes and 44 recorded events each.
- API29 report ZIP SHA256: `880ed72f9a9cee5267d75131258474eb99485e2ea2015f5925634a0bd239cfe7`.
- API35 report ZIP SHA256: `fdf88e0a2d726c66c41d19dec0833b05053567034114afb47b7181726c8d62c0`.
- JVM report ZIP matches GitHub SHA256: `e1dc95bc1c8abec0330c268496a00c33fc320c33a24220e78d3b58fc00c7634d`.

## Release boundary

Status: IMPLEMENTED_BUT_INCOMPLETE for signed pair delivery. These are owned-fixture checks. ARM32 CPU emulation and ELF discovery do not prove Android ARM32 guest execution on a phone, third-party gameplay effects or universal engine support. Live native writes do not provide thread quiescence for arbitrary concurrently executing game code.

The preserved private signing key/credentials and exact authenticated Space predecessor APK are absent. The current upgrade tool requires predecessor SHA256 `6f9cd491a2bc344538a515408d3f9b7682f494eab9caabab7f7dda372d4d79a6`; a different host must not be silently accepted. The repository has no published Space release asset. Updated overlay code requires rebuilding Space, verifying kernel/resource preservation, signing with the preserved key and validating the signed pair on Android before delivery. No substitute key or unsigned installable-release claim is made. Existing 0.0.46 artifacts documented in RELEASE_0.0.46.md belong to its earlier tested commit, not this checkpoint.
