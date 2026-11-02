package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference

/**
 * Behavioral patterns recognized from DEX method bodies.
 * Every pattern is a concrete, name-independent code shape.
 */
enum class DexBehavioralPatternType(val label: String) {
    DAMAGE_WRITE_BACK("Понижение поля на константу с записью результата обратно"),
    ZERO_THRESHOLD_CHECK("Булево сравнение поля с нулём"),
    GUARDED_CONSUMPTION("Расход поля под нулевым условием в том же методе"),
    CLAMPED_INCREMENT("Повышение поля на константу с записью результата обратно"),
}

data class DexBehavioralPattern(
    val type: DexBehavioralPatternType,
    val field: String,
    val method: String,
    val detail: String,
    /** For ZERO_THRESHOLD_CHECK: which constant makes the gameplay state "good". */
    val thresholdAction: DexLocalAction? = null,
)

/**
 * Result of the structural pass over one DEX: behavioral patterns plus the
 * relations they were built from (proven getters, calls, field readers/writers).
 */
data class DexBehavioralModel(
    val patterns: List<DexBehavioralPattern>,
    val provenGetters: Map<String, String>,
    val calls: Map<String, Set<String>>,
    val fieldReaders: Map<String, Set<String>>,
    val fieldWriters: Map<String, Set<String>>,
    val methodsAnalyzed: Int,
    val methodsSkipped: Int,
)

/**
 * Method-local structural analysis of DEX bodies.
 *
 * Recognizes resource-mutation shapes independent of method names:
 *  - field = field - const, written back (optionally clamped by Math.max(0, x));
 *  - boolean `field <= 0` / `field > 0` checks;
 *  - guarded consumption `if (field > 0) { field = field - const }`;
 *  - field = field + const, written back (optionally clamped by Math.min(N, x)).
 *
 * A field read through a proven no-argument field getter counts as a read of
 * that field (one-level interprocedural dataflow). Methods with exception
 * handlers, more than 256 instructions, or an instruction shape the analyzer
 * cannot reason about are skipped (fail-closed). Register bindings are
 * invalidated at every branch, so a fact is only recorded on a straight-line
 * path where the value is proven.
 */
object DexBehavioralAnalyzer {
    private const val MAX_INSTRUCTIONS = 256
    private val SCALAR_TYPES = setOf("I", "F")

    fun analyze(dex: DexFile, cancellation: CancellationSignal): DexBehavioralModel {
        val provenGetters = HashMap<String, String>()
        val methods = ArrayList<Method>()
        for (classDef in dex.classes) {
            checkCancelled(cancellation)
            if (DexLocalPatchEngine.excludedClass(classDef.type)) continue
            for (method in classDef.methods) {
                if (method.implementation == null) continue
                methods += method
                val body = DexMethodBodyInspector.inspect(method)
                val field = body.field
                if ((body.kind == DexMethodBodyKind.INSTANCE_FIELD_GETTER ||
                            body.kind == DexMethodBodyKind.STATIC_FIELD_GETTER) &&
                    field != null && field.type in SCALAR_TYPES
                ) {
                    provenGetters[methodId(method)] = fieldIdentity(field)
                }
            }
        }

        val patterns = ArrayList<DexBehavioralPattern>()
        val calls = HashMap<String, Set<String>>()
        val fieldReaders = HashMap<String, HashSet<String>>()
        val fieldWriters = HashMap<String, HashSet<String>>()
        var analyzed = 0
        var skipped = 0
        for (method in methods) {
            checkCancelled(cancellation)
            val impl = method.implementation!!
            if (impl.tryBlocks.isNotEmpty()) {
                skipped++
                continue
            }
            val code = impl.instructions.take(MAX_INSTRUCTIONS + 1).toList()
            if (code.size > MAX_INSTRUCTIONS) {
                skipped++
                continue
            }
            val facts = scanMethod(method, code, provenGetters)
            if (facts == null) {
                skipped++
                continue
            }
            analyzed++
            val mid = methodId(method)
            if (facts.calleeIds.isNotEmpty()) calls[mid] = facts.calleeIds.toSet()
            facts.readFields.forEach { fieldReaders.getOrPut(it) { HashSet() }.add(mid) }
            facts.writtenFields.forEach { fieldWriters.getOrPut(it) { HashSet() }.add(mid) }

            for ((field, amount) in facts.subtractions) {
                if (facts.writeBack[field] == true) {
                    patterns += DexBehavioralPattern(
                        DexBehavioralPatternType.DAMAGE_WRITE_BACK, field, mid,
                        "Метод вычитает " + amount + " из поля и записывает результат обратно.",
                    )
                }
            }
            for ((field, amount) in facts.increments) {
                if (facts.writeBack[field] == true) {
                    patterns += DexBehavioralPattern(
                        DexBehavioralPatternType.CLAMPED_INCREMENT, field, mid,
                        "Метод прибавляет " + amount + " к полю и записывает результат обратно.",
                    )
                }
            }
            for ((field, action) in facts.zeroChecks) {
                if (method.returnType == "Z" && facts.writtenFields.isEmpty()) {
                    patterns += DexBehavioralPattern(
                        DexBehavioralPatternType.ZERO_THRESHOLD_CHECK, field, mid,
                        "Метод без побочных эффектов возвращает сравнение поля с нулём.",
                        action,
                    )
                }
            }
            for (field in facts.zeroChecks.keys) {
                if (facts.subtractions.containsKey(field) && facts.writeBack[field] == true) {
                    patterns += DexBehavioralPattern(
                        DexBehavioralPatternType.GUARDED_CONSUMPTION, field, mid,
                        "Метод расходует поле только при ненулевом значении.",
                    )
                }
            }
        }
        return DexBehavioralModel(
            patterns = patterns,
            provenGetters = provenGetters,
            calls = calls,
            fieldReaders = fieldReaders,
            fieldWriters = fieldWriters,
            methodsAnalyzed = analyzed,
            methodsSkipped = skipped,
        )
    }

    private class MethodFacts {
        val fields = ArrayList<String>()
        val readFields = HashSet<String>()
        val writtenFields = HashSet<String>()
        val subtractions = HashMap<String, Int>()
        val increments = HashMap<String, Int>()
        val writeBack = HashMap<String, Boolean>()
        val zeroChecks = HashMap<String, DexLocalAction>()
        val calleeIds = HashSet<String>()

        fun fieldIndex(identity: String): Int {
            val existing = fields.indexOf(identity)
            if (existing >= 0) return existing
            fields += identity
            return fields.size - 1
        }
    }

    private fun scanMethod(
        method: Method,
        code: List<Instruction>,
        provenGetters: Map<String, String>,
    ): MethodFacts? {
        val facts = MethodFacts()
        val registerCount = method.implementation!!.registerCount
        if (registerCount < 1) return null
        val derivedFrom = IntArray(registerCount) { -1 }
        val constInt = HashMap<Int, Int>()
        val isStatic = AccessFlags.STATIC.isSet(method.accessFlags)
        // This bounded pass currently reasons only about methods without explicit parameters.
        // For other signatures, do not guess the position of the receiver register.
        if (method.parameters.isNotEmpty()) return null
        val thisReg = if (isStatic) -1 else registerCount - 1
        var pendingResultField: Int? = null

        for (instruction in code) {
            // A pending invoke result is valid only for an immediately following move-result.
            if (instruction.opcode != Opcode.MOVE_RESULT &&
                instruction.opcode !in INVOKE_OPS) {
                pendingResultField = null
            }
            val op = instruction.opcode
            when {
                op == Opcode.IGET -> {
                    val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                        ?: return null
                    val two = instruction as? TwoRegisterInstruction ?: return null
                    if (field.type !in SCALAR_TYPES || field.definingClass != method.definingClass ||
                        two.registerB != thisReg
                    ) {
                        derivedFrom[two.registerA] = -1
                        continue
                    }
                    val identity = fieldIdentity(field)
                    derivedFrom[two.registerA] = facts.fieldIndex(identity)
                    facts.readFields += identity
                }
                op == Opcode.IPUT -> {
                    val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                        ?: return null
                    val two = instruction as? TwoRegisterInstruction ?: return null
                    if (field.type !in SCALAR_TYPES || field.definingClass != method.definingClass) {
                        continue
                    }
                    val identity = fieldIdentity(field)
                    facts.writtenFields += identity
                    // IPUT vA, vB, field: vA holds the value, vB the object.
                    val valueReg = two.registerA
                    val valueField = derivedFrom[valueReg]
                    if (valueField >= 0 && facts.fields[valueField] == identity) {
                        facts.writeBack[identity] = true
                    }
                    derivedFrom[valueReg] = -1
                }
                op == Opcode.CONST_4 || op == Opcode.CONST_16 || op == Opcode.CONST -> {
                    val one = instruction as? OneRegisterInstruction ?: return null
                    val literal = (instruction as? NarrowLiteralInstruction)?.narrowLiteral ?: return null
                    constInt[one.registerA] = literal
                    derivedFrom[one.registerA] = -1
                }
                op in INVOKE_OPS -> {
                    val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                        ?: return null
                    facts.calleeIds += methodIdOf(ref)
                    // Invoke instructions never specify the result register. Read their
                    // ordered argument registers (35c or 3rc); the result is assigned
                    // only by the immediately following move-result instruction.
                    val arguments = when (instruction) {
                        is FiveRegisterInstruction ->
                            listOf(instruction.registerC, instruction.registerD,
                                instruction.registerE, instruction.registerF,
                                instruction.registerG).take(instruction.registerCount)
                        is RegisterRangeInstruction ->
                            (instruction.startRegister until
                                instruction.startRegister + instruction.registerCount).toList()
                        else -> return null
                    }
                    var expectedWords = if (op in STATIC_INVOKE_OPS) 0 else 1
                    for (param in ref.parameterTypes) {
                        expectedWords += if (param.toString() in setOf("J", "D")) 2 else 1
                    }
                    if (arguments.size != expectedWords ||
                        arguments.any { it !in 0 until registerCount }) return null

                    val getterField = provenGetters[methodIdOf(ref)]
                    pendingResultField = when {
                        getterField != null && ref.definingClass == method.definingClass &&
                            ((op in STATIC_INVOKE_OPS && arguments.isEmpty()) ||
                                (op !in STATIC_INVOKE_OPS && arguments.size == 1 &&
                                    arguments[0] == thisReg)) ->
                            facts.fieldIndex(getterField)
                        isMathClamp(ref) && ref.parameterTypes.map { it.toString() } ==
                            listOf("I", "I") && ref.returnType == "I" &&
                            arguments.size == 2 ->
                            arguments.map { derivedFrom[it] }.firstOrNull { it >= 0 }
                        else -> {
                            // Unknown calls could invalidate an earlier field value.
                            derivedFrom.fill(-1)
                            null
                        }
                    }
                }
                op == Opcode.MOVE_RESULT -> {
                    val one = instruction as? OneRegisterInstruction ?: return null
                    derivedFrom[one.registerA] = pendingResultField ?: -1
                    constInt.remove(one.registerA)
                    pendingResultField = null
                }
                op in SUB_OPS -> {
                    val dest = destRegister(instruction, registerCount) ?: return null
                    val (sources, literal) = arithmeticSources(instruction)
                    val (fieldIdx, constValue) = fieldAndConstant(sources, literal, derivedFrom, constInt)
                    if (fieldIdx >= 0 && constValue != null && constValue > 0) {
                        facts.subtractions[facts.fields[fieldIdx]] = constValue
                    }
                    derivedFrom[dest] = fieldIdx
                    constInt.remove(dest)
                }
                op in ADD_OPS -> {
                    val dest = destRegister(instruction, registerCount) ?: return null
                    val (sources, literal) = arithmeticSources(instruction)
                    val (fieldIdx, constValue) = fieldAndConstant(sources, literal, derivedFrom, constInt)
                    if (fieldIdx >= 0 && constValue != null) {
                        when {
                            constValue > 0 -> facts.increments[facts.fields[fieldIdx]] = constValue
                            // D8 normally lowers "field - 7" to add-int/lit8 -7.
                            // This is a subtraction only for the literal ADD opcodes:
                            // never infer one from a register whose sign is unknown.
                            constValue < 0 &&
                                op in setOf(Opcode.ADD_INT_LIT8, Opcode.ADD_INT_LIT16) ->
                                facts.subtractions[facts.fields[fieldIdx]] = -constValue
                        }
                    }
                    derivedFrom[dest] = fieldIdx
                    constInt.remove(dest)
                }
                op in IFZ_OPS -> {
                    val one = instruction as? OneRegisterInstruction ?: return null
                    val f = derivedFrom[one.registerA]
                    if (f >= 0) facts.zeroChecks[facts.fields[f]] = thresholdAction(op)
                    derivedFrom.fill(-1)
                }
                op in IF_OPS -> {
                    val two = instruction as? TwoRegisterInstruction ?: return null
                    val fa = derivedFrom[two.registerA]
                    val fb = derivedFrom[two.registerB]
                    when {
                        fa >= 0 && constInt[two.registerB] == 0 ->
                            facts.zeroChecks[facts.fields[fa]] = thresholdAction(op)
                        fb >= 0 && constInt[two.registerA] == 0 ->
                            facts.zeroChecks[facts.fields[fb]] = thresholdAction(op)
                    }
                    derivedFrom.fill(-1)
                }
                op == Opcode.MOVE -> {
                    val two = instruction as? TwoRegisterInstruction ?: return null
                    derivedFrom[two.registerA] = derivedFrom[two.registerB]
                    val value = constInt[two.registerB]
                    if (value == null) constInt.remove(two.registerA)
                    else constInt[two.registerA] = value
                }
                op == Opcode.GOTO || op == Opcode.GOTO_16 || op == Opcode.GOTO_32 ->
                    derivedFrom.fill(-1)
                op == Opcode.RETURN -> Unit
                else -> {
                    when (instruction) {
                        is ThreeRegisterInstruction -> derivedFrom[instruction.registerA] = -1
                        is TwoRegisterInstruction -> derivedFrom[instruction.registerA] = -1
                        is OneRegisterInstruction -> derivedFrom[instruction.registerA] = -1
                        else -> derivedFrom.fill(-1)
                    }
                }
            }
        }
        return facts
    }

    /**
     * The javac layout for `return field <cmp> 0` is: compare, then the
     * fall-through stores 1 (true) and the jump target stores 0 (false).
     * So the method is TRUE exactly when the jump condition does NOT hold.
     *  - jump when field > 0 / >= 0 / != 0  -> true when depleted  -> FALSE
     *  - jump when field <= 0 / < 0 / == 0  -> true when available -> TRUE
     */
    private fun thresholdAction(op: Opcode): DexLocalAction = when (op) {
        Opcode.IF_GT, Opcode.IF_GTZ,
        Opcode.IF_GE, Opcode.IF_GEZ,
        Opcode.IF_NE, Opcode.IF_NEZ -> DexLocalAction.FALSE
        Opcode.IF_LE, Opcode.IF_LEZ,
        Opcode.IF_LT, Opcode.IF_LTZ,
        Opcode.IF_EQ, Opcode.IF_EQZ -> DexLocalAction.TRUE
        else -> DexLocalAction.FALSE
    }

    private fun fieldAndConstant(
        sources: List<Int>,
        literal: Int?,
        derivedFrom: IntArray,
        constInt: Map<Int, Int>,
    ): Pair<Int, Int?> {
        var fieldIdx = -1
        var fieldReg = -1
        for (s in sources) {
            val f = derivedFrom[s]
            if (f >= 0 && fieldIdx < 0) {
                fieldIdx = f
                fieldReg = s
            }
        }
        var constValue = literal
        if (constValue == null) {
            for (s in sources) {
                if (s == fieldReg) continue
                constInt[s]?.let { constValue = it }
            }
        }
        return Pair(fieldIdx, constValue)
    }

    private fun arithmeticSources(instruction: Instruction): Pair<List<Int>, Int?> {
        (instruction as? ThreeRegisterInstruction)?.let {
            return Pair(listOf(it.registerB, it.registerC), null)
        }
        (instruction as? TwoRegisterInstruction)?.let {
            val literal = (instruction as? NarrowLiteralInstruction)?.narrowLiteral
            val sources = if (instruction.opcode.name.endsWith("_2ADDR")) {
                listOf(it.registerA, it.registerB)
            } else {
                listOf(it.registerB)
            }
            return Pair(sources, literal)
        }
        return Pair(emptyList(), null)
    }

    private fun destRegister(instruction: Instruction, registerCount: Int): Int? {
        (instruction as? ThreeRegisterInstruction)?.let {
            if (it.registerA in 0 until registerCount) return it.registerA
        }
        (instruction as? TwoRegisterInstruction)?.let {
            if (it.registerA in 0 until registerCount) return it.registerA
        }
        (instruction as? OneRegisterInstruction)?.let {
            if (it.registerA in 0 until registerCount) return it.registerA
        }
        return null
    }

    private fun isMathClamp(ref: MethodReference): Boolean =
        ref.definingClass == "Ljava/lang/Math;" && ref.name in setOf("max", "min")

    private fun fieldIdentity(field: FieldReference): String =
        field.definingClass + "->" + field.name + ":" + field.type

    private fun methodId(method: Method): String = methodIdPublic(method)

    fun methodIdPublic(method: Method): String =
        method.definingClass + "->" + method.name +
            "(" + method.parameterTypes.joinToString("") + ")" + method.returnType

    private fun methodIdOf(ref: MethodReference): String =
        ref.definingClass + "->" + ref.name +
            "(" + ref.parameterTypes.joinToString("") + ")" + ref.returnType

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
    }

    private val INVOKE_OPS = setOf(
        Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_STATIC, Opcode.INVOKE_SUPER,
        Opcode.INVOKE_DIRECT, Opcode.INVOKE_INTERFACE,
        Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_STATIC_RANGE,
        Opcode.INVOKE_SUPER_RANGE, Opcode.INVOKE_DIRECT_RANGE, Opcode.INVOKE_INTERFACE_RANGE,
    )
    private val STATIC_INVOKE_OPS = setOf(
        Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE,
    )
    private val SUB_OPS = setOf(
        Opcode.SUB_INT, Opcode.SUB_INT_2ADDR,
    )
    private val ADD_OPS = setOf(
        Opcode.ADD_INT, Opcode.ADD_INT_2ADDR,
        Opcode.ADD_INT_LIT8, Opcode.ADD_INT_LIT16,
    )
    private val IFZ_OPS = setOf(
        Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ,
        Opcode.IF_GTZ, Opcode.IF_LEZ, Opcode.IF_GEZ,
    )
    private val IF_OPS = setOf(
        Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT,
        Opcode.IF_GT, Opcode.IF_LE, Opcode.IF_GE,
    )
}
