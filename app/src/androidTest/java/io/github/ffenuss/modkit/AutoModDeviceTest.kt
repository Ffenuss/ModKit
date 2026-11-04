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

    private fun obfHit() {
        val button = device.wait(Until.findObject(By.text(Pattern.compile("obf hit", Pattern.CASE_INSENSITIVE))), 10_000)
        requireNotNull(button) { "Fixture obfuscated damage button is missing" }.click()
        device.waitForIdle()
    }

    /** The fixture scrolls on smaller emulators; no UI test assumes a fixed viewport. */
    private fun fixtureText(label: String): androidx.test.uiautomator.UiObject2 {
        // Android's default Button style applies all-caps text transformation.
        // Match the visible accessibility text rather than Java setText casing.
        val selector = By.text(Pattern.compile(Pattern.quote(label), Pattern.CASE_INSENSITIVE))
        var control = device.wait(Until.findObject(selector), 1000)
        if (control != null) return control
        val center = device.displayWidth / 2
        val top = device.displayHeight / 5
        val bottom = device.displayHeight * 4 / 5
        repeat(4) {
            device.swipe(center, bottom, center, top, 25)
            control = device.wait(Until.findObject(selector), 750)
            if (control != null) return requireNotNull(control)
        }
        repeat(4) {
            device.swipe(center, top, center, bottom, 25)
            control = device.wait(Until.findObject(selector), 750)
            if (control != null) return requireNotNull(control)
        }
        evidence("fixture-control-missing.png") { device.takeScreenshot(it) }
        evidence("fixture-control-missing-hierarchy.xml") { device.dumpWindowHierarchy(it) }
        throw AssertionError("Fixture UI element not reachable: $label; screenshot and hierarchy were saved")
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
        // Android 35 may reject a background-context activity start while the
        // fixture game owns the foreground task. Use the test shell (never
        // production code) to deterministically bring our own launcher forward.
        val launcherComponent =
            android.content.ComponentName(context, MainActivity::class.java).flattenToShortString()
        val foreground = device.executeShellCommand(
            "am start -W -n $launcherComponent -f 0x14000000",
        )
        assertTrue("ModKit launcher must be foreground: $foreground",
            device.wait(Until.hasObject(By.pkg(context.packageName)), 15_000))
        val installButton = device.wait(Until.findObject(By.text("Установить")), 30_000)
        if (installButton == null) {
            // Preserve the actual foreground screen before failing: a previously
            // completed UI build may not have been restored after a new Activity.
            evidence("missing-retained-install-screen.png") { device.takeScreenshot(it) }
            evidence("missing-retained-install-hierarchy.xml") { device.dumpWindowHierarchy(it) }
        }
        assertNotNull("Retained UI build must remain installable", installButton)
        val attemptedAt = System.currentTimeMillis()
        installButton.click()
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            val status = RepackedRuntimeInstallStatusStore.status.value
            if (status.updatedAtEpochMs >= attemptedAt) {
                if (status.kind == RepackedRuntimeInstallStatusKind.SUCCESS) break
                if (status.kind == RepackedRuntimeInstallStatusKind.FAILURE) {
                    evidence("ui-install-failure-screen.png") { device.takeScreenshot(it) }
                    evidence("ui-install-failure-hierarchy.xml") { device.dumpWindowHierarchy(it) }
                    throw AssertionError("Installer reported failure: ${status.message} (session=${status.sessionId})")
                }
                device.findObject(By.text("Подтвердить установку"))?.click()
                val systemInstall = device.findObject(By.res("com.android.packageinstaller", "ok_button"))
                    ?: device.findObject(By.res("com.google.android.packageinstaller", "ok_button"))
                    ?: device.findObject(By.text("Install"))
                    ?: device.findObject(By.text("INSTALL"))
                    ?: device.findObject(By.text("Update"))
                    ?: device.findObject(By.text("UPDATE"))
                if (systemInstall != null) {
                    evidence("ui-install-confirmation-screen.png") { device.takeScreenshot(it) }
                    evidence("ui-install-confirmation-hierarchy.xml") { device.dumpWindowHierarchy(it) }
                    systemInstall.click()
                }
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

    /**
     * Structural discovery end-to-end: the obfuscated health (single-letter
     * members, no gameplay names) is confirmed by code behavior alone, patched
     * by the existing engine, and the running game must reflect the change.
     */
    @Test fun d_structuralDiscoveryPatchesTheObfuscatedHealthAlone() = runBlocking {
        val installed = io.github.ffenuss.modkit.data.InstalledAppRepository(context).find(fixturePackage)!!
        val target = AnalysisTargetDescriptor.InstalledPackage(fixturePackage, "ModKit Test Game")
        val analysis = FastArtifactIndexer.index(installed.apkFiles, signal, progress)
        val scan = DexAutoModCoordinator.scan(context, target, analysis, signal, progress)

        val matching = scan.opportunities.filter {
            it.matchedByStructure && it.className.endsWith("/ObfuscatedStats;") && it.methodName == "d"
        }
        if (matching.size != 1) {
            val dexEvidence = buildString {
                for (apk in installed.apkFiles) {
                    ZipFile(apk).use { archive ->
                        val entries = archive.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (!entry.name.endsWith(".dex")) continue
                            val dex = DexBackedDexFile(
                                Opcodes.getDefault(), archive.getInputStream(entry).use { it.readBytes() },
                            )
                            for (clazz in dex.classes) {
                                if (!clazz.type.endsWith("/ObfuscatedStats;")) continue
                                append("found class ").append(clazz.type).append(" in ").append(entry.name)
                                for (method in clazz.methods) {
                                    append("\n").append(method.name).append("()")
                                    append(method.returnType).append(": ")
                                    append(method.implementation?.instructions?.take(40)?.joinToString(" ") {
                                        it.opcode.name
                                    } ?: "no body")
                                }
                            }
                        }
                    }
                }
            }
            val obfCandidates = scan.opportunities.filter { it.className.contains("ObfuscatedStats") }
            throw AssertionError(
                "Missing unique obfuscated structural health recipe; matches=${matching.size}, " +
                    "structuralConfirmed=${scan.structuralMechanicsConfirmed}, " +
                    "structuralCandidates=${scan.structuralCandidates}, " +
                    "nearby=${obfCandidates.map { it.methodName to it.matchedByStructure }}, " +
                    "hypotheses=${scan.mechanicHypotheses.take(8)}; dex=[$dexEvidence]",
            )
        }
        val obfHealth = matching.single()
        assertEquals(DexLocalCategory.HEALTH, obfHealth.category)
        assertEquals(DexLocalAction.INT_9999, obfHealth.action)
        assertEquals(MechanicConfidence.CONFIRMED, obfHealth.confidence)
        assertTrue("Confirmed structural candidate must be selectable", obfHealth.selectable)
        assertTrue("Structural confirmation must be reported", scan.structuralMechanicsConfirmed >= 1)
        // A name-only or structure-only false positive must not be confirmed:
        // the stamina gate (h) and the distance counter (j) stay non-selectable.
        assertTrue(scan.opportunities.none {
            it.matchedByStructure && it.className.endsWith("/ObfuscatedStats;") &&
                it.methodName in setOf("e", "j", "h", "g")
        })

        val recipe = DexRecipeCatalog.create(scan).single {
            it.selectable && it.dex.any { d -> d.id == obfHealth.id }
        }
        val preparation = PatchPreparationPlan(analysis.index.artifactSha256, true,
            System.currentTimeMillis(), emptyList(), emptyList())
        val built = AutoModBuildCoordinator.build(context, target, analysis, preparation,
            listOf(recipe), signal, progress)
        assertEquals(installed.apkFiles.size, built.files.size)
        assertTrue(built.files.all { it.signature.verified })
        assertTrue(built.mutationDiffVerification.verified)

        val plan = RepackedRuntimeInstallPlanner.plan(built, signal)
        device.executeShellCommand("appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow")
        // Test-fixture cleanup only: a fresh install regardless of the prior
        // signature state left by a_/b_/c_. The production app never uninstalls.
        device.executeShellCommand("pm uninstall $fixturePackage")
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
        assertTrue("Obfuscated health must be raised by structural discovery",
            device.hasObject(By.text("OBF ALIVE | Obf HP: 9999")))
        repeat(3) { obfHit() }
        assertTrue("Patched obfuscated getter must feed the real death rule",
            device.hasObject(By.text("OBF ALIVE | Obf HP: 9999")))
        evidence("obfuscated-structural-mod.png") { device.takeScreenshot(it) }
    }

    /**
     * Local-currency discovery with evidence from real D8 instructions:
     * two-way coin write-back + exact field name -> a modifiable getter.
     * Confirm an actual increase in successful purchases, not just the HUD.
     */
    @Test fun e_localCoinBalanceAllowsMoreFixturePurchases() = runBlocking {
        launchGame()
        fixtureText("Coins: 20 | Purchases: 0")
        repeat(3) {
            fixtureText("Buy coins item").click()
            device.waitForIdle()
        }
        assertTrue(
            "Baseline should allow only two purchases from twenty coins",
            fixtureText("Coins: 0 | Purchases: 2") != null,
        )

        val installed = io.github.ffenuss.modkit.data.InstalledAppRepository(context).find(fixturePackage)!!
        val target = AnalysisTargetDescriptor.InstalledPackage(fixturePackage, "ModKit Test Game")
        val analysis = FastArtifactIndexer.index(installed.apkFiles, signal, progress)
        val scan = DexAutoModCoordinator.scan(context, target, analysis, signal, progress)
        val wallets = scan.opportunities.filter {
            it.matchedByStructure && it.className.endsWith("/WalletStats;") &&
                it.methodName == "getCoins" && it.category == DexLocalCategory.CURRENCY
        }
        assertEquals(
            "Expected exactly one confirmed local coin getter; hypotheses=${scan.mechanicHypotheses}",
            1, wallets.size,
        )
        val coins = wallets.single()
        assertEquals(MechanicConfidence.CONFIRMED, coins.confidence)
        assertEquals(DexLocalAction.INT_9999, coins.action)
        assertTrue(coins.selectable)
        val recipe = DexRecipeCatalog.create(scan).single {
            it.selectable && it.dex.any { d -> d.id == coins.id }
        }
        val preparation = PatchPreparationPlan(
            analysis.index.artifactSha256, true,
            System.currentTimeMillis(), emptyList(), emptyList(),
        )
        val built = AutoModBuildCoordinator.build(
            context, target, analysis, preparation,
            listOf(recipe), signal, progress,
        )
        assertEquals(installed.apkFiles.size, built.files.size)
        assertTrue(built.files.all { it.signature.verified })
        assertTrue(built.mutationDiffVerification.verified)

        val plan = RepackedRuntimeInstallPlanner.plan(built, signal)
        device.executeShellCommand("appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow")
        // Only our disposable fixture. Production never uninstalls user apps.
        device.executeShellCommand("pm uninstall $fixturePackage")
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        device.waitForIdle()
        val submission = AndroidRepackedRuntimeInstaller.submit(context, plan, signal)
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            val status = RepackedRuntimeInstallStatusStore.status.value
            if (status.kind == RepackedRuntimeInstallStatusKind.USER_ACTION_REQUIRED) {
                instrumentation.runOnMainSync {
                    RepackedRuntimeInstallConfirmationStore.open(context, submission.sessionId)
                }
                val systemInstall =
                    device.findObject(By.res("com.android.packageinstaller", "ok_button"))
                        ?: device.findObject(By.res("com.google.android.packageinstaller", "ok_button"))
                        ?: device.findObject(By.text("Install"))
                        ?: device.findObject(By.text("INSTALL"))
                        ?: device.findObject(By.text("Update"))
                systemInstall?.click()
            }
            if (status.kind == RepackedRuntimeInstallStatusKind.SUCCESS) break
            assertNotEquals(status.message, RepackedRuntimeInstallStatusKind.FAILURE, status.kind)
            device.waitForIdle(500)
        }
        assertEquals(RepackedRuntimeInstallStatusKind.SUCCESS, RepackedRuntimeInstallStatusStore.status.value.kind)

        launchGame()
        fixtureText("Coins: 9999 | Purchases: 0")
        repeat(3) {
            fixtureText("Buy coins item").click()
            device.waitForIdle()
        }
        assertTrue(
            "Patched getter must allow a third real transaction",
            fixtureText("Coins: 9999 | Purchases: 3") != null,
        )
        evidence("currency-structural-mod.png") { device.takeScreenshot(it) }
    }

    /**
     * New structural cooldown proof must allow skill activation after
     * replacing only a read-only on-cooldown predicate. A visible flag
     * without a changed action counter is not enough to pass.
     */
    @Test fun f_confirmedCooldownAllowsOfflineSkillExecution() = runBlocking {
        launchGame()
        fixtureText("Cooldown: 3 | Actions: 0")
        repeat(3) {
            fixtureText("Use skill").click()
            device.waitForIdle()
        }
        fixtureText("Cooldown: 3 | Actions: 0")

        val installed = io.github.ffenuss.modkit.data.InstalledAppRepository(context)
            .find(fixturePackage)!!
        val target = AnalysisTargetDescriptor.InstalledPackage(fixturePackage, "ModKit Test Game")
        val analysis = FastArtifactIndexer.index(installed.apkFiles, signal, progress)
        val scan = DexAutoModCoordinator.scan(context, target, analysis, signal, progress)
        val matches = scan.opportunities.filter {
            it.className.endsWith("/CooldownStats;") &&
                it.methodName == "isOnCooldown" &&
                it.category == DexLocalCategory.COOLDOWN &&
                it.matchedByStructure
        }
        assertEquals(
            "One confirmed cooldown predicate expected; hypotheses=${scan.mechanicHypotheses}",
            1, matches.size,
        )
        val candidate = matches.single()
        assertEquals(MechanicConfidence.CONFIRMED, candidate.confidence)
        assertEquals(DexLocalAction.FALSE, candidate.action)
        assertTrue(candidate.selectable)
        val recipe = DexRecipeCatalog.create(scan).single {
            it.selectable && it.dex.any { d -> d.id == candidate.id }
        }

        val preparation = PatchPreparationPlan(
            analysis.index.artifactSha256, true,
            System.currentTimeMillis(), emptyList(), emptyList(),
        )
        val built = AutoModBuildCoordinator.build(
            context, target, analysis, preparation,
            listOf(recipe), signal, progress,
        )
        assertEquals(installed.apkFiles.size, built.files.size)
        assertTrue(built.files.all { it.signature.verified })
        assertTrue(built.mutationDiffVerification.verified)

        val plan = RepackedRuntimeInstallPlanner.plan(built, signal)
        device.executeShellCommand("appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow")
        // The test owns this disposable fixture; production never uninstalls.
        device.executeShellCommand("pm uninstall $fixturePackage")
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        device.waitForIdle()
        val submission = AndroidRepackedRuntimeInstaller.submit(context, plan, signal)
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            val status = RepackedRuntimeInstallStatusStore.status.value
            if (status.kind == RepackedRuntimeInstallStatusKind.USER_ACTION_REQUIRED) {
                instrumentation.runOnMainSync {
                    RepackedRuntimeInstallConfirmationStore.open(context, submission.sessionId)
                }
                val systemInstall =
                    device.findObject(By.res("com.android.packageinstaller", "ok_button"))
                        ?: device.findObject(By.res("com.google.android.packageinstaller", "ok_button"))
                        ?: device.findObject(By.text("Install"))
                        ?: device.findObject(By.text("INSTALL"))
                        ?: device.findObject(By.text("Update"))
                systemInstall?.click()
            }
            if (status.kind == RepackedRuntimeInstallStatusKind.SUCCESS) break
            assertNotEquals(status.message, RepackedRuntimeInstallStatusKind.FAILURE, status.kind)
            device.waitForIdle(500)
        }
        assertEquals(
            RepackedRuntimeInstallStatusKind.SUCCESS,
            RepackedRuntimeInstallStatusStore.status.value.kind,
        )

        launchGame()
        fixtureText("Cooldown: 3 | Actions: 0")
        repeat(3) {
            fixtureText("Use skill").click()
            device.waitForIdle()
        }
        fixtureText("Cooldown: 3 | Actions: 3")
        evidence("cooldown-structural-mod.png") { device.takeScreenshot(it) }
    }

    /**
     * Speed evidence is the live displacement delta, not a matching name.
     * The float getter is deliberately named 'a' in the owned test fixture.
     */
    @Test fun g_obfuscatedSpeedGetterDoublesRealMovementDistance() = runBlocking {
        launchGame()
        fixtureText("Movement distance: 0")
        repeat(3) {
            fixtureText("Move step").click()
            device.waitForIdle()
        }
        fixtureText("Movement distance: 3")

        val installed = io.github.ffenuss.modkit.data.InstalledAppRepository(context)
            .find(fixturePackage)!!
        val target = AnalysisTargetDescriptor.InstalledPackage(fixturePackage, "ModKit Test Game")
        val analysis = FastArtifactIndexer.index(installed.apkFiles, signal, progress)
        val scan = DexAutoModCoordinator.scan(context, target, analysis, signal, progress)
        val matches = scan.opportunities.filter {
            it.matchedByStructure && it.className.endsWith("/MovementStats;") &&
                it.methodName == "a" && it.category == DexLocalCategory.MOVEMENT
        }
        assertEquals(
            "One confirmed obfuscated speed getter expected; hypotheses=${scan.mechanicHypotheses}",
            1, matches.size,
        )
        val speed = matches.single()
        assertEquals(MechanicConfidence.CONFIRMED, speed.confidence)
        assertEquals(DexLocalAction.FLOAT_2, speed.action)
        assertTrue(speed.selectable)
        val recipe = DexRecipeCatalog.create(scan).single {
            it.selectable && it.dex.any { d -> d.id == speed.id }
        }
        val preparation = PatchPreparationPlan(
            analysis.index.artifactSha256, true,
            System.currentTimeMillis(), emptyList(), emptyList(),
        )
        val built = AutoModBuildCoordinator.build(
            context, target, analysis, preparation,
            listOf(recipe), signal, progress,
        )
        assertEquals(installed.apkFiles.size, built.files.size)
        assertTrue(built.files.all { it.signature.verified })
        assertTrue(built.mutationDiffVerification.verified)

        val plan = RepackedRuntimeInstallPlanner.plan(built, signal)
        device.executeShellCommand("appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow")
        // The device test may remove only its own disposable fixture.
        device.executeShellCommand("pm uninstall $fixturePackage")
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        device.waitForIdle()
        val submission = AndroidRepackedRuntimeInstaller.submit(context, plan, signal)
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            val status = RepackedRuntimeInstallStatusStore.status.value
            if (status.kind == RepackedRuntimeInstallStatusKind.USER_ACTION_REQUIRED) {
                instrumentation.runOnMainSync {
                    RepackedRuntimeInstallConfirmationStore.open(context, submission.sessionId)
                }
                val systemInstall =
                    device.findObject(By.res("com.android.packageinstaller", "ok_button"))
                        ?: device.findObject(By.res("com.google.android.packageinstaller", "ok_button"))
                        ?: device.findObject(By.text("Install"))
                        ?: device.findObject(By.text("INSTALL"))
                        ?: device.findObject(By.text("Update"))
                systemInstall?.click()
            }
            if (status.kind == RepackedRuntimeInstallStatusKind.SUCCESS) break
            assertNotEquals(status.message, RepackedRuntimeInstallStatusKind.FAILURE, status.kind)
            device.waitForIdle(500)
        }
        assertEquals(
            RepackedRuntimeInstallStatusKind.SUCCESS,
            RepackedRuntimeInstallStatusStore.status.value.kind,
        )
        // Keep the genuinely signed build for a later positive Space selection
        // test. Do not synthesize a signer/hash from a random installed package.
        io.github.ffenuss.modkit.ui.AutoModBuildRecord(
            plan, built.builtAtEpochMs,
            listOf("Owned fixture: verified movement step"),
            built.reportFile.absolutePath,
        ).save(context)

        launchGame()
        fixtureText("Movement distance: 0")
        repeat(3) {
            fixtureText("Move step").click()
            device.waitForIdle()
        }
        fixtureText("Movement distance: 6")
        evidence("movement-structural-mod.png") { device.takeScreenshot(it) }
    }

    /**
     * Space hub must list a previously verified build profile without falsely
     * enabling a launch for an APK signed by a different certificate.
     * This uses only a disposable, local test record; no user's app is touched.
     */
    @Test fun h_spaceListsPerGameBuildButBlocksUntrustedLaunch() {
        io.github.ffenuss.modkit.space.SpaceActiveProfileStore.clear(context)
        val artifact = "d".repeat(64)
        val saved = File(context.filesDir, "space-test-build.apk")
        saved.writeBytes(byteArrayOf(1, 2, 3, 4))
        val recordFile = File(context.filesDir, "build-history/$artifact.json")
        try {
            val installPlan = RepackedRuntimeInstallPlan(
                artifactSha256 = artifact,
                packageName = fixturePackage,
                signerCertificateSha256 = setOf("e".repeat(64)),
                apks = listOf(
                    RepackedRuntimeInstallApk(
                        "base.apk", saved.absolutePath, "f".repeat(64), saved.length(),
                    ),
                ),
                totalBytes = saved.length(),
                blockers = emptyList(),
            )
            io.github.ffenuss.modkit.ui.AutoModBuildRecord(
                installPlan,
                System.currentTimeMillis() + 120_000L,
                listOf("Test-only local profile"),
                "",
            ).save(context)
            val selectedProfile = io.github.ffenuss.modkit.space.SpaceBuildProfileIndex
                .load(context).single { it.plan.packageName == fixturePackage }
            assertEquals("The test profile must be the active package entry",
                artifact, selectedProfile.plan.artifactSha256)
            val safety = io.github.ffenuss.modkit.space.SpaceInstalledPackageLauncher
                .status(context, selectedProfile)
            assertNotEquals(
                "A fabricated APK must never be launchable",
                io.github.ffenuss.modkit.space.SpaceLaunchStatus.READY, safety,
            )
            io.github.ffenuss.modkit.ui.AutoModSessionStore(context).clear()
            AnalysisManager.clearTerminalState()

            val component = android.content.ComponentName(
                context, MainActivity::class.java,
            ).flattenToShortString()
            device.executeShellCommand("am start -W -n $component -f 0x14008000")
            fixtureText("Профили моих игр").click()
            fixtureText("Space · игровые профили")
            fixtureText(fixturePackage)
            val choose = fixtureText("Выбрать активной игрой")
            choose.click()
            device.waitForIdle()
            assertNull(
                "Untrusted installed APK must never become the active Space profile",
                io.github.ffenuss.modkit.space.SpaceActiveProfileStore.read(context),
            )
            val emptyIndex = android.net.Uri.parse(
                "content://${context.packageName}.space-menu/index",
            )
            val untrustedIndex = JSONObject(
                requireNotNull(context.contentResolver.openInputStream(emptyIndex))
                    .bufferedReader().use { it.readText() },
            )
            assertEquals(0, untrustedIndex.getJSONArray("profiles").length())
            val launch = fixtureText("Запустить установленную игру")
            evidence("space-signature-guard.png") { device.takeScreenshot(it) }
            evidence("space-signature-guard-hierarchy.xml") { device.dumpWindowHierarchy(it) }
            // UiAutomator can expose an enabled Text child even when its
            // Compose Button ancestor is disabled. Verify the actual action.
            launch.click()
            device.waitForIdle()
            assertTrue(
                "A mismatched APK must not leave Space or launch the game: $safety",
                device.hasObject(By.text("Space · игровые профили")),
            )
        } finally {
            io.github.ffenuss.modkit.space.SpaceActiveProfileStore.clear(context)
            recordFile.delete()
            saved.delete()
        }
    }

    /**
     * Positive counterpart of h_: the actual signer- and SHA-verified APK
     * from g_ can be selected in Space, and the choice remains exact after
     * navigating away and back. Only the disposable owned fixture is used.
     */
    @Test fun i_spaceSelectsAndPersistsOnlyTheVerifiedFixtureBuild() = runBlocking {
        val saved = io.github.ffenuss.modkit.space.SpaceBuildProfileIndex.load(context)
            .single { it.plan.packageName == fixturePackage }
        assertEquals(
            io.github.ffenuss.modkit.space.SpaceLaunchStatus.READY,
            io.github.ffenuss.modkit.space.SpaceInstalledPackageLauncher.status(context, saved),
        )
        io.github.ffenuss.modkit.space.SpaceActiveProfileStore.clear(context)
        try {
            io.github.ffenuss.modkit.ui.AutoModSessionStore(context).clear()
            AnalysisManager.clearTerminalState()
            val component = android.content.ComponentName(
                context, MainActivity::class.java,
            ).flattenToShortString()
            device.executeShellCommand("am start -W -n $component -f 0x14008000")
            fixtureText("Профили моих игр").click()
            fixtureText("Space · игровые профили")
            fixtureText("Выбрать активной игрой").click()
            device.waitForIdle()
            fixtureText("Активный профиль: $fixturePackage")
            val chosen = io.github.ffenuss.modkit.space.SpaceActiveProfileStore.read(context)
            assertEquals(io.github.ffenuss.modkit.space.SpaceActiveProfileKey.of(saved), chosen)
            assertEquals(
                saved,
                io.github.ffenuss.modkit.space.SpaceActiveProfileSelection.resolve(
                    chosen,
                    listOf(saved to io.github.ffenuss.modkit.space.SpaceLaunchStatus.READY),
                ),
            )
            // A different version of the same package must never be selected.
            assertNull(
                io.github.ffenuss.modkit.space.SpaceActiveProfileSelection.resolve(
                    chosen, listOf(
                        saved.copy(plan = saved.plan.copy(artifactSha256 = "f".repeat(64))) to
                            io.github.ffenuss.modkit.space.SpaceLaunchStatus.READY,
                    ),
                ),
            )
            // The old host's exact /index and /profile contract accepts this
            // read-only inventory. No static recipe is published as runtime.
            val indexUri = android.net.Uri.parse(
                "content://${context.packageName}.space-menu/index",
            )
            val index = JSONObject(
                requireNotNull(context.contentResolver.openInputStream(indexUri))
                    .bufferedReader().use { it.readText() },
            )
            assertEquals(1, index.getInt("schema"))
            val entries = index.getJSONArray("profiles")
            assertEquals(1, entries.length())
            val entry = entries.getJSONObject(0)
            val profileUri = android.net.Uri.parse(
                "content://${context.packageName}.space-menu/profile/${entry.getString("file")}",
            )
            val profileBytes = requireNotNull(context.contentResolver.openInputStream(profileUri))
                .use { it.readBytes() }
            val actualHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(profileBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(entry.getString("sha256"), actualHash)
            val profile = JSONObject(String(profileBytes, Charsets.UTF_8))
            assertEquals(1, profile.getInt("schema"))
            assertEquals("none", profile.getString("backend"))
            assertEquals(fixturePackage, profile.getString("packageName"))
            assertEquals(0, profile.getJSONArray("items").length())
            assertEquals(saved.plan.apks.size, profile.getJSONArray("sources").length())
            val expected = saved.plan.apks.map { it.expectedSha256.lowercase() to it.size }.toSet()
            val actual = (0 until profile.getJSONArray("sources").length()).map {
                val source = profile.getJSONArray("sources").getJSONObject(it)
                source.getString("sha256") to source.getLong("size")
            }.toSet()
            assertEquals(expected, actual)
            evidence("space-verified-active-game.png") { device.takeScreenshot(it) }
        } finally {
            io.github.ffenuss.modkit.space.SpaceActiveProfileStore.clear(context)
            val indexUri = android.net.Uri.parse(
                "content://${context.packageName}.space-menu/index",
            )
            val cleared = JSONObject(
                requireNotNull(context.contentResolver.openInputStream(indexUri))
                    .bufferedReader().use { it.readText() },
            )
            assertEquals(0, cleared.getJSONArray("profiles").length())
        }
    }

    /**
     * The legacy Space host observes the index URI. Verify that notifications
     * follow durable preference commits, not invalid selection attempts.
     * Only the owned, signed movement fixture from g_ is used here.
     */
    @Test fun j_spaceNotifiesOnlyAfterVerifiedSelectionAndClear() = runBlocking {
        val saved = io.github.ffenuss.modkit.space.SpaceBuildProfileIndex.load(context)
            .single { it.plan.packageName == fixturePackage }
        assertEquals(
            io.github.ffenuss.modkit.space.SpaceLaunchStatus.READY,
            io.github.ffenuss.modkit.space.SpaceInstalledPackageLauncher.status(context, saved),
        )
        io.github.ffenuss.modkit.space.SpaceActiveProfileStore.clear(context)
        val indexUri = android.net.Uri.parse(
            "content://${context.packageName}.space-menu/index",
        )
        val delivered = java.util.concurrent.atomic.AtomicInteger()
        val activated = java.util.concurrent.CountDownLatch(1)
        val cleared = java.util.concurrent.CountDownLatch(1)
        val observer = object : android.database.ContentObserver(null) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                if (uri != indexUri) return
                when (delivered.incrementAndGet()) {
                    1 -> activated.countDown()
                    2 -> cleared.countDown()
                }
            }
        }
        context.contentResolver.registerContentObserver(indexUri, false, observer)
        try {
            val invalid = saved.copy(
                plan = saved.plan.copy(signerCertificateSha256 = setOf("f".repeat(64))),
            )
            assertTrue(
                "Changing the signer must prevent activation",
                runCatching {
                    io.github.ffenuss.modkit.space.SpaceActiveProfileStore.activate(
                        context, invalid,
                    )
                }.isFailure,
            )
            assertNull(io.github.ffenuss.modkit.space.SpaceActiveProfileStore.read(context))
            assertEquals("Rejected selection must not notify the host", 0, delivered.get())

            val selected = io.github.ffenuss.modkit.space.SpaceActiveProfileStore
                .activate(context, saved)
            assertEquals(
                io.github.ffenuss.modkit.space.SpaceActiveProfileKey.of(saved),
                selected,
            )
            assertTrue(
                "A committed selection must notify the host",
                activated.await(10, java.util.concurrent.TimeUnit.SECONDS),
            )
            val active = JSONObject(
                requireNotNull(context.contentResolver.openInputStream(indexUri))
                    .bufferedReader().use { it.readText() },
            )
            assertEquals(1, active.getJSONArray("profiles").length())
            val name = active.getJSONArray("profiles").getJSONObject(0).getString("file")
            val oldProfileUri = android.net.Uri.parse(
                "content://${context.packageName}.space-menu/profile/$name",
            )

            io.github.ffenuss.modkit.space.SpaceActiveProfileStore.clear(context)
            assertTrue(
                "A committed clear must notify the host",
                cleared.await(10, java.util.concurrent.TimeUnit.SECONDS),
            )
            assertNull(io.github.ffenuss.modkit.space.SpaceActiveProfileStore.read(context))
            val empty = JSONObject(
                requireNotNull(context.contentResolver.openInputStream(indexUri))
                    .bufferedReader().use { it.readText() },
            )
            assertEquals(0, empty.getJSONArray("profiles").length())
            assertTrue(
                "Cleared profiles must not remain readable via the provider",
                runCatching {
                    context.contentResolver.openInputStream(oldProfileUri)?.use { it.readBytes() }
                }.isFailure,
            )
        } finally {
            context.contentResolver.unregisterContentObserver(observer)
            io.github.ffenuss.modkit.space.SpaceActiveProfileStore.clear(context)
        }
    }

}
