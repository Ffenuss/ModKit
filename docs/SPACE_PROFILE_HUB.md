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
- The selection is **local ModKit metadata only**. The legacy Space host does
  not consume this preference yet; authenticating that handoff is a separate
  feature and requires its own device tests.
