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
