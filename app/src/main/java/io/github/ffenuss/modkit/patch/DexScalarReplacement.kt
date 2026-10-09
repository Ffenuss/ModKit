package io.github.ffenuss.modkit.patch

import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.immutable.instruction.*

/** Declared DEX return types determine register width and literal representation. */
internal object DexScalarReplacement {
    val supportedTypes = setOf("Z", "I", "F", "J", "D")
    fun isWide(type: String) = type == "J" || type == "D"
    fun returnOpcode(type: String) = if (isWide(type)) Opcode.RETURN_WIDE else Opcode.RETURN
    fun literal(type: String, action: DexLocalAction): Long {
        require(type in when (action) {
            DexLocalAction.TRUE, DexLocalAction.FALSE -> setOf("Z")
            DexLocalAction.INT_9999, DexLocalAction.INT_99 -> setOf("I", "J")
            DexLocalAction.FLOAT_2 -> setOf("F", "D")
        }) { "DEX action does not match the declared return type." }
        return when (action) {
            DexLocalAction.TRUE -> 1L
            DexLocalAction.FALSE -> 0L
            DexLocalAction.INT_9999 -> 9999L
            DexLocalAction.INT_99 -> 99L
            DexLocalAction.FLOAT_2 -> if (type == "D") 2.0.toBits() else 2.0f.toBits().toLong()
        }
    }
    fun instruction(type: String, action: DexLocalAction): Instruction {
        val value = literal(type, action)
        return when {
            isWide(type) -> ImmutableInstruction51l(Opcode.CONST_WIDE, 0, value)
            type == "Z" -> ImmutableInstruction11n(Opcode.CONST_4, 0, value.toInt())
            else -> ImmutableInstruction31i(Opcode.CONST, 0, value.toInt())
        }
    }
}
