# Signed Space runtime update — 2026-10-10

Packaging code: b693bd44b368f6732c416046804fdaad679635b4.
PR: https://github.com/Ffenuss/ModKit/pull/31

## Exact source and update

Input: user-supplied ModKit-Space-v0.0.37-new-key.apk.
Input SHA256: ec3ffc685880b27ad23b3305334a992dad313ce67ff7690420ffc7d0e2f22870.
Input and output certificate SHA256:
b44a6c2b53689c16cb08da3ca22e4aba20d9d0d1ecbc26df13e15c04b6665073.

Output: ModKit-Space-v0.0.37-runtime-update.apk, 21760952 bytes.
Output SHA256: 10e8fbf9b99e130f44c842556faee9260b43b735ca1574ce9d5921e9128353b2.
Companion: signed ModKit 0.0.46, SHA256
42f1358f4837b7eaba1aa6d4835aea18593a8294fc103ce721e3fdcf07d36b75.

The source is accepted only by its exact hash paired with its single pinned
signer. The legacy hash/signer pairing remains required on the legacy route.
Unknown hashes, cross-paired signatures, duplicate entries and kernel/resource
changes remain rejected. Signing reuses the provided preserved key locally.
The output has the same package and signer, so it is an update of the supplied
Space, not a migration to another signer. No uninstall is required for that
Android package/signature relationship.

Exactly three decompressed payloads changed: classes4.dex,
assets/modkit-space-engine.apk, assets/modkit-space-engine.sha256.
Every other non-signature entry is byte-identical, including all bootstrap DEX,
ARM64 native kernel files, binary manifest and resources. No guest APK changed.
ZIP and page alignment checks passed; the output signature verified with the
expected single certificate. The original manifest package
com.dualspace.multispace.androidx, versionCode 99/versionName 9.9.9.99,
minSdk 21 and targetSdk 33 are preserved. The filename denotes a runtime refresh;
it is not a new manifest version. The ModKit pair targets Android 8/API26+.

## Compiled payload evidence

The host overlay and engine carrier came from verified artifact 11644683081 of
https://github.com/Ffenuss/ModKit/actions/runs/37990542252, tested source
9b258720c4c1a4e35ca3d286f91bb24a2de950a1. Their source files are unchanged at
the packaging commit. Artifact ZIP SHA256:
4503d5d32d1e641e54198f15583a37055ec56d342c27e0ed2bb82f8d11f77fd9.
Engine carrier SHA256:
f79908643cc7a73d3b9800db77554405b375731e6fc817c2b14357ebd54636b5.
That checkpoint includes the closed-session/queued-write fixes, preserved guest
APK behavior, exact JNI primitive signatures and ABI/image ownership guards.
It passed 13 executor and 3 guest tests after a fresh-emulator retry; original
UI failures and limits are retained in X86_64_JNI_VALIDATION_20261009.md.

Local Python guards ran successfully, including exact new-key predecessor,
wrong-signer and same-signer/migration-report tests. The original Launcher
reference suite was skipped because that different exact Launcher was not
supplied; this does not skip validation of the supplied signed predecessor.

## Remaining device boundary

The supplied proprietary Space contains only arm64-v8a native kernel files.
Owned x86_64 fixture tests do not prove this proprietary ARM64 kernel boots.
No ARM64 phone/device is available in this environment. Installation, startup,
profile handoff from the signed ModKit, original third-party game launch and
ON/OFF restoration through this exact signed Space remain pending on such a
device. No physical-phone, universal-engine or complete root/no-root coverage
claim is made. The deliverable is a signed, structurally verified update pair;
complete release certification remains IMPLEMENTED_BUT_INCOMPLETE.

## Phone screenshot follow-up

The supplied phone screenshots show ModKit 0.0.46 attempting an external launch
of the host's unexported MainActivity and Android rejecting it with Permission
Denial. The previous companion is therefore not a working handoff pair on that
phone. ModKit 0.0.47 uses the PackageManager public launcher intent unchanged
and notifies the existing authenticated profile provider; Space synchronizes
saved menus when its main screen resumes. No host manifest changes are needed.
A cross-UID owned-fixture test reproduces the private-Activity rejection and
requires the public launch to display real game state. Phone verification of
this corrected path and gameplay ON/OFF remains pending.

## Packaging CI confirmation

Packaging source checks and owned-device checks completed successfully in
https://github.com/Ffenuss/ModKit/actions/runs/38052700769. Downloaded artifact
11670581110 matches published SHA256
a7d1025e9d24e3b5bcb0dd62d816b8aa8ddab305d033d8564664044a2e27fdf8.
The actual XML reports contain 13 executor tests and 3 guest-menu tests, all
without failures, errors or skipped tests. These run on owned x86_64 fixtures.
The corrected ModKit handoff passed independent validation at commit
55cbec48dfd43aa94b89fc506bd3409622c8f7a8 in
https://github.com/Ffenuss/ModKit/actions/runs/38053180214:
452 JVM tests with zero failures/errors/skips, lint with zero errors, 32 CPU
vectors and 9 device tests each on Android 29 and 35. Both downloaded Android
XML reports explicitly contain the successful cross-UID launcher regression.
Production compilation and preserved-key signing follow validation.

Binary manifest inspection of the exact supplied predecessor confirms its public
SplashActivity has exported=true, while MainActivity has no exported attribute
or public intent filter. The fix preserves the resolved launcher component,
MAIN action and LAUNCHER category instead of replacing them with an internal
component or custom action.

## Signed corrected companion

ModKit-v0.0.47.apk: versionCode 47, main production package
io.github.ffenuss.modkit, release with no debuggable flag. SHA256:
f066b14333c334331e66fb807105fae8c09babff4df6743a298f36fcbb4013a1.
Certificate SHA256 matches the supplied Space and preserved key:
b44a6c2b53689c16cb08da3ca22e4aba20d9d0d1ecbc26df13e15c04b6665073.
APK v2/v3 signatures verified; ZIP payloads are identical to validated unsigned
release before signing; 16 KiB page alignment passes.
Unsigned release artifact 11670187953 matches published ZIP SHA256
7d74eb5c3b629c599b88b743227f8529061e1c711f13a59eef124046ccf85cdc.

The current installable pair is this ModKit 0.0.47 plus the Space runtime
refresh described above. Install updates, open ModKit's saved menu, use its
public-launcher button, then select the original application inside Space.
The in-app GitHub download button still requires a published modkit-space.apk
release asset; no such public distribution has been claimed by this work.
Owned fixture execution is confirmed, while real-phone corrected handoff and
third-party game effects remain unverified. An available recipe describing a
UI display is not evidence of changing the corresponding gameplay state.
