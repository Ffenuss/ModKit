# ModKit Space host — original-app sessions

The primary product is an original application running inside a virtual space with ModKit's host-owned overlay. Repacking a guest APK is an expert workflow, not the primary launch path.

This module adapts the exact user-provided `Launcher MLBB V2.3.apk` already used by Aniimo QA. It keeps the host package `com.dualspace.multispace.androidx`, original binary manifest, resources, native VirtualApp engine, installation UI and guest application data paths. The proprietary reference APK and its binaries are not committed to this repository.

## Implemented

- Analyze original APK/APKS/XAPK/split sets in ModKit; the guest APKs are not rewritten.
- Keep one version-bound menu per package in one shared host, with authenticated automatic profile synchronization.
- Preserve the selected package and virtual user across imports and process recreation.
- Chain the pinned virtual kernel's original guest Application callbacks and attach a compact menu to guest Activities without root or system overlay permission.
- Execute only explicit native patches with checked ABI, ELF address, image hash and original bytes; disable restores the original bytes and uncertain restoration stays an error.
- Obtain the native runtime from the authenticated installed ModKit provider and stage it read-only in host private code cache.
- Keep the original virtual engine, Google resources and per-user virtual Google package handling.
- Suppress six exact host advertisement methods while preserving their required superclass/theme setup.
- Replace output only after signature, alignment and payload-preservation verification.

## Deliberate limits

Signed test APKs are assembled and the owned native executor fixture passed on Android 35 (x86_64), including 7 → 999 → 7, restore-all, wrong-image rejection and unchanged APK/library files. Main-app ARM64 recipe vectors passed independently. These checks do not prove proprietary host startup, overlay lifetime, real third-party game effects or Google sign-in; those still require phone verification. The main recipe exporter emits verified ARM64 IL2CPP patches and typed exported JNI getter patches. Structural detection of other engines or versions is not executable support. Root devices can use the same guest-process route; no root-only external-process executor is added here.

Advertising SDKs and their network initialization remain present: the patch suppresses the proven launch/resume interstitial surfaces. Google APKs are neither fabricated nor redistributed, and their existing kernel handling is preserved. An updated ModKit installation is required together with the updated space host.

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

### Upgrade an existing native-recipes test host

`upgrade_host.py` provides a separate route for the previously signed
`ModKit-Space-native-recipes-test.apk` (SHA256
`6f9cd491a2bc344538a515408d3f9b7682f494eab9caabab7f7dda372d4d79a6`).
It validates this exact predecessor and its single authenticated signer, then
replaces only `classes4.dex` and the two engine assets. Every other decompressed
entry must remain identical, including the bootstrap DEX, binary manifest,
resources and virtual engine. Original-host validation is unchanged.

Build the current host DEX and engine carrier as in the source CI, then run:

```sh
python3 spacehost/upgrade_host.py prepare OLD_SPACE.apk HOST_CLASSES.dex ENGINE.apk SPACE_UNSIGNED.apk
python3 spacehost/upgrade_host.py sign OLD_SPACE.apk SPACE_UNSIGNED.apk SPACE_UPDATED.apk
```

Both commands use `MODKIT_SPACE_BUILD_TOOLS` or `ANDROID_SDK_ROOT`. An optional
`MODKIT_SPACE_JAVA` selects the Java executable. The sign command uses the same
keystore, alias and password environment variables as `build.sh`; an independent
key password can be supplied as `MODKIT_SPACE_KEY_PASSWORD`. Secrets are never
command-line arguments. Preparing an unsigned APK does not require a password;
it is an intermediate file and cannot be installed as an update. Signing stages
the result and publishes it only after signer, alignment and preservation checks.
The manifest version is retained; Android permits same-version updates signed
with the existing key. This packaging check does not prove phone compatibility.

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

The host no longer exposes analysis controls. Its existing original application picker remains responsible for installation and launch. Guest Activity resume opens the in-process menu without a system overlay grant. Settings retain separate package/virtual-user targets across host updates and can launch a previously opened target after checking it is still installed. Adding a new target does not remove other targets. Google components and the virtual kernel are retained.

Menu transfer, per-package selection and APK-version binding are implemented below. Guest-process native recipe execution is implemented in the current source; device validation is pending. Only explicit native patches receive switches. The `spaceengine` carrier remains packaged for internal validation but is not exposed as analysis UI in the host.

The source CI can export public Android build tools and apktool for local signing. The proprietary host and signing keystore never enter CI or git. Signed APK delivery still requires local payload verification and Android device validation before publishing the shared release asset.


## Version-bound per-application menu handoff

Each analyzed application has one menu profile. Different packages keep independent profiles in the same host; importing one never replaces another package. The application expands APK/APKS/XAPK/ZIP APK sets for indexing, scanning and subsequent SHA checks, preserving original inner filenames in unique private staging. Archive extraction validates size/CRC and deletes only its own staging on error/cancellation/close. Non-APK downloaded data/OBB and unsupported executable formats still require additional analysis backends.

Menu preparation runs automatically before completed analysis, using the same source workspace. Existing DEX and proven IL2CPP recipe discovery feeds the profile; genre inference requires at least two independent declared-symbol signals and an unambiguous match. Engine evidence and genre search priorities never imply executable support. Unsupported/ambiguous genres remain unknown. Menu JSON distinguishes candidates from prepared recipes. Schema 2 uses `backend: native_v1`; only verified ARM64 IL2CPP or typed exported JNI recipes with exact addresses, original bytes and library hashes are exported as executable patches. Schema 1 remains readable as inventory only.

The ModKit FileProvider grants a data-only JSON profile to the exact existing host MainActivity. The original host updates its intent in `onNewIntent`; lifecycle resume consumes it. The host accepts only bounded supported profiles from the ModKit provider route, atomically saves by package, and chooses the corresponding menu on launch. It compares the complete multiset of APK byte hashes/sizes against actual virtual sources, allowing kernel path/name changes but rejecting updated/missing/substituted APKs. Stale async target results do not render into the current target menu. No guest APK is modified.

Tests cover archive extraction/cancellation/limits, cautious genre planning, Android profile isolation and stale-byte rejection, plus main-app preparation from the owned APK-set fixture and readable FileProvider handoff. These tests do not prove arbitrary real-game effects or the proprietary host's on-device launch.


Completed analysis now remains on the space result until the user explicitly selects APK expert editing. The main application's **Сохранённые меню** browser lists one latest profile per package, allowing handoff after closing ModKit or installing/updating the shared host without repeating analysis. Owned-fixture UI tests exercise the saved browser and explicit expert transition.

The shared host now automatically pulls the latest prepared profiles on opening the application picker and on refreshing the selected target. This also works when the host was installed after analysis. ModKit's read-only `.space-menu` provider authenticates the Binder caller UID and pins the public certificate fingerprint of the authorized Aniimo QA space key. There are no grants to arbitrary apps, no write endpoints and no guest APK payloads. The explicit handoff button also checks the host signature. A future production signing-key change requires updating the pin.

The bounded index contains profile content hashes. Space sync preserves existing menus on malformed entries, hash changes during reading, inaccessible ModKit or schema errors; unchanged profiles are not rewritten. Test ModKit takes precedence when both app variants are installed. Device fixtures cover two-menu automatic delivery, unchanged sync, corrupt-content rejection and updating one menu without replacing another. Main-device tests check provider self-read and rejection of a separate untrusted test APK UID.

## Selected session persistence

Menu synchronization and explicit profile imports register available packages without changing the selected game or virtual user. Only an actual launch or target selection changes the selected session. The host restores that validated session on process recreation and rechecks installation and APK identities before showing its menu. Invalid persisted targets are excluded from the picker. The target-store regression suite covers two packages, two virtual users, re-import and recreation.

## Guest lifecycle bridge

The host Application now also calls `SpaceHost.guestBootstrap` immediately before each original onCreate return, after its original initialization on that path. In virtual client processes only, this chains the verified `VirtualCore.bo()/ax(k)` callback interface. The existing delegate executes first and keeps its return values and original exceptions. The observed `d(Application)` precedes guest onCreate; `b(Application)` follows it in the pinned client call chain.

An observed guest is accepted only when its package and Application match the virtual client; the virtual user is derived with the kernel's `VUserHandle.s(vuid)` function. Its real Application and ClassLoader are retained inside that same process. Google dependencies are excluded. This callback layer installs the guest Activity menu after the original Application callback. The menu and controller execute in that same guest process; there is no external command channel and guest APKs are not rewritten. Local callback tests verify order, result and failure preservation and observer-failure isolation; exact-reference checks verify the required ABI. Proprietary guest startup remains unverified on a device.

## Native guest menu — current implementation

The guest menu attaches to resumed Activity decor without requiring root or the system overlay grant. Only explicit schema-2 patches have switches. Library identity, original bytes, executable segment bounds and a unique loaded module are checked before writing. Disabling restores the recorded original bytes; an uncertain write or permission restoration is reported as an error rather than an OFF state. Selecting another app first restores enabled recipes.

Closing the guest menu fences commands inside the native controller's lock:
an in-flight write finishes before restoration, and queued commands cannot
re-enable a recipe after closure. Closure still reports uncertain restoration as
an error, and repeated closure can retry a previously rejected safe restoration.

The runtime payload is obtained from the authenticated installed ModKit provider, verified by SHA-256 and ELF ABI, and staged read-only in host private code cache. An updated ModKit installation is required as well as the updated space host. The main exporter emits ARM64 IL2CPP and typed exported JNI patches; DEX candidates and other engines do not become executable merely because they were detected. Native loader support for four ABIs is infrastructure, not universal engine/version compatibility. Root devices can use the same guest-process route, but this change adds no root-only external-process executor.

A new owned-fixture Android test loads a real ELF, applies a recipe, observes 7 → 999 → 7, checks restore-all, rejects a wrong library hash and verifies unchanged source APK and library bytes. All nine space device tests passed in workflow run 37818451654. Java/API35 compilation and the exact-reference Python checks passed locally. Proprietary host startup, Google login and third-party game effects still require device verification.

### Typed JNI recipes and movable guest menu

JNI preparation reads native Java declarations from original APK/split DEX files and resolves the exact short or signature-qualified export in ARM64 ELF images. It supports recognized no-argument gameplay getters returning `int`, `boolean` or `float`; JNI escaping, overloaded declarations and VM short-name precedence are explicit. A unique exported function, bounded read-only body, executable file-backed address, full library hash and original bytes are required. Incomplete indexes, duplicate libraries, aliases and overlapping patches are rejected. Dynamic `RegisterNatives`, obfuscated names and arbitrary C++ exports are not inferred; gameplay effect still requires testing.

The 0.0.36 line additionally supports declared JNI `long` and `double` getters, emitting x0 and d0 return bodies respectively. Wide values do not enable wide DEX rewriting. The owned native APK adds separate stamina/speed getters and scanner tests check three exported recipes; independent CPU vectors include both new return types. Declaration/overload counts are indexed once rather than repeatedly rescanning the entire declaration list.

Bounded menu transfer prioritizes actual executable patches over inventory-only candidates while preserving genre order within each group. A large candidate prefix cannot hide a valid native recipe. Compound/acronym genre signals are tokenized, and at least two distinct symbol names are required for inferred genre. These changes expand specific typed native coverage, not all engines/versions.

The guest menu can collapse and drag within screen insets. Its position persists across Activity recreation, lifecycle callbacks remove old views, busy switches prevent duplicate writes, and late-loaded native libraries are periodically rechecked. No additional visible controls are added.

`GuestMenuDeviceTest` uses production overlay/controller code in the original owned `nativefixture` process. It checks the real native value, switch ON/OFF, Activity recreation, one overlay, collapse/drag bounds, restoration and unchanged source APK. This is distinct from proprietary virtual-kernel/Google sign-in verification on a phone. `AutoModDeviceTest` independently checks an executable JNI profile exported from an original APK without IL2CPP.

### Live recipe state verification (2026-10-09)

Capability refresh now rechecks confirmed OFF and ON recipes against the loaded image and actual code bytes. Repeated switch requests also verify their state before returning success. Restore-all revalidates inactive recipes before reporting a clean session. Changed bytes, a missing image or a failed image lookup invalidate confirmed states; the controller latches ERROR without overwriting foreign code. Unavailable recipes can still become ready when their libraries load later.

Local Java 8-target compilation and an isolated JVM harness against the production controller passed 11 scenarios covering code drift, image loss/failure, late loading, repeated requests and normal restore. The same harness fails against the previous controller. Eight Python source tests passed; proprietary-reference tests were skipped because the reference APK is absent. Workflow 37892744188 passed the new controller Android regressions. The expanded JNI/menu line subsequently passed full Android CI 37893427425 (unit tests, lint, APK assembly, seven independent ARM64 CPU vectors and API29/API35 device tests) and space checks 37893427445. These are owned-fixture checks, not proprietary host startup or phone validation.

### Activity-owned host settings and retained genre

The host picker now exposes one MK entry whose panel belongs to its Activity decor. It requests no system overlay grant, has no second floating bubble, and detaches on pause/destroy or guest launch. Target dialogs use the live Activity rather than an application-context system window. Guest menus retain their in-process lifecycle.

Clicking the existing genre label opens correction without adding another toolbar control. A selected genre is retained on later analysis of the same package. Reordering preserves all source identities and exact native patch payloads and prioritizes executable items. Profile writes notify the read-only bridge; the host refreshes through authenticated/hash-checked sync while preserving its selected application. API35 host compilation and local Python tests passed; new host panel and genre-preservation Android tests are pending CI for this change.

## Authorized replacement signing key (0.0.37)

The companion ModKit trusts only the existing QA certificate and the explicitly
pinned replacement certificate. `upgrade_host.py sign --new-key` verifies the
old predecessor signature, then requires the new pinned certificate on output.
This is a fresh installation, not a same-signer update; Android will reject an
update over the old space. Removing the old space can erase its local guest data.
The package, manifest and kernel remain unchanged. Preserve the new keystore
and its credentials for subsequent updates; private signing material is never
committed to this repository.

## Typed primitive JNI getter arguments (0.0.38)

The companion scanner accepts up to eight declared primitive arguments
(boolean, byte, char, short, int, long, float and double) while retaining the
existing return-type and read-only body checks. Object and array arguments are
excluded. Overloads require exact JNI export resolution; ambiguous short names
are rejected. Recipe identities include the full method signature. Two owned
getAmmo overloads exercise int/long arguments, distinct ELF addresses and
independent menu switches. The reference-argument getter stays excluded.
This extends the engine-neutral native getter route; it does not implement
Flutter Dart AOT, Unreal Blueprint, Mono CIL or universal engine support.
The existing signed Space 0.0.37 runtime accepts these schema-2 recipes.
