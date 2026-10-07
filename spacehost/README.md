# ModKit Space host — original-app sessions

The primary product is an original application running inside a virtual space with ModKit's host-owned overlay. Repacking a guest APK is an expert workflow, not the primary launch path.

This module adapts the exact user-provided `Launcher MLBB V2.3.apk` already used by Aniimo QA. It keeps the host package `com.dualspace.multispace.androidx`, original binary manifest, resources, native VirtualApp engine, installation UI and guest application data paths. The proprietary reference APK and its binaries are not committed to this repository.

## Implemented

- Host `MultiSpaceApplication.onCreate` registers the ModKit UI only in the host's main process.
- The original host launch Runnable supplies the target's real package and virtual user ID. Guests are not modified.
- A single MK window appears on a launch request when overlay permission exists. The host has an explicit permission button; returning from Settings only checks the grant and does not reopen Settings.
- The overlay inspects `com.android.vending`, `com.google.android.gms` and `com.google.android.gsf` through the virtual package manager for that same virtual user. Missing packages are reported as missing; an exception is not converted into "Google ready".
- The Google Play button uses the virtual activity manager, never an outside-device Play Store Intent.
- Original base/split paths are resolved via `VirtualCore.ck(package, 0)` and `InstalledAppInfo.f(virtualUser)`, after checking installation for that user. The host PackageManager is not a fallback.
- The overlay can start/cancel a read-only inventory of the actual original APK set. It reports SHA-256 identities, ZIP entry count, DEX/ELF/IL2CPP metadata header counts and ABI paths. Headers are observations, not validated engine detections or discovered mods.
- Every result has a unique session ID. All source hashes and the virtual package record are checked again before displaying a result. Switching target/user, cancelling, or hiding the overlay invalidates outstanding progress/results.
- Six exact host advertisement methods are patched: interstitial launch wrappers, the direct interstitial display method, the advertisement-only resume receiver, and advertisement Activities. The Activity superclass callback and the existing AppCompat theme initialization are preserved before immediate finish.
- DEX, manifest, resources and native-engine preservation are checked during packaging. Existing output is replaced only after signature, alignment and payload verification.

## Deliberate limits

This is a source integration stage, not a verified release APK. The shared artifact indexer, DEX header inventory and universal ELF structural inventory are now wired to the host via a private engine carrier. This wiring still needs APK/device validation. IL2CPP semantic analysis and runtime mutation controllers are not connected; there are no pretend gameplay toggles. Successful launch, overlay lifetime, all host advertising surfaces, Google sign-in and actual game compatibility still require Android device tests. Advertising SDKs and their network initialization remain present; this stage suppresses the proven launch/resume interstitial surfaces, not every possible advertisement format. Google APKs are neither fabricated nor redistributed. Their existing handling in the kernel is preserved; this module does not claim that every device already has all Google packages in its virtual user.

The old host package identity is retained because changing it previously broke virtual initialization. Do not uninstall an existing working space just to install this build. A same-package update requires its existing signing key; an unrelated key will cause Android's normal signer conflict.

## Build

Requires Python 3.10+, Java 17, apktool 2.12.1+, Android API35 SDK and build-tools35. Set:

- `MODKIT_SPACE_ENGINE_APK` — APK from `gradle :spaceengine:assembleDebug` (a private DEX carrier, never installed as a guest)
- `ANDROID_SDK_ROOT`
- `MODKIT_SPACE_KEYSTORE` — existing host/QA keystore
- `MODKIT_SPACE_ALIAS`
- `MODKIT_SPACE_STORE_PASSWORD`
- optional `MODKIT_SPACE_BUILD_TOOLS` and `MODKIT_SPACE_ANDROID_JAR`

Run `bash spacehost/build.sh /path/to/Launcher.apk /path/to/ModKit-Space.apk`.

The reference SHA256 must be `251acbe2e3199b4a7b6454a495dcdeac0a479dfa00b14066f4615fed37bc6719`. Any different or already modified APK is rejected. No guest APK is a build input.

## Verification

`python3 -m unittest discover -s spacehost/tests -v` checks bootstrap preservation and rejects unsupported inputs. Supplying `MODKIT_SPACE_REFERENCE=/path/to/Launcher.apk` also runs the exact-reference DEX tests, which check all unselected method bodies and the Google/virtual-launch ABI. The source workflow does not have the proprietary host and explicitly skips those reference-only tests.

Local verification on 2026-10-07: six Python tests passed, including both reference-only tests. Java compilation against API35 passed and the authored Java session-identity test passed. APK assembly, signing and device execution were not performed in that environment because apktool/D8 build binaries were unavailable and their download was blocked.

## Source-session verification (2026-10-07)

Java API35 compilation, `SpacePolicyTest`, `SourceInventoryTest` and all six Python tests (with the reference host) passed. The inventory fixtures cover base/split aggregation, unchanged input hashes, unique sessions, user identity, cancellation, missing/duplicate inputs, malformed ZIPs and source replacement during scanning. The reference DEX checks now also verify the `ck` and `InstalledAppInfo.f` ABI remains unchanged.

An additional attempt to inventory the previously available local game APK set failed with `zip END header not found`; no successful game inventory is claimed for those local files. The host source is not yet assembled into a signed APK or tested on a device.

The source workflow also runs on `feature/space-*` pushes and executes the new inventory tests. It still needs the proprietary reference locally for the two reference-only checks.

## Shared engine integration

`:analysiscore` contains the original ModKit artifact models, indexer, runtime fingerprint profiler and DEX/ELF inventory engines. The app retains `FastArtifactIndexer` as an adapter for its routing, evidence graph and persistent cache. The portable indexer now propagates cancellation inside ZIP traversal rather than returning a warning, and rejects ambiguous container names.

`:spaceengine` packages the shared module and its Kotlin runtime. The builder verifies its public bridge ABI, stores the carrier plus SHA-256 in new host assets, and preserves every original host entry except the already documented `classes2.dex` replacement/signatures. `SpaceEngine` checks the carrier identity, stages it read-only in private code cache, and loads engine/Kotlin namespaces separately from the old host. Only Java platform types cross the boundary. Temporary ELF files are removed after each run.

The overlay's analysis action now invokes this bridge before rechecking source hashes and virtual package metadata. A report remains structural evidence rather than an available modification. DEX analysis is bounded to the fixed header; ELF analysis includes segments and dynamic symbols; IL2CPP metadata is indexed but not semantically decoded in the host yet.

At commit time, local Java/API35 compilation and nine Python tests passed. JVM Kotlin tests and the actual carrier APK build are delegated to the expanded source workflow; their result must be checked before considering the integration validated.
