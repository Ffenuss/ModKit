package io.github.ffenuss.modkit.patch

import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.immutable.*
import org.jf.dexlib2.immutable.instruction.*
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.*
import org.junit.Test

class DexPureMathCallTest {
    private fun reference(name: String, type: String, owner: String = "Ljava/lang/Math;",
                          parameters: List<String> = List(if (name == "min" || name == "max") 2 else 1) { type }): MethodReference =
        ImmutableMethodReference(owner, name, parameters, type)
    private fun call(reference: MethodReference, registers: List<Int>, range: Boolean = false,
                     opcode: Opcode = Opcode.INVOKE_STATIC): Instruction = if (range)
        ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, registers.first(), registers.size, reference)
        else (registers + List(5 - registers.size) { 0 }).let {
            ImmutableInstruction35c(opcode, registers.size, it[0], it[1], it[2], it[3], it[4], reference)
        }
    private fun method(type: String, parameters: List<String>, registers: Int, code: List<Instruction>) =
        ImmutableMethod("Ldev/game/Player;", if (type == "J") "getAmmo" else "getRunSpeed",
            parameters.map { ImmutableMethodParameter(it, emptySet(), null) }, type,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, emptySet(), emptySet(),
            ImmutableMethodImplementation(registers, code, emptyList(), emptyList()))
    private fun proof(type: String, parameters: List<String>, registers: Int, vararg code: Instruction) =
        DexMethodBodyInspector.inspect(method(type, parameters, registers, code.toList()))

    @Test fun acceptsAllExactOverloadsAndBothInvokeEncodings() {
        for (type in listOf("I", "J", "F", "D")) for (name in listOf("min", "max", "abs")) for (range in listOf(false, true)) {
            val ref = reference(name, type)
            val width = if (type in listOf("J", "D")) 2 else 1
            val words = ref.parameterTypes.size * width
            val result = proof(type, ref.parameterTypes.map { it.toString() }, words + 2,
                call(ref, (2 until words + 2).toList(), range),
                ImmutableInstruction11x(if (width == 2) Opcode.MOVE_RESULT_WIDE else Opcode.MOVE_RESULT, 0),
                ImmutableInstruction11x(if (width == 2) Opcode.RETURN_WIDE else Opcode.RETURN, 0))
            assertEquals("$name $type range=$range: ${result.detail}", DexMethodBodyKind.READ_ONLY_COMPUTATION, result.kind)
            assertNull(result.fieldIdentity)
        }
    }

    private val extraPrototypes = listOf(
        Triple("round", "F", "I"), Triple("round", "D", "J"),
        Triple("floor", "D", "D"), Triple("ceil", "D", "D"), Triple("sqrt", "D", "D"))

    @Test fun acceptsNewExactPrototypesWithIndependentArgumentAndResultWidths() {
        for ((name, input, output) in extraPrototypes) for (range in listOf(false, true)) {
            val inputWidth = if (input == "D") 2 else 1
            val outputWidth = if (output in listOf("J", "D")) 2 else 1
            val ref = reference(name, output, parameters = listOf(input))
            val invoke = call(ref, (2 until 2 + inputWidth).toList(), range)
            val evidence = requireNotNull(DexPureMathCall.inspect(invoke))
            assertEquals(listOf(2 to inputWidth), evidence.arguments)
            assertEquals(outputWidth, evidence.resultWidth)
            val move = if (outputWidth == 2) Opcode.MOVE_RESULT_WIDE else Opcode.MOVE_RESULT
            val ret = if (outputWidth == 2) Opcode.RETURN_WIDE else Opcode.RETURN
            val result = proof(output, listOf(input), 2 + inputWidth, invoke,
                ImmutableInstruction11x(move, 0), ImmutableInstruction11x(ret, 0))
            assertTrue("$name($input)$output range=$range: ${result.detail}", result.supportsScalarReplacement)
            assertFalse(proof(output, listOf(input), 2 + inputWidth, invoke,
                ImmutableInstruction11x(if (outputWidth == 2) Opcode.MOVE_RESULT else Opcode.MOVE_RESULT_WIDE, 0),
                ImmutableInstruction11x(ret, 0)).supportsScalarReplacement)
            assertFalse(proof(output, listOf(input), 2 + inputWidth,
                ImmutableInstruction10t(Opcode.GOTO, 4), invoke,
                ImmutableInstruction11x(move, 0), ImmutableInstruction11x(ret, 0)).supportsScalarReplacement)
            assertFalse(proof(output, listOf(input), 2 + inputWidth,
                ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                ImmutableInstruction21t(Opcode.IF_EQZ, 0, 5), invoke,
                ImmutableInstruction11x(move, 0), ImmutableInstruction11x(ret, 0)).supportsScalarReplacement)
            if (inputWidth == 2) {
                assertFalse(proof(output, listOf(input), 4,
                    ImmutableInstruction11n(Opcode.CONST_4, 3, 1), invoke,
                    ImmutableInstruction11x(move, 0), ImmutableInstruction11x(ret, 0)).supportsScalarReplacement)
                assertFalse(proof(output, listOf(input), 4, call(ref, listOf(1, 2), range),
                    ImmutableInstruction11x(move, 0), ImmutableInstruction11x(ret, 0)).supportsScalarReplacement)
                assertNull(DexPureMathCall.inspect(call(ref, listOf(0, 2))))
            }
        }
    }

    @Test fun rejectsInventedRoundAndDoubleOnlyPrototypes() {
        for (name in listOf("round", "floor", "ceil", "sqrt"))
            for (input in listOf("I", "J", "F", "D")) for (output in listOf("I", "J", "F", "D")) {
                if (Triple(name, input, output) in extraPrototypes) continue
                val ref = reference(name, output, parameters = listOf(input))
                assertNull("$name($input)$output", DexPureMathCall.inspect(call(ref,
                    if (input in listOf("J", "D")) listOf(0, 1) else listOf(0))))
            }
        for ((name, input, output) in extraPrototypes) {
            val words = if (input == "D") listOf(0, 1) else listOf(0)
            assertNull(DexPureMathCall.inspect(call(reference(name, output, "Ldev/game/Math;", listOf(input)), words)))
            assertNull(DexPureMathCall.inspect(call(reference(name, output, parameters = listOf(input, input)), words)))
            assertNull(DexPureMathCall.inspect(call(reference(name, output, parameters = listOf(input)), words,
                opcode = Opcode.INVOKE_VIRTUAL)))
        }
    }

    @Test fun rejectsOtherOwnersThrowingMethodsAndInventedOverloads() {
        val refs = listOf(reference("abs", "I", "Ldev/game/Math;"), reference("absExact", "I"),
            reference("random", "D", parameters = emptyList()), reference("min", "I", parameters = listOf("I", "J")),
            reference("abs", "B"), reference("abs", "I", parameters = listOf("Ljava/lang/Object;")))
        for (ref in refs) assertNull(DexPureMathCall.inspect(call(ref, List(ref.parameterTypes.size) { it })))
        assertNull(DexPureMathCall.inspect(call(reference("abs", "I"), listOf(0), opcode = Opcode.INVOKE_VIRTUAL)))
    }

    @Test fun validatesArgumentWordCountsAndConsecutiveWideHalves() {
        val ref = reference("max", "J")
        assertNull(DexPureMathCall.inspect(call(ref, listOf(0, 1))))
        assertNull(DexPureMathCall.inspect(call(ref, listOf(0, 2, 3, 4))))
        // Two distinct valid pairs need not themselves be adjacent in 35c.
        assertNotNull(DexPureMathCall.inspect(call(ref, listOf(0, 1, 3, 4))))
        assertNull(DexPureMathCall.inspect(ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 0, 3, ref)))
    }

    @Test fun rejectsUndefinedAndBrokenArgumentPairs() {
        for (registers in listOf(listOf(0, 1), listOf(3, 4))) assertFalse(proof("J", listOf("J", "J"), 6,
            call(reference("abs", "J"), registers), ImmutableInstruction11x(Opcode.MOVE_RESULT_WIDE, 0),
            ImmutableInstruction11x(Opcode.RETURN_WIDE, 0)).supportsScalarReplacement)
        assertFalse(proof("J", listOf("J"), 4,
            ImmutableInstruction11n(Opcode.CONST_4, 3, 1), call(reference("abs", "J"), listOf(2, 3)),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_WIDE, 0), ImmutableInstruction11x(Opcode.RETURN_WIDE, 0)).supportsScalarReplacement)
        assertFalse(proof("I", listOf("I"), 2, call(reference("abs", "I"), listOf(0)),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0), ImmutableInstruction11x(Opcode.RETURN, 0)).supportsScalarReplacement)
    }

    @Test fun requiresTheImmediateResultWithExactWidth() {
        val invoke = call(reference("abs", "I"), listOf(1))
        assertFalse(proof("I", listOf("I"), 2, invoke, ImmutableInstruction10x(Opcode.NOP),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0), ImmutableInstruction11x(Opcode.RETURN, 0)).supportsScalarReplacement)
        assertFalse(proof("I", listOf("I"), 2, invoke, ImmutableInstruction11x(Opcode.MOVE_RESULT_WIDE, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0)).supportsScalarReplacement)
        assertFalse(proof("I", listOf("I"), 2, ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0)).supportsScalarReplacement)
        assertFalse(proof("I", listOf("I"), 2, invoke, ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0), ImmutableInstruction11x(Opcode.RETURN, 0)).supportsScalarReplacement)
    }

    @Test fun rejectsBranchesThatBypassTheInvokeBeforeItsResult() {
        assertFalse(proof("I", listOf("I"), 2,
            ImmutableInstruction11n(Opcode.CONST_4, 0, 1), // 0
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, 5), // 1 -> 6, bypasses call
            call(reference("abs", "I"), listOf(1)), // 3
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0), // 6
            ImmutableInstruction11x(Opcode.RETURN, 0)).supportsScalarReplacement)
        assertFalse(proof("I", listOf("I"), 2, ImmutableInstruction10t(Opcode.GOTO, 4),
            call(reference("abs", "I"), listOf(1)), ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0)).supportsScalarReplacement)
    }

    @Test fun supportsChainedCallsAndBranchesBeforeTheWholeCallPair() {
        val result = proof("I", listOf("I"), 2,
            ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, 2), // both paths reach the invoke
            call(reference("abs", "I"), listOf(1)), ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            call(reference("min", "I"), listOf(0, 1)), ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0))
        assertTrue(result.detail, result.supportsScalarReplacement)
    }
}
