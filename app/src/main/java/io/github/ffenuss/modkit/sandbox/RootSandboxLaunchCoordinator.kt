package io.github.ffenuss.modkit.sandbox

import android.content.Context
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.data.InstalledAppTarget

data class RootSandboxRunningSession(
    val userId: Int,
    val packageName: String,
    val pid: Int,
    val launcherComponent: String,
    val activation: SandboxRuntimeActivationSession,
    val overlay: RootSandboxOverlayLaunchResult,
)

/**
 * One-call user flow for the root ParallelSpace path:
 * clone into the managed profile -> launch -> bind exact PID -> apply selected
 * confirmed mods -> start the floating overlay in the same sandbox user.
 */
object RootSandboxLaunchCoordinator {
    fun launch(
        context: Context,
        app: InstalledAppTarget,
        profile: SandboxProfile,
        cancellation: CancellationSignal,
    ): RootSandboxRunningSession {
        val launch =
            RootSandboxAndroidProfileManager
                .installExistingAndLaunch(
                    packageName =
                        profile.packageName,
                    cancellation =
                        cancellation,
                )

        val activation =
            RootSandboxRuntimePatchCoordinator
                .activateProfile(
                    context = context,
                    app = app,
                    profile = profile,
                    expectedPid = launch.pid,
                    cancellation = cancellation,
                )

        val overlay =
            try {
                RootSandboxOverlayLauncher
                    .start(
                        userId =
                            launch.userId,
                        session =
                            activation,
                        cancellation =
                            cancellation,
                    )
            } catch (failure: Throwable) {
                runCatching {
                    RootSandboxRuntimePatchCoordinator
                        .rollbackSession(
                            session =
                                activation,
                            cancellation =
                                cancellation,
                        )
                }
                throw IllegalStateException(
                    "Игра запущена в sandbox, но mod menu не стартовало; " +
                        "изменения были откатаны: " +
                        (
                            failure.message
                                ?: failure
                                    .javaClass
                                    .simpleName
                            ),
                    failure,
                )
            }

        return RootSandboxRunningSession(
            userId = launch.userId,
            packageName = launch.packageName,
            pid = launch.pid,
            launcherComponent =
                launch.launcherComponent,
            activation = activation,
            overlay = overlay,
        )
    }

    fun stopOverlayAndRollback(
        session: RootSandboxRunningSession,
        cancellation: CancellationSignal,
    ) {
        runCatching {
            RootSandboxOverlayLauncher.stop(
                userId = session.userId,
                cancellation = cancellation,
            )
        }
        RootSandboxRuntimePatchCoordinator
            .rollbackSession(
                session =
                    session.activation,
                cancellation =
                    cancellation,
            )
    }
}
