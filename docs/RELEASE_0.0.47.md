# ModKit 0.0.47 — public Space launcher handoff

Source: 55cbec48dfd43aa94b89fc506bd3409622c8f7a8, PR #31.
Android validation: https://github.com/Ffenuss/ModKit/actions/runs/38053180214
Space validation: https://github.com/Ffenuss/ModKit/actions/runs/38052700769

## Reported phone failure and change

The supplied phone screenshot shows Android refusing the external launch of
com.dualspace.multispace.MainActivity because it is not exported. The exact
supplied Space manifest instead exports
com.dualspace.multispace.ui.activity.SplashActivity with MAIN/LAUNCHER.

ModKit now preserves PackageManager's public launcher intent, checks that the
resolved Activity is enabled, exported and accessible, and opens that entry.
The existing signer-authenticated provider supplies prepared menus when Space
resumes its main screen. No custom action, FileProvider grant, or change to the
host manifest is required. Missing/stale profiles and untrusted host signers
remain rejected. The profile must be inside the provider's 256-menu window.
UI messages describe automatic synchronization rather than claiming import
has already completed.

## Verification

- 452 JVM tests: zero failures, errors, or skipped tests; lint: zero errors.
- 32 independent CPU vectors: 12 ARM64, 10 ARM32, 10 x86_64.
- Android 29 single-APK and Android 35 split-APK: nine tests each, zero
  failures, errors, or skipped tests. The new cross-UID case reproduces the
  private Activity rejection and requires the public launcher to show actual
  fixture game state. MAIN/LAUNCHER, absence of data and ClipData are checked.
- Both DEX runs completed all 44 events, including independent ON/OFF and
  restoration of original getter values.
- Space: source checks and 13 executor plus three guest overlay tests passed.

The release job completed after JVM/lint and both Android jobs succeeded.
Artifact 11670187953 has archive SHA-256
7d74eb5c3b629c599b88b743227f8529061e1c711f13a59eef124046ccf85cdc.
Unsigned APK SHA-256:
64b356505ee754e66c4d1c004512bea86b2ef5b3b26a6d13641968fdcbc76f10.
Signed ModKit-v0.0.47.apk: 2,166,425 bytes, SHA-256
f066b14333c334331e66fb807105fae8c09babff4df6743a298f36fcbb4013a1.
Package io.github.ffenuss.modkit, versionCode 47/versionName 0.0.47, minSdk 26,
targetSdk 36, non-debuggable production build. The provided preserved key was
used locally. Signature schemes v2/v3, the single preserved certificate and
16 KiB page alignment were checked; every ZIP entry remains byte-identical
to the CI-produced unsigned package after signing.

## Companion Space

Use ModKit-Space-v0.0.37-runtime-update.apk, SHA-256
10e8fbf9b99e130f44c842556faee9260b43b735ca1574ce9d5921e9128353b2.
Its single certificate SHA-256 is
b44a6c2b53689c16cb08da3ca22e4aba20d9d0d1ecbc26df13e15c04b6665073.
Compared with the exact supplied signed predecessor, only classes4.dex and
the two engine assets change. Kernel, manifest, resources and guest APKs are
unchanged. Signature and 16 KiB page alignment checks passed.

## Remaining boundary

The supplied Space kernel supports ARM64 only. These tests use owned fixtures
and do not prove that this proprietary kernel boots or runs third-party games
on a phone. Installation, public-launcher handoff, automatic menu import and
gameplay ON/OFF through this exact signed pair still require an ARM64-device
check. No universal engine/version support is claimed. The DropTheCat
screenshots show a display-level recipe; changing gameplay is not established
by changing that UI value.
