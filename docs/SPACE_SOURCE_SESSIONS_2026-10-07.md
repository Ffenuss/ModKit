# Original application sessions in the virtual space

The host overlay now offers a read-only inventory of the original base APK and split APKs belonging to the selected package and virtual user. It obtains paths from the virtual kernel (`cp`, `ck`, `InstalledAppInfo.f`), not the device's outside-space package manager. This retains the product direction: original application inside a space with the overlay, no guest repackaging.

Each inventory run receives its own cancellation token and UUID. Inputs must be readable, unique canonical files. SHA-256 identities are collected before inspection and checked again afterward. The kernel record is resolved again before publishing so that an update/uninstall cannot silently leave evidence bound to an old path. Results and progress are ignored after a target/user change, explicit cancellation, or overlay removal. Inventory work uses its own executor and does not block the Google runtime checks.

The report currently contains base/split sizes and hashes, an ordered set identity, ZIP entry count, file signatures for DEX/ELF/IL2CPP metadata, and ABI directory names. It reads only eight uncompressed bytes per ZIP entry, caps the set at 128 APKs and 100000 entries, rejects duplicate ZIP names, and does not extract files or modify guests. All hashes stream in fixed-size buffers. It reports no gameplay changes, inferred mod offsets, runtime attachment or fake engine validation.

## Validation

- API35 Java compilation passed.
- Existing session policy test passed.
- New JVM fixtures passed: base/splits, exact hashes, unique sessions, cancellation, missing/duplicate inputs, malformed ZIP and source mutation rejection.
- Six Python tests passed against the exact reference host. The unchanged ABI assertions include metadata resolution (`ck`, `f`) as well as Google checks and virtual launch.
- Attempting the local game-file set failed due to a missing ZIP end header; that attempt produced no inventory result. This is not device validation.

## Remaining implementation

1. Extract the existing ModKit analysis indexer into a reusable module while preserving its public app API and tests. Consume these verified original sources, with cancellation and the same package/user session identity.
2. Load the shared engine in the host with an explicit dependency/classloader strategy; the reference host already ships third-party classes, so blindly adding another Kotlin/AndroidX runtime risks conflicts.
3. Persist bounded reports and attach only validated findings to the corresponding session. Add capability checks before exposing runtime actions.
4. Assemble/sign with the existing host key and run Android acceptance checks: original install and splits, Aniimo launch, overlay switching/cancellation, launch/resume ad suppression, virtual Google Play and GMS compatibility.

The host ad patches and virtual Google handling from the preceding implementation remain present. A signed, device-tested APK is still outstanding.
