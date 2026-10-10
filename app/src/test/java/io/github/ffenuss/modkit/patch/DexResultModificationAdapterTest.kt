package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.modification.*
import java.io.File
import java.nio.file.Files
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.*
import org.jf.dexlib2.immutable.*
import org.jf.dexlib2.immutable.instruction.*
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.*
import org.junit.Test

class DexResultModificationAdapterTest {
    private val signal = object : CancellationSignal { override fun isCancelled() = false }
    private fun dex(type: String = "I"): ByteArray {
        val owner = "Lgame/Player;"
        val method = ImmutableMethod(owner, if (type == "I") "getHealth" else "getRunSpeed", emptyList(), type, AccessFlags.PUBLIC.value,
            emptySet(), emptySet(), ImmutableMethodImplementation(if (type == "D") 3 else 2, listOf(
                when (type) {
                    "F" -> ImmutableInstruction31i(Opcode.CONST, 0, 1.25f.toRawBits())
                    "D" -> ImmutableInstruction51l(Opcode.CONST_WIDE, 0, 1.25.toRawBits())
                    else -> ImmutableInstruction31i(Opcode.CONST, 0, 20)
                }, ImmutableInstruction11x(if (type == "D") Opcode.RETURN_WIDE else Opcode.RETURN, 0)), emptyList(), emptyList()))
        val clazz = ImmutableClassDef(owner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", emptyList(), null,
            emptySet(), emptyList(), listOf(method))
        val pool = DexPool(Opcodes.getDefault()).apply { internClass(clazz) }
        val store = MemoryDataStore()
        return try { pool.writeTo(store); store.data.copyOf(store.size) }
        finally { store.close() }
    }
    @Test fun commonRequestActuallyCreatesAWrapperAndPreservesTheOriginalMethod() {
        val source = dex()
        val adapter = DexResultModificationAdapter(source, 0, "classes.dex", signal)
        val request = adapter.requests.single()
        assertEquals(ScalarKind.INT32, request.target.valueKind)
        val prepared = adapter.prepare(request) as AdapterPreparation.Prepared
        source.fill(0) // adapter owns its source snapshot
        val root = Files.createTempDirectory("common-dex-adapter").toFile()
        try {
            val rewritten = adapter.rewrite(listOf(prepared), File(root, "out.dex"), signal)
            val methods = DexBackedDexFile(null, rewritten.file.readBytes()).classes.single().methods.toList()
            assertTrue(methods.any { it.name == "getHealth" })
            assertTrue(methods.any { it.name == DexRuntimeSwitchRewriter.backupName(request.id) })
            assertEquals(setOf(request.id), rewritten.appliedIds)
        } finally { root.deleteRecursively() }
    }
    @Test fun sameMultiplierRuleCallsOriginalOnBothPathsAndUsesTheCorrectWidth() {
        for (type in listOf("F", "D")) {
            val adapter = DexResultModificationAdapter(dex(type), 0, "classes.dex", signal)
            val base = adapter.requests.single()
            val coefficient = if (type == "F") ScalarValue.Single(2f) else ScalarValue.DoublePrecision(2.0)
            val request = base.copy(operation = ValueOperation.Multiply(coefficient))
            val prepared = adapter.prepare(request) as AdapterPreparation.Prepared
            val root = Files.createTempDirectory("common-dex-multiply").toFile()
            try {
                val rewritten = adapter.rewrite(listOf(prepared), File(root, "out.dex"), signal)
                val methods = DexBackedDexFile(null, rewritten.file.readBytes()).classes.single().methods.toList()
                val wrapper = methods.single { it.name == "getRunSpeed" }
                val code = wrapper.implementation!!.instructions.toList()
                assertEquals(2, code.count { it.opcode == Opcode.INVOKE_DIRECT })
                assertEquals(1, code.count { it.opcode == if (type == "F") Opcode.MUL_FLOAT else Opcode.MUL_DOUBLE })
                val conditionalIndex = code.indexOfFirst { it.opcode == Opcode.IF_EQZ }
                val branchStart = code.take(conditionalIndex).sumOf { it.codeUnits }
                val targetOffset = branchStart + (code[conditionalIndex] as OffsetInstruction).codeOffset
                var offset = 0
                val landing = code.indexOfFirst { instruction -> (offset == targetOffset).also { offset += instruction.codeUnits } }
                assertEquals(code.indexOfLast { it.opcode == Opcode.INVOKE_DIRECT }, landing)
                assertEquals(if (type == "F") 3 else 5, wrapper.implementation!!.registerCount)
                val original = methods.single { it.name == DexRuntimeSwitchRewriter.backupName(request.id) }
                assertEquals(if (type == "F") 1.25f.toRawBits().toLong() else 1.25.toRawBits(),
                    (original.implementation!!.instructions.first() as WideLiteralInstruction).wideLiteral)
                val next = adapter.prepare(request.copy(operation = ValueOperation.Multiply(
                    if (type == "F") ScalarValue.Single(5f) else ScalarValue.DoublePrecision(5.0)))) as AdapterPreparation.Prepared
                assertNotEquals(prepared.payloadId, next.payloadId)
                assertEquals(request.operation, adapter.selection(prepared).resultTransform)
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun changedValuesAndSpaceHostCannotReuseTheRepackagedBackend() {
        val adapter = DexResultModificationAdapter(dex(), 0, "classes.dex", signal)
        val request = adapter.requests.single()
        assertTrue(adapter.prepare(request.copy(operation = ValueOperation.Replace(ScalarValue.Integral(ScalarKind.INT32, 123)))) is AdapterPreparation.Blocked)
        assertTrue(adapter.prepare(request.copy(point = InterceptionPoint.ARGUMENT, argumentIndex = 0)) is AdapterPreparation.Blocked)
        assertTrue(ModificationPlanner.blockers(request, ExecutionHost.SPACE, adapter.capabilities,
            BindingEvidence(true, true, true, true, true, true)).isNotEmpty())
        val prepared = adapter.prepare(request) as AdapterPreparation.Prepared
        try {
            adapter.selection(prepared.copy(request = request.copy(id = "other")))
            fail("Different method reused prepared payload")
        } catch (_: IllegalArgumentException) { }
    }
}
