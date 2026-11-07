# Space profile hub — non-virtualized first integration

This stage connects the **current verified AutoMod build pipeline** to a single
catalog of per-game profiles. It does **not** yet merge the legacy
`spacehost` / `spaceengine` branch, start a virtual Android environment,
inject a runtime process, or expose live modification toggles.

## User flow

1. Analyze a supported installed game or APK.
2. Select executable, verified-preflight recipes and create a signed APK-set.
3. ModKit persists the existing `AutoModBuildRecord` in `build-history/`.
4. Open **Space · игровые профили** from the start screen.
5. See one latest usable saved build per Android package.
6. Install a signed APK through the existing user-approved Android installer.
7. The profile launcher checks the installed package, **current signer**, and
   **actual SHA-256 of every installed base/split APK** against the saved build
   before allowing the regular Android launch. The hashes are collected from
   the installed package source on an IO dispatcher.
8. Choose an **active game profile** for future Space handoff, or clear the
   selection. Selection is written only after rechecking the installed APK's
   complete signature/content identity. The selected key includes the precise
   artifact SHA, so another game or an updated build is never silently chosen.

Changing the APK's signature, uninstalling the build, deleting its local staged
files, or replacing the game prevents launching from that saved profile.

## Security / evidence boundary

- `SpaceBuildProfileIndex`: bounded disk inventory, SHA-only filenames,
  canonical file checks, parser reuse, per-package deduplication. It does not
  invent mod capabilities or mix APK splits between apps.
- `SpaceLaunchIdentityPolicy`: pure launch decision for package name, **exact
  current signer set**, full APK-set content hashes, and launch activity.
  Any mismatch fails closed.
- `SpaceInstalledPackageLauncher`: reads Android `SigningInfo` on API 28+,
  legacy signatures for API 26–27; hashes certificate bytes and all installed
  base/split APK files with SHA-256. Hashing occurs off the UI thread and is
  repeated before every launch request.
- `SpaceProfilesScreen`: displays build metadata, explicit active-game selection
  and supported regular Android start action; neither UI labels nor switches
  claim dynamic runtime modifications.
- `SpaceActiveProfileStore` and `SpaceActiveProfileSelection`: explicit
  preference key (package + exact artifact SHA), repeat identity check before
  saving, no fallback on missing packages or mismatched signers/content, and
  never auto-select a different version of the same game.
- The feature is intended to be compatible with a **future** authenticated
  export to the previously developed `SpaceMenuProvider` /
  `SpaceMenuHandoff` contract. Do not copy those legacy modules wholesale
  without verifying their current build and permission model.

## Read-only synchronization with legacy `spacehost`

The second integration layer exposes one explicit active, signer- and
APK-content-verified build through the **existing** legacy host protocol:

- `content://<ModKit applicationId>.space-menu/index` returns JSON
  `{"schema":1,"profiles":[{"file":"...json","sha256":"..."}]}`;
  if nothing is selected, or the installed package has changed, the list is empty.
- `/profile/<file>` contains legacy `MenuProfile` **schema 1**,
  `backend="none"`, exact package, build artifact hash and the **installed
  signed APK-set** SHA-256 and sizes in `sources`.
- `items=[]`, `engines=[]`. No DEX/static recipe is represented as a
  live toggle; the host must separately match its own guest source files,
  signer and virtual user. This transfer confers **no execution capability**.
- Reads are allowed only to the ModKit app's UID or the host package
  `com.dualspace.multispace.androidx` with one of the two pinned QA signing
  certificates previously allowed by the legacy host bridge. No wildcard
  packages, alternate signers, unsigned hosts, or permission-free APK access.
  All insert/update/delete operations are rejected.
- The explicit **"Открыть Space · только сведения об игре"** action rechecks
  the active installed build, notifies the provider, then starts only a
  signer-authenticated, exported host launcher Activity. An absent or
  differently signed host produces a visible error.
- A repacked static ModKit APK may have bytes different from the original game.
  The old host must independently verify that its virtual guest **exactly
  matches** the exported source-set; otherwise the menu remains unavailable.
  An original, unchanged APK is not inferred from a repacked build.
- The old host retains its own cache and virtual-user selection. Clearing the
  active game in ModKit stops future publication but **does not delete old
  profiles cached by the host**, remotely revoke permissions, or automatically
  select a guest inside the host. The user still chooses the original guest
  and virtual user in that host. Removing host-cached profiles is a separate
  verified feature, not an implied part of this bridge.

**Build limits:** the proprietary reference host binary and its original
signing credentials are not in the current repository. The provider and
schema can be tested locally in ModKit, but a successful end-to-end host
bootstrap, virtual Google Play, overlay attachment or reversible live recipe
requires an independently built and signed host pair and phone testing.
Do not merge the legacy Space host modules blindly into the main app.

## Explicit limitations

- This is a **catalog + standard Android launcher**, not containerization,
  Play Services hosting, root mode, a multi-user system or a floating overlay.
- A matching certificate **alone** is insufficient: the launcher also checks
  installed APK bytes against the originally verified signed build. This proves
  file identity, not that a gameplay effect occurred. It does **not** promote
  individual recipes to FUNCTIONALLY_VERIFIED.
- Only the latest *available* build of each package is displayed. Historic
  builds whose signed staging files are missing are omitted.
- Saved builds do not become runtime toggles. Further work must implement a
  verified, process-bound command channel and an authenticated Space host.
- No online purchase, server-side state or anti-cheat bypass is provided.

## Selection verification

- JVM tests cover two games, stale SHA after rebuilding, missing/untrusted
  installed packages, duplicate records and malformed signatures.
- Android instrumentation opens the profile screen with a deliberately
  untrusted saved APK. Neither Launch nor Select may activate it.
- The same suite saves the **actual verified build** of the owned movement
  fixture, installs it, selects it in Space and reads back the exact saved
  package/SHA identity. A newer build with the same package must not resolve
  against that old selection.
- **Verified CI (2026-11-04):** [GitHub Actions #41795225279](https://github.com/Ffenuss/ModKit/actions/runs/41795225279)
  passed JVM tests, lint, APK assembly, Android API 29 single APK and Android
  API 35 split APK. This validates ModKit-side selection, not a virtual host.
- The selection is **local ModKit metadata only**. The legacy Space host does
  not consume this preference yet; authenticating that handoff is a separate
  feature and requires its own device tests.

## Provider change notifications

**Verified CI (2026-11-04):** [GitHub Actions #41813897594](https://github.com/Ffenuss/ModKit/actions/runs/41813897594)
passed JVM tests, Android Lint, APK assembly, and full Android API 29 / 35
instrumentation suites, including the new notification and clear regression.
This validates the ModKit-side provider, **not** a separately signed Space host.


Every successful active-profile activation publishes a content change notification
on `content://<applicationId>.space-menu/index` **after** the exact-profile
preference is committed. Clearing the active profile also announces the empty
index. These notifications are hints for a listening host; the host still
must re-read the provider and verify guest identities. Notification delivery
does not revoke or delete profiles previously cached by the legacy host.

## Independently buildable Space Companion (proof of cross-app identity)

The new `:spacecompanion` module builds a separate Android app with package
`io.github.ffenuss.modkit.spacecompanion`. It deliberately does **not** reuse
or patch the unavailable proprietary legacy virtual host binary.

- ModKit exposes its existing read-only `schema=1`, `backend=none` profile
  only if the companion is signed with the *same certificate* as the installed
  ModKit APK. Legacy QA host certificate pins remain separate.
- The companion verifies the publisher package's Android signature before
  reading any provider data. It rechecks profile SHA-256 against the index,
  accepts a single active, non-executable profile and verifies the **complete**
  installed base/split APK hash-and-size set.
- The companion reads no APK contents from ModKit, saves no cached executable
  profiles, and re-reads/re-hashes before offering an **ordinary Android launch**.
  It responds to provider change notifications and clears the view when
  ModKit withdraws the active game.
- Build and install both apps with the same signing certificate. Debug builds
  from the same Gradle/Android environment normally share its debug key;
  unrelated signatures fail closed.
- The CI-owned fixture validates successful cross-app sync and revocation on
  Android API 29 and 35. These tests establish cross-app trust and APK identity,
  **not virtualization**, root capabilities, in-guest overlays, Google Play,
  or reversible runtime recipe execution.

- **Verified CI (2026-11-04):** [GitHub Actions #41817035604](https://github.com/Ffenuss/ModKit/actions/runs/41817035604) passed JVM tests, lint, both APK assemblies and signed APK verification, Android API 29 single-APK and Android API 35 split-APK device tests including Companion handoff and selection withdrawal. The initial run exposed a crash registering a ContentObserver for an uninstalled ModKit authority; Companion now checks installed matching signers and skips absent authorities.

The companion is an intentional, replaceable integration checkpoint. Real
non-root virtualization requires its own supported container and guest process
lifecycle, with independent game/runtime evidence before enabling toggles.

## Multi-game catalog for the independently built Companion

The authenticated provider now also serves a **separate**
`content://<ModKit applicationId>.space-menu/catalog` endpoint with
`{"schema":2,"backend":"none","profiles":[...]}`. This contains up to 32
latest usable build records from distinct package names. Each entry includes
the package name, artifact SHA-256, exact certificate hashes and full APK/split
SHA-256 + size set. There are **no executable recipes, binary paths, tokens or
runtime toggles** in this endpoint.

The old `/index` and `/profile` contract remains unchanged and continues
to expose only the explicitly selected active, *installed-verified* game.
The multi-game catalog is exclusively for the identically signed Companion;
the pinned legacy host cannot enumerate it. Entries are build-time records,
not proof of current installation. Companion treats them as untrusted until
the user selects a game. Every per-game launch:

1. Re-authenticates ModKit's signing certificate and re-reads the current
   catalog (the selected artifact, signer and source sets must still match).
2. Checks the installed game's **actual signer certificate set** against its
   build record.
3. Streams and hashes every installed base/split APK; missing, additional,
   duplicate or modified APKs fail closed.
4. Checks Android launch activity and only then starts the regular app.

Several games can be listed in the same Companion, with independent
verification and no automatic fallback to another version. On-device
verification does not create a virtual Android user, provide Play Services,
insert an overlay into a game process, or apply reversible runtime mods.

### Multi-game catalog validation

**CI passed (2026-11-04):** [Android CI #41834338518](https://github.com/Ffenuss/ModKit/actions/runs/41834338518).
JVM tests, Android Lint, both APK builds and signature checks, Android API 29
single-APK instrumentation and Android API 35 split-APK instrumentation all
succeeded. The device tests include the independent catalog when no legacy
active profile is selected and a verified regular Android launch of the owned
fixture from the Companion catalog button.

This does **not** constitute virtualization, in-game overlay execution, or
support for untested third-party games.

## Opt-in system overlay shell (Android, non-root)

The independent Space Companion now declares the Android overlay permission
(`SYSTEM_ALERT_WINDOW`) and a user-initiated `specialUse` foreground service,
with a visible notification and explicit Stop action. Each catalog game has
an **"Оверлей и запуск"** button distinct from the normal launch button.
If draw-over-apps permission is absent, Companion opens the system consent
screen and requires the user to tap launch again after granting it.

When the user chooses the overlay launch, Companion **first checks** the
signed ModKit catalog, installed certificate(s) and complete APK identity.
Only then it starts its own non-exported foreground service and launches
the installed game normally. The service revalidates the selected profile
independently before adding a draggable `TYPE_APPLICATION_OVERLAY` window,
which offers a compact MK bubble, a non-executable identity panel,
re-verification and an explicit close action. The window is removed on
service shutdown, mismatched build identity or revoked publisher catalog.

**Deliberately not implemented:** writable guest memory, overlay-controlled
game modifications, root runtime patching, Play Services hosting, cloned
user profiles or an isolated guest process. The overlay has no pretend
on/off mod switches and does not inject into any other package.

Validation uses only `dev.modkit.fixture` on disposable emulator devices.
CI grants the overlay app-op using ADB solely for this test; real users must
grant Android's own permission in Settings. Android 14+ uses the
`specialUse` foreground service type; commercial store distribution would
also require a review of platform and Google Play declaration policies.

### Overlay verification evidence

**GitHub Actions #41890594720 (2026-11-04): PASS.** JVM unit tests,
Android Lint, both debug APK assemblies and signed APK checks passed. Device
tests on Android API 29 (single owned APK) and Android API 35 (split owned
APK) passed, including the `m_spaceOverlayShellFollowsVerifiedFixture`
scenario. That scenario used a disposable emulator with an explicitly
granted draw-over-apps app-op and checked the real floating MK bubble,
its non-executable panel, closure, and continued fixture process.

These results demonstrate **the overlay shell and cross-app handoff** only.
They are not proof of sandboxed game execution, in-game memory modification
or third-party compatibility. The normal production consent flow must be
completed by the device owner.

## Fixture-only runtime toggle proof (cooperative, non-virtual)

A first *executable* live control is now implemented for the separately owned
`dev.modkit.fixture` test game. This is intentionally narrower than a
universal game modification system:

- The fixture exports a versioned `content://dev.modkit.fixture.modkit-runtime`
  call endpoint that authorizes only the **actual installed UID** belonging
  to `io.github.ffenuss.modkit.spacecompanion`.
- The fixture process owns an ephemeral `doubleMovement` flag. A player tap
  on **Move step** performs one normal movement write, plus a second movement
  write only when enabled. Normal DEX recipe tests retain their existing
  unmodified movement getter.
- Space Companion shows the runtime ON/OFF button **only** for this explicitly
  supported fixture package, and only after reading the live acknowledged
  state. No generic, unauthenticated runtime patch toggle is advertised.
- Before each ON/OFF, Companion revalidates the currently authenticated ModKit
  catalog, installed certificate identity, and every APK/split hash.
- The game resets the flag on `Activity.onPause`, and Companion sends a safe
  OFF command on overlay close, service cleanup or profile revocation.
  No live-control flag is persisted and no third-party app memory is accessed.
- The Android instrumented regression proves baseline movement, double
  movement while ON, baseline movement after OFF, and baseline movement after
  closing the floating menu, including denial of an unrelated caller UID.

**Not supported:** executing this recipe on other games without an
explicitly implemented and confirmed compatible runtime adapter, virtual
guest execution, root-only memory modifications or universal live injection.

### Verified live ON/OFF runtime effect (2026-11-04)

[GitHub Actions #41897137536](https://github.com/Ffenuss/ModKit/actions/runs/41897137536)
**completed successfully**: JVM unit tests, Android Lint, both APK builds
and verification; Android API 29 (single APK) and Android API 35 (split APK)
device suites each passed all 14 tests.

New instrumentation `n_spaceOwnedFixtureLiveToggleChangesActualMovement`
confirms: (1) other app UID cannot operate the owned fixture provider,
(2) ordinary baseline step, (3) two movement writes while the live toggle is
ON, (4) one movement write again after OFF, and (5) one movement write after
closing the overlay, including safe state cleanup. Distances are verified by
**deltas**, not presumed initial cumulative values, so they work even after
existing static movement recipe tests. The existing Android 35 package
installer test was stabilized against short-lived UiAutomator buttons.

The explicitly cooperative `dev.modkit.fixture` runtime is the only supported
live recipe target. This check is **not** evidence for unowned Android games,
multiple engine runtime backends, or non-root virtualization.

## Runtime recipe contract v2 (two verified independent effects)

`dev.modkit.fixture` now implements the opt-in
`owned_fixture_recipe_v2` contract. The fixture provider returns a bounded,
versioned catalog of **executable** recipe IDs, human labels, and authoritative
live enabled states. It supports exactly two allowed operations:
`movement_x2` (a second movement write-back per player step) and
`damage_shield` (the real player-damage branch is not taken while enabled).

The Companion recognizes the **specific owned fixture adapter** only; it
rejects any unknown ID, duplicate, mismatched label, malformed array,
wrong version, or suspended game claiming enabled states. It constructs
overlay controls from the verified adapter's acknowledged catalog rather
than a static list of speculative game features. Every user ON/OFF
rechecks the publisher catalog, installed game certificate, exact
base/split APK hashes and the active runtime, then requires an explicit
state acknowledgement. The controls have separate state and can operate
concurrently. Shutdown and selection revocation send safe OFF for **both**
recipes; Activity.onPause independently disables both in the game.

On-device validation exercises both recipe switches, in-game actual
health and movement, independence, per-recipe OFF and cleanup when
closing the floating menu. This is **not** a generic recipe execution
protocol for APKs that were not deliberately prepared to support it.
The existing runtimeprobe payload remains a separate in-app/native
probe and is not automatically treated as a v2 executor.

### Recipe contract v2 verification (2026-11-04)

[GitHub Actions #41901298315](https://github.com/Ffenuss/ModKit/actions/runs/41901298315)
**finished SUCCESS (attempt 2)**. JVM tests, lint and both debug APK
assemblies were green on the original attempt; Android API 29 single APK
instrumentation and Android API 35 split APK instrumentation both passed all
15 device tests. A first API 35 attempt suffered an unrelated emulator/system
ANR ("Pixel Launcher isn't responding"), confirmed by the saved UI hierarchy
and device logs. Only the failed API 35 job was rerun, successfully, without
any source changes. The independent fixture damage-handler counter prevents
pre-existing static health getter modifications from masking the observed
behavior of the new shield recipe. The `o_spaceTwoLiveRecipesAreIndependentAndReversible`
scenario proves two concurrent effects, independent OFF for shield, actual
movement write-back and both controls returned OFF on overlay shutdown.

This evidence applies to the **cooperating owned fixture only**. No general
DEX/native live executor or Android guest virtualization was introduced.

## Native Runtime Probe control from Space Companion (prepared APKs only)

This branch adds a **distinct**, fail-closed native patch backend for APKs
previously prepared through ModKit's existing repacked Runtime Probe path.
The runtime probe is injected as a DEX provider into the prepared APK,
so it does not require the game's author to ship a custom Space API.

- ModKit still must **prepare and configure** the native `PATCH` recipes
  using `configureTestMenu`, with exact library, binary virtual address,
  original and replacement byte sequences (already validated by the runtime).
- The injected `RuntimeEvidenceProvider` accepts new
  `preparedNativeStatusV1`, `preparedNativeSetV1` and
  `preparedNativeRestoreV1` calls **only** from the installed
  `io.github.ffenuss.modkit.spacecompanion` UID with a matching ModKit
  publisher signing identity. The Companion cannot configure a recipe or
  send arbitrary addresses/bytes via this interface.
- A native recipe is selectable only while a tracked game Activity is
  resumed and a compatible, explicitly configured `PATCH` item exists.
  The exact native patcher requires the current bytes to match the expected
  bytes and performs the inverse write when switched off.
- The Companion re-reads the authenticated ModKit catalog and re-verifies
  the complete signed installed base/split APK set **before every write**.
  It independently checks the exact injected provider name and versioned
  response, bounded unique IDs and labels, and the acknowledged per-item
  write status. Unknown and non-prepared games retain an information-only
  overlay with no fabricated switch.
- `Activity.onPause`, configuration replacement and clearing attempt
  to restore active native patches. Closing or revoking the Space overlay
  also sends the safe OFF request for the session.

**Evidence levels are intentionally separate.** An acknowledged native
binary patch is a **byte-level write-back confirmation only**. It does NOT
demonstrate that the intended gameplay mechanic changed. The user-facing
native overlay text explicitly says that the gameplay effect is unconfirmed.
In addition, the feature does not inject code into arbitrary running
third-party games: using it requires preparing, re-signing and installing a
separate APK through ModKit. Only the cooperating owned fixture has existing
device-level gameplay proofs; this native backend still needs its own
positive Android in-game effect test on a prepared sample native library.

**Still absent:** true cloned/virtual guest process, live DEX hook for
unmodified APKs, root runtime engine, generic Unity/Unreal/Godot adapters,
and a claim of universal mod compatibility.

### Prepared native bridge verification (2026-11-05)

[GitHub Actions #42020021924](https://github.com/Ffenuss/ModKit/actions/runs/42020021924)
**completed SUCCESS** on functional code commit `be460802`.
The suite passed JVM unit tests, Android Lint, both APK assemblies,
signed APK checks, ARM64 recipe vectors, and all **16 Android device tests**
on API 29 (single APK) and API 35 (split APK). The newly added
`p_unpreparedFixtureNeverAdvertisesNativeBackend` case proves the
ordinary signed fixture without the injected probe does not receive
any phantom native toggles; existing cooperative fixture effects still
pass on both devices.

A prepared runtime probe is now also initialized from Companion before
bringing the verified game Activity to the foreground. This guarantees
the probe gets a chance to register lifecycle callbacks on fresh launch
instead of missing the game's initial resume; it is read-only until
an explicit ON/OFF command. For already-running apps, actual lifecycle
callback delivery remains an Android process-state prerequisite.

**Not proven by this suite:** a successful native code write and a measured
gameplay effect driven end-to-end by Companion on a separate prepared
non-cooperating APK. The new backend remains experimental until such a
positive native fixture test is executed.

## Confirmed native game effect from external Space overlay (2026-11-05)

**New positive on-device proof:** [GitHub Actions #42037815175](https://github.com/Ffenuss/ModKit/actions/runs/42037815175)
completed **SUCCESS** at commit `f4a2d4a6`. JVM unit tests, Android
Lint, app/Companion/native test APK builds, signature validation, and ARM64
reference recipe vectors passed. Instrumentation passed **17/17 tests on
Android API 29** and **17/17 on Android API 35**.

The final Android test,
`q_preparedNativeProbeChangesRealIndependentGame`, executes on both
emulators and proves the native patch affects actual gameplay:

1. New `:nativefixture` APK (`dev.modkit.nativefixture`) has a JNI
   gameplay movement function in `libmodkit_native_fixture.so` and
   deliberately **does not** contain a ModKit-specific controller or
   ContentProvider. Its baseline movement advances **2 units** per tap.
2. Test parses the installed **x86_64 ELF .dynsym** and executable
   section of the real game library to locate a unique exported
   `native_fixture_step` function. It checks exact original machine
   instructions `mov eax,2; ret`; any mismatch rejects the patch.
3. ModKit's actual repacked-test instrumenter adds Runtime Probe DEX
   and its native library to a new APK copy. The common build tail signs,
   aligns, and verifies the APK. The CI-only test uninstalls **only
   this owned fixture** and reinstalls the prepared APK through
   Android's normal user-approved PackageInstaller flow.
4. ModKit configures exactly one already-validated `PATCH` item:
   the function's immediate value changes from **2** to **7**.
   A fully verified Space catalog entry is saved for the installed APK.
5. Space Companion, signed like ModKit, opens a real external
   `TYPE_APPLICATION_OVERLAY` above the separate game, discovers its
   prepared Runtime Probe and toggles the recipe by ID.
6. Instrumentation proves **+2 before ON, +7 while ON, +2 after OFF**,
   followed by **+2 after closing the overlay** with automatic restoration.
   This is a confirmed gameplay effect, *not* an assertion based on UI
   switch labels or a successful native byte-write acknowledgement.

This establishes a functioning **prepared native APK → external overlay →
actual native gameplay mutation → restoration** pipeline, independent of a
game-authored ModKit control API.

**Scope limits:** Android emulators for API 29/35 are both **x86_64**;
the binary positive proof is therefore x86_64 only, even though the
test APK compiles four Android ABIs. The tested game is entirely
repository-owned; no third-party engine, production game, ARM64 native
write-back, root mode, non-repacked runtime or virtual guest was
validated by this test. The original native fixture deliberately has
**no package visibility** for the publisher or Companion; the prepared APK
receives those scoped entries during manifest injection (see below).
Runtime patching on real games must still be backed by an exact per-target recipe and in-game effect
validation; byte-write acknowledgement alone is insufficient.

## Scoped package visibility for prepared native APKs (2026-11-08)

On Android 11+, the Runtime Probe must discover the exact ModKit publisher
and Space Companion packages before accepting authenticated IPC calls. The
previous positive native fixture test relied on a manually authored
`<queries>` block inside the **original** fixture APK; an arbitrary APK
without this block could fail closed before applying a valid recipe.

The binary-manifest injector now adds only the three exact package names
`io.github.ffenuss.modkit`, `io.github.ffenuss.modkit.test`, and
`io.github.ffenuss.modkit.spacecompanion` to a prepared APK's manifest.
If an existing root-level `<queries>` element is present, it preserves
unrelated entries and appends only missing package declarations; otherwise
it inserts a new element immediately before `<application>`. Repeated
preparation leaves the bytes unchanged. This adds package *visibility*, not
access privileges, signature exemptions, or executable patch recipes.

The repository-owned `:nativefixture` no longer declares those packages
up front. Its full positive native gameplay test now serves as an Android
11+ regression for injector-supplied visibility, keeping exact caller UID,
signer validation, installed-APK hash checks, and native byte validation.

This change is limited to **explicitly prepared, re-signed APKs**. It does
not permit cross-app runtime injection into unmodified games, virtualized
Android guests, or root mode; actual validation should be taken from the
CI run for the branch and not inferred from these source changes alone.

### Verified Android CI (PR #52)

[Actions run #42414699593](https://github.com/Ffenuss/ModKit/actions/runs/42414699593)
for functional commit `1f30adb` completed **SUCCESS**, including:

- JVM tests, Android Lint, signed APK builds, native payload checks and
  independently executed ARM64 recipe reference vectors — **PASS**.
- Android API 29, single original fixture APK — **17/17 device tests PASS**.
- Android API 35, original fixture split APK — **17/17 device tests PASS**
  on rerun attempt 2, with **no source changes** from the functional commit.
- The first API 35 attempt was blocked by an emulator-level **Pixel Launcher
  isn't responding** ANR, with a saved screenshot and UI evidence. Retrying
  the failed job alone restored the previously passing 17/17 behavior.
- `q_preparedNativeProbeChangesRealIndependentGame` now confirms that the
  original native game manifest has **no** package visibility declarations,
  while the installed prepared manifest contains exactly the three required
  publisher/Companion package declarations, before measuring actual native
  movement `+2 → +7 → +2` and reset on overlay close.

The validated change does not demonstrate support for arbitrary external
Android games or any real ARM64 native patch execution; both device emulator
runs use x86_64. The final workflow-filter/doc commits do not alter
production code after this successful validation.

## ARM64 exact-fixture CPU proof (2026-11-08)

The existing positive native gameplay Android test now uses the device's
reported 64-bit ABI (`x86_64` or `arm64-v8a`) to choose its exact ELF
symbol/byte recipe. No address is guessed: `NativeFixtureElfProof`
resolves the exported `native_fixture_step` from the *installed*
fixture's dynamic symbol table and verifies the matching executable section.

For ARM64, the sole prepared write replaces one aligned 4-byte
instruction `mov w0,#2` (`40008052`) with `mov w0,#7`
(`e0008052`), leaving the `ret` untouched. The same exact
expected-original-byte guard applies on ON and inverse OFF. The device
test also validates this recipe against the ARM64 ELF bundled in the
original multi-ABI fixture APK, even when the Android emulator uses x86_64.

CI additionally takes the *actual Android NDK-built* ARM64
`libmodkit_native_fixture.so` from `:nativefixture:assembleDebug`
and runs `tools/verify_arm64_fixture_elf.py`. The verifier checks
ELF64 class, AArch64 machine type, section boundaries, the unique
exported function in `.dynsym`, its precise file offset and original
bytes. Unicorn 2.1.4 then independently executes the original function,
the guarded replacement and exact restoration, expecting
`2 → 7 → 2`. A stale original-byte expectation must be rejected.
The JSON evidence is saved to
`app/build/arm64-fixture-verification.json`.

**Verification boundary:** this is actual NDK ARM64 *machine-code
execution in Unicorn*, not an Android ARM64 device/overlay write-back
demonstration. Existing x86_64 Android device tests continue exercising
actual prepared APKs, user-confirmed installation, overlay control
and in-game movement. The ARM64 on-device branch will require a
separate real ARM64 test run before it can be called proven; no claims
are made for arbitrary games, non-repacked runtime injection or root.

### CI evidence for ARM64 ELF proof (PR #53)

[GitHub Actions #42419020019](https://github.com/Ffenuss/ModKit/actions/runs/42419020019)
completed **SUCCESS** on functional commit `2f878ff`:

- JVM tests, Lint, all APK assemblies/signatures, existing ARM64 reference
  vectors, and the new real NDK ELF + Unicorn test — **PASS**.
- ARM64 `native_fixture_step` symbol VA `0x688`, ELF file offset
  `1672`, original instruction+return `40008052c0035fd6`.
- Exact first instruction `40008052 → e0008052`, independently
  executed in Unicorn as `2 → 7 → 2`; stale expected bytes rejected
  and restoration byte-identical.
- Android API29 single APK — **17/17 device tests PASS**.
- Android API35 split APK — **17/17 device tests PASS**.
- The initial host verifier had rejected normal file-less ELF
  `SHT_NOBITS` sections. The parser was corrected; this was a
  test-tool bug, not a native Runtime Probe or game behavior regression.
  No source changes were needed after the successful functional commit
  except documentation and restoring the CI push filter.

**Not established:** Android ARM64 live mprotect/write/restore from
the Space Companion. Both Android device suites still use x86_64
emulators; on-device ARM64 validation remains a separate release gate.
