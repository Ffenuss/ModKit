# Architecture

## Main flow

TARGET → FAST INVENTORY → ARTIFACT INDEX → MULTI-RUNTIME PROFILER → SMART ENGINE ROUTER → FAST/TARGETED ENGINES → PARTIAL RESULTS → EVIDENCE GRAPH → targeted confirmation → runtime/root only if required → AutoMod/Patch Lab → internal prepare/preflight → build/sign/verify → APK.

## Core invariants

- Multi-label runtime detection.
- No full rescan per engine.
- Fail-closed evidence promotion.
- Incremental results.
- Heartbeat and cancellation on every long task.
- Heavy engines cannot block first useful output.
- SHA-bound executable bindings.
- Expert Lab can invoke individual compatible tools independently.
