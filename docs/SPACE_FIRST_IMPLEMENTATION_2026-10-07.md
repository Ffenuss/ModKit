# ModKit: original applications inside a space

The owner's 2026-10-07 requirement changes the release destination: users add the original installed application to a space, then launch it with the host-owned ModKit overlay. Guests must not require repacking to get the overlay. Host launch/resume advertisements must be suppressed. Google Play and needed Google services must be available and checked inside the same virtual user.

The previous APK-repack executor remains an expert capability. PRs #24–#27 and the verified 0.0.35 baseline remain valuable analysis/runtime sources; the installer compatibility failure is not the first blocker for the primary space launch route anymore. Root capabilities from PR #28 are a separate optional backend to reconcile with the unified line, not a replacement for a functioning non-root virtual host.

## First implementation

`spacehost/` uses the exact supplied host from the Aniimo work, preserving its initialization and kernel. It adds a generic, host-only overlay bootstrap, a verified launch-Runnable hook, virtual-user-scoped Google inspection and suppression of the proven interstitial routes. The host APK is user-supplied and is not added to the public repository. Current tests establish source compilation and static payload/DEX preservation, not Android gameplay success.

## Next implementation sequence

1. Assemble the host using the existing signing key; test the original app grid, guest startup, permission grant/revoke and one MK window on physical ARM64. Verify launch/resume ads are absent and check for additional host advertising paths.
2. Check virtual Play Store, GMS, GSF, sign-in and a guest's actual service discovery in the same user. Report specific missing components and do not substitute host-device detection.
3. Bring the existing ModKit inventory/analysis modules into the host through a separate module with a kernel adapter. Bind every session to guest package, virtual user, PID, artifact digest and a per-launch session token.
4. Connect reversible mutation controllers through the in-process virtual-client lifecycle, using existing DEX/native/root evidence requirements. A visible overlay alone must not enable switches.
5. Add universal action correlation and engine adapters to that shared runtime, then cover split packages, restarts, low storage and untouched original application data.
6. Produce one integrated RC APK; update main after the established integration and release checks.

No claim of a completed universal runtime, working game modifications or Play Integrity compatibility is made by the first stage.
