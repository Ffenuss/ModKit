# Open mod mechanisms and the Space execution gap

Research date: 2026-10-10. Source review only: the referenced games and mods were
not run here. A working desktop mod does not prove an Android-compatible loader
or a matching mobile binary. No third-party mod code was copied into ModKit.

## Reviewed implementations

| Game / genre | Open implementation | Actual mechanism | Disable behavior | Space requirement |
| --- | --- | --- | --- | --- |
| Stardew Valley / farming simulator and RPG | CJB Cheats Menu, InfiniteHealthCheat | Each update, after a world-ready check, assigns current player health from maximum health. | Disabling stops the update subscription; it does not restore previously lost health. | Resolve the live player and typed fields, run on the game update thread, rebind after world/player changes. |
| Stardew Valley / simulator | CJB Cheats Menu, MoveSpeedCheat | Adds a buff with a specific mod-owned ID, renews its duration, and respects running/cutscene conditions. | Removes that buff on configuration changes. | A game-specific API adapter and ownership of the created effect; do not overwrite a global speed constant. |
| Stardew Valley / simulator | CJB Cheats Menu, FreezeTimeCheat | Repeatedly resets the game's clock interval while configured location predicates are true. | Stops enforcement; it does not rewind the clock. | Typed clock object/state binding, update callback and predicates. |
| Slay the Spire / card roguelike | BaseMod, Energy and InfiniteEnergy | Command toggles a flag and grants energy. A method prefix captures energy, the original useEnergy runs, and a postfix restores the captured energy while enabled. | Original consumption resumes. The command's previous grant is not undone. | Before/after hooks, per-invocation storage and original-method execution. Replacing the entire method with RET would also skip unrelated work. |
| Mindustry / automation and tower-defense RTS | Hackustry, worldoptions | Resolves the current rules object, checks a field is boolean and toggles rules such as infiniteResources, waveTimer and unitAmmo; unit cap is numeric. | Toggles the current rule again, rather than implementing a generic snapshot rollback. UI is disabled in the menu and for a network client. | In-process DEX object access or a supported mod-loader adapter, world lifetime and execution-authority checks. |
| Risk of Rain 2 / action roguelike shooter | DebugToolkit, Command_Noclip | Changes collision layers or collider trigger state, changes gravity, writes velocity during updates and hooks out-of-bounds handling. Reapplies after body changes. | Rebuilds collisions/gravity and disables at disconnect/run teardown. | A coordinated group of runtime-object operations, player targeting, update callbacks, original call-through and lifecycle cleanup. |

## Inspected source paths

CJB Cheats Menu tree snapshot `9bf8e959209eb19b047c6bfca81783408ade1db8`:

- [InfiniteHealthCheat.cs](https://github.com/CJBok/SDV-Mods/blob/master/CJBCheatsMenu/Framework/Cheats/PlayerAndTools/InfiniteHealthCheat.cs)
- [MoveSpeedCheat.cs](https://github.com/CJBok/SDV-Mods/blob/master/CJBCheatsMenu/Framework/Cheats/PlayerAndTools/MoveSpeedCheat.cs)
- [FreezeTimeCheat.cs](https://github.com/CJBok/SDV-Mods/blob/master/CJBCheatsMenu/Framework/Cheats/Time/FreezeTimeCheat.cs)

BaseMod tree snapshot `26de1afc1a8ea7595b61f940de0ac29650f2c025`:

- [Energy.java](https://github.com/daviscook477/BaseMod/blob/master/mod/src/main/java/basemod/devcommands/energy/Energy.java)
- [InfiniteEnergy.java](https://github.com/daviscook477/BaseMod/blob/master/mod/src/main/java/basemod/patches/com/megacrit/cardcrawl/ui/panels/EnergyPanel/InfiniteEnergy.java)

Hackustry tree snapshot `82dca946b366bca7f0767254e1dfb61afe4780c7`:

- [worldoptions.js](https://github.com/QmelZ/hackustry/blob/master/scripts/features/v4/worldoptions.js)

DebugToolkit tree snapshot `4f5d135aa8dabb0ec2cad107b72e074e310017cb`:

- [Command_Noclip.cs](https://github.com/harbingerofme/DebugToolkit/blob/master/Code/DT-Commands/Command_Noclip.cs)
- [Hooks.cs](https://github.com/harbingerofme/DebugToolkit/blob/master/Code/Hooks.cs)

## What the current Space actually executes

`spacehost/.../NativePatch.java` accepts a module, ABI, binary virtual address,
image hash and equal-sized expected/replacement bytes. `SpaceNativeBackend.java`
checks the loaded image and delegates a code write to RuntimeNativeBridge.
The native catalog generates bounded scalar-return replacements and a limited
single-field-setter skip. DEX rewriting has original-body backups, but the
original Space profile does not expose an in-process DEX method-hook backend.

This supports some result overrides. It does not provide any of the following:

- general original-call trampolines with before/after hooks;
- live managed object resolution and typed field access;
- game-thread update callbacks;
- game API / buff ownership adapters;
- coordinated collision/gravity/movement operations.

Adding semantic names cannot supply those execution capabilities. Do not turn
the researched examples into enabled switches until their required backend and
target binding exist.

## Implementation order derived from the source review

1. Preserve the original call and implement typed result transforms. A speed
   multiplier must operate on the real computed value, retaining side effects,
   rather than replacing every matching getter with an absolute constant.
2. Add typed before/after hooks with invocation-local state. The energy example
   requires reentrancy and concurrent-call correctness; a shared global saved
   value is not a suitable generic implementation.
3. Add runtime object binding to a process/session/world generation and execution
   on the owning game thread. Reacquire the player after scene changes or death.
4. Implement field maintenance and owned-effect operations. The recipe must state
   whether OFF restores behavior, removes its owned effect, or restores a field;
   never imply that previously applied healing/rewards are automatically undone.
5. Implement grouped runtime recipes for collision/gravity/movement, with
   original values or game-owned restoration APIs, conflict handling and cleanup.

For each executor, prove behavior on owned applications: original side effects
still occur, ON affects the intended object, OFF resumes original behavior,
unrelated objects remain unchanged, reload/restart clears session state, and
stale callbacks cannot touch a replacement or destroyed object. These are
requirements for future implementation, not completed tests of these mods.

The minimal overlay remains the control surface. Game/version-specific adapters
may use the same recipe operations; desktop SMAPI, ModTheSpire, BepInEx and the
Mindustry JavaScript loader are not claimed to run automatically inside Space.
