# 0.0.36 — Flutter JSON and Unreal INI resource mods

Status: implementation prepared; Android CI and distribution APK verification pending.

The ordinary AutoMod screen now discovers numeric/boolean fields in packaged Flutter JSON and loose Unreal INI resources. Users can choose values and build a signed APK. Resource changes are applied at build time, visibly distinguished from live DEX/native switches. The build can contain both kinds, and a resource-only build does not advertise nonfunctional overlay switches.

Every edit is bound to the analyzed artifact, APK split, original file SHA and field value. Stale/ambiguous data is rejected; unrelated archive contents and source APKs are preserved. See [executor review](porting/ENGINE_RESOURCE_MODS.md) for bounds and exact coverage.

No universal engine support is claimed. Dart AOT, Unreal PAK/IoStore/Blueprint and other engine backends remain incomplete. This release contains no installer-source compatibility change; that work is parked separately. Real-app effects and all user-reported games remain unconfirmed until tested on their actual artifacts.

Validation results and downloadable APK identity will be recorded after CI completes.
