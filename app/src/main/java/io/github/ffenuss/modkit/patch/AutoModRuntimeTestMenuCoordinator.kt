package io.github.ffenuss.modkit.patch

import android.content.Context
import io.github.ffenuss.modkit.analysis.AnalysisTargetDescriptor
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.EngineResultCache
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.runtime.AndroidRepackedRuntimeProbeTransport
import io.github.ffenuss.modkit.runtime.BinaryAndroidManifestProbeInjector
import io.github.ffenuss.modkit.runtime.RepackedRuntimeBuildCoordinator
import io.github.ffenuss.modkit.runtime.RepackedRuntimeBuildResult
import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstrumentationCoordinator
import io.github.ffenuss.modkit.runtime.RepackedRuntimeProbeIdentityVerifier
import io.github.ffenuss.modkit.runtime.RepackedRuntimeTestAppLauncher
import io.github.ffenuss.modkit.runtime.RepackedRuntimeTestMenuStatus
import io.github.ffenuss.modkit.runtime.RepackedRuntimeDexSwitchInjector
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AutoModRuntimeTestMenuBuild(
    val build: RepackedRuntimeBuildResult,
    val menu: RuntimeGameplayTestMenuSpec,
)

data class AutoModRuntimeTestMenuLaunch(
    val menuStatus: RepackedRuntimeTestMenuStatus,
    val activityClassName: String,
)

/**
 * Builds a repacked test APK containing ModKit's runtime probe and an in-game
 * menu. Static source APKs are reopened and SHA-checked before instrumentation.
 */
object AutoModRuntimeTestMenuCoordinator {
    suspend fun build(
        context: Context,
        target: AnalysisTargetDescriptor,
        result: FastAnalysisResult,
        preparation: PatchPreparationPlan,
        cancellation: CancellationSignal,
        progress: ProgressSink,
        selected: List<AutoModRecipe>? = null,
    ): AutoModRuntimeTestMenuBuild =
        withContext(Dispatchers.IO) {
            require((selected != null && selected.none { it.native != null }) || preparation.sourceShaVerified) {
                "Исходная версия не подтверждена по SHA-256."
            }
            require(
                preparation.artifactSha256.equals(
                    result.index.artifactSha256,
                    ignoreCase = true,
                ),
            ) {
                "План подготовки относится к другой версии приложения."
            }

            val menu = if (selected == null) {
                // Existing expert test-menu workflow remains available.
                RuntimeGameplayTestMenuBuilder.build(
                    result = result,
                    preparation = preparation,
                    analysisResultsRoot = File(context.filesDir, "analysis-results"),
                    stagingRoot = File(context.filesDir, "runtime-menu-staging"),
                )
            } else {
                // The simple AutoMod build must include only the user's
                // selected switches. It must never bake in static changes.
                SelectedRuntimeMenuBuilder.build(
                    result = result,
                    selected = selected,
                    analysisResultsRoot = File(context.filesDir, "analysis-results"),
                    cancellation = cancellation,
                )
            }
            require(menu.items.isNotEmpty()) {
                "ModKit пока не нашёл ни одной цели для runtime test menu."
            }

            val cache =
                EngineResultCache(
                    File(
                        context.filesDir,
                        "analysis-cache",
                    ),
                )
            val snapshot =
                PatchWorkspaceProvider.open(
                    context = context,
                    target = target,
                    expected = result,
                    cache = cache,
                    cancellation = cancellation,
                    progress = progress,
                )
            snapshot.use {
                val outputRoot =
                    File(
                        context.filesDir,
                        "automod-runtime-test/" + UUID.randomUUID().toString(),
                    )
                try {
                    val instrumentation =
                        RepackedRuntimeInstrumentationCoordinator
                            .instrumentNativeLookup(
                                context = context,
                                workspace = snapshot.workspace,
                                outputRoot = outputRoot,
                                cancellation = cancellation,
                            )
                    val dexSelections = selected.orEmpty().flatMap { recipe -> recipe.dex.map {
                        DexRuntimeSelection(it, DexRuntimeSwitchRewriter.switchId(recipe.id))
                    } }
                    val dexInjection = if (dexSelections.isEmpty()) null else RepackedRuntimeDexSwitchInjector.inject(
                        instrumentation.nativeProbeInjection, result.index.sources.map { it.displayName },
                        dexSelections, outputRoot, cancellation,
                    )
                    val build = if (dexInjection != null) RepackedRuntimeBuildCoordinator.buildDexSwitchInjected(
                        context, instrumentation.base.manifestInventory, dexInjection, outputRoot, cancellation, progress,
                    ) else RepackedRuntimeBuildCoordinator.buildNativeProbeInjected(
                                context = context,
                                manifestInventory =
                                    instrumentation
                                        .base
                                        .manifestInventory,
                                injection =
                                    instrumentation
                                        .nativeProbeInjection,
                                outputRoot = outputRoot,
                                cancellation = cancellation,
                                progress = progress,
                            )
                    require(
                        build.artifactSha256.equals(
                            result.index.artifactSha256,
                            ignoreCase = true,
                        ),
                    ) {
                        "Runtime test build относится к другой версии цели."
                    }

                    RepackedRuntimeInstrumentationCoordinator.cleanup(instrumentation.base, outputRoot)
                    AutoModRuntimeTestMenuBuild(
                        build = build,
                        menu = menu,
                    )
                } catch (failure: Throwable) {
                    // This directory belongs only to this attempt; earlier builds remain installable.
                    outputRoot.deleteRecursively()
                    throw failure
                }
            }
        }

    suspend fun configureAndLaunch(
        context: Context,
        prepared: AutoModRuntimeTestMenuBuild,
    ): AutoModRuntimeTestMenuLaunch =
        withContext(Dispatchers.IO) {
            val build = prepared.build
            val authority =
                build.packageName +
                    BinaryAndroidManifestProbeInjector
                        .AUTHORITY_SUFFIX
            val transport =
                AndroidRepackedRuntimeProbeTransport(
                    context,
                )
            val installed =
                requireNotNull(
                    transport.inspectInstalled(
                        packageName =
                            build.packageName,
                        authority = authority,
                    ),
                ) {
                    "Установленный runtime test APK не найден."
                }
            RepackedRuntimeProbeIdentityVerifier.verify(
                build = build,
                installed = installed,
            )

            val status =
                transport.configureTestMenu(
                    authority = authority,
                    items = prepared.menu.items,
                )
            require(
                status.packageName ==
                    build.packageName &&
                    status.itemCount ==
                    prepared.menu.items.size
            ) {
                "Runtime test menu не подтвердило загруженную конфигурацию."
            }

            val launch =
                RepackedRuntimeTestAppLauncher.launch(
                    context = context,
                    build = build,
                )
            AutoModRuntimeTestMenuLaunch(
                menuStatus = status,
                activityClassName =
                    launch.activityClassName,
            )
        }
}
