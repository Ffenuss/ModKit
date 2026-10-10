package io.github.ffenuss.modkit.patch

import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.*
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference
import org.junit.Assert.*
import org.junit.Test

class DexMethodBodyInspectorTest {
    private val owner = "Ldev/game/Player;"
    private fun method(vararg code: Instruction) = ImmutableMethod(owner, "a", emptyList(), "I",
        AccessFlags.PUBLIC.value, emptySet(), emptySet(),
        ImmutableMethodImplementation(2, code.toList(), emptyList(), emptyList()))
    private val field = ImmutableFieldReference(owner, "health", "I")

    @Test fun narrowFieldReadsRequireTheExactOpcodeTypeAndReceiver() {
        for ((type, instanceOpcode, staticOpcode) in listOf(
            Triple("B", Opcode.IGET_BYTE, Opcode.SGET_BYTE),
            Triple("S", Opcode.IGET_SHORT, Opcode.SGET_SHORT),
            Triple("C", Opcode.IGET_CHAR, Opcode.SGET_CHAR))) {
            val reference = ImmutableFieldReference(owner, "health", type)
            fun typed(static: Boolean, instruction: Instruction) = ImmutableMethod(owner, "a", emptyList(), type,
                AccessFlags.PUBLIC.value or if (static) AccessFlags.STATIC.value else 0, emptySet(), emptySet(),
                ImmutableMethodImplementation(2, listOf(instruction, ImmutableInstruction11x(Opcode.RETURN, 0)), emptyList(), emptyList()))
            assertEquals(DexMethodBodyKind.INSTANCE_FIELD_GETTER, DexMethodBodyInspector.inspect(typed(false,
                ImmutableInstruction22c(instanceOpcode, 0, 1, reference))).kind)
            assertEquals(DexMethodBodyKind.STATIC_FIELD_GETTER, DexMethodBodyInspector.inspect(typed(true,
                ImmutableInstruction21c(staticOpcode, 0, reference))).kind)
            assertFalse(DexMethodBodyInspector.inspect(typed(false,
                ImmutableInstruction22c(Opcode.IGET, 0, 1, reference))).supportsScalarReplacement)
            assertFalse(DexMethodBodyInspector.inspect(typed(false,
                ImmutableInstruction22c(instanceOpcode, 0, 0, reference))).supportsScalarReplacement)
            assertFalse(DexMethodBodyInspector.inspect(typed(false,
                ImmutableInstruction22c(instanceOpcode, 0, 1, field))).supportsScalarReplacement)
        }
    }

    @Test fun narrowReplacementsRejectIncompatibleActions() {
        for (type in listOf("B", "S", "C")) {
            assertEquals(99L, DexScalarReplacement.literal(type, DexLocalAction.INT_99))
            for (action in listOf(DexLocalAction.TRUE, DexLocalAction.FALSE, DexLocalAction.FLOAT_2)) {
                try { DexScalarReplacement.literal(type, action); fail("$type must reject $action") }
                catch (_: IllegalArgumentException) { }
            }
        }
    }

    @Test fun recoversTheFieldEvenWhenTheGetterNameIsObfuscated() {
        val proof = DexMethodBodyInspector.inspect(method(
            ImmutableInstruction22c(Opcode.IGET, 0, 1, field),
            ImmutableInstruction11x(Opcode.RETURN, 0)))
        assertEquals(DexMethodBodyKind.INSTANCE_FIELD_GETTER, proof.kind)
        assertEquals("$owner->health:I", proof.fieldIdentity)
    }

    @Test fun rejectsAFieldReadFromAnUnprovenObject() {
        assertFalse(DexMethodBodyInspector.inspect(method(
            ImmutableInstruction22c(Opcode.IGET, 0, 0, field),
            ImmutableInstruction11x(Opcode.RETURN, 0))).supportsScalarReplacement)
    }

    @Test fun rejectsANameMatchWithSideEffects() {
        assertFalse(DexMethodBodyInspector.inspect(method(
            ImmutableInstruction11n(Opcode.CONST_4, 0, 7),
            ImmutableInstruction22c(Opcode.IPUT, 0, 1, field),
            ImmutableInstruction11x(Opcode.RETURN, 0))).supportsScalarReplacement)
    }

    @Test fun rejectsReturningAnUninitializedDifferentRegister() {
        assertFalse(DexMethodBodyInspector.inspect(method(
            ImmutableInstruction11n(Opcode.CONST_4, 0, 7),
            ImmutableInstruction11x(Opcode.RETURN, 1))).supportsScalarReplacement)
    }
}
