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
