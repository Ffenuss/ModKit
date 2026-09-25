# Flick Shot Rogues: runtime-overlay diagnostic snapshot (2026-09-25)

Source: user-provided `ModKit-AutoMod-report-a530cc7f86e1.zip`, generated
2026-09-25T17:12:58Z, schema v2, artifact SHA-256
`a530cc7f86e1b809b38fd492d27a6d3af2e58730d227c0329d4d96a1e337e62e`.
The original game APK and native library were **not** supplied; no game
assets, dump.cs or method bodies are committed.

## What the report actually confirms

- Unity IL2CPP metadata v31; ARM64 binary. 112,059 method metadata
  definitions and 69,169 fields. **Only 30,000** binary method bindings
  materialized, so the catalog is not exhaustive.
- 409 current strict candidates, only eight of them initially marked
  selectable. The simple catalog applies further proof checks and scalar
  choices: **308 displayed recipes; 12 selectable; 296 blocked**.
- **209 blocked recipes** are semantic field-name signals for which the runtime
  field offset, live object instance and safe state-writing operation are
  unproven. Do not turn those signals into switches or constant writes.
- The 12 selectable recipes involve scalar getters/return paths including
  health, specific damage multipliers, stamina costs, progression cost and
  camera zoom. Some methods belong to HUD/display classes. Their *purpose*
  and actual gameplay effects were **not** confirmed by device testing.
- More complex methods (e.g. damage state updates) are deliberately blocked
  when they have unknown instructions, side effects, calls or unresolved
  control flow. An address binding alone does not justify a live patch.

## UX decision

For ARM64 recipes with an independently verified read-only body, full
function-pointer index, unique body, exact target binding and verified
original/replacement byte pair, the simple UI creates a separate
**runtime-probe-injected** build. Those changes are *not* pre-applied to
`libil2cpp.so`: an externally approved ModKit overlay controls initially-OFF
switches via authenticated ContentProvider IPC into the running game process.
A rejected byte comparison leaves the visible switch OFF; turning it OFF
restores the exact original bytes. This is a user-initiated runtime test,
not automatic proof of a gameplay effect.

Legacy DEX patches remain available with a **static-only** label for
compatibility with existing functionality and Android instrumentation. A
reversible DEX executor needs its own explicit design and separate tests.
Unknown/sensitive categories remain unavailable to runtime switching.

## Android security and distribution

Overlay permission is only a user interface capability. It does **not** give
ModKit the ability to modify another process, replace the original signing
key, override Google Play install provenance or guarantee that a modified
third-party app launches. The native toggle executor is included in a
separately signed test APK. The injector and original source inputs remain
SHA-verified. On signature conflict, do not silently uninstall the original:
saved data may be lost. Unsupported or protected targets must produce a
specific diagnostic, not a fabricated success.

## Required manual Android tests before considering this feature complete

1. On a *developer-owned or explicitly authorized* ARM64 IL2CPP fixture,
   select at least one proven scalar return recipe. Verify the built APK's
   native method starts with original bytes, and the overlay contains only
   selected items, all initially OFF.
2. Deny overlay permission: the app must not start an overlay and must
   continue to retain the existing signed APK and saved analysis.
3. Grant SYSTEM_ALERT_WINDOW in Android Settings: confirm an actual floating
   `MK` bubble appears over the fixture. Confirm that gameplay is unchanged
   while switches are OFF.
4. Toggle ON and OFF repeatedly while the target module is loaded. Check
   IPC acknowledgements, expected byte comparison, effect, exact byte
   restoration and failure handling when the module is missing.
5. Verify behavior after rotation, screen lock, game exit/relaunch, ModKit
   process restart, overlay permission revocation and service termination.
6. Validate a multi-split fixture, reinstall and signature-conflict recovery
   **without removing any user app or data**.
7. Run Android 29 and 35 automated regression suites. Those existing suites
   use a separate DEX fixture and do not prove Flick Shot IL2CPP runtime
   compatibility.

Status: implementation/automated tests on this PR do not constitute
physical Unity-game runtime confirmation.
