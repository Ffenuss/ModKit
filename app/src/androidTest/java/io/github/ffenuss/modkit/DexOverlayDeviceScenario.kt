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
import java.util.zip.ZipFile
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
            evidence("dex-owned-fixture.apk") { source.copyTo(it, overwrite = true) }
            stage = "fixture-switch-opcodes"
            ZipFile(source).use { archive ->
                val classes = archive.entries().asSequence().filter { it.name.matches(Regex("classes(?:[0-9]+)?\\.dex")) }
                    .flatMap { entry -> org.jf.dexlib2.dexbacked.DexBackedDexFile(null,
                        archive.getInputStream(entry).use { it.readBytes() }).classes.asSequence() }.toList()
                val methods = requireNotNull(classes.singleOrNull { it.type == "Ldev/modkit/fixture/PlayerStats;" }) {
                    "PlayerStats missing: ${classes.map { it.type }}"
                }.methods.toList()
                for ((name, opcode) in listOf("getAmmo" to org.jf.dexlib2.Opcode.PACKED_SWITCH,
                        "getRunSpeed" to org.jf.dexlib2.Opcode.SPARSE_SWITCH)) {
                    assertTrue("Owned fixture must execute $opcode", requireNotNull(methods.singleOrNull { it.name == name }) {
                        "Missing $name: ${methods.map { it.name }}"
                    }
                        .implementation!!.instructions.any { it.opcode == opcode })
                }
            }
            stage = "fixture-pure-math-calls"
            ZipFile(source).use { archive ->
                val methods = archive.entries().asSequence().filter { it.name.matches(Regex("classes(?:[0-9]+)?\\.dex")) }
                    .flatMap { entry -> org.jf.dexlib2.dexbacked.DexBackedDexFile(null,
                        archive.getInputStream(entry).use { it.readBytes() }).classes.asSequence() }
                    .single { it.type == "Ldev/modkit/fixture/PlayerStats;" }.methods.toList()
                val expected = listOf("getEnergy" to "min(II)I", "getMaxHealth" to "max(II)I",
                    "getMagazineSize" to "min(II)I", "getAmmo" to "max(JJ)J",
                    "getRunSpeed" to "abs(D)D", "canSprint" to "min(FF)F",
                    "getEnergy" to "round(F)I", "getAmmo" to "round(D)J", "getRunSpeed" to "sqrt(D)D",
                    "getRunSpeed" to "floor(D)D", "getRunSpeed" to "ceil(D)D")
                expected.forEach { (name, prototype) ->
                    val calls = methods.single { it.name == name }.implementation!!.instructions.mapNotNull { instruction ->
                        ((instruction as? org.jf.dexlib2.iface.instruction.ReferenceInstruction)?.reference
                            as? org.jf.dexlib2.iface.reference.MethodReference)?.takeIf { it.definingClass == "Ljava/lang/Math;" }
                    }
                    assertTrue("Owned fixture must execute Math.$prototype", calls.any {
                        it.name + "(" + it.parameterTypes.joinToString("") + ")" + it.returnType == prototype
                    })
                }
                events.put(JSONObject().put("event", "pure-math-fixture-verified").put("observed", expected.size))
            }
            val analysis = FastArtifactIndexer.index(listOf(source), signal, progress)
            val scan = DexLocalPatchEngine.scanApks(listOf(source), false, signal)
            val recipes = DexRecipeCatalog.create(scan)
            events.put(JSONObject().put("event", "discovered-recipes").put("observed",
                recipes.joinToString("; ") { "${it.dex.map { m -> m.methodName + m.signature }}: ${it.blocker ?: "ready"}" })
                .put("warnings", scan.warnings.joinToString("; ")))
            stage = "discover-health"
            val health = recipes.single { it.selectable && it.dex.any { m -> m.methodName == "getHealth" } }
            stage = "discover-sprint"
            val sprint = recipes.single { it.selectable && it.dex.any { m -> m.methodName == "canSprint" } }
            stage = "discover-ammo"
            val ammo = recipes.single { it.selectable && it.dex.any { m -> m.methodName == "getAmmo" && m.signature == "(JI)J" } }
            stage = "discover-speed"
            val speed = recipes.single { it.selectable && it.dex.any { m -> m.methodName == "getRunSpeed" && m.signature == "(JDJI)D" } }
            assertEquals(DexMethodBodyKind.READ_ONLY_COMPUTATION, ammo.dex.single().bodyKind)
            assertEquals(DexMethodBodyKind.READ_ONLY_COMPUTATION, speed.dex.single().bodyKind)
            assertEquals(2, health.dex.size)
            stage = "discover-narrow-results"
            val energy = recipes.single { it.selectable && it.dex.any { m -> m.methodName == "getEnergy" && m.signature == "()B" } }
            val maxHealth = recipes.single { it.selectable && it.dex.any { m -> m.methodName == "getMaxHealth" && m.signature == "()S" } }
            val magazine = recipes.single { it.selectable && it.dex.any { m -> m.methodName == "getMagazineSize" && m.signature == "()C" } }
            assertTrue(energy.title.contains("значение 127"))
            val baseRecipes = listOf(health, sprint, ammo, speed)
            val narrowRecipes = listOf(energy, maxHealth, magazine)
            stage = "build"
            val prepared = AutoModRuntimeTestMenuCoordinator.build(context,
                AnalysisTargetDescriptor.FileUri(Uri.fromFile(source).toString(), "Owned DEX fixture"), analysis,
                PatchPreparationPlan(analysis.index.artifactSha256, false, System.currentTimeMillis(), emptyList(), emptyList()),
                signal, progress, baseRecipes + narrowRecipes)
            assertTrue(prepared.menu.items.all { it.mode == RepackedRuntimeTestMenuItemMode.DEX })
            assertEquals(7, prepared.menu.patchItemCount)
            val baseItems = prepared.menu.items.filter { item -> baseRecipes.any { DexRuntimeSwitchRewriter.switchId(it.id) == item.id } }
            val narrowItems = prepared.menu.items.filter { item -> narrowRecipes.any { DexRuntimeSwitchRewriter.switchId(it.id) == item.id } }
            assertEquals(4, baseItems.size)
            assertEquals(3, narrowItems.size)
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
            val configured = transport.configureTestMenu(authority, baseItems, embeddedMenu = false)
            assertEquals(4, configured.patchItemCount)
            assertFalse(configured.embeddedMenu)
            AutoModBuildRecord(plan, System.currentTimeMillis(), listOf(health.title, sprint.title, ammo.title, speed.title), prepared.build.reportPath,
                runtimeMenuItems = baseItems).save(context)
            assertEquals(baseItems, requireNotNull(AutoModBuildRecord.load(context, plan.artifactSha256)).runtimeMenuItems)
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
            for (mode in 1..3) {
                action("Next mode")
                expect("Ammo: ${4294967298L + mode} | Speed: ${1.25 + mode}", "switch-original-case-$mode")
            }
            action("Reset")
            toggle(ammo.title, true)
            action("Reset")
            expect("Ammo: 9999 | Speed: 1.25", "long-enabled-double-original")
            toggle(speed.title, true)
            action("Reset")
            expect("Ammo: 9999 | Speed: 2.0", "wide-both-enabled")
            for (mode in 1..3) {
                action("Next mode")
                expect("Ammo: 9999 | Speed: 2.0", "switch-both-enabled-case-$mode")
            }
            action("Reset")
            toggle(ammo.title, false)
            action("Reset")
            expect("Ammo: 4294967298 | Speed: 2.0", "long-restored-double-independent")
            toggle(speed.title, false)
            action("Reset")
            expect("Ammo: 4294967298 | Speed: 1.25", "wide-original-restored")
            for (mode in 1..3) {
                action("Next mode")
                expect("Ammo: ${4294967298L + mode} | Speed: ${1.25 + mode}", "switch-restored-case-$mode")
            }
            action("Reset")

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
            val unknown = baseItems.first().copy(id = "dex:" + "0".repeat(32))
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
            closePanel()
            assertNotEquals(pid, transport.testMenuSwitchSnapshot(authority).pid)
            expect("ALIVE | Health: 20", "process-restart-default-off")
            expect("Ammo: 4294967298 | Speed: 1.25", "wide-process-restart-default-off")

            stage = "reconfigure"
            toggle(health.title, true)
            transport.configureTestMenu(authority, baseItems, embeddedMenu = false)
            openPanel(); assertSwitch(health.title, false); closePanel()
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
            assertTrue(transport.configureTestMenu(authority, baseItems).embeddedMenu)
            assertTrue(device.wait(Until.hasObject(embeddedBubble), 10_000))
            assertEquals(1, device.findObjects(By.text("MK")).size)
            device.executeShellCommand("am force-stop $fixture")
            launch()
            assertTrue(device.wait(Until.hasObject(embeddedBubble), 10_000))
            assertTrue(transport.testMenuStatus(authority).embeddedMenu)
            events.put(JSONObject().put("event", "embedded-mode-after-restart").put("observed", 1))
            evidence("dex-embedded-menu.png") { device.takeScreenshot(it) }

            stage = "return-to-external-menu"
            assertFalse(transport.configureTestMenu(authority, baseItems, embeddedMenu = false).embeddedMenu)
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
            stage = "narrow-results"
            context.stopService(Intent(context, ModKitRuntimeOverlayService::class.java))
            assertTrue(device.wait(Until.gone(bubble), 10_000))
            assertEquals(3, transport.configureTestMenu(authority, narrowItems, embeddedMenu = false).patchItemCount)
            AutoModBuildRecord(plan, System.currentTimeMillis(), narrowRecipes.map { it.title }, prepared.build.reportPath,
                runtimeMenuItems = narrowItems).save(context)
            ModKitRuntimeOverlayService.start(context, plan.artifactSha256)
            requireNotNull(device.wait(Until.findObject(bubble), 15_000))
            launch()
            action("Reset")
            expect("Energy: -7 | Max health: -300 | Magazine: 50000", "narrow-original-signed-and-unsigned")
            toggle(energy.title, true)
            action("Reset")
            expect("Energy: 127 | Max health: -300 | Magazine: 50000", "byte-on-bounded-independent")
            toggle(maxHealth.title, true)
            action("Reset")
            expect("Energy: 127 | Max health: 9999 | Magazine: 50000", "short-on-independent")
            toggle(magazine.title, true)
            action("Reset")
            expect("Energy: 127 | Max health: 9999 | Magazine: 9999", "char-on-independent")
            toggle(energy.title, false)
            action("Reset")
            expect("Energy: -7 | Max health: 9999 | Magazine: 9999", "byte-off-restores-sign")
            toggle(maxHealth.title, false)
            action("Reset")
            expect("Energy: -7 | Max health: -300 | Magazine: 9999", "short-off-restores-sign")
            toggle(magazine.title, false)
            action("Reset")
            expect("Energy: -7 | Max health: -300 | Magazine: 50000", "char-off-restores-unsigned")
            toggle(energy.title, true)
            toggle(maxHealth.title, true)
            toggle(magazine.title, true)
            device.executeShellCommand("am force-stop $fixture")
            launch()
            expect("Energy: -7 | Max health: -300 | Magazine: 50000", "narrow-process-restart-default-off")
            openPanel()
            narrowRecipes.forEach { assertSwitch(it.title, false) }
            closePanel()
            stage = "complete"
        } finally {
            evidence("dex-overlay-final.png") { device.takeScreenshot(it) }
            evidence("dex-overlay-hierarchy.xml") { device.dumpWindowHierarchy(it) }
            evidence("dex-overlay-metrics.json") { it.writeText(JSONObject().put("stage", stage).put("api", Build.VERSION.SDK_INT)
                .put("events", events).put("scope", "owned DEX fixture; seven automatically discovered recipes, eight instrumented methods; exact Math int/long/float/double calls").toString(2)) }
            context.stopService(Intent(context, ModKitRuntimeOverlayService::class.java))
            device.executeShellCommand("pm uninstall $fixture")
        }
    }

    private fun launch() {
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(fixture)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.textContains("Health:")), 15_000))
    }
    private fun clearAccessibilityCache() {
        // A resized/replaced overlay can remain visible while UiAutomation's
        // cached window list omits it (captured on API29 and API35). Refresh
        // observations only: never repeat a click or assume the requested state.
        // API34 exposes clearCache; on older APIs AOSP setServiceInfo clears the
        // connection cache before resending the unchanged service configuration.
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        else automation.serviceInfo = automation.serviceInfo
    }
    private fun openPanel() {
        clearAccessibilityCache()
        if (!device.hasObject(By.text("ModKit · моды"))) requireNotNull(device.wait(Until.findObject(bubble), 10_000)).click()
        clearAccessibilityCache()
        assertTrue("DEX panel must open", device.wait(Until.hasObject(By.text("ModKit · моды")), 10_000))
    }
    private fun closePanel() {
        clearAccessibilityCache()
        // Reconfiguration and acknowledged switches announce a new subtree.
        // Android 29 can briefly omit a visible bubble from accessibility lookup.
        requireNotNull(device.wait(Until.findObject(bubble), 10_000)) {
            "MK button must remain available after the switch state settles"
        }.click()
        clearAccessibilityCache()
        assertTrue("DEX panel must collapse", device.wait(Until.gone(By.text("ModKit · моды")), 10_000))
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
        closePanel() // free the game controls under the panel
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
