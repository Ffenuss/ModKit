# Contributing

1. Preserve the canonical technical specification.
2. Do not bulk-copy legacy code.
3. Every migrated engine requires a porting review describing what works, what remains incomplete, when the engine should run, and how completion is verified.
4. Keep fail-closed evidence semantics.
5. Add tests with every parser/engine migration.
6. Keep Simple Mode compact; expose raw internals through Expert Lab.
7. CI must pass unit tests, lint, assemble and APK integrity checks before a milestone is called complete.
