#!/usr/bin/env python3
"""Deterministic host regressions for the strict ARM64 device proof gate."""
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
    "schema": "modkit.arm64.native-live.v1",
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
    "observedNativeDistance": {
        "baseline": 2, "on": 9, "off": 11, "overlayClosed": 13,
    },
    "observedStepDeltas": [2, 7, 2, 2],
}


class Arm64LiveEvidenceTest(unittest.TestCase):
    def test_accept_exact_live_arm64_device_proof(self):
        result = validate(copy.deepcopy(GOOD_REPORT), GOOD_LOG, NONCE)
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["instrumentationTestsPassed"], 1)

    def assert_rejected(self, change, log=GOOD_LOG, nonce=NONCE):
        report = copy.deepcopy(GOOD_REPORT)
        change(report)
        with self.assertRaises(ValueError):
            validate(report, log, nonce)

    def test_reject_stale_nonce(self):
        self.assert_rejected(lambda r: r.update(nonce="ff" * 16))

    def test_reject_x86_64_translation(self):
        self.assert_rejected(lambda r: r.update(kernelMachine="x86_64"))

    def test_reject_x86_primary_abi(self):
        self.assert_rejected(lambda r: r.update(primaryAbi="x86_64"))

    def test_reject_32_bit_process(self):
        self.assert_rejected(lambda r: r.update(process64=False))

    def test_reject_spoofed_boolean_as_int(self):
        self.assert_rejected(lambda r: r.update(apiLevel=True))

    def test_reject_stale_instruction(self):
        self.assert_rejected(lambda r: r["recipe"].update(originalHex="e0008052"))

    def test_reject_unaligned_native_symbol(self):
        self.assert_rejected(lambda r: r["recipe"].update(functionVirtualAddress=0x689))

    def test_reject_wrong_game_effect(self):
        self.assert_rejected(lambda r: r["observedNativeDistance"].update(on=4))

    def test_reject_wrong_return_to_baseline(self):
        self.assert_rejected(lambda r: r.update(observedStepDeltas=[2, 7, 7, 2]))

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
