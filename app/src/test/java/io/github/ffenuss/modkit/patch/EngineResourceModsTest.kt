package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.*
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class EngineResourceModsTest {
    private val signal = AtomicCancellationSignal()
    private val sha = "a".repeat(64)
    private fun discover(text: String, format: EngineResourceFormat = EngineResourceFormat.FLUTTER_JSON) =
        EngineResourceMods.discover(sha, 0, "base.apk", "assets/flutter_assets/game.json", format, text.toByteArray(), signal)
    private fun change(recipe: AutoModRecipe, value: String) = requireNotNull(recipe.withScalarValue(value).resource)

    @Test fun jsonEditsNestedValuesWithoutReserializingUnselectedBytes() {
        val original = "{ \"player\": {\"health\":20, \"speed\":1.5},\"enabled\":false,\"text\":\"кириллица\\n😀\",\"array\":[7,null] }\r\n"
        val recipes = discover(original)
        assertEquals(4, recipes.size)
        assertTrue(recipes.all { it.selectable && !it.verification.runtimeConfirmed && !it.verification.purposeConfirmed })
        val health = recipes.single { it.resource!!.key == "/player/health" }
        assertFalse(RuntimeRecipeSelectionPolicy.supports(health))
        val enabled = recipes.single { it.resource!!.key == "/enabled" }
        val output = EngineResourceMods.rewrite(original.toByteArray(), listOf(change(health, "99"), change(enabled, "true")), signal)
        assertEquals(original.replace("\"health\":20", "\"health\":99").replace("\"enabled\":false", "\"enabled\":true"), output.toString(Charsets.UTF_8))
        assertTrue(health.resource!!.value != health.withScalarValue("99").resource!!.value)
        assertEquals(health, health.withScalarValue("not-a-number"))
    }

    @Test fun rejectsDuplicateDecodedJsonKeysAndMalformedInputs() {
        for (input in listOf("{\"a\":1,\"\\u0061\":2}", "{\"x\":01}", "{\"x\":NaN}", "[1,]", "{\"a\":true}x", "{\"a\":TRUE}", "{\"x\":\"\\q\"}")) {
            assertTrue(input, runCatching { discover(input) }.isFailure)
        }
        assertTrue(runCatching { discover("[".repeat(34) + "1" + "]".repeat(34)) }.isFailure)
        assertTrue(runCatching { EngineResourceDocument.parse(byteArrayOf(0xc3.toByte(), 0x28), EngineResourceFormat.FLUTTER_JSON, signal) }.isFailure)
    }

    @Test fun pointerEscapesPreventNamesAndArrayIndicesColliding() {
        val recipes = discover("{\"a/b\":1,\"a\":{\"b\":2},\"~\":3,\"arr\":[true,false]}")
        assertEquals(setOf("/a~1b", "/a/b", "/~0", "/arr/0", "/arr/1"), recipes.map { it.resource!!.key }.toSet())
        assertEquals(5, recipes.map { it.id }.toSet().size)
    }

    @Test fun resourceShaValueTypeAndDuplicateSelectionsAreEnforced() {
        val original = "{\"health\":20}"
        val recipe = discover(original).single()
        val selected = change(recipe, "99")
        fun fails(bytes: ByteArray, changes: List<EngineResourceChange>) = assertTrue(runCatching { EngineResourceMods.rewrite(bytes, changes, signal) }.isFailure)
        fails("{\"health\":21}".toByteArray(), listOf(selected))
        fails(original.toByteArray(), listOf(selected.copy(oldValue = "19")))
        fails(original.toByteArray(), listOf(selected.copy(value = "true")))
        fails(original.toByteArray(), listOf(selected, selected))
        fails(original.toByteArray(), listOf(selected.copy(key = "/other")))
    }

    @Test fun iniPreservesBomCrLfCommentsAndDoesNotEditMergeOperatorsOrDuplicates() {
        val text = "; header\r\n[/Script/Game.Player]\r\nHealth = 20 \r\nEnabled=True\r\n+Items=7\r\nCount=2\r\n[Other]\r\nCount=3\r\n[/script/game.player]\r\ncount=4\r\nItems=5\r\n"
        val bytes = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val recipes = EngineResourceMods.discover(sha, 0, "base.apk", "assets/Game/Config/DefaultGame.ini", EngineResourceFormat.UNREAL_INI, bytes, signal)
        assertEquals(setOf("/script/game.player\u001fhealth", "/script/game.player\u001fenabled", "other\u001fcount"), recipes.map { it.resource!!.key }.toSet())
        val selected = recipes.single { it.resource!!.key.endsWith("\u001fhealth") }
        val rewritten = EngineResourceMods.rewrite(bytes, listOf(change(selected, "99")), signal)
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.replace("Health = 20", "Health = 99").toByteArray(Charsets.UTF_16LE), rewritten)
    }

    @Test fun cancellationAndDecompressionLimitsStopDiscovery() {
        val cancelled = AtomicCancellationSignal().also { it.cancel() }
        assertThrows(AnalysisCancelledException::class.java) { EngineResourceMods.discover(sha, 0, "base.apk", "assets/flutter_assets/a.json", EngineResourceFormat.FLUTTER_JSON, "[1]".toByteArray(), cancelled) }
        assertThrows(IllegalArgumentException::class.java) { EngineResourceMods.read(ByteArray(EngineResourceDocument.MAX_BYTES + 1).inputStream(), signal) }
    }

    @Test fun discoversBothEnginesAcrossApkSplitsAndRejectsAmbiguousPaths() {
        val root = Files.createTempDirectory("resource-mod-scan").toFile()
        try {
            fun zip(name: String, path: String, content: String) = File(root, name).also { file ->
                ZipOutputStream(file.outputStream()).use { it.putNextEntry(ZipEntry(path)); it.write(content.toByteArray()); it.closeEntry() }
            }
            val json = "assets/flutter_assets/data/settings.json"
            val ini = "assets/MyGame/Config/DefaultGame.ini"
            val base = zip("base.apk", json, "{\"health\":20}")
            val split = zip("config.apk", ini, "[Player]\nHealth=20\n")
            val files = listOf(base, split)
            val indexed = FastArtifactIndexer.index(files, signal, ProgressSink { })
            val profiles = listOf("flutter", "unreal").map { RuntimeProfile(it, it, DetectionStatus.LIKELY, DetectionConfidence.HIGH, emptyList()) }
            val workspace = AnalysisWorkspace(indexed.index.copy(runtimeProfiles = profiles), files.mapIndexed { i, f -> WorkspaceSource(indexed.index.sources[i], f) })
            val scan = EngineResourceMods.scan(workspace, signal, ProgressSink { })
            assertEquals(2, scan.examinedFiles)
            assertEquals(2, scan.recipes.size)
            assertEquals(setOf(0, 1), scan.recipes.map { it.resource!!.apkIndex }.toSet())
            val duplicated = workspace.copy(index = workspace.index.copy(entries = workspace.index.entries + workspace.index.entries.first { it.path == json }))
            val blocked = EngineResourceMods.scan(duplicated, signal, ProgressSink { })
            assertEquals(1, blocked.recipes.size)
            assertTrue(blocked.warnings.any { it.contains("неоднозначен") })
        } finally { root.deleteRecursively() }
    }

    @Test fun generatedManifestsWrongPathsAndNoEngineEvidenceAreNotEditable() {
        assertNull(EngineResourceMods.format("assets/flutter_assets/AssetManifest.json", setOf("flutter")))
        assertNull(EngineResourceMods.format("assets/flutter_assets/game.json", emptySet()))
        assertNull(EngineResourceMods.format("assets/Other/default.ini", setOf("unreal")))
        assertThrows(IllegalArgumentException::class.java) { EngineResourceMods.discover(sha, 0, "base.apk", "assets/../game.json", EngineResourceFormat.FLUTTER_JSON, "[1]".toByteArray(), signal) }
    }
}
