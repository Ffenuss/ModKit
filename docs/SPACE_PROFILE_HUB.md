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
7. The profile launcher checks the installed package **and current signer**
   against that saved build before allowing the regular Android launch.

Changing the APK's signature, uninstalling the build, deleting its local staged
files, or replacing the game prevents launching from that saved profile.

## Security / evidence boundary

- `SpaceBuildProfileIndex`: bounded disk inventory, SHA-only filenames,
  canonical file checks, parser reuse, per-package deduplication. It does not
  invent mod capabilities or mix APK splits between apps.
- `SpaceLaunchIdentityPolicy`: pure launch decision for package name, **exact
  current signer set**, and launch activity. Signer mismatch fails closed.
- `SpaceInstalledPackageLauncher`: reads Android `SigningInfo` on API 28+,
  legacy signatures for API 26–27; hashes DER certificate bytes with SHA-256.
  A fresh identity check is performed on every launch request.
- `SpaceProfilesScreen`: displays build metadata and supported start action;
  neither UI labels nor switches claim dynamic runtime modifications.
- The feature is intended to be compatible with a **future** authenticated
  export to the previously developed `SpaceMenuProvider` /
  `SpaceMenuHandoff` contract. Do not copy those legacy modules wholesale
  without verifying their current build and permission model.

## Explicit limitations

- This is a **catalog + standard Android launcher**, not containerization,
  Play Services hosting, root mode, a multi-user system or a floating overlay.
- A matching signing certificate only proves installed APK signer identity;
  it does not prove that the currently installed APK bytes equal a saved
  modified build. Do not promote a recipe to FUNCTIONALLY_VERIFIED on this basis.
- Only the latest *available* build of each package is displayed. Historic
  builds whose signed staging files are missing are omitted.
- Saved builds do not become runtime toggles. Further work must implement a
  verified, process-bound command channel and an authenticated Space host.
- No online purchase, server-side state or anti-cheat bypass is provided.
