# Architecture decisions

## ADR-001 — No legacy bulk import
Legacy UniRevLab code is used as a reference and source of tested ideas, not copied wholesale. Every subsystem is reviewed against the canonical specification before it enters ModKit.

## ADR-002 — Fast path first
The first useful result must not wait for unrelated deep engines. Work is classified as FAST, TARGETED, CONFIRMATION or BACKGROUND.

## ADR-003 — Single shared artifact index
Archive enumeration is centralized. Specialized engines consume the shared index and open only artifacts they actually need.

## ADR-004 — Evidence is fail-closed
Extensions, strings, entropy and proximity are signals, not exact identity. Exact/CHANGE_READY states require stronger proof.

## ADR-005 — Heartbeat is time-based
A slow large file must continue publishing heartbeat even if byte/entry thresholds are not reached.

## ADR-006 — File-picker copy hashes while copying
A SAF-selected target is already streamed into app-private storage. SHA-256 is computed in that same pass and reused by the indexer, avoiding a redundant full read.

## ADR-007 — Interrupted is not fake RUNNING
A process/device restart turns an unfinished persisted run into an explicit Interrupted state. Current v0.0.1 can restart the same target; reusable partial-engine output caching remains unfinished.

## ADR-008 — Runtime detection remains multi-label
Detection of one framework never excludes DEX, native, WebView, SDK or other runtimes. Routing decides what executes.
