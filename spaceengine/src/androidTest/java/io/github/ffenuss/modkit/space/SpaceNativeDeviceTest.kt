package io.github.ffenuss.modkit.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modkit.nativefixture.GameActivity
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.ElfImage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class SpaceNativeDeviceTest {
    private class MutableBackend(patch: NativePatch) : SpaceNativeController.Backend {
        var memory = patch.expected.copyOf()
        var available = true
        var fail = false
        var writes = 0
        override fun imageMatches(patch: NativePatch): Boolean {
            if (fail) throw IllegalStateException("Image lookup failed")
            return available
        }
        override fun bytesMatch(patch: NativePatch, bytes: ByteArray) = memory.contentEquals(bytes)
        override fun write(patch: NativePatch, expected: ByteArray, replacement: ByteArray): Int {
            writes++
            if (!memory.contentEquals(expected)) return 0
            memory = replacement.copyOf()
            return 1
        }
    }

    @Test fun liveStateDriftRejectsRefreshRepeatedRequestsAndCleanRestore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = File(context.cacheDir, "state-drift-fixture").apply { writeText("owned") }
        try {
            val menu = MenuProfile(profile(original, patch("arm64-v8a", 16, "e0008052c0035fd6", "e07c8052c0035fd6", "a".repeat(64))).toString())
            // Exercise both active/inactive states and every public path that can claim success.
            for (enabled in listOf(false, true)) for (operation in listOf("refresh", "repeat", "restore")) {
                val backend = MutableBackend(menu.items.single().patch!!)
                val controller = SpaceNativeController(menu, backend)
                controller.refresh()
                assertTrue(controller.set("fixture-value", enabled))
                backend.memory = ByteArray(backend.memory.size) { 0x55.toByte() }
                val writes = backend.writes
                when (operation) {
                    "refresh" -> controller.refresh()
                    "repeat" -> assertFalse(controller.set("fixture-value", enabled))
                    "restore" -> assertFalse(controller.restoreAll())
                }
                assertEquals(SpaceNativeController.State.ERROR, controller.state("fixture-value"))
                assertEquals("Foreign code must never be overwritten", writes, backend.writes)
                backend.memory = menu.items.single().patch!!.expected.copyOf()
                controller.refresh()
                assertEquals("Uncertain states stay latched", SpaceNativeController.State.ERROR, controller.state("fixture-value"))
                assertFalse(controller.restoreAll())
            }
        } finally { original.delete() }
    }

    @Test fun missingAndFailingImagesInvalidateConfirmedStatesButAllowLateLoading() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = File(context.cacheDir, "state-image-fixture").apply { writeText("owned") }
        try {
            val menu = MenuProfile(profile(original, patch("arm64-v8a", 16, "e0008052c0035fd6", "e07c8052c0035fd6", "a".repeat(64))).toString())
            for (enabled in listOf(false, true)) for (failure in listOf(false, true)) {
                val backend = MutableBackend(menu.items.single().patch!!).apply { available = false }
                val controller = SpaceNativeController(menu, backend)
                controller.refresh()
                assertEquals(SpaceNativeController.State.UNAVAILABLE, controller.state("fixture-value"))
                backend.fail = true
                controller.refresh()
                assertEquals(SpaceNativeController.State.UNAVAILABLE, controller.state("fixture-value"))
                backend.fail = false; backend.available = true
                controller.refresh()
                assertTrue(controller.set("fixture-value", enabled))
                val writes = backend.writes
                backend.available = false; backend.fail = failure
                controller.refresh()
                assertEquals(SpaceNativeController.State.ERROR, controller.state("fixture-value"))
                assertFalse(controller.restoreAll())
                assertEquals(writes, backend.writes)
            }
        } finally { original.delete() }
    }

    private val running = object : CancellationSignal { override fun isCancelled() = false }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun patch(abi: String, address: Long, original: String, replacement: String, hash: String) = JSONObject()
        .put("abi", abi).put("module", "libmodkit_fixture.so").put("address", address)
        .put("expected", original).put("replacement", replacement).put("imageSha256", hash)
    private fun profile(source: File, patch: JSONObject) = JSONObject().put("schema", 2).put("backend", "native_v1")
        .put("packageName", "dev.modkit.nativefixture").put("label", "Owned native fixture")
        .put("artifactSha256", "a".repeat(64)).put("genre", "Не определён").put("truncated", false)
        .put("engines", JSONArray(listOf("Native fixture")))
        .put("sources", JSONArray().put(JSONObject().put("sha256", SourceInventory.hash(source, SourceInventory.Cancellation())).put("size", source.length())))
        .put("items", JSONArray().put(JSONObject().put("id", "fixture-value").put("category", "Параметр")
            .put("title", "Native value").put("state", "static_recipe").put("detail", "Owned fixture")
            .put("evidence", "modkit_fixture_value").put("patch", patch)))

    @Test fun nativeRecipeRunsAndRestoresWithoutRepackingOrRoot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = File(context.cacheDir, "space-owned-original.apk")
        instrumentation.context.assets.open("native-fixture.apk").use { input -> original.outputStream().use { input.copyTo(it) } }
        val token = SourceInventory.Cancellation()
        val before = SourceInventory.hash(original, token)
        val abi = SpaceNativePayload.processAbi()
        val image = File(context.codeCacheDir, "libmodkit_fixture.so")
        ZipFile(original).use { zip -> zip.getInputStream(zip.getEntry("lib/$abi/libmodkit_fixture.so")).use { input ->
            image.outputStream().use { input.copyTo(it) }
        } }
        try {
            assertTrue("Authenticated runtime transfer must load the exact process ABI", SpaceNativePayload.load(context))
            System.load(image.canonicalPath)
            assertEquals(7, GameActivity.readNativeValue())
            val bytes = ByteArray(8)
            val address = ElfImage.open(image, running).use { elf ->
                val symbol = elf.dynamicSymbols.single { it.name == "modkit_fixture_value" && it.defined }
                assertEquals(8L, symbol.size)
                RandomAccessFile(image, "r").use { it.seek(requireNotNull(elf.fileOffsetForVa(symbol.value, 8))); it.readFully(bytes) }
                symbol.value
            }
            val replacement = when (abi) {
                "arm64-v8a" -> "e07c8052c0035fd6"
                "armeabi-v7a" -> "e70300e31eff2fe1"
                "x86", "x86_64" -> "b8e7030000c39090"
                else -> error("Unsupported fixture ABI")
            }
            val json = profile(original, patch(abi, address, hex(bytes), replacement, SourceInventory.hash(image, token)))
            val menu = MenuProfile(json.toString())
            assertTrue(menu.matches(listOf(original), token))
            val controller = SpaceNativeController(menu, SpaceNativeBackend(listOf(original)))
            controller.refresh()
            assertEquals(SpaceNativeController.State.OFF, controller.state("fixture-value"))
            try {
                assertTrue(controller.set("fixture-value", true))
                assertEquals(SpaceNativeController.State.ON, controller.state("fixture-value"))
                assertEquals(999, GameActivity.readNativeValue())
                assertTrue(controller.set("fixture-value", false))
                assertEquals(7, GameActivity.readNativeValue())
                assertTrue(controller.set("fixture-value", true))
                assertTrue(controller.restoreAll())
                assertEquals(7, GameActivity.readNativeValue())
            } finally { assertTrue(controller.restoreAll()) }
            assertEquals(before, SourceInventory.hash(original, token))
            assertEquals(hex(bytes), hex(RandomAccessFile(image, "r").use { raf ->
                ElfImage.open(image, running).use { elf -> raf.seek(requireNotNull(elf.fileOffsetForVa(address, 8))) }
                ByteArray(8).also { raf.readFully(it) }
            }))
            val wrong = JSONObject(json.toString())
            wrong.getJSONArray("items").getJSONObject(0).getJSONObject("patch").put("imageSha256", "b".repeat(64))
            val rejected = SpaceNativeController(MenuProfile(wrong.toString()), SpaceNativeBackend(listOf(original)))
            rejected.refresh(); assertFalse(rejected.set("fixture-value", true)); assertEquals(7, GameActivity.readNativeValue())
        } finally { original.delete() }
    }

    @Test fun uncertainRestorationCannotBecomeAnOffSwitch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = File(context.cacheDir, "controller-fixture").apply { writeText("owned") }
        try {
            val menu = MenuProfile(profile(original, patch("arm64-v8a", 16, "e0008052c0035fd6", "e07c8052c0035fd6", "a".repeat(64))).toString())
            var memory = menu.items.single().patch!!.expected.copyOf()
            var uncertain = false
            val controller = SpaceNativeController(menu, object : SpaceNativeController.Backend {
                override fun imageMatches(patch: NativePatch) = true
                override fun bytesMatch(patch: NativePatch, bytes: ByteArray) = bytes.contentEquals(memory)
                override fun write(patch: NativePatch, expected: ByteArray, replacement: ByteArray): Int {
                    if (!memory.contentEquals(expected)) return 0
                    memory = replacement.copyOf(); return if (uncertain) -1 else 1
                }
            })
            controller.refresh(); assertTrue(controller.set("fixture-value", true))
            uncertain = true
            assertFalse(controller.set("fixture-value", false))
            assertEquals(SpaceNativeController.State.ERROR, controller.state("fixture-value"))
            assertFalse(controller.restoreAll())
            val duplicate = JSONObject(profile(original, patch("arm64-v8a", 16, "e0008052c0035fd6", "e07c8052c0035fd6", "a".repeat(64))).toString())
            val second = JSONObject(duplicate.getJSONArray("items").getJSONObject(0).toString()).put("id", "another")
            duplicate.getJSONArray("items").put(second)
            assertThrows(Exception::class.java) { MenuProfile(duplicate.toString()) }
        } finally { original.delete() }
    }
}
