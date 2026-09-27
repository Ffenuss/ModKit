# Flutter and Unreal resource modification executors

This is new implementation, not imported legacy code. The earlier Flutter/Unreal inventories only recognized files. This stage introduces exact changes to packaged text resources, alongside the existing reversible DEX and ARM64 recipes.

## Supported scope

- Flutter: UTF-8 JSON assets below `assets/flutter_assets/`, including nested objects/arrays, integer/decimal/boolean values. Generated asset/font/native manifests are excluded. Strings, URLs and names are preserved.
- Unreal: loose `.ini` files under an APK `assets/.../Config/` directory, UTF-8 or BOM-marked UTF-16. Unique scalar keys in sections are eligible. Repeated keys (case insensitive), array/merge operators and ambiguous split paths are excluded.
- Discovery runs in the ordinary AutoMod flow when the artifact index has the corresponding engine profile. It reports each file field and original value; field names do not prove gameplay purpose or premium entitlement.
- A chosen value is applied at build time and is shown as such. Resource recipes have no runtime toggle. They can share a build with initially OFF reversible DEX/native switches; a resource-only build has zero menu switches.

## Evidence and bounds

The source APK-set is reopened and SHA-256 checked for discovery and again for building. Every resource change binds the artifact SHA, APK index/name, entry, content SHA/size, field identity and original value. The executor edits only selected lexical spans, reparses the result, compares all scalar fields, and verifies every ZIP entry after rebuilding. Original APKs are untouched. Signing and ZIP alignment use the existing verified build path.

Limits are explicit: 2 MiB per text file, 32 MiB across the scan, 10,000 eligible fields, JSON depth 32 and 100,000 nodes. Omitted files produce a warning. Duplicate JSON keys, malformed UTF, unsafe paths, stale content, cancellation, unknown selections and type-changing replacements fail closed. Unreal's configuration hierarchy or application overrides can supersede a changed packaged default; successful rewriting is not runtime evidence.

## Incomplete scope

No Dart AOT, Blueprint/UObject, PAK/IoStore rewriting, encrypted-resource decoding, external downloads, or resource reload hook is implemented here. ModKit does not manufacture an INI file where none exists. The user-provided screenshots/reports alone cannot prove any changed value is consumed by their particular game. No universal engine/app coverage is claimed.

## Verification

- JVM: strict parsing, exact unselected-byte preservation, mixed encodings, ambiguous keys, cancelled/oversized/stale inputs, cross-split resources and combined DEX/resource staging.
- Android API 29 and 35: an owned Flutter 3.35.4 debug x86_64 application loads `assets/game.json` through `rootBundle.loadString`. The test builds/installs a resource-only mod, observes real game state, verifies the unchanged damage field and relaunches the process. This fixture contains no ModKit API or test override.
- Unreal verification is parser/archive verification only; no Unreal runtime fixture is available in this repository. A successful CI run does not prove Delta Force or STAR DIVE support.
- Unit/lint/assembly/signature/alignment and existing native/DEX overlay tests remain required. See release notes for actual completed run IDs and counts; this document describes the gates, not a claim that pending gates passed.

Primary format references: [Flutter assets](https://docs.flutter.dev/ui/assets/assets-and-images), [Unreal configuration](https://dev.epicgames.com/documentation/unreal-engine/configuration-files-in-unreal-engine), [pinned Flutter release](https://github.com/flutter/flutter/releases/tag/3.35.4).
