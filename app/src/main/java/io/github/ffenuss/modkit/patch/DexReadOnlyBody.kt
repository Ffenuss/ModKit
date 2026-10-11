package io.github.ffenuss.modkit.patch

import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.*
import org.jf.dexlib2.iface.reference.FieldReference

/** Finite, side-effect-free scalar CFG with definite register-pair assignment. */
object DexReadOnlyBody {
    private data class Flow(val write: Int = 0, val a: Int = 0, val b: Int = 0, val c: Int = 0)

    fun inspect(method: Method): DexMethodBodyEvidence {
        fun blocked(reason: String) = DexMethodBodyEvidence(DexMethodBodyKind.UNSUPPORTED, reason)
        val body = method.implementation ?: return blocked("Нет тела DEX-метода.")
        if (!DexMethodParameters.supported(method.parameterTypes) || method.returnType !in DexScalarReplacement.supportedTypes ||
            body.registerCount < maxOf(1, DexMethodParameters.inputWords(method)) || body.tryBlocks.isNotEmpty()) return blocked("Неподдерживаемая сигнатура, регистры или обработчики исключений.")
        val code = body.instructions.take(257)
        if (code.size > 256) return blocked("Тело больше 256 инструкций: требуется расширенный анализ потока данных.")
        val addresses = IntArray(code.size)
        var position = 0
        code.forEachIndexed { index, instruction -> addresses[index] = position; position += instruction.codeUnits }
        val indices = addresses.withIndex().associate { it.value to it.index }
        // Switch offsets are relative to the opcode, not its data table. Use
        // Long arithmetic so malformed signed offsets cannot wrap into the body.
        fun target(index: Int, offset: Int): Int? {
            val address = addresses[index].toLong() + offset
            return if (address in 0L until position.toLong()) indices[address.toInt()] else null
        }
        val states = IntArray(code.size)
        val successors = Array(code.size) { emptyList<Int>() }
        val postorder = ArrayList<Int>()
        val flows = arrayOfNulls<Flow>(code.size)
        val mathCalls = arrayOfNulls<DexPureMathCall.Evidence>(code.size)
        var reason = "В методе есть вызов, запись состояния или неподдерживаемая инструкция."
        var returns = 0
        fun visit(index: Int): Boolean {
            if (index !in code.indices) { reason = "Переход за границы тела DEX."; return false }
            if (states[index] == 1) { reason = "Обнаружен цикл: завершение ещё не доказано."; return false }
            if (states[index] == 2) return true
            val instruction = code[index]
            val op = instruction.opcode
            val math = DexPureMathCall.inspect(instruction)
            mathCalls[index] = math
            val flow = when {
                math != null -> {
                    if (code.getOrNull(index + 1)?.opcode != math.resultOpcode) {
                        reason = "Результат Math должен считываться следующей инструкцией правильной ширины."; return false
                    }
                    Flow()
                }
                op in mathResults -> {
                    val producer = code.getOrNull(index - 1)?.let(DexPureMathCall::inspect)
                    if (producer == null || op != producer.resultOpcode) {
                        reason = "MOVE_RESULT не связан с разрешённым Math-вызовом."; return false
                    }
                    Flow(write = producer.resultWidth)
                }
                op == DexScalarReplacement.returnOpcode(method.returnType) -> Flow(a = if (DexScalarReplacement.isWide(method.returnType)) 2 else 1)
                op in unconditional || op == Opcode.NOP -> Flow()
                op in jumps -> Flow(a = 1, b = if (instruction is TwoRegisterInstruction) 1 else 0)
                op in switches -> Flow(a = 1)
                op in reads -> Flow(write = if (op in wideReads) 2 else 1)
                else -> registerFlows[op]
            } ?: run { reason = "Нужен анализ ${op.name}: возможны вызовы или побочные эффекты."; return false }
            flows[index] = flow
            val a = (instruction as? OneRegisterInstruction)?.registerA
            val b = (instruction as? TwoRegisterInstruction)?.registerB
            val c = (instruction as? ThreeRegisterInstruction)?.registerC
            fun fits(register: Int?, width: Int) = width == 0 || register != null && register >= 0 && register <= body.registerCount - width
            if (!fits(a, maxOf(flow.write, flow.a)) || !fits(b, flow.b) || !fits(c, flow.c) ||
                op in instanceReads && !fits(b, 1)) {
                reason = "Недопустимый регистр или неполная пара DEX."; return false
            }
            states[index] = 1
            val next: List<Int>
            when {
                op == DexScalarReplacement.returnOpcode(method.returnType) -> { returns++; next = emptyList() }
                op in jumps -> {
                    val destination = target(index, (instruction as OffsetInstruction).codeOffset)
                    if (destination == null) { reason = "Переход не указывает на начало инструкции."; return false }
                    next = if (op in unconditional) listOf(destination) else listOf(index + 1, destination)
                }
                op in switches -> {
                    val tableIndex = target(index, (instruction as OffsetInstruction).codeOffset)
                    val table = tableIndex?.let { code[it] as? SwitchPayload }
                    val expected = if (op == Opcode.PACKED_SWITCH) Opcode.PACKED_SWITCH_PAYLOAD else Opcode.SPARSE_SWITCH_PAYLOAD
                    if (tableIndex == null || addresses[tableIndex] % 2 != 0 || table == null || table.opcode != expected) {
                        reason = "Таблица switch отсутствует, имеет неверный тип или выравнивание."; return false
                    }
                    val elements = table.switchElements.take(257)
                    if (elements.size > 256 || elements.zipWithNext().any { (a, b) ->
                            if (op == Opcode.PACKED_SWITCH) b.key.toLong() != a.key.toLong() + 1
                            else b.key <= a.key
                        }) {
                        reason = "Таблица switch слишком велика или ключи некорректны."; return false
                    }
                    val destinations = elements.map { target(index, it.offset) }
                    if (destinations.any { it == null }) {
                        reason = "Ветвь switch не указывает на начало инструкции."; return false
                    }
                    // Include the no-match path even when every listed case returns.
                    // Data tables themselves must never enter the executable CFG.
                    next = (listOf(index + 1) + destinations.filterNotNull()).distinct()
                }
                op in reads -> {
                    val field = (instruction as ReferenceInstruction).reference as? FieldReference ?: return false
                    if (field.definingClass != method.definingClass || field.type !in fieldTypes.getValue(op) ||
                        op in instanceReads && (AccessFlags.STATIC.isSet(method.accessFlags) || b != DexMethodParameters.thisRegister(method, body.registerCount))) {
                        reason = "Чтение не привязано к полю правильного типа своего класса/this."; return false
                    }
                    next = listOf(index + 1)
                }
                else -> next = listOf(index + 1)
            }
            if (!next.all(::visit)) return false
            successors[index] = next
            states[index] = 2
            postorder += index
            return true
        }
        if (!visit(0) || returns == 0) return blocked(reason)
        // A MOVE_RESULT cannot be reached through a branch that bypasses its invoke.
        val predecessors = Array(code.size) { mutableSetOf<Int>() }
        successors.forEachIndexed { source, targets -> targets.forEach { predecessors[it].add(source) } }
        for (index in code.indices) if (states[index] == 2 && code[index].opcode in mathResults &&
            predecessors[index] != setOf(index - 1)) return blocked("Переход обходит Math-вызов перед MOVE_RESULT.")
        // Only facts true on EVERY incoming edge survive. Low/high markers are
        // kept together: overwriting either half invalidates the old pair.
        val incoming = arrayOfNulls<IntArray>(code.size)
        incoming[0] = IntArray(body.registerCount).apply {
            var register = body.registerCount - DexMethodParameters.inputWords(method)
            if (!AccessFlags.STATIC.isSet(method.accessFlags)) this[register++] = THIS
            for (type in method.parameterTypes) {
                if (DexScalarReplacement.isWide(type.toString())) { this[register++] = LOW; this[register++] = HIGH }
                else this[register++] = SCALAR
            }
        }
        for (index in postorder.asReversed()) {
            val values = requireNotNull(incoming[index]).clone()
            val instruction = code[index]
            val op = instruction.opcode
            val flow = requireNotNull(flows[index])
            val a = (instruction as? OneRegisterInstruction)?.registerA ?: 0
            val b = (instruction as? TwoRegisterInstruction)?.registerB ?: 0
            val c = (instruction as? ThreeRegisterInstruction)?.registerC ?: 0
            fun assigned(register: Int, width: Int) = when (width) {
                0 -> true
                1 -> values[register] == SCALAR
                2 -> values[register] == LOW && values[register + 1] == HIGH
                else -> false
            }
            if (mathCalls[index]?.arguments?.any { (register, width) ->
                    register < 0 || register > body.registerCount - width || !assigned(register, width)
                } == true) return blocked("Аргументы Math не определены или wide-пара повреждена.")
            if (!assigned(a, flow.a) || !assigned(b, flow.b) || !assigned(c, flow.c) ||
                op in instanceReads && values[b] != THIS) return blocked("Возвращаемое значение или полная пара регистров не определены на каждом пути.")
            fun clear(register: Int) {
                if (values[register] == LOW && register + 1 < values.size) values[register + 1] = 0
                if (values[register] == HIGH && register > 0) values[register - 1] = 0
                values[register] = 0
            }
            if (flow.write > 0) {
                clear(a)
                if (flow.write == 2) { clear(a + 1); values[a] = LOW; values[a + 1] = HIGH }
                else values[a] = SCALAR
            }
            for (next in successors[index]) {
                val previous = incoming[next]
                incoming[next] = if (previous == null) values.clone() else IntArray(values.size) {
                    if (previous[it] == values[it]) values[it] else 0
                }
            }
        }
        return DexMethodBodyEvidence(DexMethodBodyKind.READ_ONLY_COMPUTATION,
            "Проверены все достижимые ветви и скалярные/парные регистры: " +
                if (mathCalls.any { it != null }) "только чтение, вычисления и точные Math.min/max/abs/round/floor/ceil/sqrt без записи состояния."
                else "вычисление без вызовов и записи состояния.")
    }

    private const val SCALAR = 1
    private const val THIS = 2
    private const val LOW = 3
    private const val HIGH = 4
    private val mathResults = setOf(Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_WIDE)
    private val unconditional = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
    private val switches = setOf(Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH)
    private val jumps = unconditional + setOf(Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE,
        Opcode.IF_GT, Opcode.IF_LE, Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ)
    private val instanceReads = setOf(Opcode.IGET, Opcode.IGET_BOOLEAN, Opcode.IGET_BYTE, Opcode.IGET_CHAR, Opcode.IGET_SHORT, Opcode.IGET_WIDE)
    private val reads = instanceReads + setOf(Opcode.SGET, Opcode.SGET_BOOLEAN, Opcode.SGET_BYTE, Opcode.SGET_CHAR, Opcode.SGET_SHORT, Opcode.SGET_WIDE)
    private val wideReads = setOf(Opcode.IGET_WIDE, Opcode.SGET_WIDE)
    private val fieldTypes = mapOf(
        Opcode.IGET to setOf("I", "F"), Opcode.SGET to setOf("I", "F"),
        Opcode.IGET_BOOLEAN to setOf("Z"), Opcode.SGET_BOOLEAN to setOf("Z"),
        Opcode.IGET_BYTE to setOf("B"), Opcode.SGET_BYTE to setOf("B"),
        Opcode.IGET_SHORT to setOf("S"), Opcode.SGET_SHORT to setOf("S"),
        Opcode.IGET_CHAR to setOf("C"), Opcode.SGET_CHAR to setOf("C"),
        Opcode.IGET_WIDE to setOf("J", "D"), Opcode.SGET_WIDE to setOf("J", "D"),
    )
    private val registerFlows: Map<Opcode, Flow> = buildMap {
        fun add(names: List<String>, flow: Flow) {
            Opcode.values().filter { it.name.uppercase().replace('-', '_').replace('/', '_') in names }.forEach { put(it, flow) }
        }
        add(listOf("CONST_4", "CONST_16", "CONST", "CONST_HIGH16"), Flow(write = 1))
        add(listOf("CONST_WIDE_16", "CONST_WIDE_32", "CONST_WIDE", "CONST_WIDE_HIGH16"), Flow(write = 2))
        add(listOf("MOVE", "MOVE_FROM16", "MOVE_16", "NEG_INT", "NOT_INT", "NEG_FLOAT", "INT_TO_FLOAT", "FLOAT_TO_INT",
            "INT_TO_BYTE", "INT_TO_CHAR", "INT_TO_SHORT"), Flow(write = 1, b = 1))
        add(listOf("MOVE_WIDE", "MOVE_WIDE_FROM16", "MOVE_WIDE_16", "NEG_LONG", "NOT_LONG", "NEG_DOUBLE", "LONG_TO_DOUBLE", "DOUBLE_TO_LONG"), Flow(write = 2, b = 2))
        add(listOf("INT_TO_LONG", "INT_TO_DOUBLE", "FLOAT_TO_LONG", "FLOAT_TO_DOUBLE"), Flow(write = 2, b = 1))
        add(listOf("LONG_TO_INT", "LONG_TO_FLOAT", "DOUBLE_TO_INT", "DOUBLE_TO_FLOAT"), Flow(write = 1, b = 2))
        add(listOf("CMPL_FLOAT", "CMPG_FLOAT"), Flow(write = 1, b = 1, c = 1))
        add(listOf("CMP_LONG", "CMPL_DOUBLE", "CMPG_DOUBLE"), Flow(write = 1, b = 2, c = 2))
        for (operation in listOf("ADD", "SUB", "MUL", "AND", "OR", "XOR", "SHL", "SHR", "USHR")) {
            add(listOf("${operation}_INT"), Flow(write = 1, b = 1, c = 1))
            add(listOf("${operation}_INT_2ADDR"), Flow(write = 1, a = 1, b = 1))
            add(listOf("${operation}_INT_LIT8", "${operation}_INT_LIT16"), Flow(write = 1, b = 1))
            val shift = operation in listOf("SHL", "SHR", "USHR")
            add(listOf("${operation}_LONG"), Flow(write = 2, b = 2, c = if (shift) 1 else 2))
            add(listOf("${operation}_LONG_2ADDR"), Flow(write = 2, a = 2, b = if (shift) 1 else 2))
        }
        add(listOf("RSUB_INT", "RSUB_INT_LIT8"), Flow(write = 1, b = 1))
        for (operation in listOf("ADD", "SUB", "MUL", "DIV", "REM")) for (type in listOf("FLOAT", "DOUBLE")) {
            val width = if (type == "DOUBLE") 2 else 1
            add(listOf("${operation}_${type}"), Flow(write = width, b = width, c = width))
            add(listOf("${operation}_${type}_2ADDR"), Flow(write = width, a = width, b = width))
        }
        // Integer/long DIV and REM can throw; do not erase those exceptions.
    }
}
