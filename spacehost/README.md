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

This is a source integration stage, not a verified release APK. The shared artifact indexer, DEX header inventory and universal ELF structural inventory are now wired to the host via a private engine carrier. This wiring still needs APK/device validation. Metadata definition inventory is connected; IL2CPP code-address binding and runtime mutation controllers are not connected; there are no pretend gameplay toggles. Successful launch, overlay lifetime, all host advertising surfaces, Google sign-in and actual game compatibility still require Android device tests. Advertising SDKs and their network initialization remain present; this stage suppresses the proven launch/resume interstitial surfaces, not every possible advertisement format. Google APKs are neither fabricated nor redistributed. Their existing handling in the kernel is preserved; this module does not claim that every device already has all Google packages in its virtual user.

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

The overlay's analysis action now invokes this bridge before rechecking source hashes and virtual package metadata. A report remains structural evidence rather than an available modification. DEX analysis is bounded to the fixed header; ELF analysis includes segments and dynamic symbols; IL2CPP metadata definitions are now decoded using the same reader as the app, with a bounded inventory and explicit counts/limitations.

At commit time, local Java/API35 compilation and nine Python tests passed. JVM Kotlin tests and the actual carrier APK build are delegated to the expanded source workflow; their result must be checked before considering the integration validated.

## Metadata inventory

The host now extracts IL2CPP metadata into unique private temporary files, verifies entry sizes/CRC, and calls the shared `Il2CppMetadataReader`. Reports preserve container/path, parsed and declared counts, version/support/truncation, and a bounded selection of actual image/type/method/field names and tokens. Metadata tokens are not code addresses or change-ready mod offsets. Unsupported layouts retain diagnostics without fabricated definitions.

The phone inventory attempts at most four files, 64 MiB per file and 128 MiB in total; per-file reader limits are 20000 types, 75000 methods/fields and 2048 images. These do not reduce the main app reader's default limits. Temporary files are deleted on success, parse failure, cancellation and extraction errors. Source integrity is checked again by the host session afterward.

The shared reader now sweeps bounded field indices rather than expanding each type's range. Invalid ranges are reported; overlapping ownership remains unresolved. This avoids quadratic work on damaged metadata without inventing owners. Carrier verification requires the actual shared metadata class definitions, so an older header-only carrier is rejected. New metadata tests are pending CI at this change's publication.


## Main application and shared space

Analysis stays in the ModKit application. A completed analysis offers **Скачать пространство**. The download channel checks non-draft, non-prerelease GitHub releases for the exact `modkit-space.apk` asset; the empty channel reports that the host has not been published. It never substitutes the engine carrier or a per-game repack.

The host no longer exposes analysis controls. Its existing original application picker remains responsible for installation and launch. Launch opens the overlay menu immediately when overlay permission is granted. Settings retain separate package/virtual-user targets across host updates and can launch a previously opened target after checking it is still installed. Adding a new target does not remove other targets. Google components and the virtual kernel are retained.

Menu transfer, per-package selection and APK-version binding are implemented below. Guest-process recipe execution remains pending. No gameplay switches are presented until a real executor exists. The `spaceengine` carrier remains packaged for internal validation but is not exposed as analysis UI in the host.

The source CI can export public Android build tools and apktool for local signing. The proprietary host and signing keystore never enter CI or git. Signed APK delivery still requires local payload verification and Android device validation before publishing the shared release asset.


## Version-bound per-application menu handoff

Each analyzed application has one menu profile. Different packages keep independent profiles in the same host; importing one never replaces another package. The application expands APK/APKS/XAPK/ZIP APK sets for indexing, scanning and subsequent SHA checks, preserving original inner filenames in unique private staging. Archive extraction validates size/CRC and deletes only its own staging on error/cancellation/close. Non-APK downloaded data/OBB and unsupported executable formats still require additional analysis backends.

Menu preparation runs automatically before completed analysis, using the same source workspace. Existing DEX and proven IL2CPP recipe discovery feeds the profile; genre inference requires at least two independent declared-symbol signals and an unambiguous match. Engine evidence and genre search priorities never imply executable support. Unsupported/ambiguous genres remain unknown. Menu JSON distinguishes candidates from statically prepared recipes and explicitly advertises `backend: none` until a guest-process executor is integrated.

The ModKit FileProvider grants a data-only JSON profile to the exact existing host MainActivity. The original host updates its intent in `onNewIntent`; lifecycle resume consumes it. The host accepts only bounded supported profiles from the ModKit provider route, atomically saves by package, and chooses the corresponding menu on launch. It compares the complete multiset of APK byte hashes/sizes against actual virtual sources, allowing kernel path/name changes but rejecting updated/missing/substituted APKs. Stale async target results do not render into the current target menu. No guest APK is modified.

Tests cover archive extraction/cancellation/limits, cautious genre planning, Android profile isolation and stale-byte rejection, plus main-app preparation from the owned APK-set fixture and readable FileProvider handoff. These tests do not prove arbitrary real-game effects or the proprietary host's on-device launch.


Completed analysis now remains on the space result until the user explicitly selects APK expert editing. The main application's **Сохранённые меню** browser lists one latest profile per package, allowing handoff after closing ModKit or installing/updating the shared host without repeating analysis. Owned-fixture UI tests exercise the saved browser and explicit expert transition.

The shared host now automatically pulls the latest prepared profiles on opening the application picker and on refreshing the selected target. This also works when the host was installed after analysis. ModKit's read-only `.space-menu` provider authenticates the Binder caller UID and pins the public certificate fingerprint of the authorized Aniimo QA space key. There are no grants to arbitrary apps, no write endpoints and no guest APK payloads. The explicit handoff button also checks the host signature. A future production signing-key change requires updating the pin.

The bounded index contains profile content hashes. Space sync preserves existing menus on malformed entries, hash changes during reading, inaccessible ModKit or schema errors; unchanged profiles are not rewritten. Test ModKit takes precedence when both app variants are installed. Device fixtures cover two-menu automatic delivery, unchanged sync, corrupt-content rejection and updating one menu without replacing another. Main-device tests check provider self-read and rejection of a separate untrusted test APK UID.

## Selected session persistence

Menu synchronization and explicit profile imports register available packages without changing the selected game or virtual user. Only an actual launch or target selection changes the selected session. The host restores that validated session on process recreation and rechecks installation and APK identities before showing its menu. Invalid persisted targets are excluded from the picker. The target-store regression suite covers two packages, two virtual users, re-import and recreation.

## Guest lifecycle bridge

The host Application now also calls `SpaceHost.guestBootstrap` immediately before each original onCreate return, after its original initialization on that path. In virtual client processes only, this chains the verified `VirtualCore.bo()/ax(k)` callback interface. The existing delegate executes first and keeps its return values and original exceptions. The observed `d(Application)` precedes guest onCreate; `b(Application)` follows it in the pinned client call chain.

An observed guest is accepted only when its package and Application match the virtual client; the virtual user is derived with the kernel's `VUserHandle.s(vuid)` function. Its real Application and ClassLoader are retained inside that same process. Google dependencies are excluded. This layer performs no memory or APK writes, exports no commands and still has `backend:none`; guest-process execution and the IPC command channel remain pending. Local callback tests verify order, result and failure preservation and observer-failure isolation; exact-reference checks verify the required ABI. Proprietary guest startup remains unverified on a device.
