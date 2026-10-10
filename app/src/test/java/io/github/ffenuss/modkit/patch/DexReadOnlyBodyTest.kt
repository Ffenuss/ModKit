package io.github.ffenuss.modkit.patch

import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.ImmutableMethodParameter
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

    private fun switchMethod(code: List<Instruction>) = ImmutableMethod(owner, "getAmmo",
        listOf(ImmutableMethodParameter("I", emptySet(), null)), "J",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, emptySet(), emptySet(),
        ImmutableMethodImplementation(3, code, emptyList(), emptyList()))

    private fun switchCode(op: Opcode, payload: Instruction, selector: Int = 2): List<Instruction> = listOf(
        ImmutableInstruction10x(Opcode.NOP), // 0: switch is deliberately not at zero
        ImmutableInstruction31t(op, selector, 13), // 1 -> table at 14
        ImmutableInstruction21s(Opcode.CONST_WIDE_16, 0, 7), // 4: no-match path
        ImmutableInstruction11x(Opcode.RETURN_WIDE, 0), // 6
        ImmutableInstruction21s(Opcode.CONST_WIDE_16, 0, 11), // 7: first case
        ImmutableInstruction11x(Opcode.RETURN_WIDE, 0), // 9
        ImmutableInstruction21s(Opcode.CONST_WIDE_16, 0, 13), // 10: second case
        ImmutableInstruction11x(Opcode.RETURN_WIDE, 0), // 12
        ImmutableInstruction10x(Opcode.NOP), // 13: unreachable alignment padding
        payload) // 14

    @Test fun supportsPackedAndSparseSwitchesWithOpcodeRelativeTargets() {
        val tables = listOf(
            Opcode.PACKED_SWITCH to ImmutablePackedSwitchPayload(listOf(
                ImmutableSwitchElement(-1, 6), ImmutableSwitchElement(0, 9))),
            Opcode.SPARSE_SWITCH to ImmutableSparseSwitchPayload(listOf(
                ImmutableSwitchElement(-10, 6), ImmutableSwitchElement(1000, 9))))
        for ((op, table) in tables) {
            val proof = DexMethodBodyInspector.inspect(switchMethod(switchCode(op, table)))
            assertEquals(proof.detail, DexMethodBodyKind.READ_ONLY_COMPUTATION, proof.kind)
            assertNull(proof.fieldIdentity)
        }
    }

    @Test fun switchCasesAndDefaultMustAllBeReadOnlyAndAssigned() {
        val table = ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(0, 6), ImmutableSwitchElement(1, 9)))
        for (writeIndex in listOf(2, 4, 6)) {
            val code = switchCode(Opcode.PACKED_SWITCH, table).toMutableList()
            code[writeIndex] = ImmutableInstruction21c(Opcode.SPUT_WIDE, 0, ImmutableFieldReference(owner, "ammo", "J"))
            assertFalse(DexReadOnlyBody.inspect(switchMethod(code)).supportsScalarReplacement)
        }
        assertFalse(DexReadOnlyBody.inspect(switchMethod(switchCode(Opcode.PACKED_SWITCH, table, 1))).supportsScalarReplacement)
        val join = listOf(
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 0, 11), // 0
            ImmutableInstruction31t(Opcode.PACKED_SWITCH, 2, 10), // 2 -> 12
            ImmutableInstruction10t(Opcode.GOTO, 6), // 5 -> 11
            ImmutableInstruction10x(Opcode.NOP), // 6
            ImmutableInstruction10t(Opcode.GOTO, 4), // 7 -> 11
            ImmutableInstruction11n(Opcode.CONST_4, 1, 0), // 8: break a pair on one case
            ImmutableInstruction10t(Opcode.GOTO, 2), // 9 -> 11
            ImmutableInstruction10x(Opcode.NOP), // 10
            ImmutableInstruction11x(Opcode.RETURN_WIDE, 0), // 11
            ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(0, 4), ImmutableSwitchElement(1, 6))))
        assertFalse(DexReadOnlyBody.inspect(switchMethod(join)).supportsScalarReplacement)
        val validJoin = join.toMutableList().apply { this[5] = ImmutableInstruction10x(Opcode.NOP) }
        val proof = DexReadOnlyBody.inspect(switchMethod(validJoin))
        assertTrue(proof.detail, proof.supportsScalarReplacement)
    }

    @Test fun rejectsSwitchLoopsInvalidTargetsAndExecutablePayloads() {
        for (offset in listOf(0, 7, 13, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val table = ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(0, offset)))
            assertFalse("offset=$offset", DexReadOnlyBody.inspect(switchMethod(switchCode(Opcode.PACKED_SWITCH, table))).supportsScalarReplacement)
        }
        val table = ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(0, 6), ImmutableSwitchElement(1, 9)))
        val fallthrough = switchCode(Opcode.PACKED_SWITCH, table).toMutableList().apply {
            this[7] = ImmutableInstruction10t(Opcode.GOTO, 2) // 12 -> payload at 14
        }
        assertFalse(DexReadOnlyBody.inspect(switchMethod(fallthrough)).supportsScalarReplacement)
    }

    @Test fun rejectsWrongMissingMisalignedAndOversizedSwitchTables() {
        val table = ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(0, 6)))
        assertFalse(DexReadOnlyBody.inspect(switchMethod(switchCode(Opcode.SPARSE_SWITCH, table))).supportsScalarReplacement)
        assertFalse(DexReadOnlyBody.inspect(switchMethod(switchCode(Opcode.PACKED_SWITCH, ImmutableInstruction10x(Opcode.NOP)))).supportsScalarReplacement)
        val misaligned = switchCode(Opcode.PACKED_SWITCH, table).toMutableList().apply {
            removeAt(8); this[1] = ImmutableInstruction31t(Opcode.PACKED_SWITCH, 2, 12)
        }
        assertFalse(DexReadOnlyBody.inspect(switchMethod(misaligned)).supportsScalarReplacement)
        val oversized = ImmutablePackedSwitchPayload((0..256).map { ImmutableSwitchElement(it, 6) })
        assertFalse(DexReadOnlyBody.inspect(switchMethod(switchCode(Opcode.PACKED_SWITCH, oversized))).supportsScalarReplacement)
        val duplicates = ImmutableSparseSwitchPayload(listOf(ImmutableSwitchElement(1, 6), ImmutableSwitchElement(1, 9)))
        assertFalse(DexReadOnlyBody.inspect(switchMethod(switchCode(Opcode.SPARSE_SWITCH, duplicates))).supportsScalarReplacement)
    }
}
