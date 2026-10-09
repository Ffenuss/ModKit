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
        if (method.definingClass != "Ljava/lang/Math;") return null
        val parameters = method.parameterTypes.map { it.toString() }
        val argumentType = when (method.name) {
            "abs", "min", "max" -> method.returnType.takeIf { type ->
                type in setOf("I", "J", "F", "D") &&
                    parameters == List(if (method.name == "abs") 1 else 2) { type }
            }
            "round" -> when {
                parameters == listOf("F") && method.returnType == "I" -> "F"
                parameters == listOf("D") && method.returnType == "J" -> "D"
                else -> null
            }
            "floor", "ceil", "sqrt" -> "D".takeIf {
                parameters == listOf("D") && method.returnType == "D"
            }
            else -> null
        } ?: return null
        val width = if (DexScalarReplacement.isWide(argumentType)) 2 else 1
        val resultWidth = if (DexScalarReplacement.isWide(method.returnType)) 2 else 1
        val words = parameters.size * width
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
        return Evidence(registers.chunked(width).map { it.first() to width }, resultWidth)
    }
}
