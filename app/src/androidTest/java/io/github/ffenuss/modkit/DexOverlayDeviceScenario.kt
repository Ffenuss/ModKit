package io.github.ffenuss.modkit

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.ffenuss.modkit.analysis.*
import io.github.ffenuss.modkit.patch.*
import io.github.ffenuss.modkit.runtime.*
import io.github.ffenuss.modkit.ui.AutoModBuildRecord
import java.io.File
import java.security.MessageDigest
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*

/** Tests real ART execution, not a simulated DEX interpreter. Only our disposable fixture is removed. */
class DexOverlayDeviceScenario(private val instrumentation: Instrumentation, private val device: UiDevice) {
    private val context = instrumentation.targetContext
    private val fixture = "dev.modkit.fixture"
    private val authority = fixture + BinaryAndroidManifestProbeInjector.AUTHORITY_SUFFIX
    private val transport = AndroidRepackedRuntimeProbeTransport(context)
    private val signal = AtomicCancellationSignal()
    private val progress = ProgressSink { android.util.Log.i("DexOverlayTest", it.currentTask.orEmpty()) }
    private val events = JSONArray()
    private val bubble = By.desc("Открыть мод-меню ModKit")
    private val embeddedBubble = By.desc("Встроенное мод-меню ModKit")

    fun run(evidence: (String, (File) -> Unit) -> Unit, install: (RepackedRuntimeInstallPlan) -> Unit) = runBlocking {
        var stage = "build"
        try {
            val root = File(context.filesDir, "dex-overlay-validation").apply { mkdirs() }
            val source = File(root, "fixture.apk")
            instrumentation.context.assets.open("fixture.apk").use { input -> source.outputStream().use { input.copyTo(it) } }
            val analysis = FastArtifactIndexer.index(listOf(source), signal, progress)
            val scan = DexLocalPatchEngine.scanApks(listOf(source), false, signal)
            val recipes = DexRecipeCatalog.create(scan)
            val health = recipes.single { it.selectable && it.dex.any { m -> m.methodName == "getHealth" } }
            val sprint = recipes.single { it.selectable && it.dex.any { m -> m.methodName == "canSprint" } }
            val ammo = recipes.single { it.selectable && it.dex.any { m -> m.methodName == "getAmmo" && m.signature == "()J" } }
            val speed = recipes.single { it.selectable && it.dex.any { m -> m.methodName == "getRunSpeed" && m.signature == "()D" } }
            assertEquals(DexMethodBodyKind.READ_ONLY_COMPUTATION, ammo.dex.single().bodyKind)
            assertEquals(DexMethodBodyKind.READ_ONLY_COMPUTATION, speed.dex.single().bodyKind)
            assertEquals(2, health.dex.size)
            val prepared = AutoModRuntimeTestMenuCoordinator.build(context,
                AnalysisTargetDescriptor.FileUri(Uri.fromFile(source).toString(), "Owned DEX fixture"), analysis,
                PatchPreparationPlan(analysis.index.artifactSha256, false, System.currentTimeMillis(), emptyList(), emptyList()),
                signal, progress, listOf(health, sprint, ammo, speed))
            assertTrue(prepared.menu.items.all { it.mode == RepackedRuntimeTestMenuItemMode.DEX })
            assertEquals(4, prepared.menu.patchItemCount)
            val plan = RepackedRuntimeInstallPlanner.plan(prepared.build, signal)
            // A later attempt with different selections must not replace the first APK.
            val second = AutoModRuntimeTestMenuCoordinator.build(context,
                AnalysisTargetDescriptor.FileUri(Uri.fromFile(source).toString(), "Owned DEX fixture"), analysis,
                PatchPreparationPlan(analysis.index.artifactSha256, false, System.currentTimeMillis(), emptyList(), emptyList()),
                signal, progress, listOf(health))
            assertEquals(1, second.menu.patchItemCount)
            plan.apks.forEach { apk ->
                val bytes = File(apk.signedPath).readBytes()
                assertEquals(apk.expectedSha256, MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) })
                assertFalse(second.build.signedApks.any { it.signedPath == apk.signedPath })
            }
            events.put(JSONObject().put("event", "previous-build-preserved").put("observed", plan.apks.size))
            stage = "install"
            device.executeShellCommand("pm uninstall $fixture")
            install(plan)
            RepackedRuntimeProbeIdentityVerifier.verify(prepared.build, requireNotNull(transport.inspectInstalled(fixture, authority)))
            val configured = transport.configureTestMenu(authority, prepared.menu.items, embeddedMenu = false)
            assertEquals(4, configured.patchItemCount)
            assertFalse(configured.embeddedMenu)
            AutoModBuildRecord(plan, System.currentTimeMillis(), listOf(health.title, sprint.title, ammo.title, speed.title), prepared.build.reportPath,
                runtimeMenuItems = prepared.menu.items).save(context)
            assertEquals(prepared.menu.items, requireNotNull(AutoModBuildRecord.load(context, plan.artifactSha256)).runtimeMenuItems)
            device.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            device.waitForIdle()
            ModKitRuntimeOverlayService.start(context, plan.artifactSha256)
            requireNotNull(device.wait(Until.findObject(bubble), 15_000))
            launch()
            assertExternalOnly("external-menu-single")

            stage = "baseline"
            expect("ALIVE | Health: 20", "baseline-health")
            repeat(3) { action("Sprint") }
            expect("Distance: 2", "baseline-sprint-limit")
            repeat(3) { action("Take damage") }
            expect("GAME OVER | Health: 0", "baseline-death")
            action("Reset")

            stage = "wide-getters"
            expect("Ammo: 4294967298 | Speed: 1.25", "wide-original-beyond-int-range")
            toggle(ammo.title, true)
            action("Reset")
            expect("Ammo: 9999 | Speed: 1.25", "long-enabled-double-original")
            toggle(speed.title, true)
            action("Reset")
            expect("Ammo: 9999 | Speed: 2.0", "wide-both-enabled")
            toggle(ammo.title, false)
            action("Reset")
            expect("Ammo: 4294967298 | Speed: 2.0", "long-restored-double-independent")
            toggle(speed.title, false)
            action("Reset")
            expect("Ammo: 4294967298 | Speed: 1.25", "wide-original-restored")

            stage = "enable-health"
            toggle(health.title, true)
            repeat(3) { action("Take damage") }
            expect("ALIVE | Health: 9999", "health-enabled")
            evidence("dex-overlay-health-on.png") { device.takeScreenshot(it) }

            stage = "independent-sprint"
            repeat(3) { action("Sprint") }
            expect("Distance: 2", "sprint-still-original")
            toggle(sprint.title, true)
            repeat(3) { action("Sprint") }
            expect("Distance: 5", "sprint-enabled")
            toggle(sprint.title, false)
            repeat(3) { action("Sprint") }
            expect("Distance: 5", "sprint-disabled")
            expect("ALIVE | Health: 9999", "health-independent")

            stage = "reject-unknown"
            val unknown = prepared.menu.items.first().copy(id = "dex:" + "0".repeat(32))
            assertFalse(transport.setTestMenuSwitch(authority, unknown.id, true))
            assertTrue(runCatching { transport.configureTestMenu(authority, listOf(unknown)) }.isFailure)
            assertTrue(transport.testMenuSwitchSnapshot(authority).enabledById.getValue(DexRuntimeSwitchRewriter.switchId(health.id)))

            stage = "disable-health"
            toggle(health.title, false)
            action("Sprint") // refresh display without modifying health
            expect("ALIVE | Health: 9992", "off-preserves-game-data")
            action("Reset")
            repeat(3) { action("Take damage") }
            expect("GAME OVER | Health: 0", "original-death-restored")
            evidence("dex-overlay-health-off.png") { device.takeScreenshot(it) }

            stage = "process-restart"
            toggle(ammo.title, true)
            toggle(speed.title, true)
            toggle(health.title, true)
            toggle(sprint.title, true)
            val pid = transport.testMenuSwitchSnapshot(authority).pid
            device.executeShellCommand("am force-stop $fixture")
            launch()
            assertExternalOnly("external-mode-after-restart")
            openPanel()
            assertSwitch(health.title, false)
            assertSwitch(sprint.title, false)
            assertSwitch(ammo.title, false)
            assertSwitch(speed.title, false)
            device.findObject(bubble).click()
            assertNotEquals(pid, transport.testMenuSwitchSnapshot(authority).pid)
            expect("ALIVE | Health: 20", "process-restart-default-off")
            expect("Ammo: 4294967298 | Speed: 1.25", "wide-process-restart-default-off")

            stage = "reconfigure"
            toggle(health.title, true)
            transport.configureTestMenu(authority, prepared.menu.items, embeddedMenu = false)
            openPanel(); assertSwitch(health.title, false); device.findObject(bubble).click()
            action("Reset")
            expect("ALIVE | Health: 20", "reconfigured-original")

            stage = "clear"
            toggle(health.title, true)
            transport.clearTestMenu(authority)
            assertTrue(transport.testMenuSwitchSnapshot(authority).enabledById.isEmpty())
            action("Reset")
            expect("ALIVE | Health: 20", "cleared-original")

            stage = "embedded-menu-compatibility"
            context.stopService(Intent(context, ModKitRuntimeOverlayService::class.java))
            assertTrue(device.wait(Until.gone(bubble), 10_000))
            assertTrue(transport.configureTestMenu(authority, prepared.menu.items).embeddedMenu)
            assertTrue(device.wait(Until.hasObject(embeddedBubble), 10_000))
            assertEquals(1, device.findObjects(By.text("MK")).size)
            device.executeShellCommand("am force-stop $fixture")
            launch()
            assertTrue(device.wait(Until.hasObject(embeddedBubble), 10_000))
            assertTrue(transport.testMenuStatus(authority).embeddedMenu)
            events.put(JSONObject().put("event", "embedded-mode-after-restart").put("observed", 1))
            evidence("dex-embedded-menu.png") { device.takeScreenshot(it) }

            stage = "return-to-external-menu"
            assertFalse(transport.configureTestMenu(authority, prepared.menu.items, embeddedMenu = false).embeddedMenu)
            assertTrue(device.wait(Until.gone(embeddedBubble), 10_000))
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            device.waitForIdle()
            ModKitRuntimeOverlayService.start(context, plan.artifactSha256)
            requireNotNull(device.wait(Until.findObject(bubble), 15_000))
            launch()
            assertExternalOnly("returned-to-external-menu")
            toggle(health.title, true)
            action("Take damage")
            expect("ALIVE | Health: 9999", "external-menu-after-mode-change")
            toggle(health.title, false)
            stage = "complete"
        } finally {
            evidence("dex-overlay-final.png") { device.takeScreenshot(it) }
            evidence("dex-overlay-hierarchy.xml") { device.dumpWindowHierarchy(it) }
            evidence("dex-overlay-metrics.json") { it.writeText(JSONObject().put("stage", stage).put("api", Build.VERSION.SDK_INT)
                .put("events", events).put("scope", "owned DEX fixture; four automatically discovered recipes, five instrumented methods").toString(2)) }
            context.stopService(Intent(context, ModKitRuntimeOverlayService::class.java))
            device.executeShellCommand("pm uninstall $fixture")
        }
    }

    private fun launch() {
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(fixture)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.textContains("Health:")), 15_000))
    }
    private fun openPanel() {
        if (!device.hasObject(By.text("ModKit · моды"))) requireNotNull(device.wait(Until.findObject(bubble), 10_000)).click()
    }
    private fun assertExternalOnly(event: String) {
        assertTrue(device.wait(Until.hasObject(bubble), 10_000))
        assertTrue(device.wait(Until.gone(embeddedBubble), 10_000))
        assertFalse(transport.testMenuStatus(authority).embeddedMenu)
        assertEquals(1, device.findObjects(By.text("MK")).size)
        events.put(JSONObject().put("event", event).put("observed", 1))
    }
    private fun toggle(label: String, value: Boolean) {
        openPanel()
        val selector = By.desc("Мод: $label")
        val node = requireNotNull(device.wait(Until.findObject(selector.enabled(true)), 15_000))
        assertEquals(!value, node.isChecked)
        node.click()
        assertSwitch(label, value)
        device.findObject(bubble).click() // free the game controls under the panel
    }
    private fun assertSwitch(label: String, value: Boolean) {
        assertTrue("DEX switch $label must be acknowledged as $value",
            device.wait(Until.hasObject(By.desc("Мод: $label").enabled(true).checked(value)), 15_000))
    }
    private fun action(text: String) {
        requireNotNull(device.wait(Until.findObject(By.text(Pattern.compile(Pattern.quote(text), Pattern.CASE_INSENSITIVE))), 10_000)).click()
        device.waitForIdle()
    }
    private fun expect(text: String, event: String) {
        assertTrue("$event: $text", device.wait(Until.hasObject(By.text(text)), 10_000))
        events.put(JSONObject().put("event", event).put("observed", text))
    }
}
