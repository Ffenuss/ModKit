package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.CancellationSignal

object RootProcessReattachCoordinator {
    fun isSameProcess(
        packageName: String,
        pid: Int,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ): Boolean {
        if (
            pid <= 0 ||
            !packageName.matches(
                Regex(
                    "[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+",
                ),
            )
        ) {
            return false
        }
        val result =
            runCatching {
                runner.run(
                    command =
                        "cat /proc/" +
                            pid +
                            "/cmdline",
                    maxOutputBytes =
                        16 * 1024,
                    cancellation =
                        cancellation,
                )
            }.getOrNull()
                ?: return false
        if (
            result.exitCode != 0 ||
            result.truncated
        ) {
            return false
        }
        val identity =
            result.output
                .toString(
                    Charsets.UTF_8,
                )
                .substringBefore(
                    '\u0000',
                )
                .trim()
        return identity ==
            packageName
    }

    fun findReplacementMainPid(
        packageName: String,
        androidUserId: Int?,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ): Int? {
        val matches =
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
                    androidUserId ==
                        null ||
                        it.androidUserId ==
                        androidUserId
                }
        return matches
            .singleOrNull()
            ?.pid
    }
}
