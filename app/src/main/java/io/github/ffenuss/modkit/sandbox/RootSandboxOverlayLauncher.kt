package io.github.ffenuss.modkit.sandbox

import io.github.ffenuss.modkit.BuildConfig
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.runtime.AndroidRootCommandRunner
import io.github.ffenuss.modkit.runtime.RootCommandRunner

data class RootSandboxOverlayLaunchResult(
    val sandboxUserId: Int,
    val overlayUserId: Int,
    val itemCount: Int,
)

object RootSandboxOverlayLauncher {
    const val CONFIG_EXTRA =
        "modkit_overlay_b64"
    private const val PER_USER_RANGE =
        100_000

    fun start(
        userId: Int,
        session: SandboxRuntimeActivationSession,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ): RootSandboxOverlayLaunchResult {
        require(userId > 0) {
            "Game sandbox must use a secondary Android profile."
        }
        require(session.records.isNotEmpty()) {
            "Overlay requires active sandbox modifications."
        }

        val packageName =
            BuildConfig.APPLICATION_ID
        val overlayUserId =
            android.os.Process.myUid() /
                PER_USER_RANGE
        val appOps =
            runner.run(
                command =
                    "appops set --user " +
                        overlayUserId +
                        " " +
                        packageName +
                        " SYSTEM_ALERT_WINDOW allow",
                maxOutputBytes =
                    16 * 1024,
                cancellation =
                    cancellation,
            )
        require(
            appOps.exitCode == 0 &&
                !appOps.truncated,
        ) {
            "Could not grant ModKit overlay app-op."
        }

        val config =
            SandboxOverlayConfigCodec
                .encode(
                    SandboxOverlayConfigCodec
                        .fromSession(
                            session,
                        ),
                )
        val component =
            packageName +
                "/.sandbox.ModMenuOverlayService"

        runner.run(
            command =
                "am stopservice --user " +
                    overlayUserId +
                    " -n " +
                    component,
            maxOutputBytes =
                16 * 1024,
            cancellation =
                cancellation,
        )

        val start =
            runner.run(
                command =
                    "am start-foreground-service --user " +
                        overlayUserId +
                        " -n " +
                        component +
                        " --es " +
                        CONFIG_EXTRA +
                        " " +
                        config,
                maxOutputBytes =
                    32 * 1024,
                cancellation =
                    cancellation,
            )
        val output =
            start.output
                .toString(
                    Charsets.UTF_8,
                )
        require(
            start.exitCode == 0 &&
                !start.truncated &&
                !output.contains(
                    "Error:",
                    ignoreCase = true,
                ) &&
                !output.contains(
                    "Exception",
                    ignoreCase = true,
                ),
        ) {
            "Could not start ModKit overlay host: " +
                output.trim()
        }

        return RootSandboxOverlayLaunchResult(
            sandboxUserId = userId,
            overlayUserId =
                overlayUserId,
            itemCount =
                session.records.size,
        )
    }

    fun stop(
        userId: Int,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ) {
        if (userId <= 0) return
        val overlayUserId =
            android.os.Process.myUid() /
                PER_USER_RANGE
        runner.run(
            command =
                "am stopservice --user " +
                    overlayUserId +
                    " -n " +
                    BuildConfig.APPLICATION_ID +
                    "/.sandbox.ModMenuOverlayService",
            maxOutputBytes = 16 * 1024,
            cancellation = cancellation,
        )
    }
}
