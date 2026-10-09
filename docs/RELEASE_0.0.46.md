# ModKit 0.0.46 — implementation and validation

Code tested: `89cb4a8fc3e644d1c270cf9922345168191d1aac`.
PR: https://github.com/Ffenuss/ModKit/pull/31
Android CI: https://github.com/Ffenuss/ModKit/actions/runs/37984662887
Space CI: https://github.com/Ffenuss/ModKit/actions/runs/37984330515

## Implemented

Exact static `java.lang.Math.round(float):int`, `round(double):long`,
`floor(double):double`, `ceil(double):double`, and `sqrt(double):double`.
Both invoke encodings are accepted. Argument words and result width are
checked separately. Invalid owners, prototypes, wide pairs, result widths,
and branches bypassing invoke before MOVE_RESULT remain blocked.

Backup methods preserve the original Math calls, constants, register counts,
result instructions and signatures for OFF. The owned fixture retains seven
menu switches and eight instrumented methods. No new UI controls were added.

## Verified

| Check | Result |
| --- | --- |
| ModKit unit tests | 447 passed; 0 failures/errors/skips |
| Owned fixture original values | 106 checks passed |
| Lint | Passed |
| Debug and production release assembly | Passed |
| Independent ARM64 execution | 12 vectors passed |
| Android 29, single APK | 8 tests passed; 0 failures/errors/skips |
| Android 35, split APK | 8 tests passed; 0 failures/errors/skips |
| DEX overlay scenario | Complete on both APIs; 11 exact Math prototypes observed and 44 events each |
| Space source checks | Passed |
| Space engine Android 35 | 12 tests passed |
| Guest overlay Android 35 | 2 tests passed |
| Downloaded artifacts | ZIP digests match GitHub; APK hashes match CI |
| APK ZIP alignment | Both APKs passed 16 KiB alignment verification |
| Debug APK signature | Verified, v2; one Android Debug signer |

The existing Space 0.0.37 was not rebuilt. These checks use owned fixtures,
not third-party games or a physical phone. New DEX behavior was tested in the
owned repacked fixture; this does not establish arbitrary DEX execution in a
Space guest or universal engine/version/ABI coverage.

## Deliverables and signing blocker

`ModKit-v0.0.46-debug.apk` is installable as the separate `.test` package.
It uses the CI debug certificate and is not an update of the signed main app.
Debug certificate SHA-256:
`8a34860872bc5c0580aafcd844ba8200654b2af1fca2338ed3b6e6f40d3d8ccb`.

`ModKit-v0.0.46-unsigned.apk` uses the main package
`io.github.ffenuss.modkit`. It is ready for maintainer signing but cannot be
installed while unsigned. Device CI tested the debug build; the minified
release has structural build checks, not a signed release install test.

The previous private signing key and password are unavailable in this
conversation. No replacement key was created. Production delivery remains
IMPLEMENTED_BUT_INCOMPLETE until signing with the preserved key, signature
verification, and installation/launch checks are completed.

APK SHA-256:

- `ModKit-v0.0.46-debug.apk`: `cfa1fee04b0bedc14b924ca3ff01a7fa6382640f6977cc6d771690d03102ea4a`
- `ModKit-v0.0.46-unsigned.apk`: `2f0496a52ac85ed28f845085d58f4429012e5ab64608bb1737b59863739e57cd`
