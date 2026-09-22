package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal

data class RootInstalledGameOverlayResult(
    val process: RootRunningAppProcess,
    val overlay: RootProcessOverlayLaunchResult,
)

/**
 * Launches an installed user-facing app when it is not running yet, resolves
 * the exact new main-process PID, and then starts the same live overlay path
 * used by direct process attach.
 */
object RootInstalledGameOverlayCoordinator {
    private const val PER_USER_RANGE =
        100_000

    fun launchAndAttach(
        packageName: String,
        label: String,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ): RootInstalledGameOverlayResult {
        require(
            packageName.matches(
                Regex(
                    "[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+",
                ),
            ),
        ) {
            "Некорректный package."
        }

        val userId =
            currentUserId(
                cancellation =
                    cancellation,
                runner = runner,
            )
        val component =
            resolveLauncher(
                packageName =
                    packageName,
                userId = userId,
                cancellation =
                    cancellation,
                runner = runner,
            )
                ?: error(
                    "У приложения не найдена launcher Activity.",
                )

        val start =
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
        val startText =
            start.output
                .toString(
                    Charsets.UTF_8,
                )
        require(
            start.exitCode == 0 &&
                !start.truncated &&
                !startText.contains(
                    "Error:",
                    ignoreCase = true,
                )
        ) {
            "Не удалось запустить приложение: " +
                startText.trim()
        }

        var resolved:
            RootRunningAppProcess? = null
        for (
            attempt in
                0 until 60
        ) {
            if (
                cancellation
                    .isCancelled()
            ) {
                throw AnalysisCancelledException()
            }
            val candidates =
                RootProcessDiscovery
                    .listMainAppProcesses(
                        cancellation =
                            cancellation,
                        runner = runner,
                    )
                    .filter {
                        it.packageName ==
                            packageName
                    }
                    .filter {
                        processUserId(
                            pid = it.pid,
                            cancellation =
                                cancellation,
                            runner = runner,
                        ) ==
                            userId
                    }

            if (candidates.size == 1) {
                resolved =
                    candidates.single()
                break
            }
            require(
                candidates.size <= 1
            ) {
                "После запуска найдено несколько main PID одного package в одном Android user."
            }
            Thread.sleep(
                100L,
            )
        }

        val process =
            resolved
                ?: error(
                    "Игра запущена, но её main process не появился за 6 секунд.",
                )

        val overlay =
            RootProcessOverlayLauncher
                .startAndBringToFront(
                    packageName =
                        packageName,
                    pid = process.pid,
                    label = label,
                    cancellation =
                        cancellation,
                    runner = runner,
                )

        return RootInstalledGameOverlayResult(
            process = process,
            overlay = overlay,
        )
    }

    private fun currentUserId(
        cancellation: CancellationSignal,
        runner: RootCommandRunner,
    ): Int {
        val result =
            runner.run(
                command =
                    "am get-current-user",
                maxOutputBytes =
                    1024,
                cancellation =
                    cancellation,
            )
        require(
            result.exitCode == 0 &&
                !result.truncated
        ) {
            "Не удалось определить текущего Android user."
        }
        return result.output
            .toString(
                Charsets.UTF_8,
            )
            .trim()
            .lineSequence()
            .lastOrNull()
            ?.trim()
            ?.toIntOrNull()
            ?: error(
                "Android user id не распознан.",
            )
    }

    private fun processUserId(
        pid: Int,
        cancellation: CancellationSignal,
        runner: RootCommandRunner,
    ): Int? {
        val result =
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
        if (
            result.exitCode != 0 ||
            result.truncated
        ) {
            return null
        }
        val uid =
            result.output
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
                ?: return null
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
