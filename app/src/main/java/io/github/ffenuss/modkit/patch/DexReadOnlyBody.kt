package io.github.ffenuss.modkit.patch

import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.*
import org.jf.dexlib2.iface.reference.FieldReference

/** Finite, side-effect-free scalar CFG; unsupported paths remain explicit. */
object DexReadOnlyBody {
    fun inspect(method: Method): DexMethodBodyEvidence {
        fun blocked(reason: String) = DexMethodBodyEvidence(DexMethodBodyKind.UNSUPPORTED, reason)
        val body = method.implementation ?: return blocked("Нет тела DEX-метода.")
        val code = body.instructions.take(257)
        if (code.size > 256) return blocked("Тело больше 256 инструкций: требуется расширенный анализ потока данных.")
        val addresses = IntArray(code.size)
        var position = 0
        code.forEachIndexed { index, instruction -> addresses[index] = position; position += instruction.codeUnits }
        val indices = addresses.withIndex().associate { it.value to it.index }
        val states = IntArray(code.size)
        val successors = Array(code.size) { emptyList<Int>() }
        val postorder = ArrayList<Int>()
        var reason = "В методе есть вызов, запись состояния или неподдерживаемая инструкция."
        var returns = 0
        fun visit(index: Int): Boolean {
            if (index !in code.indices) { reason = "Переход за границы тела DEX."; return false }
            if (states[index] == 1) { reason = "Обнаружен цикл: завершение ещё не доказано."; return false }
            if (states[index] == 2) return true
            val instruction = code[index]
            val op = instruction.opcode
            if ((instruction is OneRegisterInstruction && instruction.registerA !in 0 until body.registerCount) ||
                (instruction is TwoRegisterInstruction && instruction.registerB !in 0 until body.registerCount) ||
                (instruction is ThreeRegisterInstruction && instruction.registerC !in 0 until body.registerCount)) {
                reason = "Недопустимый регистр DEX."; return false
            }
            states[index] = 1
            val next: List<Int>
            when {
                op == Opcode.RETURN -> { returns++; next = emptyList() }
                op in jumps -> {
                    val destination = indices[addresses[index] + (instruction as OffsetInstruction).codeOffset]
                    if (destination == null) { reason = "Переход не указывает на начало инструкции."; return false }
                    next = if (op in unconditional) listOf(destination) else listOf(index + 1, destination)
                }
                op in reads -> {
                    val field = (instruction as ReferenceInstruction).reference as? FieldReference ?: return false
                    val instance = op in instanceReads
                    if (field.definingClass != method.definingClass || field.type !in setOf("Z", "B", "S", "C", "I", "F") ||
                        (instance && (AccessFlags.STATIC.isSet(method.accessFlags) ||
                            (instruction as TwoRegisterInstruction).registerB != body.registerCount - 1))) {
                        reason = "Чтение не привязано к скалярному полю своего класса/this."; return false
                    }
                    next = listOf(index + 1)
                }
                op in registerOnly -> next = listOf(index + 1)
                else -> { reason = "Нужен анализ ${op.name}: возможны вызовы или побочные эффекты."; return false }
            }
            if (!next.all(::visit)) return false
            successors[index] = next
            states[index] = 2
            postorder += index
            return true
        }
        if (!visit(0) || returns == 0) return blocked(reason)
        // Definite assignment on the proven acyclic CFG. At joins only facts true
        // on EVERY incoming edge survive; an arbitrary register is not a value.
        val incoming = arrayOfNulls<IntArray>(code.size)
        incoming[0] = IntArray(body.registerCount).apply {
            if (!AccessFlags.STATIC.isSet(method.accessFlags)) this[lastIndex] = 2 // this, not a scalar
        }
        for (index in postorder.asReversed()) {
            val values = requireNotNull(incoming[index]).clone()
            val instruction = code[index]
            val op = instruction.opcode
            val name = op.name.uppercase().replace('-', '_').replace('/', '_')
            fun scalar(register: Int) = values[register] == 1
            val readsValid = when {
                op == Opcode.RETURN -> scalar((instruction as OneRegisterInstruction).registerA)
                op in unconditional || op == Opcode.NOP || name.startsWith("CONST") -> true
                op in instanceReads -> values[(instruction as TwoRegisterInstruction).registerB] == 2
                op in reads -> true
                op in jumps -> scalar((instruction as OneRegisterInstruction).registerA) &&
                    (instruction !is TwoRegisterInstruction || scalar(instruction.registerB))
                name.endsWith("_2ADDR") -> scalar((instruction as TwoRegisterInstruction).registerA) && scalar(instruction.registerB)
                instruction is ThreeRegisterInstruction -> scalar(instruction.registerB) && scalar(instruction.registerC)
                instruction is TwoRegisterInstruction -> scalar(instruction.registerB)
                else -> false
            }
            if (!readsValid) return blocked("Возвращаемое значение или вход вычисления не определены на каждом пути.")
            if (op !in jumps && op != Opcode.RETURN && op != Opcode.NOP) values[(instruction as OneRegisterInstruction).registerA] = 1
            for (next in successors[index]) {
                val previous = incoming[next]
                incoming[next] = if (previous == null) values.clone() else IntArray(values.size) {
                    if (previous[it] == values[it]) values[it] else 0
                }
            }
        }
        return DexMethodBodyEvidence(DexMethodBodyKind.READ_ONLY_COMPUTATION,
            "Проверены все достижимые ветви и возвращаемые регистры: вычисление без вызовов и записи состояния.")
    }

    private val unconditional = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
    private val jumps = unconditional + setOf(Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE,
        Opcode.IF_GT, Opcode.IF_LE, Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ)
    private val instanceReads = setOf(Opcode.IGET, Opcode.IGET_BOOLEAN, Opcode.IGET_BYTE, Opcode.IGET_CHAR, Opcode.IGET_SHORT)
    private val reads = instanceReads + setOf(Opcode.SGET, Opcode.SGET_BOOLEAN, Opcode.SGET_BYTE, Opcode.SGET_CHAR, Opcode.SGET_SHORT)
    private val registerOnly = setOf(Opcode.NOP, Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16,
        Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16,
        Opcode.NEG_INT, Opcode.NOT_INT, Opcode.NEG_FLOAT, Opcode.INT_TO_FLOAT, Opcode.FLOAT_TO_INT,
        Opcode.INT_TO_BYTE, Opcode.INT_TO_CHAR, Opcode.INT_TO_SHORT, Opcode.CMPL_FLOAT, Opcode.CMPG_FLOAT) +
        Opcode.values().filter {
            it.name.uppercase().replace('-', '_').replace('/', '_') in arithmeticNames
        }
    private val arithmeticNames: Set<String> get() = buildSet {
        for (op in listOf("ADD", "SUB", "MUL", "AND", "OR", "XOR", "SHL", "SHR", "USHR")) {
            add("${op}_INT"); add("${op}_INT_2ADDR"); add("${op}_INT_LIT8"); add("${op}_INT_LIT16")
        }
        add("RSUB_INT"); add("RSUB_INT_LIT8")
        for (op in listOf("ADD", "SUB", "MUL", "DIV", "REM")) { add("${op}_FLOAT"); add("${op}_FLOAT_2ADDR") }
    }
}
