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

    @Test fun a0_originalApkSetProducesVersionBoundSpaceMenuAndReadableHandoff() = runBlocking {
        val installed = io.github.ffenuss.modkit.data.InstalledAppRepository(context).find(fixturePackage)!!
        val before = installed.apkFiles.map { java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
        val archive = File(context.cacheDir, "space-fixture.apks")
        java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
            installed.apkFiles.forEach { file ->
                zip.putNextEntry(java.util.zip.ZipEntry("original/" + file.name))
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        var profile: File? = null
        try {
            ArtifactPackageLoader.open(archive, File(context.cacheDir, "space-fixture-set"), signal, progress).use { loaded ->
                val result = FastArtifactIndexer.index(loaded.files, signal, progress)
                val workspace = AnalysisWorkspace(result.index, result.index.sources.zip(loaded.files).map { (descriptor, file) -> WorkspaceSource(descriptor, file) })
                val menu = io.github.ffenuss.modkit.space.SpaceMenuCoordinator.prepare(context,
                    AnalysisTargetDescriptor.InstalledPackage(fixturePackage, "Owned fixture"), result, signal, progress, workspace)
                assertEquals(fixturePackage, menu.packageName)
                assertTrue("Fixture gameplay candidates must reach the profile", menu.candidates > 0)
                profile = File(menu.profilePath)
                val json = JSONObject(profile!!.readText())
                assertEquals("none", json.getString("backend"))
                assertEquals(result.index.artifactSha256, json.getString("artifactSha256"))
                assertEquals(installed.apkFiles.size, json.getJSONArray("sources").length())
                assertTrue(json.getJSONArray("items").length() > 0)
                val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", profile!!)
                assertTrue(uri.path!!.startsWith("/space_menu/"))
                val handoff = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes().toString(Charsets.UTF_8) }
                assertEquals(profile!!.readText(), handoff)
            }
            val after = installed.apkFiles.map { java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
            assertEquals("Menu preparation must leave all original APKs untouched", before, after)
        } finally { archive.delete(); profile?.delete() }
    }

    @Test fun a_simpleInterfaceSelectsAndBuildsWithoutExpertTools() {
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
            val method = dex.classes.single { it.type == selected.className…27243 tokens truncated…ion inventory is connected; IL2CPP code-address binding and runtime mutation controllers are not connected; there are no pretend gameplay toggles. Successful launch, overlay lifetime, all host advertising surfaces, Google sign-in and actual game compatibility still require Android device tests. Advertising SDKs and their network initialization remain present; this stage suppresses the proven launch/resume interstitial surfaces, not every possible advertisement format. Google APKs are neither fabricated nor redistributed. Their existing handling in the kernel is preserved; this module does not claim that every device already has all Google packages in its virtual user.

The old host package identity is retained because changing it previously broke virtual initialization. Do not uninstall an existing working space just to install this build. A same-package update requires its existing signing key; an unrelated key will cause Android's normal signer conflict.

## Build

Requires Python 3.10+, Java 17, apktool 2.12.1+, Android API35 SDK and build-tools35. Set:

- `MODKIT_SPACE_ENGINE_APK` — APK from `gradle :spaceengine:assembleDebug` (a private DEX carrier, never installed as a guest)
- `ANDROID_SDK_ROOT`
- `MODKIT_SPACE_KEYSTORE` — existing host/QA keystore
- `MODKIT_SPACE_ALIAS`
- `MODKIT_SPACE_STORE_PASSWORD`
- optional `MODKIT_SPACE_BUILD_TOOLS` and `MODKIT_SPACE_ANDROID_JAR`

Run `bash spacehost/build.sh /path/to/Launcher.apk /path/to/ModKit-Space.apk`.

The reference SHA256 must be `251acbe2e3199b4a7b6454a495dcdeac0a479dfa00b14066f4615fed37bc6719`. Any different or already modified APK is rejected. No guest APK is a build input.

## Verification

`python3 -m unittest discover -s spacehost/tests -v` checks bootstrap preservation and rejects unsupported inputs. Supplying `MODKIT_SPACE_REFERENCE=/path/to/Launcher.apk` also runs the exact-reference DEX tests, which check all unselected method bodies and the Google/virtual-launch ABI. The source workflow does not have the proprietary host and explicitly skips those reference-only tests.

Local verification on 2026-10-07: six Python tests passed, including both reference-only tests. Java compilation against API35 passed and the authored Java session-identity test passed. APK assembly, signing and device execution were not performed in that environment because apktool/D8 build binaries were unavailable and their download was blocked.

## Source-session verification (2026-10-07)

Java API35 compilation, `SpacePolicyTest`, `SourceInventoryTest` and all six Python tests (with the reference host) passed. The inventory fixtures cover base/split aggregation, unchanged input hashes, unique sessions, user identity, cancellation, missing/duplicate inputs, malformed ZIPs and source replacement during scanning. The reference DEX checks now also verify the `ck` and `InstalledAppInfo.f` ABI remains unchanged.

An additional attempt to inventory the previously available local game APK set failed with `zip END header not found`; no successful game inventory is claimed for those local files. The host source is not yet assembled into a signed APK or tested on a device.

The source workflow also runs on `feature/space-*` pushes and executes the new inventory tests. It still needs the proprietary reference locally for the two reference-only checks.

## Shared engine integration

`:analysiscore` contains the original ModKit artifact models, indexer, runtime fingerprint profiler and DEX/ELF inventory engines. The app retains `FastArtifactIndexer` as an adapter for its routing, evidence graph and persistent cache. The portable indexer now propagates cancellation inside ZIP traversal rather than returning a warning, and rejects ambiguous container names.

`:spaceengine` packages the shared module and its Kotlin runtime. The builder verifies its public bridge ABI, stores the carrier plus SHA-256 in new host assets, and preserves every original host entry except the already documented `classes2.dex` replacement/signatures. `SpaceEngine` checks the carrier identity, stages it read-only in private code cache, and loads engine/Kotlin namespaces separately from the old host. Only Java platform types cross the boundary. Temporary ELF files are removed after each run.

The overlay's analysis action now invokes this bridge before rechecking source hashes and virtual package metadata. A report remains structural evidence rather than an available modification. DEX analysis is bounded to the fixed header; ELF analysis includes segments and dynamic symbols; IL2CPP metadata definitions are now decoded using the same reader as the app, with a bounded inventory and explicit counts/limitations.

At commit time, local Java/API35 compilation and nine Python tests passed. JVM Kotlin tests and the actual carrier APK build are delegated to the expanded source workflow; their result must be checked before considering the integration validated.

## Metadata inventory

The host now extracts IL2CPP metadata into unique private temporary files, verifies entry sizes/CRC, and calls the shared `Il2CppMetadataReader`. Reports preserve container/path, parsed and declared counts, version/support/truncation, and a bounded selection of actual image/type/method/field names and tokens. Metadata tokens are not code addresses or change-ready mod offsets. Unsupported layouts retain diagnostics without fabricated definitions.

The phone inventory attempts at most four files, 64 MiB per file and 128 MiB in total; per-file reader limits are 20000 types, 75000 methods/fields and 2048 images. These do not reduce the main app reader's default limits. Temporary files are deleted on success, parse failure, cancellation and extraction errors. Source integrity is checked again by the host session afterward.

The shared reader now sweeps bounded field indices rather than expanding each type's range. Invalid ranges are reported; overlapping ownership remains unresolved. This avoids quadratic work on damaged metadata without inventing owners. Carrier verification requires the actual shared metadata class definitions, so an older header-only carrier is rejected. New metadata tests are pending CI at this change's publication.


## Main application and shared space

Analysis stays in the ModKit application. A completed analysis offers **Скачать пространство**. The download channel checks non-draft, non-prerelease GitHub releases for the exact `modkit-space.apk` asset; the empty channel reports that the host has not been published. It never substitutes the engine carrier or a per-game repack.

The host no longer exposes analysis controls. Its existing original application picker remains responsible for installation and launch. Launch opens the overlay menu immediately when overlay permission is granted. Settings retain separate package/virtual-user targets across host updates and can launch a previously opened target after checking it is still installed. Adding a new target does not remove other targets. Google components and the virtual kernel are retained.

These are target menu profiles, not completed runtime mod recipes: analysis-result transfer, recipe execution, artifact-version binding and different menus for different target applications are still pending. No gameplay switches are presented until a real executor exists. The `spaceengine` carrier remains packaged for internal validation but is not exposed as analysis UI in the host.

The source CI can export public Android build tools and apktool for local signing. The proprietary host and signing keystore never enter CI or git. Signed APK delivery still requires local payload verification and Android device validation before publishing the shared release asset.


## Version-bound per-application menu handoff

Each analyzed application has one menu profile. Different packages keep independent profiles in the same host; importing one never replaces another package. The application expands APK/APKS/XAPK/ZIP APK sets for indexing, scanning and subsequent SHA checks, preserving original inner filenames in unique private staging. Archive extraction validates size/CRC and deletes only its own staging on error/cancellation/close. Non-APK downloaded data/OBB and unsupported executable formats still require additional analysis backends.

Menu preparation runs automatically before completed analysis, using the same source workspace. Existing DEX and proven IL2CPP recipe discovery feeds the profile; genre inference requires at least two independent declared-symbol signals and an unambiguous match. Engine evidence and genre search priorities never imply executable support. Unsupported/ambiguous genres remain unknown. Menu JSON distinguishes candidates from statically prepared recipes and explicitly advertises `backend: none` until a guest-process executor is integrated.

The ModKit FileProvider grants a data-only JSON profile to the exact existing host MainActivity. The original host updates its intent in `onNewIntent`; lifecycle resume consumes it. The host accepts only bounded supported profiles from the ModKit provider route, atomically saves by package, and chooses the corresponding menu on launch. It compares the complete multiset of APK byte hashes/sizes against actual virtual sources, allowing kernel path/name changes but rejecting updated/missing/substituted APKs. Stale async target results do not render into the current target menu. No guest APK is modified.

Tests cover archive extraction/cancellation/limits, cautious genre planning, Android profile isolation and stale-byte rejection, plus main-app preparation from the owned APK-set fixture and readable FileProvider handoff. These tests do not prove arbitrary real-game effects or the proprietary host's on-device launch.
