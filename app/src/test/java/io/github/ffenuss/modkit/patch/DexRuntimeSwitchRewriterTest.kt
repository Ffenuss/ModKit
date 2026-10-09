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
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
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

    @Test fun narrowResultsKeepTheirExactPrototypeAndOriginalBits() {
        for ((type, originalValue) in listOf("B" to -7, "S" to -300, "C" to 50000)) for (static in listOf(false, true)) {
            val name = when (type) { "B" -> "getEnergy"; "S" -> "getMaxHealth"; else -> "getMagazineSize" }
            val bytes = dex(listOf(method(name, type,
                AccessFlags.PUBLIC.value or if (static) AccessFlags.STATIC.value else 0, if (static) 1 else 2,
                ImmutableInstruction31i(Opcode.CONST, 0, originalValue), ImmutableInstruction11x(Opcode.RETURN, 0))))
            val selected = scan(bytes).single()
            assertTrue(selected.selectable)
            assertEquals("()$type", selected.signature)
            val recipe = DexRecipeCatalog.create(DexLocalPatchEngine.scanDex(bytes, 0, "classes.dex", false, signal)).single()
            val replacement = if (type == "B") 127 else 9999
            assertTrue(recipe.title.contains("значение $replacement"))
            assertTrue(recipe.title.contains("$name()"))
            assertEquals("Возвращать $replacement", selected.actionLabel)
            val actual = rewrite(bytes, listOf(DexRuntimeSelection(selected, DexRuntimeSwitchRewriter.switchId(recipe.id))))
                .classes.single().methods.toList()
            val live = actual.single { it.name == name }
            val backup = actual.single { it.name == DexRuntimeSwitchRewriter.backupName(selected.id) }
            assertEquals(type, live.returnType)
            assertEquals(type, backup.returnType)
            assertEquals(originalValue, (backup.implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral)
            val code = live.implementation!!.instructions.toList()
            assertEquals(replacement, (code[4] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals(Opcode.RETURN, code[5].opcode)
            assertEquals(type, ((code[6] as ReferenceInstruction).reference as MethodReference).returnType)
            assertEquals(Opcode.MOVE_RESULT, code[7].opcode)
            val output = File.createTempFile("narrow-direct", ".dex")
            try {
                DexLocalPatchEngine.rewriteDex(bytes, 0, "classes.dex", listOf(selected), output, false, signal)
                val instructions = DexBackedDexFile(null, output.readBytes()).classes.single().methods.single().implementation!!.instructions.toList()
                assertEquals(replacement, (instructions[0] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals(Opcode.RETURN, instructions[1].opcode)
            } finally { output.delete() }
        }
    }

    @Test fun mathCallsRemainInExactBackupsAndDisappearOnlyFromEnabledWrappers() {
        for ((mathName, input, type) in listOf(
            Triple("abs", "I", "I"), Triple("abs", "J", "J"), Triple("abs", "F", "F"), Triple("abs", "D", "D"),
            Triple("round", "F", "I"), Triple("round", "D", "J"), Triple("floor", "D", "D"),
            Triple("ceil", "D", "D"), Triple("sqrt", "D", "D"))) for (range in listOf(false, true)) {
            val inputWide = input == "J" || input == "D"
            val wide = type == "J" || type == "D"
            val name = if (type == "I" || type == "J") "getAmmo" else "getRunSpeed"
            val ref = ImmutableMethodReference("Ljava/lang/Math;", mathName, listOf(input), type)
            val constant: Instruction = if (inputWide) ImmutableInstruction51l(Opcode.CONST_WIDE, 0,
                if (input == "J") Long.MIN_VALUE else (-1.25).toBits())
                else ImmutableInstruction31i(Opcode.CONST, 0, if (input == "I") Int.MIN_VALUE else (-1.25f).toBits())
            val bytes = dex(listOf(method(name, type, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                if (inputWide || wide) 2 else 1, constant,
                if (range) ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 0, if (inputWide) 2 else 1, ref)
                else ImmutableInstruction35c(Opcode.INVOKE_STATIC, if (inputWide) 2 else 1, 0, 1, 0, 0, 0, ref),
                ImmutableInstruction11x(if (wide) Opcode.MOVE_RESULT_WIDE else Opcode.MOVE_RESULT, 0),
                ImmutableInstruction11x(if (wide) Opcode.RETURN_WIDE else Opcode.RETURN, 0))))
            val selected = scan(bytes).single()
            assertTrue(selected.reason, selected.selectable)
            assertEquals(DexMethodBodyKind.READ_ONLY_COMPUTATION, selected.bodyKind)
            val result = rewrite(bytes, listOf(DexRuntimeSelection(selected, DexRuntimeSwitchRewriter.switchId(selected.id))))
            val methods = result.classes.single().methods.toList()
            val backup = methods.single { it.name == DexRuntimeSwitchRewriter.backupName(selected.id) }
            assertEquals(type, backup.returnType)
            val backupCode = backup.implementation!!.instructions.toList()
            assertEquals(4, backupCode.size)
            assertEquals(if (inputWide || wide) 2 else 1, backup.implementation!!.registerCount)
            assertEquals(constant.opcode, backupCode[0].opcode)
            if (inputWide) assertEquals((constant as WideLiteralInstruction).wideLiteral,
                (backupCode[0] as WideLiteralInstruction).wideLiteral)
            else assertEquals((constant as NarrowLiteralInstruction).narrowLiteral,
                (backupCode[0] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals(if (wide) Opcode.RETURN_WIDE else Opcode.RETURN, backupCode[3].opcode)
            assertEquals(if (range) Opcode.INVOKE_STATIC_RANGE else Opcode.INVOKE_STATIC, backupCode[1].opcode)
            assertEquals(if (wide) Opcode.MOVE_RESULT_WIDE else Opcode.MOVE_RESULT, backupCode[2].opcode)
            val call = (backupCode[1] as ReferenceInstruction).reference as MethodReference
            assertEquals("Ljava/lang/Math;", call.definingClass)
            assertEquals(mathName, call.name)
            assertEquals(type, call.returnType)
            assertEquals(listOf(input), call.parameterTypes.map { it.toString() })
            assertEquals(DexMethodBodyKind.READ_ONLY_COMPUTATION, DexMethodBodyInspector.inspect(backup).kind)
            val wrapper = methods.single { it.name == name }.implementation!!.instructions.toList()
            assertEquals(DexRuntimeSwitchRewriter.backupName(selected.id),
                ((wrapper[6] as ReferenceInstruction).reference as MethodReference).name)
            assertEquals(if (wide) Opcode.MOVE_RESULT_WIDE else Opcode.MOVE_RESULT, wrapper[7].opcode)
        }
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

    @Test fun wideGettersUseTwoResultRegistersAndKeepThisSeparate() {
        for (type in listOf("J", "D")) for (static in listOf(false, true)) {
            val name = if (type == "J") "getAmmo" else "getRunSpeed"
            val sourceBits = if (type == "J") 4294967298L else 1.25.toBits()
            val original = method(name, type, AccessFlags.PUBLIC.value or if (static) AccessFlags.STATIC.value else 0,
                if (static) 2 else 3,
                ImmutableInstruction51l(Opcode.CONST_WIDE, 0, sourceBits), ImmutableInstruction11x(Opcode.RETURN_WIDE, 0))
            val bytes = dex(listOf(original))
            val selected = scan(bytes).single()
            assertTrue(selected.selectable)
            val result = rewrite(bytes, listOf(DexRuntimeSelection(selected, DexRuntimeSwitchRewriter.switchId(selected.id))))
            val methods = result.classes.single().methods.toList()
            val wrapper = methods.single { it.name == name }.implementation!!
            val code = wrapper.instructions.toList()
            assertEquals(if (static) 2 else 3, wrapper.registerCount)
            assertEquals(8, (code[3] as OffsetInstruction).codeOffset)
            assertEquals(Opcode.CONST_WIDE, code[4].opcode)
            assertEquals(if (type == "J") 9999L else 2.0.toBits(), (code[4] as WideLiteralInstruction).wideLiteral)
            assertEquals(Opcode.RETURN_WIDE, code[5].opcode)
            assertEquals(if (static) Opcode.INVOKE_STATIC else Opcode.INVOKE_DIRECT, code[6].opcode)
            assertEquals(if (static) 0 else 2, (code[6] as FiveRegisterInstruction).registerC)
            assertEquals(Opcode.MOVE_RESULT_WIDE, code[7].opcode)
            assertEquals(Opcode.RETURN_WIDE, code[8].opcode)
            val backup = methods.single { it.name == DexRuntimeSwitchRewriter.backupName(selected.id) }
            assertEquals(sourceBits, (backup.implementation!!.instructions.first() as WideLiteralInstruction).wideLiteral)
            val output = File.createTempFile("wide-direct", ".dex")
            try {
                DexLocalPatchEngine.rewriteDex(bytes, 0, "classes.dex", listOf(selected), output, false, signal)
                val instructions = DexBackedDexFile(null, output.readBytes()).classes.single().methods.single().implementation!!.instructions.toList()
                assertEquals(Opcode.CONST_WIDE, instructions[0].opcode)
                assertEquals(if (type == "J") 9999L else 2.0.toBits(), (instructions[0] as WideLiteralInstruction).wideLiteral)
                assertEquals(Opcode.RETURN_WIDE, instructions[1].opcode)
            } finally { output.delete() }
        }
    }

    @Test fun wideFieldProofRejectsWrongWidthMissingPairAndSideEffects() {
        for (type in listOf("J", "D")) {
            val name = if (type == "J") "getAmmo" else "getRunSpeed"
            val reference = ImmutableFieldReference(owner, if (type == "J") "ammo" else "runSpeed", type)
            val valid = method(name, type, registers = 3, instructions = arrayOf<Instruction>(
                ImmutableInstruction22c(Opcode.IGET_WIDE, 0, 2, reference), ImmutableInstruction11x(Opcode.RETURN_WIDE, 0)))
            assertEquals(DexMethodBodyKind.INSTANCE_FIELD_GETTER, DexMethodBodyInspector.inspect(valid).kind)
            val static = method(name, type, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, 2,
                ImmutableInstruction21c(Opcode.SGET_WIDE, 0, reference), ImmutableInstruction11x(Opcode.RETURN_WIDE, 0))
            assertEquals(DexMethodBodyKind.STATIC_FIELD_GETTER, DexMethodBodyInspector.inspect(static).kind)
            for (invalid in listOf(
                method(name, type, registers = 1, instructions = arrayOf<Instruction>(
                    ImmutableInstruction51l(Opcode.CONST_WIDE, 0, 11L), ImmutableInstruction11x(Opcode.RETURN_WIDE, 0))),
                method(name, type, registers = 3, instructions = arrayOf<Instruction>(
                    ImmutableInstruction22c(Opcode.IGET, 0, 2, reference), ImmutableInstruction11x(Opcode.RETURN_WIDE, 0))),
                method(name, type, registers = 3, instructions = arrayOf<Instruction>(
                    ImmutableInstruction22c(Opcode.IGET_WIDE, 0, 2, reference), ImmutableInstruction11x(Opcode.RETURN, 0))),
                method(name, type, registers = 3, instructions = arrayOf<Instruction>(
                    ImmutableInstruction22c(Opcode.IGET_WIDE, 0, 2, reference),
                    ImmutableInstruction22c(Opcode.IPUT_WIDE, 0, 2, reference), ImmutableInstruction11x(Opcode.RETURN_WIDE, 0))),
            )) assertFalse(DexMethodBodyInspector.inspect(invalid).supportsScalarReplacement)
        }
    }

    private fun parameterMethod(name: String, type: String, parameters: List<String>, registers: Int,
                                static: Boolean = false, vararg code: Instruction): Method = ImmutableMethod(
        owner, name, parameters.map { ImmutableMethodParameter(it, emptySet(), null) }, type,
        AccessFlags.PUBLIC.value or if (static) AccessFlags.STATIC.value else 0, emptySet(), emptySet(),
        ImmutableMethodImplementation(registers, code.toList(), emptyList(), emptyList()))

    @Test fun parameterOverloadsAreResolvedByTheirFullPrototype() {
        val intGetter = parameterMethod("getAmmo", "I", listOf("I"), 3, code = arrayOf<Instruction>(
            ImmutableInstruction22b(Opcode.ADD_INT_LIT8, 0, 2, 10), ImmutableInstruction11x(Opcode.RETURN, 0)))
        val longGetter = parameterMethod("getAmmo", "J", listOf("J"), 5, code = arrayOf<Instruction>(
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 0, 11),
            ImmutableInstruction12x(Opcode.ADD_LONG_2ADDR, 0, 3), ImmutableInstruction11x(Opcode.RETURN_WIDE, 0)))
        val bytes = dex(listOf(intGetter, longGetter))
        val selected = scan(bytes)
        assertEquals(setOf("(I)I", "(J)J"), selected.map { it.signature }.toSet())
        assertTrue(selected.all { it.selectable && it.bodyKind == DexMethodBodyKind.READ_ONLY_COMPUTATION })
        val recipes = DexRecipeCatalog.create(DexLocalPatchEngine.scanDex(bytes, 0, "classes.dex", false, signal))
        assertEquals(2, recipes.size)
        assertEquals(2, recipes.map { it.title }.distinct().size)
        assertTrue(recipes.any { it.title.endsWith("getAmmo(int)") })
        assertTrue(recipes.any { it.title.endsWith("getAmmo(long)") })
        val result = rewrite(bytes, selected.map { DexRuntimeSelection(it, DexRuntimeSwitchRewriter.switchId(it.id)) })
        val methods = result.classes.single().methods.toList()
        for (candidate in selected) {
            val method = methods.single { it.name == "getAmmo" && DexMethodParameters.signature(it) == candidate.signature }
            val code = method.implementation!!.instructions.toList()
            val call = code[6] as FiveRegisterInstruction
            assertEquals(if (candidate.signature == "(I)I") 2 else 3, call.registerCount)
            assertEquals(if (candidate.signature == "(I)I") 1 else 2, call.registerC)
            assertEquals(method.parameterTypes.map { it.toString() }, ((code[6] as ReferenceInstruction).reference as MethodReference).parameterTypes.map { it.toString() })
            val backup = methods.single { it.name == DexRuntimeSwitchRewriter.backupName(candidate.id) }
            assertEquals(candidate.signature, DexMethodParameters.signature(backup))
        }
        val output = File.createTempFile("parameter-direct", ".dex")
        try {
            DexLocalPatchEngine.rewriteDex(bytes, 0, "classes.dex", selected, output, false, signal)
            assertEquals(setOf("(I)I", "(J)J"), DexBackedDexFile(null, output.readBytes()).classes.single().methods.map { DexMethodParameters.signature(it) }.toSet())
        } finally { output.delete() }
    }

    @Test fun largeMixedPrimitiveFramesUseRangeCallsWithoutDroppingThisOrWideWords() {
        val types = listOf("Z", "B", "C", "S", "I", "J", "F", "D")
        assertEquals(10, DexMethodParameters.words(types))
        for (static in listOf(false, true)) {
            val inputs = if (static) 10 else 11
            val original = parameterMethod("getRunSpeed", "D", types, inputs, static,
                ImmutableInstruction10x(Opcode.NOP), ImmutableInstruction11x(Opcode.RETURN_WIDE, inputs - 2))
            val bytes = dex(listOf(original)); val candidate = scan(bytes).single()
            assertTrue(candidate.selectable)
            val result = rewrite(bytes, listOf(DexRuntimeSelection(candidate, DexRuntimeSwitchRewriter.switchId(candidate.id))))
            val method = result.classes.single().methods.single { it.name == "getRunSpeed" }
            assertEquals(inputs + 2, method.implementation!!.registerCount)
            val call = method.implementation!!.instructions.toList()[6]
            assertEquals(if (static) Opcode.INVOKE_STATIC_RANGE else Opcode.INVOKE_DIRECT_RANGE, call.opcode)
            assertEquals(2, (call as RegisterRangeInstruction).startRegister)
            assertEquals(inputs, call.registerCount)
            assertEquals(types, ((call as ReferenceInstruction).reference as MethodReference).parameterTypes.map { it.toString() })
        }
    }

    @Test fun primitiveInputProofRejectsReferencesBrokenFramesAndMistakenThisRegisters() {
        for (types in listOf(listOf("[I"), listOf("Ljava/lang/String;"), List(9) { "I" })) {
            val method = parameterMethod("getAmmo", "I", types, 12, code = arrayOf<Instruction>(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 1), ImmutableInstruction11x(Opcode.RETURN, 0)))
            assertFalse(DexMethodBodyInspector.inspect(method).supportsScalarReplacement)
            assertTrue(scan(dex(listOf(method))).isEmpty())
        }
        val broken = parameterMethod("getAmmo", "J", listOf("J"), 1, true,
            ImmutableInstruction10x(Opcode.NOP), ImmutableInstruction11x(Opcode.RETURN_WIDE, 0))
        assertFalse(DexMethodBodyInspector.inspect(broken).supportsScalarReplacement)
        val own = parameterMethod("getHealth", "I", listOf("I", "J"), 4, code = arrayOf<Instruction>(
            ImmutableInstruction22c(Opcode.IGET, 1, 0, field("health")), ImmutableInstruction11x(Opcode.RETURN, 1)))
        assertEquals(DexMethodBodyKind.INSTANCE_FIELD_GETTER, DexMethodBodyInspector.inspect(own).kind)
        val other = parameterMethod("getHealth", "I", listOf("I", "J"), 4, code = arrayOf<Instruction>(
            ImmutableInstruction22c(Opcode.IGET, 1, 3, field("health")), ImmutableInstruction11x(Opcode.RETURN, 1)))
        assertFalse(DexMethodBodyInspector.inspect(other).supportsScalarReplacement)
        val damagedPair = parameterMethod("getAmmo", "J", listOf("J"), 2, true,
            ImmutableInstruction11n(Opcode.CONST_4, 1, 1), ImmutableInstruction11x(Opcode.RETURN_WIDE, 0))
        assertFalse(DexMethodBodyInspector.inspect(damagedPair).supportsScalarReplacement)
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
