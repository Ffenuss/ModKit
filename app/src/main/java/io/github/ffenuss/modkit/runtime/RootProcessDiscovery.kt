package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal

data class RootAccessProbeResult(
    val available: Boolean,
    val uid: Int?,
    val message: String,
)

data class RootRunningAppProcess(
    val pid: Int,
    val user: String,
    val processName: String,
    val packageName: String,
) {
    val isMainProcess: Boolean
        get() =
            processName == packageName
}

/**
 * Root-first process discovery used by the dedicated Root Process Lab.
 *
 * It is independent from APK analysis. Modern Android/toybox exposes a stable
 * ps projection for PID/USER/NAME; only package-like main app processes are
 * returned, so system daemons do not pollute the picker.
 */
object RootProcessDiscovery {
    private val packageRegex =
        Regex(
            "[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+(?:[:][A-Za-z0-9_.-]+)?",
        )

    fun probe(
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ): RootAccessProbeResult {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
        val result =
            try {
                runner.run(
                    command = "id -u",
                    maxOutputBytes = 256,
                    cancellation =
                        cancellation,
                )
            } catch (
                failure: AnalysisCancelledException,
            ) {
                throw failure
            } catch (failure: Throwable) {
                return RootAccessProbeResult(
                    available = false,
                    uid = null,
                    message =
                        "su недоступен: " +
                            (
                                failure.message
                                    ?: failure
                                        .javaClass
                                        .simpleName
                                ),
                )
            }

        if (
            result.truncated ||
            result.exitCode != 0
        ) {
            return RootAccessProbeResult(
                available = false,
                uid = null,
                message =
                    "Root shell не подтвердил доступ.",
            )
        }
        val uid =
            result.output
                .toString(Charsets.UTF_8)
                .trim()
                .toIntOrNull()
        return if (uid == 0) {
            RootAccessProbeResult(
                available = true,
                uid = 0,
                message =
                    "Root подтверждён: uid=0.",
            )
        } else {
            RootAccessProbeResult(
                available = false,
                uid = uid,
                message =
                    "su запустился, но uid=" +
                        (uid?.toString() ?: "?") +
                        ", нужен uid=0.",
            )
        }
    }

    fun listMainAppProcesses(
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ): List<RootRunningAppProcess> {
        val root =
            probe(
                cancellation =
                    cancellation,
                runner = runner,
            )
        require(root.available) {
            root.message
        }

        val projected =
            runner.run(
                command =
                    "ps -A -o PID,USER,NAME",
                maxOutputBytes =
                    4 * 1024 * 1024,
                cancellation =
                    cancellation,
            )
        val result =
            if (
                projected.exitCode == 0 &&
                !projected.truncated
            ) {
                projected to true
            } else {
                val fallback =
                    runner.run(
                        command =
                            "ps -A",
                        maxOutputBytes =
                            4 * 1024 * 1024,
                        cancellation =
                            cancellation,
                    )
                require(
                    fallback.exitCode == 0
                ) {
                    "Root ps недоступен на этом Android/emulator."
                }
                require(
                    !fallback.truncated
                ) {
                    "Список процессов слишком большой и был обрезан."
                }
                fallback to false
            }

        return result.first.output
            .toString(Charsets.UTF_8)
            .lineSequence()
            .drop(1)
            .mapNotNull {
                line ->
                if (result.second) {
                    parseProjectedLine(
                        line,
                    )
                } else {
                    parseFallbackLine(
                        line,
                    )
                }
            }
            .filter {
                it.isMainProcess
            }
            .distinctBy {
                it.pid
            }
            .sortedWith(
                compareBy<RootRunningAppProcess> {
                    it.packageName.lowercase()
                }.thenBy {
                    it.pid
                },
            )
            .toList()
    }

    private fun parseProjectedLine(
        line: String,
    ): RootRunningAppProcess? {
        val columns =
            line.trim()
                .split(
                    Regex("\\s+"),
                    limit = 3,
                )
        if (columns.size != 3) {
            return null
        }
        return buildProcess(
            pidText = columns[0],
            user = columns[1],
            processName =
                columns[2]
                    .trim()
                    .substringBefore(' '),
        )
    }

    private fun parseFallbackLine(
        line: String,
    ): RootRunningAppProcess? {
        val columns =
            line.trim()
                .split(
                    Regex("\\s+"),
                )
        if (columns.size < 3) {
            return null
        }
        return buildProcess(
            pidText = columns[1],
            user = columns[0],
            processName =
                columns.last(),
        )
    }

    private fun buildProcess(
        pidText: String,
        user: String,
        processName: String,
    ): RootRunningAppProcess? {
        val pid =
            pidText.toIntOrNull()
                ?: return null
        val normalizedUser =
            user.trim()
        val normalizedProcess =
            processName.trim()
        if (
            pid <= 0 ||
            normalizedUser.isBlank() ||
            !packageRegex.matches(
                normalizedProcess,
            )
        ) {
            return null
        }
        val packageName =
            normalizedProcess.substringBefore(
                ':',
            )
        if (
            !packageRegex.matches(
                packageName,
            )
        ) {
            return null
        }

        return RootRunningAppProcess(
            pid = pid,
            user =
                normalizedUser,
            processName =
                normalizedProcess,
            packageName =
                packageName,
        )
    }
}
