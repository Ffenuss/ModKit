package io.github.ffenuss.modkit.modification

import org.junit.Assert.*
import org.junit.Test

class VerifiedRecipeAdapterTest {
    private val request = ModificationRequest("exact-native", "damage",
        ModificationTarget(CodeFamily.NATIVE, "a".repeat(64), "module!method@16", TargetShape.METHOD, ScalarKind.FLOAT32),
        InterceptionPoint.RESULT, ValueOperation.Multiply(ScalarValue.Single(2f)))
    private val capabilities = AdapterCapabilities("existing-native", CodeFamily.NATIVE, ExecutionHost.SPACE,
        setOf(InterceptionPoint.RESULT), setOf(OperationKind.MULTIPLY), setOf(ScalarKind.FLOAT32))
    private val evidence = BindingEvidence(true, true, true, true, true, true)
    @Test fun preparesOnlyTheExactVerifiedRequest() {
        val adapter = VerifiedRecipeAdapter(capabilities, listOf(VerifiedLowering(request, "verified-byte-payload", evidence)))
        val prepared = adapter.prepare(request) as AdapterPreparation.Prepared
        assertEquals("verified-byte-payload", prepared.payloadId)
        assertTrue(adapter.prepare(request.copy(operation = ValueOperation.Multiply(ScalarValue.Single(5f)))) is AdapterPreparation.Blocked)
        assertTrue(adapter.prepare(request.copy(target = request.target.copy(artifactSha256 = "b".repeat(64)))) is AdapterPreparation.Blocked)
        assertTrue(adapter.prepare(request.copy(id = "different-method")) is AdapterPreparation.Blocked)
    }
    @Test fun blocksMissingRestorationAndUnsupportedMechanisms() {
        val adapter = VerifiedRecipeAdapter(capabilities,
            listOf(VerifiedLowering(request, "payload", evidence.copy(restorationPrepared = false))))
        assertTrue(adapter.prepare(request) is AdapterPreparation.Blocked)
        val field = request.copy(point = InterceptionPoint.FIELD_WRITE, target = request.target.copy(shape = TargetShape.FIELD))
        val fieldAdapter = VerifiedRecipeAdapter(capabilities, listOf(VerifiedLowering(field, "payload", evidence)))
        assertTrue(fieldAdapter.prepare(field) is AdapterPreparation.Blocked)
    }
    @Test fun duplicateIdsCannotSilentlyChooseAnotherBody() {
        try {
            VerifiedRecipeAdapter(capabilities, listOf(VerifiedLowering(request, "first", evidence),
                VerifiedLowering(request, "second", evidence)))
            fail("Duplicate lowering accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
