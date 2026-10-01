# Aniimo Android runtime-loader evaluation

This note records the selected technical direction for ModKit's Aniimo quick-start work.

## Target

- Android package: `com.x.aniimos`
- Mobile launch: September 23, 2026.
- The first gate in ModKit is conservative runtime detection: package identity plus Unity and IL2CPP evidence.
- xLua is treated as an additional capability signal, not as a requirement for the base overlay.

## Reuse before rewrite

The existing ModKit code already has:

- a repacked runtime probe;
- an in-app floating runtime menu with switches;
- JNI/native bridge code;
- verified byte patch application and rollback;
- passive native/JNI tracing;
- a build/install/launch pipeline for repacked test APKs.

The Aniimo path should extend those pieces instead of creating a second independent loader.

## Recommended external building blocks

### ShadowHook

Repository: https://github.com/bytedance/android-inline-hook

Preferred native hook backend for production-quality Android ARM32/ARM64 instrumentation. Current releases support modern Android versions and include function-address hooks, symbol hooks, intercepts, late-loaded ELF handling, operation records, and unwind compatibility.

Use as a replaceable hook backend behind ModKit's native bridge rather than exposing ShadowHook types to UI code.

### ByteHook

Repository: https://github.com/bytedance/bhook

PLT-hook backend for cases where a PLT/GOT hook is the most stable option. Keep it optional; ModKit already contains custom PLT/JNI tracing and should not depend on both backends unless a target requires it.

### BNM-Android

Repository: https://github.com/ByNameModding/BNM-Android

Useful IL2CPP class/method/field lookup layer for Android. It can reduce dependence on fixed raw offsets when metadata is available. Integrate through an adapter so ModKit can fall back to its own IL2CPP scanners.

### Dear ImGui / Android overlay

Dear ImGui itself: https://github.com/ocornut/imgui

ModKit already has a Java floating overlay. Do not replace it immediately. Add ImGui only if the target requires a native render overlay (for example, entity ESP rendered in the game frame).

### FusionCore / BepInEx Android Launcher

FusionCore: https://github.com/All-Of-Us-Mods/FusionCore
BepInEx Android Launcher: https://github.com/NextBep/BepInEx.Android.Launcher

These are useful architectural references for one-tap Unity IL2CPP launch and modpack management. FusionCore is GPL-3.0, so its source should not be copied into the Apache-2.0 ModKit codebase unless the project intentionally accepts the resulting license obligations.

## First implementation stages

1. Detect Aniimo by package and runtime evidence.
2. Reuse the existing repacked runtime path to get a verified overlay into the target process.
3. Add a hook-backend abstraction to `runtimeprobe`; keep the current code as fallback.
4. Integrate ShadowHook as the first external backend.
5. Add an IL2CPP binding adapter. Prefer BNM when metadata is usable; fall back to ModKit's current scanners.
6. Add an xLua capability detector and read-only runtime inspection path.
7. Split overlay entries into categories and add sliders/value controls in addition to boolean switches.
8. Require every writable feature binding to pass target/module/version and expected-byte checks before it becomes selectable.

## Non-goals

This work does not add anti-cheat evasion, stealth injection, integrity-check bypasses, signature/provenance bypasses, or mechanisms intended to hide ModKit from the target.
