package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.analysis.CancellationSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootProcessDiscoveryTest {
    @Test
    fun rootProbeAndProcessListReturnOnlyMainPackageProcesses() {
        val runner =
            RootCommandRunner {
                    command,
                    _,
                    _,
                ->
                when (command) {
                    "id -u" ->
                        result("0\n")
                    "ps -A -o PID,USER,NAME" ->
                        result(
                            "PID USER NAME\n" +
                                "100 u0_a1 com.example.game\n" +
                                "101 u0_a1 com.example.game:remote\n" +
                                "200 root surfaceflinger\n" +
                                "300 u0_a2 com.example.app\n",
                        )
                    else ->
                        RootCommandResult(
                            exitCode = 1,
                            output =
                                ByteArray(0),
                            truncated = false,
                        )
                }
            }

        val probe =
            RootProcessDiscovery.probe(
                cancellation =
                    AtomicCancellationSignal(),
                runner = runner,
            )
        val processes =
            RootProcessDiscovery
                .listMainAppProcesses(
                    cancellation =
                        AtomicCancellationSignal(),
                    runner = runner,
                )

        assertTrue(probe.available)
        assertEquals(
            listOf(
                "com.example.app",
                "com.example.game",
            ),
            processes.map {
                it.packageName
            },
        )
        assertTrue(
            processes.all {
                it.isMainProcess
            },
        )
        assertFalse(
            processes.any {
                it.processName
                    .contains(":")
            },
        )
    }

    @Test
    fun fallsBackToDefaultPsProjectionForRootedEmulator() {
        val runner =
            RootCommandRunner {
                    command,
                    _,
                    _,
                ->
                when (command) {
                    "id -u" ->
                        result("0\n")
                    "ps -A -o PID,USER,NAME" ->
                        RootCommandResult(
                            exitCode = 1,
                            output =
                                ByteArray(0),
                            truncated = false,
                        )
                    "ps -A" ->
                        result(
                            "USER PID PPID VSZ RSS WCHAN ADDR S NAME\n" +
                                "u0_a42 777 1 0 0 0 0 S com.example.emugame\n",
                        )
                    else ->
                        RootCommandResult(
                            exitCode = 1,
                            output =
                                ByteArray(0),
                            truncated = false,
                        )
                }
            }

        val processes =
            RootProcessDiscovery
                .listMainAppProcesses(
                    cancellation =
                        AtomicCancellationSignal(),
                    runner = runner,
                )

        assertEquals(1, processes.size)
        assertEquals(
            777,
            processes.single().pid,
        )
        assertEquals(
            "com.example.emugame",
            processes.single()
                .packageName,
        )
    }

    @Test
    fun nonRootProbeFailsClosed() {
        val runner =
            RootCommandRunner {
                    _,
                    _,
                    _,
                ->
                result("2000\n")
            }

        val probe =
            RootProcessDiscovery.probe(
                cancellation =
                    AtomicCancellationSignal(),
                runner = runner,
            )

        assertFalse(probe.available)
        assertEquals(2000, probe.uid)
    }

    private fun result(
        text: String,
    ) =
        RootCommandResult(
            exitCode = 0,
            output =
                text.toByteArray(),
            truncated = false,
        )
}
