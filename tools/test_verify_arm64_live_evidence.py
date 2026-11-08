#!/usr/bin/env python3
"""Host-only regressions for an optional physical ARM64 game+overlay test."""
import copy
import unittest

from verify_arm64_live_evidence import validate

NONCE = "a0" * 16
GOOD_LOG = (
    "INSTRUMENTATION_STATUS: class=io.github.ffenuss.modkit.AutoModDeviceTest\n"
    "INSTRUMENTATION_STATUS: test=q_preparedNativeProbeChangesRealIndependentGame\n"
    "INSTRUMENTATION_STATUS_CODE: 0\n"
    "INSTRUMENTATION_RESULT: stream=\n\nTime: 91.234\n\n"
    "OK (1 test)\n"
    "INSTRUMENTATION_CODE: -1\n"
)
GOOD_REPORT = {
    "schema": "modkit.arm64.native-live.v2",
    "nonce": NONCE,
    "deviceAbi": "arm64-v8a",
    "primaryAbi": "arm64-v8a",
    "kernelMachine": "aarch64",
    "process64": True,
    "apiLevel": 35,
    "fixturePackage": "dev.modkit.nativefixture",
    "overlayAndInstallerConfirmed": True,
    "originalInputSha256": "be" * 32,
    "recipe": {
        "functionVirtualAddress": 0x688,
        "originalHex": "40008052",
        "replacementHex": "e0008052",
    },
    "observationPhases": [
        "START", "UNMODIFIED_ACTION", "UNMODIFIED_CONTROL_ACTION",
        "PATCH_ENABLED_ACTION", "PATCH_DISABLED_ACTION",
        "PATCH_ENABLED_REPEAT_ACTION", "PATCH_DISABLED_REPEAT_ACTION",
        "OVERLAY_CLOSED_ACTION",
    ],
    "observationTimestampMonotonicMs": [100, 200, 300, 400, 500, 600, 700, 800],
    "observedNativeDistance": {
        "start": 0, "baseline": 2, "control": 4,
        "on": 11, "off": 13, "repeatOn": 20, "repeatOff": 22,
        "overlayClosed": 24,
    },
    "observedStepDeltas": [2, 2, 7, 2, 7, 2, 2],
    "baselineControlActions": 2,
    "completedPatchOnOffCycles": 2,
}


class Arm64LiveEvidenceTest(unittest.TestCase):
    def test_accept_exact_live_arm64_device_proof(self):
        result = validate(copy.deepcopy(GOOD_REPORT), GOOD_LOG, NONCE)
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["instrumentationTestsPassed"], 1)
        self.assertEqual(result["completedPatchOnOffCycles"], 2)

    def assert_rejected(self, change, log=GOOD_LOG, nonce=NONCE):
        report = copy.deepcopy(GOOD_REPORT)
        change(report)
        with self.assertRaises(ValueError):
            validate(report, log, nonce)

    def test_reject_stale_nonce(self):
        self.assert_rejected(lambda r: r.update(nonce="ff" * 16))

    def test_reject_old_schema_or_short_single_cycle(self):
        self.assert_rejected(lambda r: r.update(schema="modkit.arm64.native-live.v1"))
        self.assert_rejected(lambda r: r.update(observedStepDeltas=[2, 7, 2, 2]))
        self.assert_rejected(lambda r: r.update(completedPatchOnOffCycles=1))
        self.assert_rejected(lambda r: r.update(baselineControlActions=1))

    def test_reject_x86_64_translation(self):
        self.assert_rejected(lambda r: r.update(kernelMachine="x86_64"))

    def test_reject_x86_primary_abi(self):
        self.assert_rejected(lambda r: r.update(primaryAbi="x86_64"))

    def test_reject_32_bit_process(self):
        self.assert_rejected(lambda r: r.update(process64=False))

    def test_reject_spoofed_boolean_as_int(self):
        self.assert_rejected(lambda r: r.update(apiLevel=True))
        self.assert_rejected(lambda r: r.update(completedPatchOnOffCycles=True))

    def test_reject_stale_instruction(self):
        self.assert_rejected(lambda r: r["recipe"].update(originalHex="e0008052"))

    def test_reject_unaligned_native_symbol(self):
        self.assert_rejected(lambda r: r["recipe"].update(functionVirtualAddress=0x689))

    def test_reject_wrong_first_game_effect(self):
        self.assert_rejected(lambda r: r["observedNativeDistance"].update(on=9))

    def test_reject_wrong_control_step(self):
        self.assert_rejected(lambda r: r["observedNativeDistance"].update(control=7))

    def test_reject_missing_second_on_effect(self):
        self.assert_rejected(lambda r: r["observedNativeDistance"].update(repeatOn=15))

    def test_reject_wrong_return_to_baseline(self):
        self.assert_rejected(lambda r: r.update(observedStepDeltas=[2, 2, 7, 2, 7, 7, 2]))
        self.assert_rejected(lambda r: r["observedNativeDistance"].update(repeatOff=27))

    def test_reject_wrong_overlay_close_rollback(self):
        self.assert_rejected(lambda r: r["observedNativeDistance"].update(overlayClosed=29))

    def test_reject_missing_or_out_of_order_ui_phases(self):
        self.assert_rejected(lambda r: r["observationPhases"].pop())
        self.assert_rejected(lambda r: r["observationPhases"].__setitem__(5, "PATCH_DISABLED_REPEAT_ACTION"))

    def test_reject_unordered_or_invalid_timestamps(self):
        self.assert_rejected(lambda r: r["observationTimestampMonotonicMs"].__setitem__(4, 400))
        self.assert_rejected(lambda r: r["observationTimestampMonotonicMs"].__setitem__(4, True))
        self.assert_rejected(lambda r: r["observationTimestampMonotonicMs"].pop())

    def test_reject_spoofed_bool_counter_and_inconsistent_deltas(self):
        self.assert_rejected(lambda r: r["observedNativeDistance"].update(start=False))
        self.assert_rejected(lambda r: r.update(observedStepDeltas=[2, 2, 7, 2, 7, 2, True]))

    def test_reject_untrusted_package(self):
        self.assert_rejected(lambda r: r.update(fixturePackage="com.example.app"))

    def test_reject_missing_gameplay_test(self):
        log = GOOD_LOG.replace(
            "test=q_preparedNativeProbeChangesRealIndependentGame",
            "test=someOtherTest")
        self.assert_rejected(lambda r: None, log=log)

    def test_reject_instrumentation_failure(self):
        self.assert_rejected(
            lambda r: None, log=GOOD_LOG.replace("OK (1 test)", "FAILURES!!!"))

    def test_reject_success_claim_without_instrumentation_code(self):
        self.assert_rejected(
            lambda r: None, log=GOOD_LOG.replace("INSTRUMENTATION_CODE: -1", ""))

    def test_reject_missing_apk_identity(self):
        self.assert_rejected(lambda r: r.update(originalInputSha256="none"))


if __name__ == "__main__":
    unittest.main()
