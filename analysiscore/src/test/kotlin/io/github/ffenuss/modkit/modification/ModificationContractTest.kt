package io.github.ffenuss.modkit.modification

import org.junit.Assert.*
import org.junit.Test

class ModificationContractTest {
    private fun target(family: CodeFamily = CodeFamily.NATIVE, point: InterceptionPoint = InterceptionPoint.RESULT,
        kind: ScalarKind = ScalarKind.FLOAT32) = ModificationTarget(family, "a".repeat(64), "exact-bound-symbol",
        if (point in setOf(InterceptionPoint.FIELD_READ, InterceptionPoint.FIELD_WRITE)) TargetShape.FIELD else TargetShape.METHOD, kind)
    private fun request(family: CodeFamily = CodeFamily.NATIVE, point: InterceptionPoint = InterceptionPoint.RESULT) =
        ModificationRequest("damage-times-two", "damage", target(family, point), point,
            ValueOperation.Multiply(ScalarValue.Single(2f)), if (point == InterceptionPoint.ARGUMENT) 1 else null)
    private inline fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid contract accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun sameRuleWorksAcrossRepresentationsAndAllFourPoints() {
        for (family in CodeFamily.values()) for (point in InterceptionPoint.values()) {
            val request = request(family, point)
            val session = ModificationDispatcher(request)
            assertEquals(ScalarValue.Single(12f), session.transform(request.target, point, ScalarValue.Single(12f), request.argumentIndex).value)
            assertTrue(session.setEnabled(true))
            assertEquals(ScalarValue.Single(24f), session.transform(request.target, point, ScalarValue.Single(12f), request.argumentIndex).value)
            assertEquals(ScalarValue.Single(-6f), session.transform(request.target, point, ScalarValue.Single(-3f), request.argumentIndex).value)
            assertTrue(session.setEnabled(false))
            assertEquals(ScalarValue.Single(7f), session.transform(request.target, point, ScalarValue.Single(7f), request.argumentIndex).value)
        }
    }
    @Test fun neverChangesAnotherTargetSlotOrArgument() {
        val request = request(point = InterceptionPoint.ARGUMENT)
        val session = ModificationDispatcher(request).apply { setEnabled(true) }
        val source = ScalarValue.Single(12f)
        assertEquals(source, session.transform(request.target.copy(locator = "other"), request.point, source, 1).value)
        assertEquals(source, session.transform(request.target.copy(artifactSha256 = "b".repeat(64)), request.point, source, 1).value)
        assertEquals(source, session.transform(request.target, InterceptionPoint.RESULT, source, 1).value)
        assertEquals(source, session.transform(request.target, request.point, source, 0).value)
    }
    @Test fun closedSessionCannotBeReenabledOrChangeValues() {
        val request = request()
        val session = ModificationDispatcher(request).apply { setEnabled(true); close() }
        assertFalse(session.setEnabled(true))
        assertEquals(ScalarValue.Single(12f), session.transform(request.target, request.point, ScalarValue.Single(12f)).value)
    }
    @Test fun preservesExactLongsAndRejectsOverflowWithoutWrapping() {
        val operation = ValueOperation.Multiply(ScalarValue.Integral(ScalarKind.INT64, 2))
        val source = ScalarValue.Integral(ScalarKind.INT64, 9007199254740993L)
        assertEquals(ScalarValue.Integral(ScalarKind.INT64, 18014398509481986L), operation.apply(source))
        val request = ModificationRequest("long", "resource", target(kind = ScalarKind.INT64), InterceptionPoint.RESULT, operation)
        val session = ModificationDispatcher(request).apply { setEnabled(true) }
        val overflow = ScalarValue.Integral(ScalarKind.INT64, Long.MAX_VALUE)
        val result = session.transform(request.target, request.point, overflow)
        assertEquals(overflow, result.value)
        assertNotNull(result.failure)
        assertFalse(result.changed)
    }
    @Test fun narrowOverflowAndTypeMismatchKeepOriginalValue() {
        val request = ModificationRequest("byte", "resource", target(kind = ScalarKind.INT8), InterceptionPoint.RESULT,
            ValueOperation.Multiply(ScalarValue.Integral(ScalarKind.INT8, 2)))
        val session = ModificationDispatcher(request).apply { setEnabled(true) }
        val byte = ScalarValue.Integral(ScalarKind.INT8, 100)
        assertEquals(byte, session.transform(request.target, request.point, byte).value)
        val wrong = ScalarValue.DoublePrecision(12.0)
        assertEquals(wrong, session.transform(request.target, request.point, wrong).value)
        assertNotNull(session.transform(request.target, request.point, wrong).failure)
    }
    @Test fun clampAndBooleanReplacementHaveSeparateTypedSemantics() {
        val kind = ScalarKind.UINT16
        val clamp = ValueOperation.Clamp(ScalarValue.Integral(kind, 10), ScalarValue.Integral(kind, 50000))
        assertEquals(ScalarValue.Integral(kind, 50000), clamp.apply(ScalarValue.Integral(kind, 60000)))
        assertEquals(ScalarValue.Integral(kind, 10), clamp.apply(ScalarValue.Integral(kind, 0)))
        assertEquals(ScalarValue.Logical(false), ValueOperation.Replace(ScalarValue.Logical(false)).apply(ScalarValue.Logical(true)))
        rejected { ValueOperation.Multiply(ScalarValue.Logical(true)) }
        rejected { ScalarValue.Integral(ScalarKind.INT8, 128) }
        rejected { ValueOperation.Clamp(ScalarValue.Single(5f), ScalarValue.Single(2f)) }
        rejected { ScalarValue.parse(ScalarKind.FLOAT32, "NaN") }
    }
    @Test fun precisionAndNonfiniteOriginalFollowFloatingPointSemantics() {
        val input = ScalarValue.Single(16777216f)
        assertEquals(ScalarValue.Single(input.value * 3f), ValueOperation.Multiply(ScalarValue.Single(3f)).apply(input))
        val double = ScalarValue.DoublePrecision(1.23456789012345)
        assertEquals(ScalarValue.DoublePrecision(double.value * 2.0), ValueOperation.Multiply(ScalarValue.DoublePrecision(2.0)).apply(double))
        val result = ValueOperation.Multiply(ScalarValue.Single(2f)).apply(ScalarValue.Single(Float.NaN)) as ScalarValue.Single
        assertTrue(result.value.isNaN())
        rejected { ValueOperation.Multiply(ScalarValue.Single(Float.POSITIVE_INFINITY)) }
    }
    @Test fun validatesShapeTypeIdentityAndArgumentIndex() {
        rejected { request().copy(argumentIndex = 0) }
        rejected { request(point = InterceptionPoint.ARGUMENT).copy(argumentIndex = -1) }
        rejected { request().copy(target = target(point = InterceptionPoint.FIELD_READ)) }
        rejected { request().copy(operation = ValueOperation.Replace(ScalarValue.Logical(true))) }
        rejected { target().copy(artifactSha256 = "unknown") }
    }
    @Test fun capabilitiesCannotInventAnExecutorOrAuthorityEvenInRoot() {
        val request = request()
        val adapter = AdapterCapabilities("bounded-native", CodeFamily.NATIVE, ExecutionHost.SPACE,
            setOf(InterceptionPoint.RESULT), setOf(OperationKind.MULTIPLY), setOf(ScalarKind.FLOAT32))
        val proven = BindingEvidence(true, true, true, true, true, true)
        assertTrue(ModificationPlanner.blockers(request, ExecutionHost.SPACE, adapter, proven).isEmpty())
        assertTrue(ModificationPlanner.blockers(request, ExecutionHost.ROOT, adapter, proven).isNotEmpty())
        assertTrue(ModificationPlanner.blockers(request(), ExecutionHost.SPACE, adapter, proven.copy(localAuthority = false)).isNotEmpty())
        assertTrue(ModificationPlanner.blockers(request(point = InterceptionPoint.FIELD_WRITE), ExecutionHost.SPACE, adapter, proven).isNotEmpty())
        assertTrue(ModificationPlanner.blockers(request(CodeFamily.SCRIPT), ExecutionHost.SPACE, adapter, proven).isNotEmpty())
        assertEquals(6, ModificationPlanner.blockers(request, ExecutionHost.SPACE, adapter, BindingEvidence()).size)
    }
}
