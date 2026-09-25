package io.github.ffenuss.modkit.patch

import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction
import org.jf.dexlib2.iface.reference.FieldReference

enum class DexMethodBodyKind { CONSTANT_RETURN, INSTANCE_FIELD_GETTER, STATIC_FIELD_GETTER, READ_ONLY_COMPUTATION, UNSUPPORTED }

data class DexMethodBodyEvidence(
    val kind: DexMethodBodyKind,
    val detail: String,
    val field: FieldReference? = null,
) {
    val supportsScalarReplacement: Boolean get() = kind != DexMethodBodyKind.UNSUPPORTED
    val fieldIdentity: String? get() = this.field?.let { "${it.definingClass}->${it.name}:${it.type}" }
}

/** Proves a narrow, side-effect-free return dataflow. It does NOT prove gameplay intent. */
object DexMethodBodyInspector {
    fun inspect(method: Method): DexMethodBodyEvidence {
        fun unsupported(why: String) = DexMethodBodyEvidence(DexMethodBodyKind.UNSUPPORTED, why)
        val body = method.implementation ?: return unsupported("Нет тела DEX-метода.")
        if (method.parameterTypes.isNotEmpty() || method.returnType !in setOf("Z", "I", "F")) {
            return unsupported("Для этой сигнатуры ещё нет проверенного рецепта.")
        }
        if (body.tryBlocks.isNotEmpty()) return unsupported("В методе есть обработчики исключений.")
        // Read at most three instructions; never materialize a large method merely to reject it.
        val code = body.instructions.take(3)
        if (code.size != 2 || code[1].opcode != Opcode.RETURN) {
            return DexReadOnlyBody.inspect(method)
        }
        val source = code[0] as? OneRegisterInstruction ?: return unsupported("Источник результата не доказан.")
        val result = code[1] as? OneRegisterInstruction ?: return unsupported("Регистр результата не доказан.")
        if (source.registerA != result.registerA || source.registerA !in 0 until body.registerCount) {
            return unsupported("Возвращается другой или недопустимый регистр.")
        }
        if (code[0].opcode in setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)) {
            return DexMethodBodyEvidence(DexMethodBodyKind.CONSTANT_RETURN,
                "Тело метода возвращает константу без вызовов и записи состояния.")
        }
        val field = (code[0] as? ReferenceInstruction)?.reference as? FieldReference
            ?: return unsupported("Нет доказанного чтения поля или константы.")
        if (field.definingClass != method.definingClass || field.type != method.returnType) {
            return unsupported("Тип или владелец прочитанного поля не совпадает с методом.")
        }
        val instanceOpcode = if (field.type == "Z") Opcode.IGET_BOOLEAN else Opcode.IGET
        val staticOpcode = if (field.type == "Z") Opcode.SGET_BOOLEAN else Opcode.SGET
        if (code[0].opcode == instanceOpcode) {
            val read = code[0] as? TwoRegisterInstruction ?: return unsupported("Нет регистра объекта.")
            if (AccessFlags.STATIC.isSet(method.accessFlags) || read.registerB != body.registerCount - 1) {
                return unsupported("Чтение поля не привязано к this.")
            }
            return DexMethodBodyEvidence(DexMethodBodyKind.INSTANCE_FIELD_GETTER,
                "Возвращается поле ${field.name}; вызовов и записи состояния нет.", field)
        }
        if (code[0].opcode == staticOpcode) {
            return DexMethodBodyEvidence(DexMethodBodyKind.STATIC_FIELD_GETTER,
                "Возвращается статическое поле ${field.name} своего класса.", field)
        }
        return unsupported("Эта инструкция чтения ещё не поддерживается.")
    }
}
