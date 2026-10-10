package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.modification.ScalarValue
import io.github.ffenuss.modkit.modification.ValueOperation
import java.io.File
import java.security.MessageDigest
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.immutable.*
import org.jf.dexlib2.immutable.instruction.*
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool

/** A group of proven getter bodies shares one runtime switch. Original code is never discarded. */
data class DexRuntimeSelection(val method: DexLocalOpportunity, val switchId: String,
    val resultTransform: ValueOperation.Multiply? = null)

object DexRuntimeSwitchRewriter {
    const val BRIDGE = "Lio/github/ffenuss/modkit/runtimeprobe/RuntimeDexSwitches;"
    private val switchPattern = Regex("dex:[0-9a-f]{32}")

    fun switchId(recipeId: String): String = "dex:" + sha(recipeId.toByteArray()).take(32)
    fun backupName(methodId: String): String = "modkit\$original\$" + sha(methodId.toByteArray()).take(24)

    fun rewrite(
        bytes: ByteArray,
        apkIndex: Int,
        dexEntry: String,
        selections: List<DexRuntimeSelection>,
        destination: File,
        cancellation: CancellationSignal,
    ): DexLocalRewrite {
        val methods = selections.map { it.method }
        require(selections.all { switchPattern.matches(it.switchId) }) { "Invalid DEX switch identity." }
        DexLocalPatchEngine.validatedSelections(bytes, apkIndex, dexEntry, methods, false, cancellation)
        val wanted = selections.associateBy { it.method.id }
        val source = DexBackedDexFile(null, bytes)
        val originals = linkedMapOf<String, Method>()
        val wrappers = linkedMapOf<String, Method>()
        val backups = linkedMapOf<String, Method>()
        val classes = source.classes.map { clazz ->
            checkCancelled(cancellation)
            val added = mutableListOf<Method>()
            val names = clazz.methods.map { it.name }.toSet()
            fun transform(method: Method): Method {
                val id = DexLocalPatchEngine.stableId(apkIndex, dexEntry, method)
                val selection = wanted[id] ?: return method
                require(!AccessFlags.INTERFACE.isSet(clazz.accessFlags)) {
                    "Runtime DEX-переключатели методов интерфейса пока не поддерживаются: ${method.definingClass}"
                }
                val backup = backupName(id)
                require(backup !in names) { "DEX original-method backup already exists: $id" }
                val flags = AccessFlags.PRIVATE.value or AccessFlags.SYNTHETIC.value or
                    (method.accessFlags and (AccessFlags.STATIC.value or AccessFlags.STRICTFP.value))
                val saved = ImmutableMethod(method.definingClass, backup, method.parameters,
                    method.returnType, flags, emptySet(), method.hiddenApiRestrictions, method.implementation)
                val wrapper = wrapper(method, selection, backup)
                require(originals.put(id, method) == null) { "Duplicate DEX method: $id" }
                wrappers[id] = wrapper
                backups[id] = saved
                added += saved
                return wrapper
            }
            val direct = clazz.directMethods.map(::transform)
            val virtual = clazz.virtualMethods.map(::transform)
            if (added.isEmpty()) clazz else ImmutableClassDef(clazz.type, clazz.accessFlags,
                clazz.superclass, clazz.interfaces, clazz.sourceFile, clazz.annotations,
                clazz.staticFields, clazz.instanceFields, direct + added, virtual)
        }
        require(originals.keys == wanted.keys) { "Not all selected DEX methods were instrumented." }
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, destination.name + ".tmp")
        try {
            DexPool.writeTo(temp.absolutePath, ImmutableDexFile(source.opcodes, classes))
            checkCancelled(cancellation)
            val verified = DexBackedDexFile(null, temp.readBytes())
            val byClass = verified.classes.associateBy { it.type }
            for ((id, original) in originals) {
                checkCancelled(cancellation)
                val actual = requireNotNull(byClass[original.definingClass]).methods.toList()
                val live = actual.single { it.name == original.name && it.parameterTypes.map { t -> t.toString() } == original.parameterTypes.map { t -> t.toString() } && it.returnType == original.returnType }
                val backup = actual.single { it.name == backupName(id) && it.parameterTypes.map { t -> t.toString() } == original.parameterTypes.map { t -> t.toString() } && it.returnType == original.returnType }
                require(live.accessFlags == original.accessFlags && backup.accessFlags == backups.getValue(id).accessFlags)
                require(canonicalBody(live, source.opcodes).contentEquals(canonicalBody(wrappers.getValue(id), source.opcodes))) {
                    "Runtime DEX wrapper verification failed: $id"
                }
                // Canonical one-method DEX comparison covers registers, references,
                // branch targets and debug data without relying on pool indices.
                require(canonicalBody(backup, source.opcodes).contentEquals(canonicalBody(original, source.opcodes))) {
                    "Original DEX method was not preserved: $id"
                }
            }
            require(temp.renameTo(destination)) { "Could not finalize runtime DEX." }
            return DexLocalRewrite(destination, originals.keys, sha(bytes), sha(destination.readBytes()))
        } catch (failure: Throwable) {
            temp.delete()
            destination.delete()
            throw failure
        }
    }

    private fun wrapper(original: Method, selection: DexRuntimeSelection, backup: String): Method {
        require(DexMethodParameters.supported(original.parameterTypes) && original.returnType in DexScalarReplacement.supportedTypes)
        val static = AccessFlags.STATIC.isSet(original.accessFlags)
        val wide = DexScalarReplacement.isWide(original.returnType)
        val constant = DexScalarReplacement.instruction(original.returnType, selection.method.action)
        val returnOp = DexScalarReplacement.returnOpcode(original.returnType)
        val transform = selection.resultTransform
        if (transform != null) require(when (original.returnType) {
            "F" -> transform.coefficient is ScalarValue.Single
            "D" -> transform.coefficient is ScalarValue.DoublePrecision
            else -> false
        }) { "DEX result multiplication requires the exact Float/Double width" }
        val locals = if (transform != null) { if (wide) 4 else 2 } else if (wide) 2 else 1
        val inputs = DexMethodParameters.inputWords(original)
        val reference = ImmutableMethodReference(original.definingClass, backup, original.parameterTypes, original.returnType)
        val invocation = if (inputs <= 5) {
            val registers = (0 until inputs).map { locals + it } + List(5 - inputs) { 0 }
            ImmutableInstruction35c(if (static) Opcode.INVOKE_STATIC else Opcode.INVOKE_DIRECT,
                inputs, registers[0], registers[1], registers[2], registers[3], registers[4], reference)
        } else ImmutableInstruction3rc(if (static) Opcode.INVOKE_STATIC_RANGE else Opcode.INVOKE_DIRECT_RANGE,
            locals, inputs, reference)
        val resultMove = ImmutableInstruction11x(if (wide) Opcode.MOVE_RESULT_WIDE else Opcode.MOVE_RESULT, 0)
        val resultReturn = ImmutableInstruction11x(returnOp, 0)
        val enabledCode = if (transform == null) listOf(constant, resultReturn) else {
            val coefficient = when (val value = transform.coefficient) {
                is ScalarValue.Single -> ImmutableInstruction31i(Opcode.CONST, 1, value.value.toRawBits())
                is ScalarValue.DoublePrecision -> ImmutableInstruction51l(Opcode.CONST_WIDE, 2, value.value.toRawBits())
                else -> error("Unsupported DEX multiplier width")
            }
            // Call the preserved original once on either path. Locals do not alias incoming arguments.
            listOf(invocation, resultMove, coefficient,
                ImmutableInstruction23x(if (wide) Opcode.MUL_DOUBLE else Opcode.MUL_FLOAT, 0, 0, if (wide) 2 else 1),
                resultReturn)
        }
        val code = listOf(
            ImmutableInstruction31c(Opcode.CONST_STRING_JUMBO, 0, ImmutableStringReference(selection.switchId)),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                ImmutableMethodReference(BRIDGE, "isEnabled", listOf("Ljava/lang/String;"), "Z")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, 2 + enabledCode.sumOf { it.codeUnits }),
        ) + enabledCode + listOf(invocation, resultMove, resultReturn)
        return ImmutableMethod(original.definingClass, original.name, original.parameters,
            original.returnType, original.accessFlags, original.annotations, original.hiddenApiRestrictions,
            ImmutableMethodImplementation(locals + inputs, code, emptyList(), emptyList()))
    }

    private fun canonicalBody(method: Method, opcodes: Opcodes): ByteArray {
        val flags = AccessFlags.PUBLIC.value or (method.accessFlags and AccessFlags.STATIC.value)
        val normalized = ImmutableMethod("Lmodkit/Body;", "body", method.parameters, method.returnType,
            flags, emptySet(), emptySet(), method.implementation)
        val clazz = ImmutableClassDef("Lmodkit/Body;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
            emptyList(), null, emptySet(), emptyList(), emptyList(),
            if (AccessFlags.STATIC.isSet(flags)) listOf(normalized) else emptyList(),
            if (AccessFlags.STATIC.isSet(flags)) emptyList() else listOf(normalized))
        val store = MemoryDataStore()
        try {
            DexPool.writeTo(store, ImmutableDexFile(opcodes, listOf(clazz)))
            return store.data
        } finally { store.close() }
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun checkCancelled(signal: CancellationSignal) {
        if (signal.isCancelled()) throw AnalysisCancelledException()
    }
}
