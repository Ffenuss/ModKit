package io.github.ffenuss.modkit.patch

import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.instruction.*
import org.jf.dexlib2.iface.reference.MethodReference

/** Exact platform overloads only; no reflection, virtual calls or throwing *Exact methods. */
internal object DexPureMathCall {
    data class Evidence(val arguments: List<Pair<Int, Int>>, val resultWidth: Int) {
        val resultOpcode: Opcode get() = if (resultWidth == 2) Opcode.MOVE_RESULT_WIDE else Opcode.MOVE_RESULT
    }

    fun inspect(instruction: Instruction): Evidence? {
        if (instruction.opcode !in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)) return null
        val method = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return null
        val count = when (method.name) { "abs" -> 1; "min", "max" -> 2; else -> return null }
        if (method.definingClass != "Ljava/lang/Math;" || method.returnType !in setOf("I", "J", "F", "D") ||
            method.parameterTypes.map { it.toString() } != List(count) { method.returnType }) return null
        val width = if (DexScalarReplacement.isWide(method.returnType)) 2 else 1
        val words = count * width
        val registers = when (instruction) {
            is FiveRegisterInstruction -> {
                if (instruction.registerCount != words) return null
                listOf(instruction.registerC, instruction.registerD, instruction.registerE,
                    instruction.registerF, instruction.registerG).take(words)
            }
            is RegisterRangeInstruction -> {
                if (instruction.registerCount != words || instruction.startRegister < 0 ||
                    instruction.startRegister > 65536 - words) return null
                List(words) { instruction.startRegister + it }
            }
            else -> return null
        }
        if (width == 2 && registers.chunked(2).any { it[1] != it[0] + 1 }) return null
        return Evidence(registers.chunked(width).map { it.first() to width }, width)
    }
}
