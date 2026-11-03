package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference

/**
 * Narrow, side-effect-bounded proof that an owned float speed getter affects
 * an actual displacement field. It does not infer speed from a name or HUD use.
 *
 * Accepted straight-line DEX shape:
 *   position = position + this.obfuscatedSpeedGetter()
 *   iput position to this
 *
 * Cross-object writes, arbitrary calls, branches, loops and mixed arithmetic
 * are rejected rather than guessed. The existing getter-body inspector
 * independently proves that replacing the selected getter is side-effect-free.
 */
internal object DexMovementEvidenceAnalyzer {
    private const val MAX_INSTRUCTIONS = 48

    private sealed interface Value {
        data class Field(val identity: String, val fromGetter: Boolean) : Value
        data class Displacement(val speed: String, val position: String) : Value
    }

    fun find(
        dex: DexFile,
        provenGetters: Map<String, String>,
        cancellation: CancellationSignal,
    ): List<DexBehavioralPattern> {
        val result = ArrayList<DexBehavioralPattern>()
        for (clazz in dex.classes) {
            if (cancellation.isCancelled()) throw AnalysisCancelledException()
            if (DexLocalPatchEngine.excludedClass(clazz.type)) continue
            for (method in clazz.methods) {
                if (cancellation.isCancelled()) throw AnalysisCancelledException()
                val speed = proveDisplacement(method, provenGetters) ?: continue
                result += DexBehavioralPattern(
                    type = DexBehavioralPatternType.MOVEMENT_DELTA_WRITE_BACK,
                    field = speed,
                    method = DexBehavioralAnalyzer.methodIdPublic(method),
                    detail = "Возврат float-геттера доказуемо участвует в приращении локальной дистанции.",
                )
            }
        }
        return result
    }

    private fun proveDisplacement(
        method: Method,
        provenGetters: Map<String, String>,
    ): String? {
        val body = method.implementation ?: return null
        if (method.returnType != "V" || method.parameters.isNotEmpty() ||
            AccessFlags.STATIC.isSet(method.accessFlags) ||
            body.tryBlocks.isNotEmpty() || body.registerCount !in 2..64
        ) return null
        val code = body.instructions.take(MAX_INSTRUCTIONS + 1).toList()
        if (code.size > MAX_INSTRUCTIONS || code.isEmpty()) return null
        val thisReg = body.registerCount - 1
        val registerValues = arrayOfNulls<Value>(body.registerCount)
        var pending: Value.Field? = null
        var observed: String? = null
        var returned = false

        for ((index, instruction) in code.withIndex()) {
            val op = instruction.opcode
            if (pending != null && op != Opcode.MOVE_RESULT) return null
            when (op) {
                Opcode.IGET -> {
                    val ref = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                        ?: return null
                    val regs = instruction as? TwoRegisterInstruction ?: return null
                    if (regs.registerA !in registerValues.indices ||
                        regs.registerB != thisReg ||
                        ref.type != "F" || ref.definingClass != method.definingClass
                    ) return null
                    registerValues[regs.registerA] = Value.Field(fieldId(ref), false)
                }
                Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE -> {
                    val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                        ?: return null
                    if (ref.definingClass != method.definingClass ||
                        ref.parameterTypes.isNotEmpty() || ref.returnType != "F"
                    ) return null
                    val receiver = when (instruction) {
                        is FiveRegisterInstruction ->
                            if (instruction.registerCount == 1) instruction.registerC else -1
                        is RegisterRangeInstruction ->
                            if (instruction.registerCount == 1) instruction.startRegister else -1
                        else -> -1
                    }
                    if (receiver != thisReg) return null
                    val field = provenGetters[methodId(ref)] ?: return null
                    if (!field.endsWith(":F")) return null
                    pending = Value.Field(field, true)
                }
                Opcode.MOVE_RESULT -> {
                    val dest = (instruction as? OneRegisterInstruction)?.registerA ?: return null
                    if (dest !in registerValues.indices) return null
                    registerValues[dest] = pending ?: return null
                    pending = null
                }
                Opcode.ADD_FLOAT, Opcode.ADD_FLOAT_2ADDR -> {
                    val dest: Int
                    val left: Int
                    val right: Int
                    if (op == Opcode.ADD_FLOAT) {
                        val i = instruction as? ThreeRegisterInstruction ?: return null
                        dest = i.registerA
                        left = i.registerB
                        right = i.registerC
                    } else {
                        val i = instruction as? TwoRegisterInstruction ?: return null
                        dest = i.registerA
                        left = i.registerA
                        right = i.registerB
                    }
                    if (listOf(dest, left, right).any { it !in registerValues.indices }) return null
                    val a = registerValues[left] as? Value.Field ?: return null
                    val b = registerValues[right] as? Value.Field ?: return null
                    val speed = when {
                        a.fromGetter && !b.fromGetter -> a.identity to b.identity
                        b.fromGetter && !a.fromGetter -> b.identity to a.identity
                        else -> return null
                    }
                    if (speed.first == speed.second ||
                        !speedField(speed.first) || !positionField(speed.second)
                    ) return null
                    registerValues[dest] = Value.Displacement(speed.first, speed.second)
                }
                Opcode.IPUT -> {
                    val ref = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                        ?: return null
                    val i = instruction as? TwoRegisterInstruction ?: return null
                    if (i.registerA !in registerValues.indices ||
                        i.registerB != thisReg || ref.type != "F" ||
                        ref.definingClass != method.definingClass
                    ) return null
                    val delta = registerValues[i.registerA] as? Value.Displacement
                        ?: return null
                    if (delta.position != fieldId(ref) || observed != null) return null
                    observed = delta.speed
                    registerValues[i.registerA] = null
                }
                Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16 -> {
                    val i = instruction as? TwoRegisterInstruction ?: return null
                    if (i.registerA !in registerValues.indices ||
                        i.registerB !in registerValues.indices) return null
                    registerValues[i.registerA] = registerValues[i.registerB]
                }
                Opcode.RETURN_VOID -> {
                    if (index != code.lastIndex || observed == null) return null
                    returned = true
                }
                Opcode.NOP -> Unit
                else -> return null
            }
        }
        return if (returned) observed else null
    }

    private fun speedField(identity: String): Boolean {
        val key = identity.substringAfter("->").substringBefore(':')
            .lowercase().filter(Char::isLetterOrDigit)
        return key in setOf(
            "movespeed", "movementspeed", "walkspeed", "runspeed",
            "sprintspeed", "playerspeed",
        )
    }

    private fun positionField(identity: String): Boolean {
        val key = identity.substringAfter("->").substringBefore(':')
            .lowercase().filter(Char::isLetterOrDigit)
        return key in setOf(
            "distance", "distancetraveled", "traveldistance",
            "positionx", "positiony", "posx", "posy",
        )
    }

    private fun fieldId(ref: FieldReference): String =
        ref.definingClass + "->" + ref.name + ":" + ref.type

    private fun methodId(ref: MethodReference): String =
        ref.definingClass + "->" + ref.name +
            "(" + ref.parameterTypes.joinToString("") + ")" + ref.returnType
}
