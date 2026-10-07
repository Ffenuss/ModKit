package io.github.ffenuss.modkit.space

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.function.Consumer

@RunWith(AndroidJUnit4::class)
class SpaceEngineDeviceTest {
    private fun hostApplication(): Application = object : Application() {
        init { attachBaseContext(InstrumentationRegistry.getInstrumentation().context) }
    }
    @Test fun realDexLoaderRunsSharedEnginesWithPrivateKotlin() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = hostApplication()
        val original = File(instrumentation.targetContext.applicationInfo.sourceDir)
        val token = SourceInventory.Cancellation()
        val before = SourceInventory.hash(original, token)
        val sources = SourceInventory.scan("io.fixture.original", 7, listOf(original), token) { }
        val messages = mutableListOf<String>()
        val report = SpaceEngine.run(app, sources, token, Consumer { messages.add(it) })
        assertTrue(report.contains("Анализаторы ModKit"))
        assertTrue(report.contains("DEX · таблицы заголовка:"))
        assertTrue(report.contains("types="))
        assertTrue(messages.isNotEmpty())
        assertEquals(before, SourceInventory.hash(original, token))
        assertFalse(File(app.cacheDir, "modkit-analysis/${sources.sessionId}").exists())
        val loaded = File(app.codeCacheDir, "modkit-engine").listFiles().orEmpty().filter { it.name.endsWith(".apk") }
        assertEquals(1, loaded.size)
        assertFalse("Dynamic DEX must be read-only", loaded.single().canWrite())
        assertTrue(SpaceEngine.privateNamespace("kotlin.jvm.internal.Intrinsics"))
        assertFalse(SpaceEngine.privateNamespace("io.github.ffenuss.modkit.space.SpaceHost"))
        assertFalse(SpaceEngine.privateNamespace("java.lang.String"))
    }
    @Test fun cancellationLeavesNoTemporaryAnalysisWorkspace() {
        val app = hostApplication()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.sourceDir)
        val token = SourceInventory.Cancellation()
        val sources = SourceInventory.scan("io.fixture.original", 0, listOf(file), token) { }
        token.cancel()
        try {
            SpaceEngine.run(app, sources, token, Consumer { })
            fail("Cancelled engine must not return a report")
        } catch (expected: IOException) {
            assertFalse(File(app.cacheDir, "modkit-analysis/${sources.sessionId}").exists())
        }
    }
}
