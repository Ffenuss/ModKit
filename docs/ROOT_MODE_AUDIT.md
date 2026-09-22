# Root mode audit

Audit target: ModKit 0.0.12 root/live stack after the behavioral-overlay merge.

## Verified implementation paths

The following paths are concrete in code and are covered by compile/lint/unit CI where applicable:

- Root access probe and main-process discovery.
- Direct attach to an exact PID with cmdline/PID-reuse checks.
- Launch of a non-running installed app, wait for its exact main PID, then attach.
- Foreground return to the selected game and floating MK overlay launch.
- Continuous bounded behavioral scan with rolling baselines and noise filtering.
- Explicit action training for movement, attack, taking damage, resources, items and generic actions.
- Manual exact/unknown scans plus changed/unchanged/increased/decreased refinement.
- Range, fuzzy and grouped-value initial scans.
- Verified runtime writes and Freeze.
- Module-root pointer-chain persistence and ASLR re-resolution.
- Per-package/per-artifact learned profiles.
- Read-only migration attempt after an app update until explicit reconfirmation.
- Managed-profile root sandbox with separate app-data and exact sandbox PID activation.
- Sandbox native-code live toggles with byte verification and rollback.
- Quick and full runtime dump modes.

## Hardening added by this audit

- Root mode opens directly into root/process discovery instead of requiring a redundant separate probe click.
- Overlay host Android user is derived from the actual ModKit UID rather than assuming user 0.
- The live overlay sizes itself to the real portrait/landscape viewport.
- Auto scanning is paused while manual scans or Freeze are running so multiple heavy root-memory jobs do not compete.
- Free-running Auto mode no longer launches expensive pointer-chain scans behind the game; persistence is automatic after explicit action training or explicit pinning.
- Manual scans have explicit quick/full scope. Quick mode bounds the initial memory pass; Full remains available when coverage matters more than latency.
- Artifact identity hashing is cached against package version + installed APK path/size/mtime metadata so reopening the overlay does not repeatedly hash large APK/split sets.
- Pointer-chain discovery/re-resolution has dedicated regression tests, including ASLR movement and ambiguous-module fail-closed behavior.

## Important gaps relative to the intended end state

These are real architectural gaps, not UI polish:

1. **Live value → writer/reader code correlation is not implemented yet.**
   The current behavioral scanner finds values that change with an action. It cannot universally prove which native/VM method reads or writes that value.

2. **Constant gameplay parameters are not universally discoverable from change-only behavior.**
   A speed cap, damage coefficient, cooldown constant or max-health constant may remain unchanged while the player acts. The current scanner may find velocity/coordinates/HP counters instead of the underlying constant.

3. **Automatic semantic naming remains heuristic outside explicit training.**
   Auto mode can rank likely gameplay state, but it cannot reliably know that an arbitrary decreasing integer is specifically HP or that a changed float is specifically movement speed without stronger evidence.

4. **Root native trace/watchpoint execution is not implemented.**
   The repository has trace/evidence infrastructure, but the root executor required to dynamically observe which instructions/methods touch a selected live address is still missing. This is the main prerequisite for automatic controls such as "disable incoming damage" instead of merely freezing HP.

5. **No rooted-device integration test exists in GitHub CI.**
   CI proves compile/unit/lint/APK integrity. It cannot prove OEM root-manager behavior, SELinux policy, /proc/<pid>/mem access, managed-profile creation or overlay behavior on a physical phone.

6. **Freeze is deliberately fail-closed but still expensive.**
   Every write revalidates process/mapping state and read-back. This is safer than a blind high-frequency writer, but it has more root-shell overhead than GameGuardian-style native in-process memory access.

7. **Automatic recovery after a game process restarts is not yet implemented.**
   Saved pointer-chain profiles survive restarts, but an already-open overlay bound to a dead PID does not transparently reattach to a newly spawned process.

## Testing recommendation

Do not spend time validating every advertised automatic mod category yet.

The useful device test for the current architecture is:

1. Enter Root and confirm the app/process list appears automatically.
2. Launch or attach one offline/local game.
3. Confirm MK appears and opens correctly in the game's orientation.
4. Test one known manual value with quick scan -> refine -> write -> Freeze.
5. Test one explicit action-training cycle (for example taking damage -> reopen MK).
6. Pin one confirmed candidate, restart the game, and confirm its pointer-chain profile can be resolved again.
7. If those pass, capture timing/errors for Auto scan and pointer persistence.

The next engineering milestone before calling root mode feature-complete is dynamic address-to-code correlation / root tracing.
