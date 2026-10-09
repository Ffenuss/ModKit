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

    private fun wide(type: String, registers: Int, vararg code: Instruction) = DexMethodBodyInspector.inspect(ImmutableMethod(owner,
        if (type == "J") "getAmmo" else "getRunSpeed", emptyList(), type,
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, emptySet(), emptySet(),
        ImmutableMethodImplementation(registers, code.toList(), emptyList(), emptyList())))

    @Test fun supportsWideArithmeticComparisonAndBothReturnPaths() {
        val proof = wide("J", 5,
            ImmutableInstruction21c(Opcode.SGET_WIDE, 0, ImmutableFieldReference(owner, "ammo", "J")), // 0
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 2, 0), // 2
            ImmutableInstruction23x(Opcode.CMP_LONG, 4, 0, 2), // 4
            ImmutableInstruction21t(Opcode.IF_LTZ, 4, 7), // 6 -> 13
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 2, 7), // 8
            ImmutableInstruction12x(Opcode.ADD_LONG_2ADDR, 0, 2), // 10
            ImmutableInstruction11x(Opcode.RETURN_WIDE, 0), // 11
            ImmutableInstruction10x(Opcode.NOP), // 12 unreachable
            ImmutableInstruction11x(Opcode.RETURN_WIDE, 2)) // 13
        assertEquals(proof.detail, DexMethodBodyKind.READ_ONLY_COMPUTATION, proof.kind)
        assertNull(proof.fieldIdentity)
        assertTrue(wide("D", 4,
            ImmutableInstruction21c(Opcode.SGET_WIDE, 0, ImmutableFieldReference(owner, "runSpeed", "D")),
            ImmutableInstruction51l(Opcode.CONST_WIDE, 2, 2.0.toBits()),
            ImmutableInstruction12x(Opcode.MUL_DOUBLE_2ADDR, 0, 2),
            ImmutableInstruction11x(Opcode.RETURN_WIDE, 0)).supportsScalarReplacement)
    }
    @Test fun writingEitherHalfInvalidatesTheOriginalPair() {
        for (half in 0..1) assertFalse(wide("J", 3,
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 0, 11),
            ImmutableInstruction11n(Opcode.CONST_4, half, 1),
            ImmutableInstruction11x(Opcode.RETURN_WIDE, 0)).supportsScalarReplacement)
        assertFalse(wide("J", 3,
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 0, 11),
            ImmutableInstruction11x(Opcode.RETURN_WIDE, 1)).supportsScalarReplacement)
        assertFalse(wide("J", 2,
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 1, 11),
            ImmutableInstruction11x(Opcode.RETURN_WIDE, 1)).supportsScalarReplacement)
    }
    @Test fun wideAssignmentsMustBeCompleteOnEveryIncomingEdge() {
        assertFalse(wide("J", 3,
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 0, 11), // 0
            ImmutableInstruction11n(Opcode.CONST_4, 2, 1), // 2
            ImmutableInstruction21t(Opcode.IF_EQZ, 2, 3), // 3 -> 6
            ImmutableInstruction11n(Opcode.CONST_4, 1, 0), // 5
            ImmutableInstruction11x(Opcode.RETURN_WIDE, 0)).supportsScalarReplacement) // 6
    }
    @Test fun overlappingMovesReadBothHalvesBeforeWriting() {
        assertTrue(wide("J", 3,
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 0, 11),
            ImmutableInstruction12x(Opcode.MOVE_WIDE, 1, 0),
            ImmutableInstruction11x(Opcode.RETURN_WIDE, 1)).supportsScalarReplacement)
        assertFalse(wide("J", 3,
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 0, 11),
            ImmutableInstruction12x(Opcode.MOVE_WIDE, 1, 0),
            ImmutableInstruction11x(Opcode.RETURN_WIDE, 0)).supportsScalarReplacement)
    }
    @Test fun conversionsAndLongShiftsUseTheirActualOperandWidths() {
        assertTrue(wide("J", 3,
            ImmutableInstruction11n(Opcode.CONST_4, 2, 3),
            ImmutableInstruction12x(Opcode.INT_TO_LONG, 0, 2),
            ImmutableInstruction12x(Opcode.SHL_LONG_2ADDR, 0, 2),
            ImmutableInstruction11x(Opcode.RETURN_WIDE, 0)).supportsScalarReplacement)
        assertFalse(wide("J", 4,
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 0, 11),
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 2, 1),
            ImmutableInstruction12x(Opcode.SHL_LONG_2ADDR, 0, 2),
            ImmutableInstruction11x(Opcode.RETURN_WIDE, 0)).supportsScalarReplacement)
        assertTrue(wide("D", 3,
            ImmutableInstruction11n(Opcode.CONST_4, 2, 3),
            ImmutableInstruction12x(Opcode.INT_TO_DOUBLE, 0, 2),
            ImmutableInstruction12x(Opcode.NEG_DOUBLE, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN_WIDE, 0)).supportsScalarReplacement)
    }
    @Test fun rejectsWideDivisionWritesAndMismatchedFieldWidths() {
        assertFalse(wide("J", 4,
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 0, 11),
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 2, 0),
            ImmutableInstruction12x(Opcode.DIV_LONG_2ADDR, 0, 2),
            ImmutableInstruction11x(Opcode.RETURN_WIDE, 0)).supportsScalarReplacement)
        assertFalse(wide("J", 2,
            ImmutableInstruction21c(Opcode.SGET_WIDE, 0, field),
            ImmutableInstruction10x(Opcode.NOP),
            ImmutableInstruction11x(Opcode.RETURN_WIDE, 0)).supportsScalarReplacement)
        assertFalse(wide("J", 2,
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 0, 11),
            ImmutableInstruction21c(Opcode.SPUT_WIDE, 0, ImmutableFieldReference(owner, "ammo", "J")),
            ImmutableInstruction11x(Opcode.RETURN_WIDE, 0)).supportsScalarReplacement)
        assertFalse(wide("J", 2,
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 0, 11),
            ImmutableInstruction10x(Opcode.NOP),
            ImmutableInstruction11x(Opcode.RETURN, 0)).supportsScalarReplacement)
    }
}
