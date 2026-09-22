package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.BuildConfig
import io.github.ffenuss.modkit.analysis.CancellationSignal

data class RootProcessOverlayLaunchResult(
    val packageName: String,
    val pid: Int,
    val androidUserId: Int,
    val launcherComponent: String?,
)

object RootProcessOverlayLauncher {
    const val CONFIG_EXTRA =
        "modkit_process_overlay_b64"
    private const val PER_USER_RANGE =
        100_000

    fun startAndBringToFront(
        packageName: String,
        pid: Int,
        label: String,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ): RootProcessOverlayLaunchResult {
        require(pid > 0) {
            "PID должен быть положительным."
        }

        val capture =
            RootRuntimeCaptureCoordinator
                .captureMaps(
                    packageName =
                        packageName,
                    cancellation =
                        cancellation,
                    runner = runner,
                    expectedPid = pid,
                )
        require(capture.pid == pid) {
            "Выбранный процесс больше не существует."
        }

        val userId =
            resolveAndroidUserId(
                pid = pid,
                cancellation = cancellation,
                runner = runner,
            )

        val overlayUserId =
            android.os.Process.myUid() /
                PER_USER_RANGE
        val appOps =
            runner.run(
                command =
                    "appops set --user " +
                        overlayUserId +
                        " " +
                        BuildConfig.APPLICATION_ID +
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
            "Не удалось разрешить overlay для ModKit."
        }

        val component =
            resolveLauncher(
                packageName =
                    packageName,
                userId =
                    userId,
                cancellation =
                    cancellation,
                runner = runner,
            )
        if (component != null) {
            val foreground =
                runner.run(
                    command =
                        "am start --user " +
                            userId +
                            " -n " +
                            component,
                    maxOutputBytes =
                        32 * 1024,
                    cancellation =
                        cancellation,
                )
            require(
                foreground.exitCode == 0,
            ) {
                "Не удалось вернуть выбранную игру на передний план."
            }
        }

        val config =
            ProcessOverlayConfigCodec
                .encode(
                    ProcessOverlayConfig(
                        packageName =
                            packageName,
                        pid = pid,
                        label =
                            label.ifBlank {
                                packageName
                            },
                    ),
                )
        val service =
            BuildConfig.APPLICATION_ID +
                "/.runtime.LiveProcessOverlayService"

        runner.run(
            command =
                "am stopservice --user " +
                    overlayUserId +
                    " -n " +
                    service,
            maxOutputBytes =
                16 * 1024,
            cancellation =
                cancellation,
        )
        val started =
            runner.run(
                command =
                    "am start-foreground-service --user " +
                        overlayUserId +
                        " -n " +
                        service +
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
            started.output
                .toString(
                    Charsets.UTF_8,
                )
        require(
            started.exitCode == 0 &&
                !started.truncated &&
                !output.contains(
                    "Error:",
                    ignoreCase = true,
                ) &&
                !output.contains(
                    "Exception",
                    ignoreCase = true,
                ),
        ) {
            "Не удалось запустить MK live overlay: " +
                output.trim()
        }

        return RootProcessOverlayLaunchResult(
            packageName =
                packageName,
            pid = pid,
            androidUserId =
                userId,
            launcherComponent =
                component,
        )
    }

    private fun resolveAndroidUserId(
        pid: Int,
        cancellation: CancellationSignal,
        runner: RootCommandRunner,
    ): Int {
        val status =
            runner.run(
                command =
                    "cat /proc/" +
                        pid +
                        "/status",
                maxOutputBytes =
                    64 * 1024,
                cancellation =
                    cancellation,
            )
        require(
            status.exitCode == 0 &&
                !status.truncated,
        ) {
            "Не удалось определить Android user выбранного процесса."
        }
        val uid =
            status.output
                .toString(
                    Charsets.UTF_8,
                )
                .lineSequence()
                .firstOrNull {
                    it.startsWith(
                        "Uid:",
                    )
                }
                ?.substringAfter(
                    "Uid:",
                )
                ?.trim()
                ?.split(
                    Regex(
                        "\\s+",
                    ),
                )
                ?.firstOrNull()
                ?.toIntOrNull()
                ?: error(
                    "UID выбранного процесса не найден.",
                )
        return uid /
            PER_USER_RANGE
    }

    private fun resolveLauncher(
        packageName: String,
        userId: Int,
        cancellation: CancellationSignal,
        runner: RootCommandRunner,
    ): String? {
        val result =
            runner.run(
                command =
                    "cmd package resolve-activity --brief --user " +
                        userId +
                        " -a android.intent.action.MAIN " +
                        "-c android.intent.category.LAUNCHER " +
                        packageName,
                maxOutputBytes =
                    32 * 1024,
                cancellation =
                    cancellation,
            )
        if (
            result.exitCode != 0 ||
            result.truncated
        ) {
            return null
        }
        return result.output
            .toString(
                Charsets.UTF_8,
            )
            .lineSequence()
            .map {
                it.trim()
            }
            .lastOrNull {
                it.contains(
                    '/',
                ) &&
                    !it.startsWith(
                        "priority=",
                    )
            }
    }
}
