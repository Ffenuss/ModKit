package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DexLocalPatchEngineTest {
    private val signal = object : CancellationSignal {
        override fun isCancelled(): Boolean = false
    }

    @Test
    fun fullVersionOnlySelectableForDevelopersOwnTestBuild() {
        val source = syntheticDex("Ldev/game/FeatureManager;", "get_IsFullVersion", "Z")
        val locked = DexLocalPatchEngine.scanDex(
            source, 0, "classes.dex", false, signal,
        )
        val eligible = locked.opportunities.single()
        assertEquals(DexLocalCategory.FULL_VERSION, eligible.category)
        assertFalse(eligible.selectable)

        val localTest = DexLocalPatchEngine.scanDex(
            source, 0, "classes.dex", true, signal,
        )
        assertTrue(localTest.opportunities.single().selectable)
    }

    @Test
    fun rewritesExactBooleanMethodAndParsesProducedDex() {
        val bytes = syntheticDex(
            "Ldev/game/Player;", "get_IsInvincible", "Z",
        )
        val found = DexLocalPatchEngine.scanDex(
            bytes, 0, "classes.dex", false, signal,
        ).opportunities.single()

        val output = File.createTempFile("modkit-dex-test", ".dex")
        try {
            val rewrite = DexLocalPatchEngine.rewriteDex(
                bytes, 0, "classes.dex", listOf(found), output, false, signal,
            )
            assertEquals(setOf(found.id), rewrite.appliedIds)
            val parsed = DexBackedDexFile(null, output.readBytes())
            val method = parsed.classes.single().methods.single()
            assertEquals("Z", method.returnType)
            assertEquals(
                listOf(Opcode.CONST_4, Opcode.RETURN),
                method.implementation!!.instructions.map { it.opcode }.toList(),
            )
        } finally {
            output.delete()
        }
    }

    @Test
    fun rewritesIntAndFloatReturnsWithExactLiteralValues() {
        val samples = listOf(
            Triple("getHealth", "I", 9999),
            Triple("getMoveSpeed", "F", 2.0f.toBits()),
        )
        samples.forEachIndexed { index, (name, returnType, expectedBits) ->
            val bytes = syntheticDex(
                "Ldev/game/Player;", name, returnType,
            )
            val candidate = DexLocalPatchEngine.scanDex(
                bytes, 0, "classes.dex", false, signal,
            ).opportunities.single()
            val output = File.createTempFile(
                "modkit-numeric-" + index, ".dex",
            )
            try {
                DexLocalPatchEngine.rewriteDex(
                    bytes, 0, "classes.dex",
                    listOf(candidate), output, false, signal,
                )
                val method = DexBackedDexFile(null, output.readBytes())
                    .classes.single().methods.single()
                val instructions = method.implementation!!
                    .instructions.toList()
                assertEquals(Opcode.CONST, instructions[0].opcode)
                assertEquals(
                    expectedBits,
                    (instructions[0] as NarrowLiteralInstruction).narrowLiteral,
                )
                assertEquals(Opcode.RETURN, instructions[1].opcode)
            } finally {
                output.delete()
            }
        }
    }

    @Test
    fun rejectsModifiedSourceBytesAndSensitiveReceiptMethods() {
        val original = syntheticDex("Ldev/game/Player;", "get_IsInvincible", "Z")
        val selected = DexLocalPatchEngine.scanDex(
            original, 0, "classes.dex", false, signal,
        ).opportunities.single()
        val changed = syntheticDex("Ldev/game/Player;", "get_MaxHealth", "I")
        val output = File.createTempFile("modkit-stale-dex", ".dex")
        try {
            val result = runCatching {
                DexLocalPatchEngine.rewriteDex(
                    changed, 0, "classes.dex", listOf(selected), output, false, signal,
                )
            }
            assertTrue(result.isFailure)
        } finally {
            output.delete()
        }

        val billing = syntheticDex(
            "Ldev/game/BillingReceiptValidator;",
            "get_IsFullVersion", "Z",
        )
        assertTrue(
            DexLocalPatchEngine.scanDex(
                billing, 0, "classes.dex", true, signal,
            ).opportunities.isEmpty(),
        )
    }

    @Test
    fun recognizesBroaderGameplayMethodsWithoutDroppingGameAndroidPackages() {
        val cases = listOf(
            Triple("getHP", "I", DexLocalCategory.HEALTH),
            Triple("getXP", "I", DexLocalCategory.EXPERIENCE),
            Triple("getPlayerLevel", "I", DexLocalCategory.EXPERIENCE),
            Triple("getInventoryCapacity", "I", DexLocalCategory.INVENTORY),
            Triple("isNoClip", "Z", DexLocalCategory.MOVEMENT),
            Triple("get_IsInvulnerable", "Z", DexLocalCategory.HEALTH),
        )
        cases.forEach { (name, returnType, category) ->
            val bytes = syntheticDex(
                "Lcom/example/android/game/PlayerStats;",
                name,
                returnType,
            )
            val scan = DexLocalPatchEngine.scanDex(
                bytes, 0, "classes.dex", false, signal,
            )
            assertEquals("Expected an exact candidate for " + name,
                1, scan.opportunities.size)
            assertEquals(category, scan.opportunities.single().category)
            assertTrue(scan.opportunities.single().selectable)
            assertEquals(1, scan.classesInspected)
            assertEquals(0, scan.classesExcluded)
        }
    }

    @Test
    fun debuggingOwnApplicationRequiresExplicitTestMode() {
        val source = syntheticDex(
            "Lcom/example/android/app/DebugSettings;",
            "isDebugEnabled",
            "Z",
        )
        val default = DexLocalPatchEngine.scanDex(
            source, 0, "classes.dex", false, signal,
        )
        assertEquals(DexLocalCategory.DEBUG_UI,
            default.opportunities.single().category)
        assertFalse(default.opportunities.single().selectable)
        val authorizedTest = DexLocalPatchEngine.scanDex(
            source, 0, "classes.dex", true, signal,
        )
        assertTrue(authorizedTest.opportunities.single().selectable)
    }

    @Test
    fun explainsWhenMethodsExistButNamesAreObfuscated() {
        val bytes = syntheticDex(
            "Lcom/example/game/Character;",
            "a",
            "Z",
        )
        val scan = DexLocalPatchEngine.scanDex(
            bytes, 0, "classes.dex", false, signal,
        )
        assertTrue(scan.opportunities.isEmpty())
        assertEquals(1, scan.methodsExamined)
        assertEquals(1, scan.methodsWithCode)
        assertEquals(1, scan.scalarNoArgumentMethods)
        assertEquals(0, scan.semanticNamesMatched)
        assertTrue(scan.explanation.contains("обфускация"))
    }

    @Test
    fun scansBothDexEntriesAndDetectsNativeGameLibrary() {
        val apk = File.createTempFile("modkit-synthetic-game", ".apk")
        try {
            ZipOutputStream(apk.outputStream().buffered()).use { output ->
                val entries = mapOf(
                    "classes.dex" to syntheticDex(
                        "Lcom/example/game/Player;",
                        "getHealth",
                        "I",
                    ),
                    "classes2.dex" to syntheticDex(
                        "Lcom/example/game/Movement;",
                        "isNoClip",
                        "Z",
                    ),
                    "lib/arm64-v8a/libgame.so" to
                        byteArrayOf(0x7f, 0x45, 0x4c, 0x46),
                )
                entries.forEach { (name, bytes) ->
                    output.putNextEntry(ZipEntry(name))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
            val scan = DexLocalPatchEngine.scanApks(
                listOf(apk), false, signal,
            )
            assertEquals(2, scan.dexFilesExamined)
            assertEquals(2, scan.opportunities.size)
            assertEquals(1, scan.nativeLibrariesObserved)
            assertEquals(2, scan.methodsExamined)
        } finally {
            apk.delete()
        }
    }

    @Test
    fun levelPatchWritesExactNinetyNineValue() {
        val bytes = syntheticDex(
            "Lcom/example/game/Stats;",
            "getPlayerLevel",
            "I",
        )
        val option = DexLocalPatchEngine.scanDex(
            bytes, 0, "classes.dex", false, signal,
        ).opportunities.single()
        assertEquals(DexLocalAction.INT_99, option.action)
        val output = File.createTempFile("modkit-level-rewrite", ".dex")
        try {
            DexLocalPatchEngine.rewriteDex(
                bytes, 0, "classes.dex",
                listOf(option), output, false, signal,
            )
            val instructions = DexBackedDexFile(
                null, output.readBytes(),
            ).classes.single().methods.single()
                .implementation!!.instructions.toList()
            assertEquals(
                99,
                (instructions[0] as NarrowLiteralInstruction).narrowLiteral,
            )
            assertEquals(Opcode.RETURN, instructions[1].opcode)
        } finally {
            output.delete()
        }
    }

        private fun syntheticDex(
        declaringClass: String,
        name: String,
        returnType: String,
    ): ByteArray {
        val method = ImmutableMethod(
            declaringClass, name, emptyList(),
            returnType,
            AccessFlags.PUBLIC.value,
            emptySet(), emptySet(),
            ImmutableMethodImplementation(
                1,
                listOf(
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                ),
                emptyList(),
                emptyList(),
            ),
        )
        val clazz = ImmutableClassDef(
            declaringClass,
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptySet(),
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(method),
        )
        val dex = ImmutableDexFile(
            Opcodes.forApi(28),
            listOf(clazz),
        )
        val path = File.createTempFile("modkit-test-source", ".dex")
        try {
            DexPool.writeTo(path.absolutePath, dex)
            return path.readBytes()
        } finally {
            path.delete()
        }
    }
}
