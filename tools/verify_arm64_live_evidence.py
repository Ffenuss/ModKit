#!/usr/bin/env python3
"""Fail-closed host validation of a *physical Android ARM64* owned-game proof.

The independent host expects two measured baseline actions, two complete
ON/OFF interventions and game-state restoration after overlay teardown.
The host never calls its own expected values a substitute for real device
execution: the exact instrumentation transcript + fresh nonce are required.
"""
import json
from pathlib import Path
import re
import sys

SCHEMA = "modkit.arm64.native-live.v2"
METHOD = "q_preparedNativeProbeChangesRealIndependentGame"
HEX_32 = re.compile(r"[0-9a-f]{32}\Z")
HEX_64 = re.compile(r"[0-9a-f]{64}\Z")
PHASES = (
    "START", "UNMODIFIED_ACTION", "UNMODIFIED_CONTROL_ACTION",
    "PATCH_ENABLED_ACTION", "PATCH_DISABLED_ACTION",
    "PATCH_ENABLED_REPEAT_ACTION", "PATCH_DISABLED_REPEAT_ACTION",
    "OVERLAY_CLOSED_ACTION",
)
COUNTER_KEYS = (
    "start", "baseline", "control", "on", "off",
    "repeatOn", "repeatOff", "overlayClosed",
)
EXPECTED_COUNTERS = (0, 2, 4, 11, 13, 20, 22, 24)
EXPECTED_DELTAS = (2, 2, 7, 2, 7, 2, 2)


def validate(report, instrumentation, nonce):
    if not isinstance(nonce, str) or not HEX_32.fullmatch(nonce):
        raise ValueError("Invalid 128-bit run nonce")
    if not isinstance(report, dict):
        raise ValueError("Expected a JSON proof object")
    for key, expected in {
        "schema": SCHEMA,
        "nonce": nonce,
        "deviceAbi": "arm64-v8a",
        "primaryAbi": "arm64-v8a",
        "kernelMachine": "aarch64",
        "process64": True,
        "fixturePackage": "dev.modkit.nativefixture",
        "overlayAndInstallerConfirmed": True,
        "baselineControlActions": 2,
        "completedPatchOnOffCycles": 2,
    }.items():
        if type(report.get(key)) is not type(expected) or report[key] != expected:
            raise ValueError(f"Invalid ARM64 device proof field: {key}")
    api = report.get("apiLevel")
    if type(api) is not int or api < 29:
        raise ValueError("Unsupported Android API level")
    sha = report.get("originalInputSha256")
    if not isinstance(sha, str) or not HEX_64.fullmatch(sha):
        raise ValueError("Missing exact original APK identity digest")
    recipe = report.get("recipe")
    if not isinstance(recipe, dict):
        raise ValueError("No verified ARM64 recipe")
    if recipe.get("originalHex") != "40008052":
        raise ValueError("Unexpected ARM64 original opcode")
    if recipe.get("replacementHex") != "e0008052":
        raise ValueError("Unexpected ARM64 replacement opcode")
    va = recipe.get("functionVirtualAddress")
    if type(va) is not int or va <= 0 or va % 4:
        raise ValueError("Missing/alignment-invalid native virtual address")

    phases = report.get("observationPhases")
    if not isinstance(phases, list) or phases != list(PHASES):
        raise ValueError("Native UI observation phases are missing or out of order")
    timestamps = report.get("observationTimestampMonotonicMs")
    if (not isinstance(timestamps, list) or len(timestamps) != len(PHASES)
            or any(type(t) is not int or t <= 0 for t in timestamps)
            or any(a >= b for a, b in zip(timestamps, timestamps[1:]))):
        raise ValueError("Native UI observations are not chronologically recorded")
    counts = report.get("observedNativeDistance")
    if not isinstance(counts, dict) or set(counts) != set(COUNTER_KEYS):
        raise ValueError("Missing repeated game movement observations")
    if any(type(counts[k]) is not int for k in COUNTER_KEYS):
        raise ValueError("Game counter values must be exact integers")
    sequence = tuple(counts[k] for k in COUNTER_KEYS)
    if sequence != EXPECTED_COUNTERS:
        raise ValueError("Game counter did not replicate +2/+7 native effect")
    deltas = report.get("observedStepDeltas")
    if (not isinstance(deltas, list) or any(type(x) is not int for x in deltas)
            or tuple(deltas) != EXPECTED_DELTAS):
        raise ValueError("Game native ON/OFF/control effect sequence mismatch")
    if tuple(b-a for a, b in zip(sequence, sequence[1:])) != tuple(deltas):
        raise ValueError("Reported step deltas do not match UI counter observations")

    if not isinstance(instrumentation, str):
        raise ValueError("Missing instrumentation transcript")
    if f"INSTRUMENTATION_STATUS: test={METHOD}" not in instrumentation:
        raise ValueError("Exact live game test was not executed")
    if not re.search(r"(?m)^OK \(1 test\)\s*$", instrumentation):
        raise ValueError("Instrumentation did not pass exactly one live test")
    if "INSTRUMENTATION_CODE: -1" not in instrumentation:
        raise ValueError("Instrumentation did not report success")
    if "FAILURES!!!" in instrumentation or "INSTRUMENTATION_FAILED" in instrumentation:
        raise ValueError("Instrumentation contains test failure")

    return {
        "status": "PASS",
        "level": "LIVE Android ARM64 owned-fixture + repeated external overlay intervention",
        "deviceAbi": report["deviceAbi"],
        "apiLevel": api,
        "kernelMachine": report["kernelMachine"],
        "originalInputSha256": sha,
        "nonce": nonce,
        "observedStepDeltas": deltas,
        "baselineControlActions": 2,
        "completedPatchOnOffCycles": 2,
        "instrumentationTestsPassed": 1,
    }


def main():
    if len(sys.argv) != 4:
        raise SystemExit("Usage: verify_arm64_live_evidence.py report.json instrumentation.txt nonce")
    report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    log = Path(sys.argv[2]).read_text(encoding="utf-8")
    summary = validate(report, log, sys.argv[3])
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
