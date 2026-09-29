package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.formats.Instruction35c
import org.jf.dexlib2.iface.instruction.formats.Instruction3rc
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction3rc
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool

data class DexInstallerCompatibilityRewrite(
    val bytes: ByteArray,
    val redirectedCalls: Int,
    val confirmedChecks: Int,
    val queryOnlyChecks: Int,
)

/**
 * Adapts only DEX calls that belong to a detector-confirmed local installer
 * decision. Mere API presence/query-only evidence remains untouched.
 *
 * Modern InstallSourceInfo requires its producer call to be bridged too so the
 * returned object can be associated with the verified original package.
 */
object DexInstallerCompatibilityRewriter {
    const val BRIDGE = "Lio/github/ffenuss/modkit/runtimeprobe/RuntimeInstallerCompatibility;"
    private const val PM = "Landroid/content/pm/PackageManager;"
    private const val SOURCE = "Landroid/content/pm/InstallSourceInfo;"
    private const val STRING = "Ljava/lang/String;"

    fun rewrite(bytes: ByteArray, signal: CancellationSignal): DexInstallerCompatibilityRewrite {
        fun check() {
            if (signal.isCancelled()) throw AnalysisCancelledException()
        }
        check()
        val scan = InstallSourceCheckDetector.scan(bytes, signal)
        val confirmed = scan.evidence.filter {
            it.confidence == InstallSourceCheckConfidence.LOCAL_BRANCH_CONFIRMED
        }
        if (confirmed.isEmpty()) {
            return DexInstallerCompatibilityRewrite(bytes, 0, 0, scan.queryOnlyCount)
        }

        val exactByMethod = confirmed.groupBy { it.methodIdentity }
            .mapValues { (_, items) -> items.map { it.instructionIndex }.toSet() }
        val modernMethods = confirmed.filter {
            it.api == "InstallSourceInfo.getInstallingPackageName"
        }.map { it.methodIdentity }.toSet()

        val source = DexBackedDexFile(null, bytes)
        var calls = 0
        val changed = linkedMapOf<String, Method>()

        fun key(method: Method): String =
            method.definingClass + "->" + method.name + "(" +
                method.parameterTypes.joinToString("") + ")" + method.returnType

        val classes = source.classes.map { clazz ->
            check()
            if (clazz.type.startsWith("Lio/github/ffenuss/modkit/runtimeprobe/")) return@map clazz
            val before = calls

            fun transform(method: Method): Method {
                val impl = method.implementation ?: return method
                val methodKey = key(method)
                val exactIndices = exactByMethod[methodKey].orEmpty()
                val modern = methodKey in modernMethods
                if (exactIndices.isEmpty() && !modern) return method

                var methodCalls = 0
                val instructions: List<Instruction> = impl.instructions.mapIndexed { index, instruction ->
                    check()
                    val reference =
                        (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    val target = reference?.let(::bridge) ?: return@mapIndexed instruction
                    val shouldRewrite =
                        index in exactIndices ||
                            (modern && isInstallSourceProducer(reference))
                    if (!shouldRewrite ||
                        instruction.opcode !in setOf(
                            Opcode.INVOKE_VIRTUAL,
                            Opcode.INVOKE_VIRTUAL_RANGE,
                        )
                    ) {
                        return@mapIndexed instruction
                    }

                    val rewritten: Instruction = when (instruction) {
                        is Instruction35c -> {
                            require(instruction.registerCount == target.parameterTypes.size) {
                                "Malformed installer invocation."
                            }
                            ImmutableInstruction35c(
                                Opcode.INVOKE_STATIC,
                                instruction.registerCount,
                                instruction.registerC,
                                instruction.registerD,
                                instruction.registerE,
                                instruction.registerF,
                                instruction.registerG,
                                target,
                            )
                        }
                        is Instruction3rc -> {
                            require(instruction.registerCount == target.parameterTypes.size) {
                                "Malformed installer range invocation."
                            }
                            ImmutableInstruction3rc(
                                Opcode.INVOKE_STATIC_RANGE,
                                instruction.startRegister,
                                instruction.registerCount,
                                target,
                            )
                        }
                        else -> error("Unsupported installer invocation format.")
                    }
                    require(rewritten.codeUnits == instruction.codeUnits)
                    methodCalls++
                    rewritten
                }
                if (methodCalls == 0) return method
                calls += methodCalls
                return ImmutableMethod(
                    method.definingClass,
                    method.name,
                    method.parameters,
                    method.returnType,
                    method.accessFlags,
                    method.annotations,
                    method.hiddenApiRestrictions,
                    ImmutableMethodImplementation(
                        impl.registerCount,
                        instructions,
                        impl.tryBlocks,
                        impl.debugItems,
                    ),
                ).also {
                    require(changed.put(methodKey, it) == null) {
                        "Duplicate DEX method identity."
                    }
                }
            }

            val direct = clazz.directMethods.map(::transform)
            val virtual = clazz.virtualMethods.map(::transform)
            if (calls == before) clazz else ImmutableClassDef(
                clazz.type,
                clazz.accessFlags,
                clazz.superclass,
                clazz.interfaces,
                clazz.sourceFile,
                clazz.annotations,
                clazz.staticFields,
                clazz.instanceFields,
                direct,
                virtual,
            )
        }

        require(calls > 0) {
            "Confirmed installer checks were found but no exact invocation was adapted."
        }

        val output = MemoryDataStore()
        val rewritten = try {
            DexPool.writeTo(output, ImmutableDexFile(source.opcodes, classes))
            output.data
        } finally {
            output.close()
        }
        check()

        val verified = DexBackedDexFile(null, rewritten)
        val verifiedMethods = verified.classes.flatMap { it.methods }.associateBy(::key)
        val originalMethods = source.classes.flatMap { it.methods }.associateBy(::key)
        require(originalMethods.keys == verifiedMethods.keys) {
            "Installer adaptation changed method identities."
        }
        for ((id, expected) in changed) {
            check()
            val actual = verifiedMethods.getValue(id)
            require(canonical(expected, source.opcodes).contentEquals(
                canonical(actual, source.opcodes),
            )) {
                "Installer adaptation changed an unexpected method body: $id"
            }
        }

        return DexInstallerCompatibilityRewrite(
            bytes = rewritten,
            redirectedCalls = calls,
            confirmedChecks = scan.confirmedCount,
            queryOnlyChecks = scan.queryOnlyCount,
        )
    }

    private fun bridge(reference: MethodReference): ImmutableMethodReference? {
        val parameters = reference.parameterTypes.map { it.toString() }
        val supported = when (reference.definingClass) {
            PM -> parameters == listOf(STRING) && (
                reference.name == "getInstallerPackageName" &&
                    reference.returnType == STRING ||
                    reference.name == "getInstallSourceInfo" &&
                    reference.returnType == SOURCE
                )
            SOURCE -> reference.name == "getInstallingPackageName" &&
                parameters.isEmpty() &&
                reference.returnType == STRING
            else -> false
        }
        return if (supported) {
            ImmutableMethodReference(
                BRIDGE,
                reference.name,
                listOf(reference.definingClass) + parameters,
                reference.returnType,
            )
        } else {
            null
        }
    }

    private fun isInstallSourceProducer(reference: MethodReference): Boolean =
        reference.definingClass == PM &&
            reference.name == "getInstallSourceInfo" &&
            reference.parameterTypes.map { it.toString() } == listOf(STRING) &&
            reference.returnType == SOURCE

    private fun canonical(
        method: Method,
        opcodes: org.jf.dexlib2.Opcodes,
    ): ByteArray {
        val clazz = ImmutableClassDef(
            method.definingClass,
            1,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptySet(),
            emptyList(),
            emptyList(),
            listOf(method),
            emptyList(),
        )
        val out = MemoryDataStore()
        return try {
            DexPool.writeTo(out, ImmutableDexFile(opcodes, listOf(clazz)))
            out.data
        } finally {
            out.close()
        }
    }
}
