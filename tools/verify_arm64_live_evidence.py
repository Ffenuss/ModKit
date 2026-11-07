#!/usr/bin/env python3
"""Fail-closed validation of a *live Android ARM64* owned-fixture report.

Unlike the NDK ELF/Unicorn test, this gate only passes on evidence emitted
after on-device UIAutomator observed a prepared APK, external overlay and
actual native game movement (2 -> 9 -> 11 -> 13).
"""
import json
from pathlib import Path
import re
import sys

SCHEMA = "modkit.arm64.native-live.v1"
METHOD = "q_preparedNativeProbeChangesRealIndependentGame"
HEX_32 = re.compile(r"[0-9a-f]{32}\Z")
HEX_64 = re.compile(r"[0-9a-f]{64}\Z")


def validate(report, instrumentation, nonce):
    if not HEX_32.fullmatch(nonce):
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
    counts = report.get("observedNativeDistance")
    if not isinstance(counts, dict) or counts != {
        "baseline": 2, "on": 9, "off": 11, "overlayClosed": 13,
    }:
        raise ValueError("Native game did not show expected +2,+7,+2,+2 sequence")
    deltas = report.get("observedStepDeltas")
    if not isinstance(deltas, list) or any(type(x) is not int for x in deltas):
        raise ValueError("Invalid movement deltas")
    if deltas != [2, 7, 2, 2]:
        raise ValueError("Game movement effect sequence mismatch")

    if not isinstance(instrumentation, str):
        raise ValueError("Missing instrumentation transcript")
    if f"INSTRUMENTATION_STATUS: test={METHOD}" not in instrumentation:
        raise ValueError("Exact live game test was not executed")
    if not re.search(r"(?m)^OK \\(1 test\\)\\s*$", instrumentation):
        raise ValueError("Instrumentation did not pass exactly one live test")
    if "INSTRUMENTATION_CODE: -1" not in instrumentation:
        raise ValueError("Instrumentation did not report success")
    if "FAILURES!!!" in instrumentation or "INSTRUMENTATION_FAILED" in instrumentation:
        raise ValueError("Instrumentation contains test failure")

    return {
        "status": "PASS",
        "level": "LIVE Android ARM64 owned-fixture and external overlay",
        "deviceAbi": report["deviceAbi"],
        "apiLevel": api,
        "kernelMachine": report["kernelMachine"],
        "originalInputSha256": sha,
        "nonce": nonce,
        "observedStepDeltas": deltas,
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
