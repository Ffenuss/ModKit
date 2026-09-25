package io.github.ffenuss.modkit

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.ffenuss.modkit.analysis.*
import io.github.ffenuss.modkit.patch.*
import io.github.ffenuss.modkit.runtime.*
import java.io.File
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import org.junit.runner.RunWith

/** The only package removed here is our disposable testgame, never a user's application. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AutoModDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val signal = AtomicCancellationSignal()
    private val progress = ProgressSink { android.util.Log.i("ModKitDeviceTest", it.currentTask.orEmpty()) }
    private val fixturePackage = "dev.modkit.fixture"

    private fun evidence(name: String, write: (File) -> Unit) {
        val temporary = File(context.cacheDir, name)
        write(temporary)
        device.executeShellCommand("mkdir -p /data/local/tmp/modkit-device-validation")
        device.executeShellCommand("run-as ${context.packageName} cat ${temporary.absolutePath} > /data/local/tmp/modkit-device-validation/$name")
    }

    private fun hit() {
        val button = device.wait(Until.findObject(By.text(Pattern.compile("take damage", Pattern.CASE_INSENSITIVE))), 10_000)
        requireNotNull(button) { "Fixture damage button is missing" }.click()
        device.waitForIdle()
    }

    private fun launchGame() {
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(fixturePackage))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue("Fixture must launch", device.wait(Until.hasObject(By.textContains("Health:")), 15_000))
    }

    @Test fun a_simpleInterfaceSelectsAndBuildsWithoutExpertTools() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("Выбрать игру")), 15_000))
        evidence("home.png") { device.takeScreenshot(it) }
        device.findObject(By.text("Выбрать игру")).click()
        assertTrue(device.wait(Until.hasObject(By.text("ModKit Test Game")), 15_000))
        device.findObject(By.text("Анализ")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Настройте свой мод")), 90_000))
        val health = device.wait(Until.findObject(By.text("Здоровье · значение 9999")), 90_000)
        assertNotNull("Actual selectable recipe must appear", health)
        evidence("modifications.png") { device.takeScreenshot(it) }
        health.click()
        val build = device.wait(Until.findObject(By.text("Создать мод · 1")), 5_000)
        assertNotNull(build)
        build.click()
        assertTrue("Build must reach result screen", device.wait(Until.hasObject(By.text("Сборка создана")), 120_000))
        assertTrue(device.wait(Until.hasObject(By.text("Установить")), 30_000))
        evidence("result.png") { device.takeScreenshot(it) }
        // Rotation must retain the successful build and its install action.
        device.setOrientationLeft()
        assertTrue(device.wait(Until.hasObject(By.text("Установить")), 15_000))
        device.setOrientationNatural()
        device.unfreezeRotation()
    }

    @Test fun b_discoversRewritesSignsInstallsAndChangesTheRunningGame() = runBlocking {
        launchGame()
        assertTrue(device.hasObject(By.text("ALIVE | Health: 20")))
        repeat(3) { hit() }
        assertTrue("Unmodified death rule must execute", device.hasObject(By.text("GAME OVER | Health: 0")))

        val installed = io.github.ffenuss.modkit.data.InstalledAppRepository(context).find(fixturePackage)!!
        val target = AnalysisTargetDescriptor.InstalledPackage(fixturePackage, "ModKit Test Game")
        val analysis = FastArtifactIndexer.index(installed.apkFiles, signal, progress)
        val scan = DexAutoModCoordinator.scan(context, target, analysis, signal, progress)
        val health = DexRecipeCatalog.create(scan).single {
            it.selectable && it.dex.any { d -> d.methodName == "getHealth" }
        }
        assertEquals("Two getters of one field are one choice", 2, health.dex.size)
        assertTrue("Obfuscated getter found through actual field read", health.dex.any { it.methodName == "a" && it.matchedByField })
        assertTrue("Side-effecting namesake must be blocked", scan.opportunities.any {
            it.className.endsWith("/AuditStats;") && !it.selectable
        })
        val preparation = PatchPreparationPlan(analysis.index.artifactSha256, true,
            System.currentTimeMillis(), emptyList(), emptyList())
        val built = AutoModBuildCoordinator.build(context, target, analysis, preparation,
            listOf(health), signal, progress)
        assertEquals(installed.apkFiles.size, built.files.size)
        assertTrue(built.files.all { it.signature.verified })
        assertTrue(built.mutationDiffVerification.verified)
        val signedScan = DexLocalPatchEngine.scanApks(built.files.map { it.file }, false, signal)
        val patched = signedScan.opportunities.filter { it.id in health.dex.map { d -> d.id } }
        assertEquals(health.dex.size, patched.size)
        assertTrue(patched.all { it.bodyKind == DexMethodBodyKind.CONSTANT_RETURN })
        val plan = RepackedRuntimeInstallPlanner.plan(built, signal)

        device.executeShellCommand("appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow")
        assertEquals(RepackedRuntimeInstallReadinessState.INSTALLED_SIGNATURE_CONFLICT,
            AndroidRepackedRuntimeInstaller.inspectReadiness(context, plan).state)
        // The production app never uninstalls the original. This is test-fixture cleanup only.
        assertTrue(device.executeShellCommand("pm uninstall $fixturePackage").contains("Success"))
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        device.waitForIdle()
        val submission = AndroidRepackedRuntimeInstaller.submit(context, plan, signal)
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val status = RepackedRuntimeInstallStatusStore.status.value
            if (status.kind == RepackedRuntimeInstallStatusKind.USER_ACTION_REQUIRED) {
                instrumentation.runOnMainSync {
                    RepackedRuntimeInstallConfirmationStore.open(context, submission.sessionId)
                }
                val install = device.wait(Until.findObject(By.res("com.android.packageinstaller", "ok_button")), 1500)
                    ?: device.findObject(By.res("com.google.android.packageinstaller", "ok_button"))
                    ?: device.findObject(By.text("Install"))
                    ?: device.findObject(By.text("INSTALL"))
                install?.click()
            }
            if (status.kind == RepackedRuntimeInstallStatusKind.SUCCESS) break
            assertNotEquals(status.message, RepackedRuntimeInstallStatusKind.FAILURE, status.kind)
            device.waitForIdle(500)
        }
        assertEquals(RepackedRuntimeInstallStatusKind.SUCCESS, RepackedRuntimeInstallStatusStore.status.value.kind)
        launchGame()
        assertTrue(device.hasObject(By.text("ALIVE | Health: 9999")))
        repeat(3) { hit() }
        assertTrue("Patched getter must feed the real death rule", device.hasObject(By.text("ALIVE | Health: 9999")))
        evidence("modified-game.png") { device.takeScreenshot(it) }
        evidence("metrics.json") { file -> file.writeText(JSONObject()
            .put("device", android.os.Build.MODEL).put("api", android.os.Build.VERSION.SDK_INT)
            .put("methodsExamined", scan.methodsExamined).put("candidates", scan.opportunities.size)
            .put("selectableMethods", scan.opportunities.count { it.selectable })
            .put("selectedRecipes", 1).put("patchedMethods", health.dex.size)
            .put("signedApks", built.files.size).put("runtimeConfirmedRecipes", 1)
            .put("baselineAfterThreeHits", "GAME OVER | Health: 0")
            .put("modifiedAfterThreeHits", "ALIVE | Health: 9999")
            .put("outputSha256", org.json.JSONArray(built.files.map { it.sha256 })).toString(2)) }
    }
}
