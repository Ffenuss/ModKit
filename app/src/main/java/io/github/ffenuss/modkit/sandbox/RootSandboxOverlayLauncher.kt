package io.github.ffenuss.modkit.sandbox

import io.github.ffenuss.modkit.BuildConfig
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.runtime.AndroidRootCommandRunner
import io.github.ffenuss.modkit.runtime.RootCommandRunner

data class RootSandboxOverlayLaunchResult(
    val userId: Int,
    val itemCount: Int,
)

object RootSandboxOverlayLauncher {
    const val CONFIG_EXTRA =
        "modkit_overlay_b64"

    fun start(
        userId: Int,
        session: SandboxRuntimeActivationSession,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ): RootSandboxOverlayLaunchResult {
        require(userId > 0) {
            "Overlay must run in sandbox user."
        }
        require(session.records.isNotEmpty()) {
            "Overlay requires active sandbox modifications."
        }
        val packageName =
            BuildConfig.APPLICATION_ID

        installExisting(
            userId = userId,
            packageName = packageName,
            cancellation = cancellation,
            runner = runner,
        )
        val appOps =
            runner.run(
                command =
                    "appops set --user " +
                        userId +
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
            "Could not grant sandbox overlay app-op."
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
        val start =
            runner.run(
                command =
                    "am start-foreground-service --user " +
                        userId +
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
            "Could not start ModKit overlay inside sandbox user: " +
                output.trim()
        }

        return RootSandboxOverlayLaunchResult(
            userId = userId,
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
        runner.run(
            command =
                "am stopservice --user " +
                    userId +
                    " -n " +
                    BuildConfig.APPLICATION_ID +
                    "/.sandbox.ModMenuOverlayService",
            maxOutputBytes = 16 * 1024,
            cancellation = cancellation,
        )
    }

    private fun installExisting(
        userId: Int,
        packageName: String,
        cancellation: CancellationSignal,
        runner: RootCommandRunner,
    ) {
        val primary =
            runner.run(
                command =
                    "cmd package install-existing --user " +
                        userId +
                        " " +
                        packageName,
                maxOutputBytes =
                    32 * 1024,
                cancellation =
                    cancellation,
            )
        if (primary.exitCode == 0) {
            return
        }
        val fallback =
            runner.run(
                command =
                    "pm install-existing --user " +
                        userId +
                        " " +
                        packageName,
                maxOutputBytes =
                    32 * 1024,
                cancellation =
                    cancellation,
            )
        require(
            fallback.exitCode == 0,
        ) {
            "Could not install ModKit overlay host into sandbox profile."
        }
    }
}
