package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.modification.*
import org.junit.Assert.*
import org.junit.Test

class NativeResultModificationAdapterTest {
    private fun bytes(vararg words: Long) = words.flatMap { word -> (0..3).map { (word ushr (it * 8)).toByte() } }.toByteArray()
    private val source get() = bytes(0xBD401000, 0xD65F03C0, 0xD503201F, 0xD503201F)
    private val target = ModificationTarget(CodeFamily.NATIVE, "a".repeat(64), "method16", TargetShape.METHOD, ScalarKind.FLOAT32)
    private val evidence = BindingEvidence(true, true, true, true, true, true)
    private fun request() = ModificationRequest("damage", "damage", target, InterceptionPoint.RESULT,
        ValueOperation.Multiply(ScalarValue.Single(2f)))
    @Test fun commonOperationActuallyLowersToTheExistingVerifiedBytes() {
        val adapter = NativeResultModificationAdapter(target, source, 16, evidence)
        val prepared = adapter.prepare(request()) as AdapterPreparation.Prepared
        assertEquals("00 10 40 BD 10 10 20 1E 00 08 30 1E C0 03 5F D6", adapter.replacementHex(prepared))
        val replacement = request().copy(operation = ValueOperation.Replace(ScalarValue.Single(2f)))
        assertTrue(adapter.prepare(replacement) is AdapterPreparation.Prepared)
    }
    @Test fun blocksUnsupportedPointsTargetsOperationsAndUnverifiedBindings() {
        val adapter = NativeResultModificationAdapter(target, source, 16, evidence)
        assertTrue(adapter.prepare(request().copy(point = InterceptionPoint.ARGUMENT, argumentIndex = 0)) is AdapterPreparation.Blocked)
        assertTrue(adapter.prepare(request().copy(target = target.copy(artifactSha256 = "b".repeat(64)))) is AdapterPreparation.Blocked)
        assertTrue(adapter.prepare(request().copy(operation = ValueOperation.Clamp(ScalarValue.Single(1f), ScalarValue.Single(2f)))) is AdapterPreparation.Blocked)
        assertTrue(NativeResultModificationAdapter(target, source, 16, evidence.copy(exactTarget = false)).prepare(request()) is AdapterPreparation.Blocked)
        assertTrue(NativeResultModificationAdapter(target, source, 12, evidence).prepare(request()) is AdapterPreparation.Blocked)
    }
    @Test fun capturesOriginalBytesAndBindsPayloadToTheExactRequest() {
        val mutableSource = source
        val adapter = NativeResultModificationAdapter(target, mutableSource, 16, evidence)
        mutableSource.fill(0)
        val prepared = adapter.prepare(request()) as AdapterPreparation.Prepared
        assertTrue(adapter.replacementHex(prepared).startsWith("00 10 40 BD"))
        try {
            adapter.replacementHex(prepared.copy(request = prepared.request.copy(operation = ValueOperation.Multiply(ScalarValue.Single(5f)))))
            fail("Changed request reused old payload")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun sideEffectsRemainRejectedEvenIfCallerSuppliesEvidenceFlags() {
        val calls = bytes(0xBD401000, 0x94000000, 0xD65F03C0, 0xD503201F, 0xD503201F)
        assertTrue(NativeResultModificationAdapter(target, calls, calls.size, evidence).prepare(request()) is AdapterPreparation.Blocked)
    }
}
