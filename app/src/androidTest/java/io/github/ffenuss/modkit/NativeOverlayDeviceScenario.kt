package io.github.ffenuss.modkit

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.ffenuss.modkit.analysis.*
import io.github.ffenuss.modkit.patch.AArch64ScalarReturnEncoder
import io.github.ffenuss.modkit.runtime.*
import io.github.ffenuss.modkit.ui.AutoModBuildRecord
import java.io.File
import java.io.RandomAccessFile
import java.util.regex.Pattern
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*

/** Runs after the existing install-permission tests; never targets a user's package. */
class NativeOverlayDeviceScenario(private val instrumentation: Instrumentation, private val device: UiDevice) {
    private val context = instrumentation.targetContext
    private val fixture = "dev.modkit.nativefixture"
    private val authority = fixture + BinaryAndroidManifestProbeInjector.AUTHORITY_SUFFIX
    private val transport = AndroidRepackedRuntimeProbeTransport(context)
    private val signal = AtomicCancellationSignal()
    private val progress = ProgressSink { android.util.Log.i("NativeOverlayTest", it.currentTask.orEmpty()) }
    private val events = JSONArray()

    fun run(evidence: (String, (File) -> Unit) -> Unit, install: (RepackedRuntimeInstallPlan) -> Unit) {
        val root = File(context.filesDir, "native-overlay-validation").apply { mkdirs() }
        var stage = "build"
        var abi = "unknown"
        try {
            val source = File(root, "native-fixture.apk")
            instrumentation.context.assets.open("native-fixture.apk").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val analysis = FastArtifactIndexer.index(listOf(source), signal, progress)
            val workspace = AnalysisWorkspace(analysis.index, listOf(WorkspaceSource(analysis.index.sources.single(), source)))
            val injection = RepackedRuntimeInstrumentationCoordinator.instrumentNativeLookup(context, workspace, root, signal)
            val built = RepackedRuntimeBuildCoordinator.buildNativeProbeInjected(context,
                injection.base.manifestInventory, injection.nativeProbeInjection, root, signal, progress)
            assertEquals(fixture, built.packageName)
            val plan = RepackedRuntimeInstallPlanner.plan(built, signal)

            val library = File(root, "libmodkit_fixture.so")
            ZipFile(source).use { zip ->
                abi = Build.SUPPORTED_ABIS.first { zip.getEntry("lib/$it/libmodkit_fixture.so") != null }
                zip.getInputStream(zip.getEntry("lib/$abi/libmodkit_fixture.so")).use { input ->
                    library.outputStream().use { input.copyTo(it) }
                }
                // Instrumentation must not statically change the target library.
                ZipFile(File(built.signedApks.single().signedPath)).use { modified ->
                    assertArrayEquals(library.readBytes(), modified.getInputStream(
                        modified.getEntry("lib/$abi/libmodkit_fixture.so")).use { it.readBytes() })
                }
            }
            val item = ElfImage.open(library, signal).use { elf ->
                val symbol = elf.dynamicSymbols.single { it.name == "modkit_fixture_value" && it.defined }
                assertEquals(8L, symbol.size)
                val offset = requireNotNull(elf.fileOffsetForVa(symbol.value, symbol.size))
                val original = ByteArray(8)
                RandomAccessFile(library, "r").use { it.seek(offset); it.readFully(original) }
                val replacement = when (abi) {
                    "arm64-v8a" -> AArch64ScalarReturnEncoder.encodeHex(Il2CppNativeReturnKind.INTEGER, "999")
                    "armeabi-v7a" -> "E7 03 00 E3 1E FF 2F E1" // movw r0, #999; bx lr
                    "x86", "x86_64" -> "B8 E7 03 00 00 C3 90 90" // mov eax, 999; ret; nop; nop
                    else -> error("Unsupported test ABI: $abi")
                }
                RepackedRuntimeTestMenuItem("fixture:value", "Runtime value", "Owned native fixture",
                    RepackedRuntimeTestMenuItemMode.PATCH, "libmodkit_fixture.so", symbol.value,
                    original.joinToString(" ") { "%02X".format(it.toInt() and 255) }, replacement)
            }
            val missing = item.copy(id = "fixture:missing", label = "Missing module", moduleName = "libmissing_fixture.so")
            val items = listOf(item, missing)
            stage = "install"
            // Only our disposable fixture is ever removed by this integration scenario.
            device.executeShellCommand("pm uninstall $fixture")
            install(plan)
            val installed = requireNotNull(transport.inspectInstalled(fixture, authority))
            RepackedRuntimeProbeIdentityVerifier.verify(built, installed)
            val configured = transport.configureTestMenu(authority, items, embeddedMenu = false)
            assertEquals(2, configured.patchItemCount)
            assertFalse(configured.embeddedMenu)
            AutoModBuildRecord(plan, System.currentTimeMillis(), listOf("Runtime value"), built.reportPath,
                runtimeMenuItems = items).save(context)

            stage = "open-overlay"
            device.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            assertTrue(Settings.canDrawOverlays(context))
            openOverlay(plan.artifactSha256)
            readValue(7, "baseline")
            assertSwitch("Runtime value", false)
            evidence("native-overlay-off.png") { device.takeScreenshot(it) }

            stage = "enable"
            clickSwitch("Runtime value")
            assertSwitch("Runtime value", true)
            assertTrue(transport.testMenuSwitchSnapshot(authority).enabledById.getValue(item.id))
            readValue(999, "enabled")
            evidence("native-overlay-on.png") { device.takeScreenshot(it) }

            stage = "reopen-overlay"
            context.stopService(Intent(context, ModKitRuntimeOverlayService::class.java))
            assertTrue(device.wait(Until.gone(By.desc("Открыть мод-меню ModKit")), 10_000))
            // Reopening the controller must query the still-enabled game rather than reset the checkbox.
            openOverlay(plan.artifactSha256)
            assertSwitch("Runtime value", true)
            readValue(999, "reopened")

            stage = "disable"
            clickSwitch("Runtime value")
            assertSwitch("Runtime value", false)
            readValue(7, "disabled")

            stage = "rejected-toggle"
            clickSwitch("Missing module")
            assertSwitch("Missing module", false)
            assertFalse(transport.testMenuSwitchSnapshot(authority).enabledById.getValue(missing.id))
            readValue(7, "missing-module-rejected")

            stage = "process-restart"
            clickSwitch("Runtime value")
            assertSwitch("Runtime value", true)
            val oldPid = transport.testMenuSwitchSnapshot(authority).pid
            device.executeShellCommand("am force-stop $fixture")
            launchFixture()
            assertSwitch("Runtime value", false)
            assertNotEquals(oldPid, transport.testMenuSwitchSnapshot(authority).pid)
            readValue(7, "process-restarted")
            evidence("native-overlay-restarted.png") { device.takeScreenshot(it) }

            stage = "replace-config"
            clickSwitch("Runtime value")
            assertSwitch("Runtime value", true)
            transport.configureTestMenu(authority, items, embeddedMenu = false)
            assertSwitch("Runtime value", false)
            readValue(7, "reconfigured-restores-code")

            stage = "clear-config"
            clickSwitch("Runtime value")
            assertSwitch("Runtime value", true)
            transport.clearTestMenu(authority)
            assertTrue(transport.testMenuSwitchSnapshot(authority).enabledById.isEmpty())
            assertTrue(device.wait(Until.hasObject(By.desc("Мод: Runtime value").enabled(false)), 10_000))
            readValue(7, "cleared-restores-code")
            stage = "complete"
        } finally {
            evidence("native-overlay-hierarchy.xml") { device.dumpWindowHierarchy(it) }
            evidence("native-overlay-final.png") { device.takeScreenshot(it) }
            evidence("native-overlay-metrics.json") { it.writeText(JSONObject()
                .put("stage", stage).put("abi", abi).put("api", Build.VERSION.SDK_INT)
                .put("events", events).put("scope", "owned native fixture; explicit recipe, not automatic IL2CPP discovery")
                .toString(2)) }
            context.stopService(Intent(context, ModKitRuntimeOverlayService::class.java))
            device.executeShellCommand("pm uninstall $fixture")
        }
    }

    private fun openOverlay(sha: String) {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        device.waitForIdle()
        ModKitRuntimeOverlayService.start(context, sha)
        requireNotNull(device.wait(Until.findObject(By.desc("Открыть мод-меню ModKit")), 15_000)).click()
        launchFixture()
        assertTrue("The panel itself must be visible", device.wait(Until.hasObject(By.text("ModKit · моды")), 10_000))
    }

    private fun launchFixture() {
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(fixture))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.textStartsWith("Native value:")), 15_000))
        assertTrue(device.wait(Until.gone(By.desc("Встроенное мод-меню ModKit")), 10_000))
        assertEquals(1, device.findObjects(By.text("MK")).size)
    }

    private fun clickSwitch(label: String) {
        requireNotNull(device.wait(Until.findObject(By.desc("Мод: $label").enabled(true)), 10_000)).click()
    }

    private fun assertSwitch(label: String, enabled: Boolean) {
        val acknowledged = device.wait(
            Until.hasObject(By.desc("Мод: $label").enabled(true).checked(enabled)), 15_000)
        if (!acknowledged) {
            val node = device.findObject(By.desc("Мод: $label"))
            val actual = node?.let { "enabled=${it.isEnabled}, checked=${it.isChecked}" } ?: "missing node"
            val remote = runCatching { transport.testMenuSwitchSnapshot(authority) }.toString()
            fail("$label must be acknowledged as $enabled; UI: $actual; target: $remote")
        }
    }

    private fun readValue(expected: Int, event: String) {
        requireNotNull(device.wait(Until.findObject(By.text(Pattern.compile("read native value", Pattern.CASE_INSENSITIVE))), 10_000)).click()
        assertTrue("$event must affect native execution", device.wait(Until.hasObject(By.text("Native value: $expected")), 10_000))
        events.put(JSONObject().put("event", event).put("observedNativeValue", expected))
    }
}
