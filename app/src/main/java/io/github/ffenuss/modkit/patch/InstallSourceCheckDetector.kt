package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference

enum class InstallSourceCheckConfidence {
    QUERY_ONLY,
    LOCAL_BRANCH_CONFIRMED,
}

data class InstallSourceCheckEvidence(
    val className: String,
    val methodName: String,
    val signature: String,
    val api: String,
    val confidence: InstallSourceCheckConfidence,
    val expectedInstallerLiteral: String? = null,
    val detail: String,
)

data class InstallSourceCheckScan(
    val evidence: List<InstallSourceCheckEvidence>,
) {
    val confirmedCount: Int get() = evidence.count {
        it.confidence == InstallSourceCheckConfidence.LOCAL_BRANCH_CONFIRMED
    }
    val queryOnlyCount: Int get() = evidence.size - confirmedCount
}

/**
 * Conservative DEX preflight for local installer-source checks.
 *
 * This does not claim that a library reference is enforcement. A query becomes
 * LOCAL_BRANCH_CONFIRMED only when its result is consumed by a local equality/null
 * decision that reaches an if-* branch in the same method. Reflection, JNI,
 * dynamically loaded code and backend attestation remain outside this detector.
 */
object InstallSourceCheckDetector {
    private const val PACKAGE_MANAGER = "Landroid/content/pm/PackageManager;"
    private const val INSTALL_SOURCE = "Landroid/content/pm/InstallSourceInfo;"
    private const val STRING = "Ljava/lang/String;"
    private const val TEXT_UTILS = "Landroid/text/TextUtils;"
    private const val OBJECTS = "Ljava/util/Objects;"
    private const val RUNTIME_PROBE = "Lio/github/ffenuss/modkit/runtimeprobe/"
    private const val MAX_FORWARD = 16

    fun scan(bytes: ByteArray, cancellation: CancellationSignal): InstallSourceCheckScan {
        fun check() {
            if (cancellation.isCancelled()) throw AnalysisCancelledException()
        }
        check()
        val dex = DexBackedDexFile(null, bytes)
        val evidence = ArrayList<InstallSourceCheckEvidence>()
        for (clazz in dex.classes) {
            check()
            if (clazz.type.startsWith(RUNTIME_PROBE)) continue
            for (method in clazz.methods) {
                check()
                evidence += scanMethod(method, cancellation)
            }
        }
        return InstallSourceCheckScan(evidence)
    }

    private fun scanMethod(method: Method, cancellation: CancellationSignal): List<InstallSourceCheckEvidence> {
        val implementation = method.implementation ?: return emptyList()
        val code = implementation.instructions.toList()
        if (code.isEmpty()) return emptyList()
        val found = ArrayList<InstallSourceCheckEvidence>()
        for (index in code.indices) {
            if (cancellation.isCancelled()) throw AnalysisCancelledException()
            val query = queryApi(code[index]) ?: continue
            val resultIndex = index + 1
            val move = code.getOrNull(resultIndex)
            if (move?.opcode != Opcode.MOVE_RESULT_OBJECT || move !is OneRegisterInstruction) {
                found += evidence(method, query, InstallSourceCheckConfidence.QUERY_ONLY, null,
                    "Точный API источника установки найден, но локальный поток результата не доказан.")
                continue
            }
            val resultRegister = move.registerA
            val decision = findDecision(code, resultIndex + 1, resultRegister)
            found += if (decision != null) {
                evidence(method, query, InstallSourceCheckConfidence.LOCAL_BRANCH_CONFIRMED,
                    decision.literal, decision.detail)
            } else {
                evidence(method, query, InstallSourceCheckConfidence.QUERY_ONLY, null,
                    "Источник установки читается, но сравнение/ветвление в этом методе не подтверждено.")
            }
        }
        return found
    }

    private data class Decision(val literal: String?, val detail: String)

    private fun findDecision(code: List<Instruction>, start: Int, installerRegister: Int): Decision? {
        val end = minOf(code.size, start + MAX_FORWARD)
        val literals = HashMap<Int, String>()
        var booleanRegister: Int? = null
        var pendingComparisonLiteral: String? = null

        for (i in start until end) {
            val instruction = code[i]
            val op = instruction.opcode

            if (op in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO) &&
                instruction is OneRegisterInstruction && instruction is ReferenceInstruction
            ) {
                val value = instruction.reference as? StringReference
                if (value != null) literals[instruction.registerA] = value.string
            }

            if (op in setOf(Opcode.IF_EQZ, Opcode.IF_NEZ) &&
                instruction is OneRegisterInstruction && instruction.registerA == installerRegister
            ) {
                return Decision(null,
                    "Результат запроса источника установки напрямую управляет локальным if-ветвлением.")
            }

            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            if (reference != null && isEquality(reference)) {
                val registers = invokeRegisters(instruction)
                if (installerRegister in registers) {
                    pendingComparisonLiteral = registers.asSequence()
                        .filter { it != installerRegister }
                        .mapNotNull(literals::get)
                        .firstOrNull()
                    val next = code.getOrNull(i + 1)
                    if (next?.opcode in setOf(Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_WIDE) &&
                        next is OneRegisterInstruction
                    ) {
                        booleanRegister = next.registerA
                    }
                }
            }

            val compared = booleanRegister
            if (compared != null && op in setOf(
                    Opcode.IF_EQZ, Opcode.IF_NEZ,
                    Opcode.IF_EQ, Opcode.IF_NE,
                )
            ) {
                val involved = when (instruction) {
                    is OneRegisterInstruction -> instruction.registerA == compared
                    is org.jf.dexlib2.iface.instruction.TwoRegisterInstruction ->
                        instruction.registerA == compared || instruction.registerB == compared
                    else -> false
                }
                if (involved) {
                    return Decision(pendingComparisonLiteral,
                        if (pendingComparisonLiteral != null)
                            "Результат источника установки сравнивается с «$pendingComparisonLiteral» и управляет локальной веткой."
                        else
                            "Результат источника установки участвует в сравнении, которое управляет локальной веткой.")
                }
            }
        }
        return null
    }

    private fun queryApi(instruction: Instruction): String? {
        val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return null
        if (instruction.opcode !in setOf(
                Opcode.INVOKE_VIRTUAL,
                Opcode.INVOKE_VIRTUAL_RANGE,
                Opcode.INVOKE_INTERFACE,
                Opcode.INVOKE_INTERFACE_RANGE,
            )
        ) return null
        val parameters = ref.parameterTypes.map { it.toString() }
        return when {
            ref.definingClass == PACKAGE_MANAGER &&
                ref.name == "getInstallerPackageName" &&
                parameters == listOf(STRING) &&
                ref.returnType == STRING ->
                "PackageManager.getInstallerPackageName"
            ref.definingClass == INSTALL_SOURCE &&
                ref.name == "getInstallingPackageName" &&
                parameters.isEmpty() &&
                ref.returnType == STRING ->
                "InstallSourceInfo.getInstallingPackageName"
            else -> null
        }
    }

    private fun isEquality(ref: MethodReference): Boolean {
        val params = ref.parameterTypes.map { it.toString() }
        return (ref.definingClass == STRING && ref.name == "equals" &&
            params == listOf("Ljava/lang/Object;") && ref.returnType == "Z") ||
            (ref.definingClass == TEXT_UTILS && ref.name == "equals" &&
                params == listOf("Ljava/lang/CharSequence;", "Ljava/lang/CharSequence;") &&
                ref.returnType == "Z") ||
            (ref.definingClass == OBJECTS && ref.name == "equals" &&
                params == listOf("Ljava/lang/Object;", "Ljava/lang/Object;") &&
                ref.returnType == "Z")
    }

    private fun invokeRegisters(instruction: Instruction): List<Int> = when (instruction) {
        is FiveRegisterInstruction -> listOf(
            instruction.registerC,
            instruction.registerD,
            instruction.registerE,
            instruction.registerF,
            instruction.registerG,
        ).take(instruction.registerCount)
        is RegisterRangeInstruction ->
            (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
        else -> emptyList()
    }

    private fun evidence(
        method: Method,
        api: String,
        confidence: InstallSourceCheckConfidence,
        literal: String?,
        detail: String,
    ) = InstallSourceCheckEvidence(
        className = method.definingClass,
        methodName = method.name,
        signature = "(" + method.parameterTypes.joinToString("") + ")" + method.returnType,
        api = api,
        confidence = confidence,
        expectedInstallerLiteral = literal,
        detail = detail,
    )
}
