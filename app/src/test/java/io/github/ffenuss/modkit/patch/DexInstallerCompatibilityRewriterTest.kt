package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.formats.Instruction35c
import org.jf.dexlib2.iface.instruction.formats.Instruction3rc
import org.jf.dexlib2.immutable.*
import org.jf.dexlib2.immutable.instruction.*
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.*
import org.junit.Test

class DexInstallerCompatibilityRewriterTest {
    private val pm = "Landroid/content/pm/PackageManager;"
    private val source = "Landroid/content/pm/InstallSourceInfo;"
    private val string = "Ljava/lang/String;"
    private val signal = AtomicCancellationSignal()
    private fun dex(reference: ImmutableMethodReference, range: Boolean = false,
                    owner: String = "Ldev/fixture/Source;", count: Int = reference.parameterTypes.size + 1): ByteArray {
        val start = if (range) 300 else 3
        val invoke: Instruction = if (range) ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, start, count, reference)
        else ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, count, start, start + 1, 0, 0, 0, reference)
        val code: List<Instruction> = listOf(ImmutableInstruction20t(Opcode.GOTO_16, 2), invoke,
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            ImmutableInstruction11x(Opcode.MOVE_EXCEPTION, 0), ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))
        val impl = ImmutableMethodImplementation(start + reference.parameterTypes.size + 1, code,
            listOf(ImmutableTryBlock(2, 3, listOf(ImmutableExceptionHandler("Ljava/lang/IllegalArgumentException;", 7)))),
            listOf(org.jf.dexlib2.immutable.debug.ImmutableLineNumber(2, 123)))
        val parameters = (listOf(reference.definingClass) + reference.parameterTypes.map { it.toString() })
            .map { ImmutableMethodParameter(it, emptySet(), null) }
        val method = ImmutableMethod(owner, "query", parameters, reference.returnType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, emptySet(), emptySet(), impl)
        val untouched = ImmutableMethod(owner, "unrelated", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            emptySet(), emptySet(), ImmutableMethodImplementation(0, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), emptyList(), emptyList()))
        val clazz = ImmutableClassDef(owner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", emptyList(), "Source.java",
            emptySet(), emptyList(), emptyList(), listOf(method, untouched), emptyList())
        val out = MemoryDataStore()
        try { DexPool.writeTo(out, ImmutableDexFile(Opcodes.forApi(30), listOf(clazz))); return out.data }
        finally { out.close() }
    }

    @Test fun preservesLegacyCallRegistersBranchesTryBlocksAndUnrelatedMethod() {
        val input = dex(ImmutableMethodReference(pm, "getInstallerPackageName", listOf(string), string))
        val result = DexInstallerCompatibilityRewriter.rewrite(input, signal)
        assertEquals(1, result.redirectedCalls)
        val method = DexBackedDexFile(null, result.bytes).classes.single().methods.single { it.name == "query" }
        val impl = requireNotNull(method.implementation)
        assertEquals(5, impl.registerCount)
        val code = impl.instructions.toList()
        assertEquals(Opcode.GOTO_16, code[0].opcode)
        assertEquals(2, (code[0] as org.jf.dexlib2.iface.instruction.OffsetInstruction).codeOffset)
        val call = code[1] as Instruction35c
        assertEquals(Opcode.INVOKE_STATIC, call.opcode)
        assertEquals(2, call.registerCount); assertEquals(3, call.registerC); assertEquals(4, call.registerD)
        val reference = call.reference as org.jf.dexlib2.iface.reference.MethodReference
        assertEquals(listOf(pm, string), reference.parameterTypes.map { it.toString() })
        assertEquals(DexInstallerCompatibilityRewriter.BRIDGE, reference.definingClass)
        assertEquals(2, impl.tryBlocks.single().startCodeAddress)
        assertEquals(3, impl.tryBlocks.single().codeUnitCount)
        assertEquals(7, impl.tryBlocks.single().exceptionHandlers.single().handlerCodeAddress)
        assertEquals(123, (impl.debugItems.single() as org.jf.dexlib2.iface.debug.LineNumber).lineNumber)
        assertEquals(Opcode.RETURN_VOID, DexBackedDexFile(null, result.bytes).classes.single().methods
            .single { it.name == "unrelated" }.implementation!!.instructions.single().opcode)
    }

    @Test fun supportsRangeRegistersAbove255AndBothModernApiSteps() {
        for (reference in listOf(ImmutableMethodReference(pm, "getInstallSourceInfo", listOf(string), source),
            ImmutableMethodReference(source, "getInstallingPackageName", emptyList(), string))) {
            val result = DexInstallerCompatibilityRewriter.rewrite(dex(reference, range = true), signal)
            assertEquals(1, result.redirectedCalls)
            val call = DexBackedDexFile(null, result.bytes).classes.single().methods.single { it.name == "query" }
                .implementation!!.instructions.toList()[1] as Instruction3rc
            assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
            assertEquals(300, call.startRegister)
            assertEquals(reference.parameterTypes.size + 1, call.registerCount)
            assertEquals(reference.returnType, (call.reference as org.jf.dexlib2.iface.reference.MethodReference).returnType)
        }
    }

    @Test fun keepsUnrecognizedApiAndBridgeOwnPlatformDispatchUnchanged() {
        val samples = listOf(dex(ImmutableMethodReference("Ldev/CustomManager;", "getInstallerPackageName", listOf(string), string)),
            dex(ImmutableMethodReference(source, "getInitiatingPackageName", emptyList(), string)),
            dex(ImmutableMethodReference(pm, "getInstallerPackageName", listOf(string), string), owner = DexInstallerCompatibilityRewriter.BRIDGE))
        for (bytes in samples) {
            val result = DexInstallerCompatibilityRewriter.rewrite(bytes, signal)
            assertEquals(0, result.redirectedCalls); assertSame(bytes, result.bytes)
        }
    }

    @Test fun rejectsMalformedArgumentCountAndCancellation() {
        val ref = ImmutableMethodReference(pm, "getInstallerPackageName", listOf(string), string)
        assertThrows(IllegalArgumentException::class.java) { DexInstallerCompatibilityRewriter.rewrite(dex(ref, count = 1), signal) }
        signal.cancel()
        assertThrows(AnalysisCancelledException::class.java) { DexInstallerCompatibilityRewriter.rewrite(dex(ref), signal) }
    }
}
