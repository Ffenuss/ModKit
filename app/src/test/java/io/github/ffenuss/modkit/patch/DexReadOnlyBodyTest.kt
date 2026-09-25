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

class DexReadOnlyBodyTest {
    private val owner = "Ldev/game/Player;"
    private val field = ImmutableFieldReference(owner, "health", "I")
    private fun inspect(vararg code: Instruction) = DexMethodBodyInspector.inspect(ImmutableMethod(owner,
        "getHealth", emptyList(), "I", AccessFlags.PUBLIC.value, emptySet(), emptySet(),
        ImmutableMethodImplementation(2, code.toList(), emptyList(), emptyList())))

    @Test fun supportsFieldArithmetic() {
        val proof = inspect(ImmutableInstruction22c(Opcode.IGET, 0, 1, field),
            ImmutableInstruction22b(Opcode.ADD_INT_LIT8, 0, 0, 7), ImmutableInstruction11x(Opcode.RETURN, 0))
        assertEquals(DexMethodBodyKind.READ_ONLY_COMPUTATION, proof.kind)
        assertNull("A computed value must not be silently grouped as a direct field getter", proof.fieldIdentity)
    }
    @Test fun checksBothSidesOfConditionalReturn() {
        val proof = inspect(ImmutableInstruction22c(Opcode.IGET, 0, 1, field), // offset 0
            ImmutableInstruction21t(Opcode.IF_LEZ, 0, 4), // offset 2 -> 6
            ImmutableInstruction11n(Opcode.CONST_4, 0, 1), // 4
            ImmutableInstruction11x(Opcode.RETURN, 0), // 5
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0), // 6
            ImmutableInstruction11x(Opcode.RETURN, 0))
        assertTrue(proof.detail, proof.supportsScalarReplacement)
    }
    @Test fun rejectsAWriteOnEitherBranch() {
        assertFalse(inspect(ImmutableInstruction22c(Opcode.IGET, 0, 1, field),
            ImmutableInstruction21t(Opcode.IF_LEZ, 0, 3),
            ImmutableInstruction11x(Opcode.RETURN, 0),
            ImmutableInstruction22c(Opcode.IPUT, 0, 1, field),
            ImmutableInstruction11x(Opcode.RETURN, 0)).supportsScalarReplacement)
    }
    @Test fun rejectsLoopsAndNonInstructionBranchTargets() {
        assertFalse(inspect(ImmutableInstruction10t(Opcode.GOTO, 0)).supportsScalarReplacement)
        assertFalse(inspect(ImmutableInstruction10t(Opcode.GOTO, 2),
            ImmutableInstruction22c(Opcode.IGET, 0, 1, field),
            ImmutableInstruction11x(Opcode.RETURN, 0)).supportsScalarReplacement)
    }
    @Test fun rejectsAnUninitializedReturnBehindANop() {
        assertFalse(inspect(ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
            ImmutableInstruction10x(Opcode.NOP), ImmutableInstruction11x(Opcode.RETURN, 1)).supportsScalarReplacement)
    }
}
