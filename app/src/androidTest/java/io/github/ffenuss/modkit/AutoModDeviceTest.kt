package io.github.ffenuss.modkit

import android.content.Intent
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
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
        val gameReady = device.wait(Until.hasObject(By.textContains("Health:")), 45_000)
        if (!gameReady) evidence("fixture-launch-failure.png") { device.takeScreenshot(it) }
        assertTrue("Fixture must launch", gameReady)
    }


    /**
     * Exercise the real system confirmation once per session. The receiver
     * normally opens it; repeatedly re-opening the same Intent on API 29
     * leaves a stale parse-error dialog over the following UI test.
     */
    private fun awaitInstallResult(
        attemptedAt: Long,
        timeoutMs: Long,
        expectedSessionId: Int? = null,
        manualUiFallback: Boolean = false,
    ): RepackedRuntimeInstallStatus {
        val deadline = System.currentTimeMillis() + timeoutMs
        var pendingSince: Long? = null
        var systemConfirmationTapped = false
        var manualConfirmationRequested = false
        while (System.currentTimeMillis() < deadline) {
            assertFalse(
                "A stale/duplicate PackageInstaller confirmation displayed a parse-error dialog",
                device.hasObject(By.textContains("There was a problem parsing the package")),
            )
            val status = RepackedRuntimeInstallStatusStore.status.value
            if (status.updatedAtEpochMs >= attemptedAt &&
                (expectedSessionId == null || status.sessionId == expectedSessionId)
            ) {
                assertNotEquals(status.message, RepackedRuntimeInstallStatusKind.FAILURE, status.kind)
                if (status.kind == RepackedRuntimeInstallStatusKind.SUCCESS) {
                    assertFalse(
                        "Successful install must not leave an extra parse-error dialog",
                        device.hasObject(By.textContains("There was a problem parsing the package")),
                    )
                    return status
                }
                if (status.kind == RepackedRuntimeInstallStatusKind.USER_ACTION_REQUIRED) {
                    if (pendingSince == null) pendingSince = System.currentTimeMillis()
                    val installerVisible =
                        device.hasObject(By.pkg("com.android.packageinstaller")) ||
                            device.hasObject(By.pkg("com.google.android.packageinstaller"))
                    if (installerVisible && !systemConfirmationTapped) {
                        device.waitForIdle()
                        val systemInstall =
                            device.findObject(By.res("com.android.packageinstaller", "ok_button"))
                                ?: device.findObject(By.res("com.google.android.packageinstaller", "ok_button"))
                                ?: device.findObject(By.text("Install"))
                                ?: device.findObject(By.text("INSTALL"))
                                ?: device.findObject(By.text("Update"))
                                ?: device.findObject(By.text("UPDATE"))
                        if (systemInstall != null) {
                            evidence("installer-confirmation-${status.sessionId}.png") { device.takeScreenshot(it) }
                            evidence("installer-confirmation-${status.sessionId}.xml") { device.dumpWindowHierarchy(it) }
                            systemInstall.click()
                            systemConfirmationTapped = true
                        }
                    } else if (!installerVisible && !manualConfirmationRequested &&
                        System.currentTimeMillis() - pendingSince >= 3_000L &&
                        RepackedRuntimeInstallConfirmationStore.availableFor(status.sessionId)
                    ) {
                        if (manualUiFallback) {
                            val button = device.findObject(By.text("Подтвердить установку"))
                            if (button != null) {
                                button.click()
                                manualConfirmationRequested = true
                            }
                        } else {
                            val id = requireNotNull(status.sessionId)
                            instrumentation.runOnMainSync {
                                assertTrue(RepackedRuntimeInstallConfirmationStore.open(context, id))
                            }
                            manualConfirmationRequested = true
                        }
                    }
                }
            }
            device.wait(Until.hasObject(By.text("Приложение установлено")), 500)
        }
        return RepackedRuntimeInstallStatusStore.status.value
    }

    @Test fun a_simpleInterfaceSelectsAndBuildsWithoutExpertTools() {
        @Suppress("DEPRECATION")
        val sourceInstaller = context.packageManager.getInstallerPackageName(fixturePackage)
        assertEquals("Owned source fixture must have a deterministic original installer",
            "com.android.shell", sourceInstaller)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val homeReady = device.wait(Until.hasObject(By.text("Выбрать игру")), 45_000)
        if (!homeReady) evidence("modkit-home-timeout.png") { device.takeScreenshot(it) }
        assertTrue("ModKit home must become accessible", homeReady)
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
        val attemptedAt = System.currentTimeMillis()
        val submission = AndroidRepackedRuntimeInstaller.submit(context, plan, signal)
        val directInstall = awaitInstallResult(
            attemptedAt, 100_000L, expectedSessionId = submission.sessionId,
        )
        assertEquals(RepackedRuntimeInstallStatusKind.SUCCESS, directInstall.kind)
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

    @Test fun d_nativeOverlayChangesAndRestoresActualCode() {
        NativeOverlayDeviceScenario(instrumentation, device).run(::evidence) { plan ->
            assertTrue(context.packageManager.canRequestPackageInstalls())
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            device.waitForIdle()
            val attemptedAt = System.currentTimeMillis()
            val submission = AndroidRepackedRuntimeInstaller.submit(context, plan, signal)
            val result = awaitInstallResult(attemptedAt, 100_000L, expectedSessionId = submission.sessionId)
            assertEquals(RepackedRuntimeInstallStatusKind.SUCCESS, result.kind)
        }
    }

    @Test fun e_dexOverlayRestoresOriginalGameLogic() {
        DexOverlayDeviceScenario(instrumentation, device).run(::evidence) { plan ->
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            device.waitForIdle()
            val attemptedAt = System.currentTimeMillis()
            val submission = AndroidRepackedRuntimeInstaller.submit(context, plan, signal)
            val result = awaitInstallResult(attemptedAt, 100_000L, expectedSessionId = submission.sessionId)
            assertEquals(RepackedRuntimeInstallStatusKind.SUCCESS, result.kind)
        }
    }

    @Test fun f_flutterResourceModChangesActualAssetBundleAndSurvivesRestart() = runBlocking {
        val pkg = "dev.modkit.enginefixture"
        fun launch() {
            val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(pkg))
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.descStartsWith("Health:")), 30_000))
            // Flutter publishes its semantics before Android removes the launch
            // splash. A physical tap during that interval is discarded by Android.
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && instrumentation.uiAutomation.windows.any {
                it.title?.toString()?.contains("Splash Screen") == true
            }) android.os.SystemClock.sleep(100)
            device.waitForIdle()
        }
        fun visible(label: String) = device.wait(Until.hasObject(By.desc(label)), 30_000)
        fun accessibilityClick(label: String): Boolean {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
            fun visit(node: AccessibilityNodeInfo): Boolean {
                if (node.contentDescription?.toString() == label && node.isClickable) {
                    return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    if (visit(child)) return true
                }
                return false
            }
            return visit(root)
        }
        fun damage(expectedHealth: Int) {
            // Flutter can publish semantics a few hundred milliseconds before the
            // Android window becomes touchable. PR23's failed API29/API35 runs
            // showed InputDispatcher dropping UiObject2 coordinate taps while the
            // same button was already exposed as a clickable semantics node.
            // Invoke the real Flutter semantics ACTION_CLICK instead of retrying a
            // dropped pointer event; the state assertion below remains unchanged.
            assertTrue("Flutter damage action must be clickable through semantics",
                accessibilityClick("Take damage"))
            assertTrue("Flutter must finish handling each action", visible("Health: $expectedHealth"))
        }
        try {
        launch()
        assertTrue("Real Flutter fixture must read the original JSON", visible("Health: 20"))
        listOf(13, 6, 0).forEach { damage(it) }
        assertTrue(visible("GAME OVER"))
        evidence("flutter-original.png") { device.takeScreenshot(it) }
        val installed = requireNotNull(io.github.ffenuss.modkit.data.InstalledAppRepository(context).find(pkg))
        val target = AnalysisTargetDescriptor.InstalledPackage(pkg, "Flutter resource fixture")
        val analysis = FastArtifactIndexer.index(installed.apkFiles, signal, progress)
        assertTrue(analysis.index.runtimeProfiles.any { it.runtimeId == "flutter" })
        val scan = EngineResourceModCoordinator.scan(context, target, analysis, signal, progress)
        val recipe = scan.recipes.single { it.resource?.key == "/health" }.withScalarValue("99")
        assertFalse(recipe.verification.runtimeConfirmed)
        val originalBytes = ZipFile(installed.apkFiles[recipe.resource!!.apkIndex]).use { zip ->
            zip.getInputStream(zip.getEntry(recipe.resource.entry)).readBytes()
        }
        val preparation = PatchPreparationPlan(analysis.index.artifactSha256, true, System.currentTimeMillis(), emptyList(), emptyList())
        val built = AutoModRuntimeTestMenuCoordinator.build(context, target, analysis, preparation, signal, progress, listOf(recipe))
        assertTrue("Static resources must not become nonfunctional menu switches", built.menu.items.isEmpty())
        val plan = RepackedRuntimeInstallPlanner.plan(built.build, signal)
        assertTrue(plan.blockers.joinToString(), plan.ready)
        val sourceAfter = ZipFile(installed.apkFiles[recipe.resource.apkIndex]).use { zip ->
            zip.getInputStream(zip.getEntry(recipe.resource.entry)).readBytes()
        }
        assertArrayEquals("Original APK must be preserved", originalBytes, sourceAfter)
        // Only our disposable Flutter fixture is removed for its signing-key change.
        assertTrue(device.executeShellCommand("pm uninstall $pkg").contains("Success"))
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        device.waitForIdle()
        device.executeShellCommand("appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow")
        val attemptedAt = System.currentTimeMillis()
        val submitted = AndroidRepackedRuntimeInstaller.submit(context, plan, signal)
        assertEquals(RepackedRuntimeInstallStatusKind.SUCCESS, awaitInstallResult(attemptedAt, 100_000, submitted.sessionId).kind)
        launch()
        assertTrue("Patched Flutter rootBundle must read 99", visible("Health: 99"))
        assertTrue("Unselected damage must stay 7", visible("Damage: 7"))
        listOf(92, 85, 78).forEach { damage(it) }
        assertTrue("The resource must change actual game state", visible("Health: 78"))
        assertTrue(visible("ALIVE"))
        evidence("flutter-modified.png") { device.takeScreenshot(it) }
        device.executeShellCommand("am force-stop $pkg")
        launch()
        assertTrue("Resource changes must survive a fresh Flutter process", visible("Health: 99"))
        evidence("flutter-resource-metrics.json") { file -> file.writeText(JSONObject()
            .put("engine", "Flutter 3.35.4 debug x86_64").put("api", Build.VERSION.SDK_INT)
            .put("sourceArtifactSha256", analysis.index.artifactSha256).put("examinedResourceFiles", scan.examinedFiles)
            .put("resourceRecipes", scan.recipes.size).put("selectedResourceChanges", 1)
            .put("runtimeSwitches", built.menu.items.size).put("originalAfterThreeHits", 0)
            .put("modifiedAfterThreeHits", 78).put("unchangedDamage", 7).put("healthAfterRestart", 99)
            .put("runtimeConfirmedRecipesInOwnedFixture", 1).put("unrealRuntimeConfirmed", false).toString(2)) }
        } finally {
            evidence("flutter-final.png") { device.takeScreenshot(it) }
            evidence("flutter-hierarchy.xml") { device.dumpWindowHierarchy(it) }
        }
    }

    @Test fun g_unrealLooseIniExecutorChangesOwnedConsumerAndSurvivesRestart() = runBlocking {
        val pkg = "dev.modkit.unrealfixture"
        fun launch() {
            val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(pkg))
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue("Owned INI fixture must launch", device.wait(Until.hasObject(By.textStartsWith("Health:")), 20_000))
            device.waitForIdle()
        }
        fun visible(label: String) = device.wait(Until.hasObject(By.text(label)), 20_000)
        fun damage(expectedHealth: Int) {
            val hit = device.wait(Until.findObject(By.text(Pattern.compile("take damage", Pattern.CASE_INSENSITIVE))), 10_000)
            requireNotNull(hit) { "Owned INI fixture damage button is missing" }.click()
            assertTrue("Owned INI consumer must apply damage", visible("Health: $expectedHealth"))
        }

        try {
            launch()
            assertTrue("Fixture must consume original loose INI", visible("Health: 20"))
            assertTrue(visible("Damage: 7"))
            listOf(13, 6, 0).forEach { damage(it) }
            assertTrue(visible("GAME OVER"))
            evidence("unreal-ini-original.png") { device.takeScreenshot(it) }

            val installed = requireNotNull(io.github.ffenuss.modkit.data.InstalledAppRepository(context).find(pkg))
            val target = AnalysisTargetDescriptor.InstalledPackage(pkg, "Owned Unreal-shaped loose INI fixture")
            val analysis = FastArtifactIndexer.index(installed.apkFiles, signal, progress)
            assertTrue(
                "Valid libUnreal.so must route the owned fixture through Unreal resource analysis",
                analysis.index.runtimeProfiles.any { it.runtimeId == "unreal" },
            )
            val scan = EngineResourceModCoordinator.scan(context, target, analysis, signal, progress)
            val key = "player\u001fhealth"
            val recipe = scan.recipes.single { it.resource?.key == key }.withScalarValue("99")
            assertEquals(EngineResourceFormat.UNREAL_INI, recipe.resource!!.format)
            assertFalse("A prepared INI edit is not yet observed runtime gameplay evidence",
                recipe.verification.runtimeConfirmed)

            val originalBytes = ZipFile(installed.apkFiles[recipe.resource.apkIndex]).use { zip ->
                zip.getInputStream(zip.getEntry(recipe.resource.entry)).readBytes()
            }
            val preparation = PatchPreparationPlan(
                analysis.index.artifactSha256, true, System.currentTimeMillis(),
                emptyList(), emptyList(),
            )
            val built = AutoModRuntimeTestMenuCoordinator.build(
                context, target, analysis, preparation, signal, progress, listOf(recipe),
            )
            assertTrue(
                "Static loose-INI resources must not masquerade as runtime overlay switches",
                built.menu.items.isEmpty(),
            )
            val plan = RepackedRuntimeInstallPlanner.plan(built.build, signal)
            assertTrue(plan.blockers.joinToString(), plan.ready)
            val sourceAfter = ZipFile(installed.apkFiles[recipe.resource.apkIndex]).use { zip ->
                zip.getInputStream(zip.getEntry(recipe.resource.entry)).readBytes()
            }
            assertArrayEquals("Original owned APK must remain byte-identical", originalBytes, sourceAfter)

            // Only this disposable owned fixture is removed because the rebuilt APK uses ModKit's test signer.
            assertTrue(device.executeShellCommand("pm uninstall $pkg").contains("Success"))
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            device.waitForIdle()
            device.executeShellCommand("appops set " + context.packageName + " REQUEST_INSTALL_PACKAGES allow")
            val attemptedAt = System.currentTimeMillis()
            val submitted = AndroidRepackedRuntimeInstaller.submit(context, plan, signal)
            assertEquals(
                RepackedRuntimeInstallStatusKind.SUCCESS,
                awaitInstallResult(attemptedAt, 100_000, submitted.sessionId).kind,
            )

            launch()
            assertTrue("Rebuilt package must consume changed INI health", visible("Health: 99"))
            assertTrue("Unselected INI damage must stay unchanged", visible("Damage: 7"))
            listOf(92, 85, 78).forEach { damage(it) }
            assertTrue(visible("ALIVE"))
            evidence("unreal-ini-modified.png") { device.takeScreenshot(it) }

            device.executeShellCommand("am force-stop $pkg")
            launch()
            assertTrue("Packaged INI change must survive a fresh process", visible("Health: 99"))
            evidence("unreal-ini-resource-metrics.json") { file ->
                file.writeText(
                    JSONObject()
                        .put("fixture", "owned loose-INI consumer with valid libUnreal.so fingerprint")
                        .put("actualUnrealEngineRuntime", false)
                        .put("api", Build.VERSION.SDK_INT)
                        .put("sourceArtifactSha256", analysis.index.artifactSha256)
                        .put("examinedResourceFiles", scan.examinedFiles)
                        .put("resourceRecipes", scan.recipes.size)
                        .put("selectedResourceChanges", 1)
                        .put("runtimeSwitches", built.menu.items.size)
                        .put("originalAfterThreeHits", 0)
                        .put("modifiedAfterThreeHits", 78)
                        .put("unchangedDamage", 7)
                        .put("healthAfterRestart", 99)
                        .put("executorRuntimeEffectObserved", true)
                        .put("unrealEngineGameplayEffectConfirmed", false)
                        .toString(2),
                )
            }
        } finally {
            evidence("unreal-ini-final.png") { device.takeScreenshot(it) }
            evidence("unreal-ini-hierarchy.xml") { device.dumpWindowHierarchy(it) }
        }
    }

    @Test fun c_installButtonInstallsTheUiBuildAfterTheOriginalConflictIsResolved() {
        // The original certificate conflict was checked in b. The app produced by a
        // uses the same persistent ModKit key and can now update our owned fixture.
        assertTrue(context.packageManager.canRequestPackageInstalls())
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val installButton = device.wait(Until.findObject(By.text("Установить")), 15_000)
        assertNotNull("Retained UI build must remain installable", installButton)
        val previousSessionId = RepackedRuntimeInstallStatusStore.status.value.sessionId
        val attemptedAt = System.currentTimeMillis()
        installButton.click()
        // A late callback from the direct-install test must never satisfy
        // this UI install assertion or be mistaken for its own success.
        val newSessionDeadline = System.currentTimeMillis() + 45_000L
        var uiSessionId: Int? = null
        while (System.currentTimeMillis() < newSessionDeadline) {
            val current = RepackedRuntimeInstallStatusStore.status.value
            if (current.updatedAtEpochMs >= attemptedAt &&
                current.sessionId != null && current.sessionId != previousSessionId
            ) {
                uiSessionId = current.sessionId
                break
            }
            device.wait(Until.hasObject(By.text("Приложение установлено")), 500)
        }
        val actualUiSession = requireNotNull(uiSessionId) {
            "The retained UI install button must submit its own PackageInstaller session."
        }
        val completed: RepackedRuntimeInstallStatus
        try {
            completed = awaitInstallResult(
                attemptedAt, 100_000L, expectedSessionId = actualUiSession,
                manualUiFallback = true,
            )
        } finally {
            evidence("ui-install-state.png") { device.takeScreenshot(it) }
            evidence("ui-install-hierarchy.xml") { device.dumpWindowHierarchy(it) }
        }
        assertTrue(
            "A new installation must complete from the retained UI build",
            completed.updatedAtEpochMs >= attemptedAt,
        )
        assertEquals(RepackedRuntimeInstallStatusKind.SUCCESS, completed.kind)
        device.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val launch = requireNotNull(device.wait(Until.findObject(By.text(Pattern.compile(
            "Запустить с мод-меню|Разрешить окно поверх игры"))), 15_000))
        launch.click()
        assertTrue("New UI builds must preserve original behaviour while OFF",
            device.wait(Until.hasObject(By.text("ALIVE | Health: 20")), 15_000))
        assertTrue(device.wait(Until.hasObject(By.desc("Открыть мод-меню ModKit")), 10_000))
        assertTrue(device.wait(Until.gone(By.desc("Встроенное мод-меню ModKit")), 10_000))
        assertEquals("Simple Mode must show only one MK button", 1, device.findObjects(By.text("MK")).size)
        verifyOriginalInstallerCompatibility("first-launch")
        try {
            val bubble = By.desc("Открыть мод-меню ModKit")
            requireNotNull(device.wait(Until.findObject(bubble), 10_000)).click()
            val healthSwitch = By.desc("Мод: Здоровье · значение 9999")
            requireNotNull(device.wait(Until.findObject(healthSwitch.enabled(true)), 10_000)).click()
            assertTrue(device.wait(Until.hasObject(healthSwitch.checked(true)), 15_000))
            device.findObject(bubble).click()
            repeat(3) { hit() }
            assertTrue("The APK selected and installed through the UI must change gameplay after enabling",
                device.hasObject(By.text("ALIVE | Health: 9999")))
            evidence("ui-installed-game.png") { device.takeScreenshot(it) }
            device.executeShellCommand("am force-stop $fixturePackage")
            launchGame()
            verifyOriginalInstallerCompatibility("process-restarted")
        } finally {
            context.stopService(Intent(context, ModKitRuntimeOverlayService::class.java))
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyOriginalInstallerCompatibility(stage: String) {
        val actual = context.packageManager.getInstallerPackageName(fixturePackage)
        assertEquals("Android must retain the real installer of the repacked build",
            context.packageName, actual)
        val node = requireNotNull(
            device.wait(Until.findObject(By.textContains("Installer direct:")), 15_000),
        ) { "Owned fixture must expose installer compatibility evidence." }
        val localView = node.text.orEmpty()
        assertTrue("Legacy local check must see the verified original installer: $localView",
            localView.contains("Installer direct: com.android.shell | gate: ALLOWED"))
        assertTrue("Reflection must still expose Android's real installer: $localView",
            localView.contains("actual: $actual"))
        if (Build.VERSION.SDK_INT >= 30) {
            assertTrue("Modern local check must see the verified original installer: $localView",
                localView.contains("InstallSource direct: com.android.shell | gate: ALLOWED"))
        } else {
            assertTrue("API29 must retain the explicit unavailable modern state: $localView",
                localView.contains("InstallSource direct: unavailable | gate: unavailable"))
        }
        evidence("installer-compatibility-$stage.json") {
            it.writeText(JSONObject()
                .put("api", Build.VERSION.SDK_INT)
                .put("observedOriginal", "com.android.shell")
                .put("actualInstaller", actual)
                .put("localView", localView)
                .put("stage", stage)
                .toString(2))
        }
        evidence("installer-compatibility-$stage.png") { device.takeScreenshot(it) }
    }
}
