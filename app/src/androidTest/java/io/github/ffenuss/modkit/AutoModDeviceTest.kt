package io.github.ffenuss.modkit

import android.content.Intent
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import java.util.zip.ZipFile
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction
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
        assertTrue("Evidence must not be empty: $name", temporary.length() > 0)
        // Public Downloads survive UTP uninstalling the target app after the run.
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (name.endsWith(".png")) "image/png" else "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ModKit-validation")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            requireNotNull(resolver.openOutputStream(uri)).use { output -> temporary.inputStream().use { it.copyTo(output) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val size = requireNotNull(resolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)).use {
                assertTrue(it.moveToFirst()); it.getLong(0)
            }
            assertEquals("Evidence must survive test-app cleanup", temporary.length(), size)
        } else error("This emulator evidence suite requires API 29 or newer")
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
        // Request from the initial ungranted state. Revoking an already granted
        // app-op can kill the instrumented process on newer Android versions.
        assertFalse(context.packageManager.canRequestPackageInstalls())
        requireNotNull(device.wait(Until.findObject(By.text("Установить")), 15_000)).click()
        assertTrue("Install button must open unknown-source settings",
            device.wait(Until.hasObject(By.pkg("com.android.settings")), 15_000))
        val permissionSwitch = device.wait(Until.findObject(By.checkable(true)), 10_000)
        assertNotNull("Android must expose the install permission switch", permissionSwitch)
        if (!permissionSwitch.isChecked) permissionSwitch.click()
        evidence("install-permission.png") { device.takeScreenshot(it) }
        device.pressBack()
        assertTrue("Returning from settings must continue to the actual certificate check",
            device.wait(Until.hasObject(By.textContains("Установленная версия подписана другим ключом")), 15_000))
        evidence("certificate-conflict.png") { device.takeScreenshot(it) }
    }

    @Test fun b_discoversRewritesSignsInstallsAndChangesTheRunningGame() = runBlocking {
        launchGame()
        assertTrue(device.hasObject(By.text("ALIVE | Health: 20")))
        repeat(3) {
            device.findObject(By.text(Pattern.compile("sprint", Pattern.CASE_INSENSITIVE))).click()
            device.waitForIdle()
        }
        assertTrue("Unmodified stamina gate must stop the third sprint", device.hasObject(By.text("Distance: 2")))
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
        val sprint = DexRecipeCatalog.create(scan).single { it.selectable && it.dex.any { d -> d.methodName == "canSprint" } }
        assertEquals(DexMethodBodyKind.READ_ONLY_COMPUTATION, sprint.dex.single().bodyKind)
        val preparation = PatchPreparationPlan(analysis.index.artifactSha256, true,
            System.currentTimeMillis(), emptyList(), emptyList())
        val built = AutoModBuildCoordinator.build(context, target, analysis, preparation,
            listOf(health, sprint), signal, progress)
        assertEquals(installed.apkFiles.size, built.files.size)
        assertTrue(built.files.all { it.signature.verified })
        assertTrue(built.mutationDiffVerification.verified)
        // A patched obfuscated getter no longer reads a named field. Re-running
        // semantic discovery is not a byte-level oracle: inspect exact identities.
        health.dex.forEach { selected ->
            val apk = built.files.single { it.file.name == analysis.index.sources[selected.apkIndex].displayName }.file
            val bytes = ZipFile(apk).use { zip -> zip.getInputStream(zip.getEntry(selected.dexEntry)).use { it.readBytes() } }
            val dex = DexBackedDexFile(Opcodes.getDefault(), bytes)
            val method = dex.classes.single { it.type == selected.className }.methods.single {
                it.name == selected.methodName && it.parameterTypes.isEmpty() && "()" + it.returnType == selected.signature
            }
            assertEquals(DexMethodBodyKind.CONSTANT_RETURN, DexMethodBodyInspector.inspect(method).kind)
            assertEquals(9999, (method.implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral)
        }
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
        repeat(3) {
            device.findObject(By.text(Pattern.compile("sprint", Pattern.CASE_INSENSITIVE))).click()
            device.waitForIdle()
        }
        assertTrue("The patched conditional getter must allow sprinting with empty stamina", device.hasObject(By.text("Distance: 3")))
        evidence("modified-game.png") { device.takeScreenshot(it) }
        evidence("metrics.json") { file -> file.writeText(JSONObject()
            .put("device", android.os.Build.MODEL).put("api", android.os.Build.VERSION.SDK_INT)
            .put("methodsExamined", scan.methodsExamined).put("candidates", scan.opportunities.size)
            .put("selectableMethods", scan.opportunities.count { it.selectable })
            .put("selectedRecipes", 2).put("patchedMethods", health.dex.size + sprint.dex.size)
            .put("signedApks", built.files.size).put("runtimeConfirmedRecipes", 2)
            .put("baselineAfterThreeSprints", 2).put("modifiedAfterThreeSprints", 3)
            .put("baselineAfterThreeHits", "GAME OVER | Health: 0")
            .put("modifiedAfterThreeHits", "ALIVE | Health: 9999")
            .put("outputSha256", org.json.JSONArray(built.files.map { it.sha256 })).toString(2)) }
    }

    @Test fun c_installButtonInstallsTheUiBuildAfterTheOriginalConflictIsResolved() {
        // The original certificate conflict was checked in b. The app produced by a
        // uses the same persistent ModKit key and can now update our owned fixture.
        assertTrue(context.packageManager.canRequestPackageInstalls())
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val installButton = device.wait(Until.findObject(By.text("Установить")), 15_000)
        assertNotNull("Retained UI build must remain installable", installButton)
        val attemptedAt = System.currentTimeMillis()
        installButton.click()
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            val status = RepackedRuntimeInstallStatusStore.status.value
            if (status.updatedAtEpochMs >= attemptedAt) {
                if (status.kind == RepackedRuntimeInstallStatusKind.SUCCESS) break
                assertNotEquals(status.message, RepackedRuntimeInstallStatusKind.FAILURE, status.kind)
                device.findObject(By.text("Подтвердить установку"))?.click()
                val systemInstall = device.findObject(By.res("com.android.packageinstaller", "ok_button"))
                    ?: device.findObject(By.res("com.google.android.packageinstaller", "ok_button"))
                    ?: device.findObject(By.text("Install"))
                    ?: device.findObject(By.text("INSTALL"))
                    ?: device.findObject(By.text("Update"))
                    ?: device.findObject(By.text("UPDATE"))
                systemInstall?.click()
            }
            device.wait(Until.hasObject(By.text("Приложение установлено")), 500)
        }
        val completed = RepackedRuntimeInstallStatusStore.status.value
        evidence("ui-install-state.png") { device.takeScreenshot(it) }
        evidence("ui-install-hierarchy.xml") { device.dumpWindowHierarchy(it) }
        assertTrue("A new installation must complete from the retained UI build", completed.updatedAtEpochMs >= attemptedAt)
        assertEquals(RepackedRuntimeInstallStatusKind.SUCCESS, completed.kind)
        launchGame()
        repeat(3) { hit() }
        assertTrue("The APK selected and installed through the UI must change gameplay",
            device.hasObject(By.text("ALIVE | Health: 9999")))
        evidence("ui-installed-game.png") { device.takeScreenshot(it) }
    }
}
