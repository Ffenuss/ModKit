package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.*
import java.io.File
import java.nio.file.Files
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.*
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.immutable.*
import org.jf.dexlib2.immutable.instruction.*
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.*
import org.junit.Test

class DexRuntimeSwitchRewriterTest {
    private val owner = "Ldev/game/Player;"
    private val signal = AtomicCancellationSignal()
    private fun field(name: String) = ImmutableFieldReference(owner, name, "I")
    private fun method(name: String, type: String, flags: Int = AccessFlags.PUBLIC.value,
                       registers: Int = 2, vararg instructions: Instruction): Method = ImmutableMethod(
        owner, name, emptyList(), type, flags, emptySet(), emptySet(),
        ImmutableMethodImplementation(registers, instructions.toList(), emptyList(), emptyList()))

    private fun getter(name: String) = method(name, "I", instructions = arrayOf<Instruction>(
        ImmutableInstruction22c(Opcode.IGET, 0, 1, field("health")), ImmutableInstruction11x(Opcode.RETURN, 0)))

    private fun dex(methods: List<Method>, interfaceType: Boolean = false): ByteArray {
        val (direct, virtual) = methods.partition { AccessFlags.PRIVATE.isSet(it.accessFlags) || AccessFlags.STATIC.isSet(it.accessFlags) }
        val clazz = ImmutableClassDef(owner, AccessFlags.PUBLIC.value or if (interfaceType) AccessFlags.INTERFACE.value else 0,
            "Ljava/lang/Object;", emptyList(), null, emptySet(), emptyList(), emptyList(), direct, virtual)
        val store = MemoryDataStore()
        try { DexPool.writeTo(store, ImmutableDexFile(Opcodes.forApi(28), listOf(clazz))); return store.data }
        finally { store.close() }
    }
    private fun scan(bytes: ByteArray) = DexLocalPatchEngine.scanDex(bytes, 0, "classes.dex", false, signal).opportunities
    private fun rewrite(bytes: ByteArray, selections: List<DexRuntimeSelection>): DexBackedDexFile {
        val root = Files.createTempDirectory("modkit-runtime-dex").toFile()
        try {
            val result = DexRuntimeSwitchRewriter.rewrite(bytes, 0, "classes.dex", selections, File(root, "out.dex"), signal)
            assertEquals(selections.map { it.method.id }.toSet(), result.appliedIds)
            return DexBackedDexFile(null, result.file.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun groupedGettersKeepBothBodiesAndShareOneSwitch() {
        val bytes = dex(listOf(getter("getHealth"), getter("a")))
        val recipe = DexRecipeCatalog.create(DexLocalPatchEngine.scanDex(bytes, 0, "classes.dex", false, signal)).single()
        assertEquals(2, recipe.dex.size)
        val id = DexRuntimeSwitchRewriter.switchId(recipe.id)
        val result = rewrite(bytes, recipe.dex.map { DexRuntimeSelection(it, id) })
        val methods = result.classes.single().methods.toList()
        assertEquals(4, methods.size)
        recipe.dex.forEach { selected ->
            val original = methods.single { it.name == DexRuntimeSwitchRewriter.backupName(selected.id) }
            assertTrue(AccessFlags.PRIVATE.isSet(original.accessFlags))
            assertEquals(DexMethodBodyKind.INSTANCE_FIELD_GETTER, DexMethodBodyInspector.inspect(original).kind)
            val wrapper = methods.single { it.name == selected.methodName }.implementation!!
            val code = wrapper.instructions.toList()
            assertEquals(id, ((code[0] as ReferenceInstruction).reference as StringReference).string)
            assertEquals(DexRuntimeSwitchRewriter.BRIDGE, ((code[1] as ReferenceInstruction).reference as MethodReference).definingClass)
            assertEquals(6, (code[3] as OffsetInstruction).codeOffset)
            assertEquals(9999, (code[4] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals(Opcode.INVOKE_DIRECT, code[6].opcode)
            assertEquals(1, (code[6] as FiveRegisterInstruction).registerC)
            assertEquals(2, wrapper.registerCount)
        }
    }

    @Test fun staticFloatAndPrivateBranchKeepTheirCallingConventions() {
        val static = method("getRunSpeed", "F", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, 1,
            ImmutableInstruction31i(Opcode.CONST, 0, 1.5f.toBits()), ImmutableInstruction11x(Opcode.RETURN, 0))
        val branch = method("canSprint", "Z", AccessFlags.PRIVATE.value, 2,
            ImmutableInstruction22c(Opcode.IGET, 0, 1, field("stamina")),
            ImmutableInstruction21t(Opcode.IF_LEZ, 0, 4),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 1), ImmutableInstruction11x(Opcode.RETURN, 0),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0))
        val bytes = dex(listOf(static, branch))
        val selected = scan(bytes)
        assertEquals(2, selected.size)
        val result = rewrite(bytes, selected.map { DexRuntimeSelection(it, DexRuntimeSwitchRewriter.switchId(it.id)) })
        val actual = result.classes.single().methods.toList()
        val speed = actual.single { it.name == "getRunSpeed" }.implementation!!
        assertEquals(1, speed.registerCount)
        assertEquals(Opcode.INVOKE_STATIC, speed.instructions.toList()[6].opcode)
        assertEquals(0, (speed.instructions.toList()[6] as FiveRegisterInstruction).registerCount)
        assertEquals(2.0f.toBits(), (speed.instructions.toList()[4] as NarrowLiteralInstruction).narrowLiteral)
        val sprint = selected.single { it.methodName == "canSprint" }
        assertEquals(DexMethodBodyKind.READ_ONLY_COMPUTATION,
            DexMethodBodyInspector.inspect(actual.single { it.name == DexRuntimeSwitchRewriter.backupName(sprint.id) }).kind)
    }

    @Test fun staleDigestAndDuplicateSelectionsAreRejected() {
        val bytes = dex(listOf(getter("getHealth")))
        val original = scan(bytes).single()
        val selection = DexRuntimeSelection(original, DexRuntimeSwitchRewriter.switchId("health"))
        assertThrows(IllegalArgumentException::class.java) { rewrite(bytes, listOf(selection.copy(method = original.copy(originalDexSha256 = "0".repeat(64))))) }
        assertThrows(IllegalArgumentException::class.java) { rewrite(bytes, listOf(selection, selection.copy(switchId = DexRuntimeSwitchRewriter.switchId("other")))) }
    }

    @Test fun backupNameCollisionIsRejectedWithoutOverwritingMethod() {
        val initial = dex(listOf(getter("getHealth")))
        val id = scan(initial).single().id
        val bytes = dex(listOf(getter("getHealth"), getter(DexRuntimeSwitchRewriter.backupName(id))))
        val original = scan(bytes).single { it.methodName == "getHealth" }
        assertThrows(IllegalArgumentException::class.java) { rewrite(bytes, listOf(DexRuntimeSelection(original, DexRuntimeSwitchRewriter.switchId("health")))) }
    }

    @Test fun sideEffectsCannotBeEnabledByForgingSelectableFlag() {
        val bytes = dex(listOf(method("getHealth", "I", instructions = arrayOf<Instruction>(
            ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
            ImmutableInstruction22c(Opcode.IPUT, 0, 1, field("health")), ImmutableInstruction11x(Opcode.RETURN, 0)))))
        val unsafe = scan(bytes).single()
        assertFalse(unsafe.selectable)
        assertThrows(IllegalArgumentException::class.java) { rewrite(bytes, listOf(DexRuntimeSelection(unsafe.copy(selectable = true), DexRuntimeSwitchRewriter.switchId("health")))) }
    }

    @Test fun interfaceMethodsExposeRuntimeBlocker() {
        val scan = DexLocalPatchEngine.scanDex(dex(listOf(getter("getHealth")), true), 0, "classes.dex", false, signal)
        assertNotNull(scan.opportunities.single().runtimeBlocker)
        assertFalse(RuntimeRecipeSelectionPolicy.supports(DexRecipeCatalog.create(scan).single()))
    }

    @Test fun cancellationDoesNotProduceOutput() {
        val bytes = dex(listOf(getter("getHealth")))
        val selected = scan(bytes).single()
        signal.cancel()
        assertThrows(AnalysisCancelledException::class.java) { rewrite(bytes, listOf(DexRuntimeSelection(selected, DexRuntimeSwitchRewriter.switchId("health")))) }
    }
}
