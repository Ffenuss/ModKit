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
                try {
                    device.findObject(By.text("Подтвердить установку"))?.click()
                } catch (_: androidx.test.uiautomator.StaleObjectException) {
                    // The install UI can be recomposed between lookup and tap.
                    // Retry on the next device polling iteration.
                }
                val systemInstall = device.findObject(By.res("com.android.packageinstaller", "ok_button"))
                    ?: device.findObject(By.res("com.google.android.packageinstaller", "ok_button"))
                    ?: device.findObject(By.text("Install"))
                    ?: device.findObject(By.text("INSTALL"))
                    ?: device.findObject(By.text("Update"))
                    ?: device.findObject(By.text("UPDATE"))
                if (systemInstall != null) {
                    evidence("ui-install-confirmation-screen.png") { device.takeScreenshot(it) }
                    evidence("ui-install-confirmation-hierarchy.xml") { device.dumpWindowHierarchy(it) }
                    try {
                        systemInstall.click()
                    } catch (_: androidx.test.uiautomator.StaleObjectException) {
                        // System PackageInstaller often replaces its button
                        // during state transition; the loop will look up anew.
                    }
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

    /**
     * Companion integration: both apps are built with the same test signer.
     * The companion must independently verify the signed fixture and observe
     * withdrawal of the active profile without retaining an executable cache.
     */
    @Test fun k_spaceCompanionVerifiesAndRevokesActiveFixture() = runBlocking {
        val saved = io.github.ffenuss.modkit.space.SpaceBuildProfileIndex.load(context)
            .single { it.plan.packageName == fixturePackage }
        assertEquals(
            io.github.ffenuss.modkit.space.SpaceLaunchStatus.READY,
            io.github.ffenuss.modkit.space.SpaceInstalledPackageLauncher.status(context, saved),
        )
        assertTrue(
            "The CI-installed companion must have the same signer as ModKit",
            io.github.ffenuss.modkit.space.SpaceTrustedHost.companionInstalled(context),
        )
        io.github.ffenuss.modkit.space.SpaceActiveProfileStore.clear(context)
        try {
            io.github.ffenuss.modkit.space.SpaceActiveProfileStore.activate(context, saved)
            io.github.ffenuss.modkit.space.SpaceTrustedHost.open(context)
            assertNotNull(
                "The independently built companion must verify the actual installed APK set",
                device.wait(Until.findObject(By.text("Профиль подтверждён: $fixturePackage")), 20_000),
            )
            io.github.ffenuss.modkit.space.SpaceActiveProfileStore.clear(context)
            assertNotNull(
                "A cleared active game must disappear from the companion on notification",
                device.wait(Until.findObject(By.text("Нет активного профиля в ModKit.")), 20_000),
            )
        } finally {
            io.github.ffenuss.modkit.space.SpaceActiveProfileStore.clear(context)
        }
    }

    /**
     * The new schema-2 catalog is independent of the single legacy selection.
     * Only the repository-owned signed movement fixture is installed/launched.
     */
    @Test fun l_spaceCatalogWorksWithoutActiveSelection() = runBlocking {
        val saved = io.github.ffenuss.modkit.space.SpaceBuildProfileIndex.load(context)
            .single { it.plan.packageName == fixturePackage }
        assertEquals(
            io.github.ffenuss.modkit.space.SpaceLaunchStatus.READY,
            io.github.ffenuss.modkit.space.SpaceInstalledPackageLauncher.status(context, saved),
        )
        io.github.ffenuss.modkit.space.SpaceActiveProfileStore.clear(context)
        val catalogUri = android.net.Uri.parse(
            "content://${context.packageName}.space-menu/catalog",
        )
        val catalog = JSONObject(
            requireNotNull(context.contentResolver.openInputStream(catalogUri))
                .bufferedReader().use { it.readText() },
        )
        assertEquals(2, catalog.getInt("schema"))
        assertEquals("none", catalog.getString("backend"))
        val entries = catalog.getJSONArray("profiles")
        assertEquals(1, entries.length())
        val entry = entries.getJSONObject(0)
        assertEquals(fixturePackage, entry.getString("packageName"))
        assertEquals(saved.plan.artifactSha256, entry.getString("artifactSha256"))
        assertEquals(saved.plan.signerCertificateSha256, (0 until
            entry.getJSONArray("signers").length()).map {
                entry.getJSONArray("signers").getString(it)
            }.toSet())
        assertEquals(saved.plan.apks.size, entry.getJSONArray("sources").length())

        // No legacy active game is required to use the independent catalog.
        val index = JSONObject(
            requireNotNull(context.contentResolver.openInputStream(
                android.net.Uri.parse("content://${context.packageName}.space-menu/index"),
            )).bufferedReader().use { it.readText() },
        )
        assertEquals(0, index.getJSONArray("profiles").length())
        io.github.ffenuss.modkit.space.SpaceTrustedHost.open(context)
        assertNotNull(
            "Companion must display the independent catalog",
            device.wait(Until.findObject(By.text("Проверить и запустить: $fixturePackage")), 20_000),
        )
        assertNotNull(
            "Legacy active selection remains empty",
            device.wait(Until.findObject(By.text("Нет активного профиля в ModKit.")), 20_000),
        )
        fixtureText("Проверить и запустить: $fixturePackage").click()
        assertNotNull(
            "The per-game button must verify then launch only the owned fixture",
            device.wait(Until.findObject(By.textContains("Movement distance:")), 20_000),
        )
    }

    /**
     * The opt-in overlay is an actual Android TYPE_APPLICATION_OVERLAY window
     * over the owned fixture, never an executable mod or virtual guest.
     * CI grants the special app-op explicitly for this disposable emulator.
     */
    @Test fun m_spaceOverlayShellFollowsVerifiedFixture() = runBlocking {
        val saved = io.github.ffenuss.modkit.space.SpaceBuildProfileIndex.load(context)
            .single { it.plan.packageName == fixturePackage }
        assertEquals(
            io.github.ffenuss.modkit.space.SpaceLaunchStatus.READY,
            io.github.ffenuss.modkit.space.SpaceInstalledPackageLauncher.status(context, saved),
        )
        val hostPackage = io.github.ffenuss.modkit.space.SpaceTrustedHost.COMPANION_PACKAGE
        assertTrue(
            "CI must explicitly grant overlay app-op on the disposable emulator",
            device.executeShellCommand("appops get $hostPackage SYSTEM_ALERT_WINDOW")
                .contains("allow"),
        )
        assertTrue(
            "The companion must be signed with the same test key",
            io.github.ffenuss.modkit.space.SpaceTrustedHost.companionInstalled(context),
        )
        io.github.ffenuss.modkit.space.SpaceTrustedHost.open(context)
        val launch = fixtureText("Оверлей и запуск: $fixturePackage")
        launch.click()
        assertNotNull(
            "The ordinary fixture must run beneath the overlay",
            device.wait(Until.findObject(By.textContains("Movement distance:")), 25_000),
        )
        val bubble = device.wait(Until.findObject(By.text("MK")), 25_000)
        assertNotNull("Real system overlay bubble must be visible", bubble)
        requireNotNull(bubble).click()
        assertNotNull(
            "The overlay must not advertise non-executable static recipes",
            device.wait(Until.findObject(
                By.text("Только сведения об игре — модификации не активны")
            ), 20_000),
        )
        fixtureText("Закрыть меню").click()
        assertTrue(
            "Stopping the overlay must remove the bubble",
            device.wait(Until.gone(By.text("MK")), 12_000),
        )
        assertTrue(
            "Closing overlay must not close the host game's normal process",
            device.hasObject(By.textContains("Movement distance:")),
        )
    }

    /**
     * Runtime capability is offered only by the explicitly cooperating owned
     * fixture. Its real in-process movement write-back changes while toggled
     * and reverts on OFF and overlay shutdown. The ordinary ModKit UID must
     * never be able to invoke the Companion-only fixture provider directly.
     */
    @Test fun n_spaceOwnedFixtureLiveToggleChangesActualMovement() = runBlocking {
        val saved = io.github.ffenuss.modkit.space.SpaceBuildProfileIndex.load(context)
            .single { it.plan.packageName == fixturePackage }
        assertEquals(
            io.github.ffenuss.modkit.space.SpaceLaunchStatus.READY,
            io.github.ffenuss.modkit.space.SpaceInstalledPackageLauncher.status(context, saved),
        )
        val control = android.net.Uri.parse("content://dev.modkit.fixture.modkit-runtime")
        assertTrue(
            "Other application UIDs must not call the fixture controller",
            runCatching {
                context.contentResolver.call(control, "set_double_movement", null,
                    android.os.Bundle().apply { putBoolean("enabled", true) })
            }.isFailure,
        )
        io.github.ffenuss.modkit.space.SpaceTrustedHost.open(context)
        fixtureText("Оверлей и запуск: $fixturePackage").click()
        assertNotNull(
            "Owned game must run under the overlay",
            device.wait(Until.findObject(By.textContains("Movement distance:")), 25_000),
        )
        assertNotNull("Overlay MK bubble expected",
            device.wait(Until.findObject(By.text("MK")), 25_000))
        fun stepAndRead(): Int {
            fixtureText("Move step").click()
            device.waitForIdle()
            val label = requireNotNull(device.wait(
                Until.findObject(By.textContains("Movement distance:")), 10_000))
            return label.text.substringAfter("Movement distance:").trim().toInt()
        }
        fun readDistance(): Int = requireNotNull(device.wait(
            Until.findObject(By.textContains("Movement distance:")), 10_000))
            .text.substringAfter("Movement distance:").trim().toInt()
        val initial = readDistance()
        val first = stepAndRead()
        val standard = first - initial
        assertTrue("Baseline movement must advance", standard > 0)

        requireNotNull(device.findObject(By.text("MK"))).click()
        fixtureText("Движение ×2: выключено").click()
        assertNotNull("The executor must acknowledge its actual ON state",
            device.wait(Until.findObject(By.text("Движение ×2: включено")), 20_000))
        requireNotNull(device.findObject(By.text("MK"))).click()
        val doubled = stepAndRead()
        assertEquals("ON must perform two real movement writes",
            standard * 2, doubled - first)

        requireNotNull(device.findObject(By.text("MK"))).click()
        fixtureText("Движение ×2: включено").click()
        assertNotNull("OFF must be confirmed by the running game",
            device.wait(Until.findObject(By.text("Движение ×2: выключено")), 20_000))
        requireNotNull(device.findObject(By.text("MK"))).click()
        val restored = stepAndRead()
        assertEquals("OFF must return to baseline movement",
            standard, restored - doubled)

        requireNotNull(device.findObject(By.text("MK"))).click()
        fixtureText("Движение ×2: выключено").click()
        assertNotNull(device.wait(Until.findObject(
            By.text("Движение ×2: включено")), 20_000))
        fixtureText("Закрыть меню").click()
        assertTrue("Overlay must fully disappear on close",
            device.wait(Until.gone(By.text("MK")), 15_000))
        val afterClose = stepAndRead()
        assertEquals("Closing the overlay must revoke live movement effects",
            standard, afterClose - restored)
    }

    /**
     * v2 proof: the overlay discovers TWO independently executable recipes.
     * Both must change real owned-game behavior, acknowledge state separately,
     * and return to normal when disabled or when the overlay closes.
     */
    @Test fun o_spaceTwoLiveRecipesAreIndependentAndReversible() = runBlocking {
        val saved = io.github.ffenuss.modkit.space.SpaceBuildProfileIndex.load(context)
            .single { it.plan.packageName == fixturePackage }
        assertEquals(
            io.github.ffenuss.modkit.space.SpaceLaunchStatus.READY,
            io.github.ffenuss.modkit.space.SpaceInstalledPackageLauncher.status(context, saved),
        )
        io.github.ffenuss.modkit.space.SpaceTrustedHost.open(context)
        fixtureText("Оверлей и запуск: $fixturePackage").click()
        assertNotNull(device.wait(Until.findObject(By.textContains("Movement distance:")), 25_000))
        assertNotNull(device.wait(Until.findObject(By.text("MK")), 25_000))

        fixtureText("Reset").click()
        fun damageCalls(): Int = requireNotNull(device.wait(
            Until.findObject(By.textContains("Damage calls:")), 10_000))
            .text.substringAfter("Damage calls:").trim().toInt()
        fun movement(): Int = requireNotNull(device.wait(
            Until.findObject(By.textContains("Movement distance:")), 10_000))
            .text.substringAfter("Movement distance:").trim().toInt()
        val baselineDamageCalls = damageCalls()
        assertEquals("Reset must clear the real damage-handler counter", 0, baselineDamageCalls)
        val first = movement()
        fixtureText("Move step").click()
        val normalStep = movement() - first
        assertTrue(normalStep > 0)

        requireNotNull(device.findObject(By.text("MK"))).click()
        assertNotNull("Movement recipe must be discovered",
            device.wait(Until.findObject(By.text("Движение ×2: выключено")), 20_000))
        assertNotNull("Health shield recipe must be separately discovered",
            device.wait(Until.findObject(By.text("Защита здоровья: выключено")), 20_000))
        fixtureText("Защита здоровья: выключено").click()
        assertNotNull(device.wait(Until.findObject(
            By.text("Защита здоровья: включено")), 20_000))
        fixtureText("Движение ×2: выключено").click()
        assertNotNull(device.wait(Until.findObject(
            By.text("Движение ×2: включено")), 20_000))
        assertNotNull("Enabling movement must not turn the shield off",
            device.findObject(By.text("Защита здоровья: включено")))

        requireNotNull(device.findObject(By.text("MK"))).click()
        fixtureText("Take damage").click()
        assertEquals("Shield ON must prevent the actual damage handler from running",
            baselineDamageCalls, damageCalls())
        val beforeDouble = movement()
        fixtureText("Move step").click()
        assertEquals("Movement ON must produce two gameplay writes",
            normalStep * 2, movement() - beforeDouble)

        requireNotNull(device.findObject(By.text("MK"))).click()
        fixtureText("Защита здоровья: включено").click()
        assertNotNull(device.wait(Until.findObject(
            By.text("Защита здоровья: выключено")), 20_000))
        assertNotNull("Disabling shield must not disable movement",
            device.findObject(By.text("Движение ×2: включено")))
        requireNotNull(device.findObject(By.text("MK"))).click()
        fixtureText("Take damage").click()
        assertEquals("Shield OFF must restore the actual damage handler",
            baselineDamageCalls + 1, damageCalls())

        requireNotNull(device.findObject(By.text("MK"))).click()
        fixtureText("Защита здоровья: выключено").click()
        assertNotNull(device.wait(Until.findObject(
            By.text("Защита здоровья: включено")), 20_000))
        fixtureText("Закрыть меню").click()
        assertTrue("Closing overlay must remove bubble",
            device.wait(Until.gone(By.text("MK")), 15_000))
        val afterCloseDamageCalls = damageCalls()
        fixtureText("Take damage").click()
        assertEquals("Closing overlay must revoke shield and restore damage handler",
            afterCloseDamageCalls + 1, damageCalls())
        val afterCloseMovement = movement()
        fixtureText("Move step").click()
        assertEquals("Closing overlay must revoke movement multiplier",
            normalStep, movement() - afterCloseMovement)
    }

    /** A signed build of the fixture without the injected probe must not
     * mysteriously acquire a native execution provider or native toggles. */
    @Test fun p_unpreparedFixtureNeverAdvertisesNativeBackend() = runBlocking {
        val selected = io.github.ffenuss.modkit.space.SpaceBuildProfileIndex.load(context)
            .single { it.plan.packageName == fixturePackage }
        assertEquals(
            io.github.ffenuss.modkit.space.SpaceLaunchStatus.READY,
            io.github.ffenuss.modkit.space.SpaceInstalledPackageLauncher.status(context, selected),
        )
        assertNull(
            "The standard fixture does not contain a prepared native provider",
            context.packageManager.resolveContentProvider(
                "$fixturePackage.modkit.runtimeprobe", 0),
        )
        io.github.ffenuss.modkit.space.SpaceTrustedHost.open(context)
        fixtureText("Оверлей и запуск: $fixturePackage").click()
        assertNotNull(device.wait(Until.findObject(By.text("MK")), 25_000))
        requireNotNull(device.findObject(By.text("MK"))).click()
        assertNotNull(device.wait(Until.findObject(
            By.text("Движение ×2: выключено")), 20_000))
        assertFalse("An unprepared fixture must not show a phantom native patch",
            device.hasObject(By.textContains("Нативный патч ·")))
        fixtureText("Закрыть меню").click()
        assertTrue(device.wait(Until.gone(By.text("MK")), 12_000))
    }

    /**
     * Positive proof of the native backend against a second game APK that
     * contains NO ModKit controller. This installs only our owned disposable
     * fixture APK, using Android's user-confirmed PackageInstaller.
     *
     * Distances must prove real native function write-back, not just an ON
     * state reported by the new provider. q_ runs after all tests for the
     * original fixture so a separate APK has no effect on their inputs.
     */
    @Test fun q_preparedNativeProbeChangesRealIndependentGame() = runBlocking {
        // Normal emulator CI remains cross-ABI. A dedicated device proof must
        // opt in and fail BEFORE installation/removal on anything but a real
        // ARM64 kernel + 64-bit process. Android x86 translation is not proof.
        val liveArm64 = InstrumentationRegistry.getArguments()
            .getString("modkit_arm64_proof") == "true"
        val liveNonce = InstrumentationRegistry.getArguments()
            .getString("modkit_proof_nonce")
        val kernelMachine = android.system.Os.uname().machine
        if (liveArm64) {
            assertEquals("ARM64 must be the device's primary ABI",
                "arm64-v8a", Build.SUPPORTED_ABIS.firstOrNull())
            assertEquals("ARM64 device proof requires an AArch64 kernel",
                "aarch64", kernelMachine)
            assertTrue("Runtime process must be 64-bit", android.os.Process.is64Bit())
            assertTrue("ARM64 required in 64-bit supported ABIs",
                Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a"))
            assertTrue("A fresh 128-bit proof nonce is required",
                liveNonce != null && Regex("[0-9a-f]{32}").matches(liveNonce))
        }
        val nativePackage = "dev.modkit.nativefixture"
        val original = requireNotNull(
            io.github.ffenuss.modkit.data.InstalledAppRepository(context).find(nativePackage)
        ) { "CI must install repository-owned native fixture before instrumentation" }
        assertEquals(1, original.apkFiles.size)
        fun visiblePackageQueries(apk: File): List<String> = ZipFile(apk).use { zip ->
            val manifest = requireNotNull(zip.getEntry("AndroidManifest.xml"))
            zip.getInputStream(manifest).use { stream ->
                BinaryAndroidManifestProbeInjector.declaredPackageQueries(stream.readBytes())
            }
        }
        assertTrue(
            "Original native game must not pre-grant package visibility",
            visiblePackageQueries(original.apkFiles.single()).isEmpty(),
        )
        assertNull(
            "Unprepared native game must not implement a ModKit controller",
            context.packageManager.resolveContentProvider(
                "$nativePackage.modkit.runtimeprobe", 0),
        )
        val nativeLaunch = requireNotNull(
            context.packageManager.getLaunchIntentForPackage(nativePackage)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(nativeLaunch)
        assertNotNull(device.wait(Until.findObject(By.text("Native distance: 0")), 15_000))
        fixtureText("Native move").click()
        assertNotNull("Unmodified native step must advance exactly 2 units",
            device.wait(Until.findObject(By.text("Native distance: 2")), 10_000))

        // Select the actual device's 64-bit ABI, not an assumed test runner
        // architecture. The exact function VA comes from its installed ELF
        // .dynsym and executable section; never guess patch addresses.
        val nativeAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull {
            it == "arm64-v8a" || it == "x86_64"
        } ?: error("Native gameplay proof requires a supported 64-bit ABI")
        val patch = NativeFixtureElfProof.readInstalled(
            original.apkFiles.single(), nativeAbi)
        // The actual candidate for the running-game recipe now comes from the
        // generic ELF discovery, not the fixture-specific ELF proof. The
        // latter remains an independent oracle for bytes/virtual address.
        val discoveredPatch = NativeFixtureScalarDiscoveryProof.discover(
            original.apkFiles.single(), nativeAbi, context.cacheDir, signal)
        assertEquals("Discovered function VA must match independent ELF parser",
            patch.address, discoveredPatch.patchVirtualAddress)
        assertEquals(patch.original, discoveredPatch.expectedHex)
        assertEquals(patch.replacement, discoveredPatch.replacementHex)
        if (liveArm64) assertEquals("arm64-v8a", nativeAbi)
        assertTrue(patch.address > 0L)
        // Every native fixture APK includes ARM64 regardless of the
        // emulator's running architecture. Validate the actual packaged
        // ARM64 ELF/symbol and exact instruction recipe here too; the
        // separate Unicorn test executes its code without claiming a live
        // ARM64 device patch.
        val arm64Patch = NativeFixtureElfProof.readInstalled(
            original.apkFiles.single(), "arm64-v8a")
        assertEquals(0L, arm64Patch.address % 4L)
        assertEquals("40008052", arm64Patch.original)
        assertEquals("e0008052", arm64Patch.replacement)
        val target = AnalysisTargetDescriptor.InstalledPackage(
            nativePackage, "ModKit Native Fixture")
        val indexed = FastArtifactIndexer.index(original.apkFiles, signal, progress)
        val workspace = PatchWorkspaceProvider.open(
            context = context,
            target = target,
            expected = indexed,
            cache = EngineResultCache(File(context.filesDir, "analysis-cache")),
            cancellation = signal,
            progress = progress,
        )
        val outputRoot = File(context.filesDir, "native-e2e-staging")
        val build = workspace.use {
            val injected = RepackedRuntimeInstrumentationCoordinator.instrumentNativeLookup(
                context, it.workspace, outputRoot, signal)
            assertEquals(nativePackage, injected.packageName)
            RepackedRuntimeBuildCoordinator.buildNativeProbeInjected(
                context = context,
                manifestInventory = injected.base.manifestInventory,
                injection = injected.nativeProbeInjection,
                outputRoot = outputRoot,
                cancellation = signal,
                progress = progress,
            )
        }
        val plan = RepackedRuntimeInstallPlanner.plan(build, signal)
        assertTrue("Prepared native APK must pass sign/align/verify", plan.ready)
        assertEquals(nativePackage, plan.packageName)

        // Re-signing changes the app identity. CI shell may uninstall only this
        // owned disposable fixture, never an unrelated installed application.
        val removed = device.executeShellCommand("pm uninstall $nativePackage")
        assertTrue("Remove disposable fixture for new signed install: $removed",
            removed.contains("Success"))
        assertTrue("Install permission must be granted by test a_ or explicit device opt-in",
            context.packageManager.canRequestPackageInstalls())

        val modkitComponent = android.content.ComponentName(
            context, MainActivity::class.java).flattenToShortString()
        device.executeShellCommand("am start -W -n $modkitComponent -f 0x14000000")
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 15_000))
        val attemptedAt = System.currentTimeMillis()
        val submission = AndroidRepackedRuntimeInstaller.submit(context, plan, signal)
        assertEquals(nativePackage, submission.packageName)
        val deadline = System.currentTimeMillis() + 75_000L
        while (System.currentTimeMillis() < deadline) {
            val status = RepackedRuntimeInstallStatusStore.status.value
            if (status.sessionId == submission.sessionId &&
                    status.updatedAtEpochMs >= attemptedAt) {
                if (status.kind == RepackedRuntimeInstallStatusKind.SUCCESS) break
                if (status.kind == RepackedRuntimeInstallStatusKind.FAILURE) {
                    throw AssertionError("Native fixture install failed: " + status.message)
                }
                if (status.kind == RepackedRuntimeInstallStatusKind.USER_ACTION_REQUIRED) {
                    runCatching {
                        RepackedRuntimeInstallConfirmationStore.open(
                            context, submission.sessionId)
                    }
                }
                val confirmation = device.findObject(
                    By.res("com.android.packageinstaller", "ok_button"))
                    ?: device.findObject(
                        By.res("com.google.android.packageinstaller", "ok_button"))
                    ?: device.findObject(By.text("Install"))
                    ?: device.findObject(By.text("INSTALL"))
                    ?: device.findObject(By.text("Update"))
                    ?: device.findObject(By.text("UPDATE"))
                    ?: device.findObject(By.text("Подтвердить установку"))
                if (confirmation != null) {
                    try { confirmation.click() }
                    catch (_: androidx.test.uiautomator.StaleObjectException) { }
                }
            }
            device.wait(Until.hasObject(By.text("App installed")), 500)
        }
        val installed = RepackedRuntimeInstallStatusStore.status.value
        assertEquals("User-approved native build must install successfully",
            RepackedRuntimeInstallStatusKind.SUCCESS, installed.kind)
        assertEquals(submission.sessionId, installed.sessionId)

        val authority = "$nativePackage.modkit.runtimeprobe"
        val transport = AndroidRepackedRuntimeProbeTransport(context)
        val exactInstalled = requireNotNull(
            transport.inspectInstalled(nativePackage, authority))
        RepackedRuntimeProbeIdentityVerifier.verify(build, exactInstalled)
        val repackedNative = requireNotNull(
            io.github.ffenuss.modkit.data.InstalledAppRepository(context).find(nativePackage)
        )
        assertEquals(
            "Prepared APK must gain exactly the scoped publisher/Companion visibility",
            listOf(
                "io.github.ffenuss.modkit",
                "io.github.ffenuss.modkit.test",
                "io.github.ffenuss.modkit.spacecompanion",
            ),
            visiblePackageQueries(repackedNative.apkFiles.single()),
        )
        val recipe = RepackedRuntimeTestMenuItem(
            id = "native:step-seven",
            label = "Native movement",
            detail = "Discovered exported scalar in owned $nativeAbi fixture: 2 -> 7",
            mode = RepackedRuntimeTestMenuItemMode.PATCH,
            moduleName = patch.module,
            binaryVirtualAddress = discoveredPatch.patchVirtualAddress,
            originalHex = discoveredPatch.expectedHex,
            replacementHex = discoveredPatch.replacementHex,
        )
        val configured = transport.configureTestMenu(authority, listOf(recipe))
        assertEquals(1, configured.patchItemCount)

        // The Space catalog is derived from a REAL build history entry.
        // Publishing a speculative item here would not authorize anything.
        io.github.ffenuss.modkit.ui.AutoModBuildRecord(
            plan, System.currentTimeMillis(), listOf("Native fixture E2E"), "")
            .save(context)
        val record = io.github.ffenuss.modkit.space.SpaceBuildProfileIndex.load(context)
            .single { it.plan.packageName == nativePackage }
        assertEquals(
            io.github.ffenuss.modkit.space.SpaceLaunchStatus.READY,
            io.github.ffenuss.modkit.space.SpaceInstalledPackageLauncher.status(context, record),
        )
        io.github.ffenuss.modkit.space.SpaceTrustedHost.open(context)
        fixtureText("Оверлей и запуск: $nativePackage").click()
        assertNotNull("Prepared native game must launch",
            device.wait(Until.findObject(By.text("Native distance: 0")), 25_000))
        val bubbleSelector = By.desc("ModKit · открыть плавающее меню")
        assertNotNull("The external floating overlay must be visible",
            device.wait(Until.findObject(bubbleSelector), 20_000))
        fixtureText("Native move").click()
        assertNotNull("Native base step must be 2 after re-signed install",
            device.wait(Until.findObject(By.text("Native distance: 2")), 10_000))

        requireNotNull(device.findObject(bubbleSelector)).click()
        fixtureText("Нативный патч · Native movement: выключено").click()
        assertNotNull("Prepared native executor must acknowledge enabled bytes",
            device.wait(Until.findObject(By.text(
                "Нативный патч · Native movement: включено")), 20_000))
        requireNotNull(device.findObject(bubbleSelector)).click()
        fixtureText("Native move").click()
        assertNotNull("Native code patch must change ACTUAL in-game movement to 7",
            device.wait(Until.findObject(By.text("Native distance: 9")), 10_000))

        requireNotNull(device.findObject(bubbleSelector)).click()
        fixtureText("Нативный патч · Native movement: включено").click()
        assertNotNull("The native executor must acknowledge OFF",
            device.wait(Until.findObject(By.text(
                "Нативный патч · Native movement: выключено")), 20_000))
        requireNotNull(device.findObject(bubbleSelector)).click()
        fixtureText("Native move").click()
        assertNotNull("OFF must restore original machine instructions and step +2",
            device.wait(Until.findObject(By.text("Native distance: 11")), 10_000))

        requireNotNull(device.findObject(bubbleSelector)).click()
        fixtureText("Нативный патч · Native movement: выключено").click()
        assertNotNull(device.wait(Until.findObject(By.text(
            "Нативный патч · Native movement: включено")), 20_000))
        fixtureText("Закрыть меню").click()
        assertTrue(device.wait(Until.gone(bubbleSelector), 15_000))
        fixtureText("Native move").click()
        assertNotNull("Closing external overlay must restore baseline native bytes",
            device.wait(Until.findObject(By.text("Native distance: 13")), 10_000))

        // Only the owned fixture has a known user-facing movement action. No
        // generic native candidate is automatically promoted or made selectable
        // by this test. Save confirmation only after actual gameplay changes.
        evidence("native-scalar-gameplay-$nativeAbi.json") { file ->
            file.writeText(JSONObject()
                .put("schema", "modkit.native-scalar-fixture-e2e.v1")
                .put("originalArtifactSha256", indexed.index.artifactSha256)
                .put("fixturePackage", nativePackage)
                .put("abi", nativeAbi)
                .put("scanner", "NativeScalarExportDiscovery")
                .put("symbol", discoveredPatch.symbol)
                .put("module", patch.module)
                .put("patchVirtualAddress", discoveredPatch.patchVirtualAddress)
                .put("expectedHex", discoveredPatch.expectedHex)
                .put("replacementHex", discoveredPatch.replacementHex)
                .put("observedNativeDistance", org.json.JSONArray(listOf(0, 2, 9, 11, 13)))
                .put("actualStepDeltas", org.json.JSONArray(listOf(2, 7, 2, 2)))
                .put("overlayClosedRestoresOriginal", true)
                .put("scope", "repository-owned native fixture only")
                .toString(2))
        }

        if (liveArm64) {
            // Emit only after the whole real, out-of-process overlay and
            // native game sequence was observed. Fresh nonce prevents stale
            // reports from ever satisfying an independent host gate.
            evidence("arm64-native-live-${requireNotNull(liveNonce)}.json") { file ->
                file.writeText(JSONObject()
                    .put("schema", "modkit.arm64.native-live.v1")
                    .put("nonce", liveNonce)
                    .put("deviceAbi", nativeAbi)
                    .put("primaryAbi", Build.SUPPORTED_ABIS.first())
                    .put("kernelMachine", kernelMachine)
                    .put("process64", android.os.Process.is64Bit())
                    .put("apiLevel", Build.VERSION.SDK_INT)
                    .put("fixturePackage", nativePackage)
                    .put("originalInputSha256", indexed.index.artifactSha256)
                    .put("recipe", JSONObject()
                        .put("functionVirtualAddress", patch.address)
                        .put("originalHex", patch.original)
                        .put("replacementHex", patch.replacement))
                    .put("observedNativeDistance", JSONObject()
                        .put("baseline", 2)
                        .put("on", 9)
                        .put("off", 11)
                        .put("overlayClosed", 13))
                    .put("observedStepDeltas", org.json.JSONArray(listOf(2, 7, 2, 2)))
                    .put("overlayAndInstallerConfirmed", true)
                    .toString(2))
            }
        }
    }

}
