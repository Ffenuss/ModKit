package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.modification.*
import java.io.File
import java.nio.file.Files
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.immutable.*
import org.jf.dexlib2.immutable.instruction.*
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.*
import org.junit.Test

class DexResultModificationAdapterTest {
    private val signal = object : CancellationSignal { override fun isCancelled() = false }
    private fun dex(): ByteArray {
        val owner = "Lgame/Player;"
        val method = ImmutableMethod(owner, "getHealth", emptyList(), "I", AccessFlags.PUBLIC.value,
            emptySet(), emptySet(), ImmutableMethodImplementation(2, listOf(
                ImmutableInstruction31i(Opcode.CONST, 0, 20), ImmutableInstruction11x(Opcode.RETURN, 0)), emptyList(), emptyList()))
        val clazz = ImmutableClassDef(owner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", emptyList(), null,
            emptySet(), emptyList(), listOf(method))
        val pool = DexPool(Opcodes.getDefault()).apply { internClass(clazz) }
        return MemoryDataStore().use { store -> pool.writeTo(store); store.data.copyOf(store.size) }
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
