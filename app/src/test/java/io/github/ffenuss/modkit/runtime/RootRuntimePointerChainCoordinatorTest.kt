package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootRuntimePointerChainCoordinatorTest {
    @Test
    fun discoversStableModuleRootPointerWithFieldOffset() {
        val target =
            0x3000_0020L
        val modulePage =
            ByteArray(PAGE_BYTES)
        putLongLe(
            modulePage,
            0x100,
            0x3000_0000L,
        )
        val runner =
            FakeRunner(
                pages =
                    mapOf(
                        0x1000_0000L to
                            modulePage,
                        0x3000_0000L to
                            ByteArray(
                                PAGE_BYTES,
                            ),
                    ),
            )

        val result =
            RootRuntimePointerChainCoordinator
                .discover(
                    packageName =
                        PACKAGE,
                    pid = PID,
                    targetAddress =
                        target,
                    cancellation =
                        AtomicCancellationSignal(),
                    runner = runner,
                    maxDepth = 1,
                    maxOffset = 0x100,
                    maxScanBytesPerDepth =
                        PAGE_BYTES.toLong(),
                )

        val anchor =
            assertNotNull(
                result.stableAnchor,
            ) as StableRuntimePointerAnchor
        assertEquals(
            "libgame.so",
            anchor.moduleIdentity,
        )
        assertEquals(
            0x100L,
            anchor.moduleFileOffset,
        )
        assertEquals(
            listOf(0x20L),
            anchor.offsetsFromAnchor,
        )
    }

    @Test
    fun resolvesPersistedPointerChainAfterAslrMove() {
        val modulePage =
            ByteArray(PAGE_BYTES)
        putLongLe(
            modulePage,
            0x100,
            0x4000_0000L,
        )
        val heapPage =
            ByteArray(PAGE_BYTES)
        putLongLe(
            heapPage,
            0x20,
            0x5000_0000L,
        )
        val runner =
            FakeRunner(
                maps =
                    (
                        "18000000-18001000 rw-p 00000000 103:02 42 /data/app/pkg/lib/arm64/libgame.so\n" +
                            "40000000-40001000 rw-p 00000000 00:00 0 [heap]\n" +
                            "50000000-50001000 rw-p 00000000 00:00 0 [anon:game]\n"
                        ),
                pages =
                    mapOf(
                        0x1800_0000L to
                            modulePage,
                        0x4000_0000L to
                            heapPage,
                        0x5000_0000L to
                            ByteArray(
                                PAGE_BYTES,
                            ),
                    ),
            )

        val resolved =
            RootRuntimePointerChainCoordinator
                .resolve(
                    packageName =
                        PACKAGE,
                    pid = PID,
                    anchor =
                        StableRuntimePointerAnchor(
                            moduleIdentity =
                                "libgame.so",
                            moduleFileOffset =
                                0x100L,
                            pointerWidth = 8,
                            offsetsFromAnchor =
                                listOf(
                                    0x20L,
                                    0x10L,
                                ),
                        ),
                    cancellation =
                        AtomicCancellationSignal(),
                    runner = runner,
                )

        assertEquals(
            0x5000_0010L,
            resolved.targetAddress,
        )
        assertEquals(
            0x1800_0100L,
            resolved.anchorAddress,
        )
    }

    @Test
    fun ambiguousModuleAnchorFailsClosed() {
        val runner =
            FakeRunner(
                maps =
                    (
                        "10000000-10001000 rw-p 00000000 103:02 42 /data/app/a/libgame.so\n" +
                            "18000000-18001000 rw-p 00000000 103:03 43 /data/app/b/libgame.so\n" +
                            "30000000-30001000 rw-p 00000000 00:00 0 [heap]\n"
                        ),
                pages =
                    emptyMap(),
            )

        val failure =
            runCatching {
                RootRuntimePointerChainCoordinator
                    .resolve(
                        packageName =
                            PACKAGE,
                        pid = PID,
                        anchor =
                            StableRuntimePointerAnchor(
                                moduleIdentity =
                                    "libgame.so",
                                moduleFileOffset =
                                    0x100L,
                                pointerWidth = 8,
                                offsetsFromAnchor =
                                    listOf(
                                        0x20L,
                                    ),
                            ),
                        cancellation =
                            AtomicCancellationSignal(),
                        runner = runner,
                    )
            }.exceptionOrNull()

        assertTrue(
            failure is
                IllegalArgumentException,
        )
        assertTrue(
            failure
                ?.message
                .orEmpty()
                .contains(
                    "ambiguous",
                    ignoreCase = true,
                ),
        )
    }

    private class FakeRunner(
        private val maps: String =
            (
                "10000000-10001000 rw-p 00000000 103:02 42 /data/app/pkg/lib/arm64/libgame.so\n" +
                    "30000000-30001000 rw-p 00000000 00:00 0 [heap]\n"
                ),
        private val pages:
            Map<Long, ByteArray>,
    ) : RootCommandRunner {
        override fun run(
            command: String,
            maxOutputBytes: Int,
            cancellation:
                io.github.ffenuss.modkit.analysis.CancellationSignal,
        ): RootCommandResult =
            when {
                command == "id -u" ->
                    result(
                        "0",
                    )
                command ==
                    "pidof $PACKAGE" ->
                    result(
                        PID.toString(),
                    )
                command ==
                    "cat /proc/$PID/cmdline" ->
                    result(
                        PACKAGE +
                            "\u0000",
                    )
                command ==
                    "cat /proc/$PID/maps" ->
                    result(
                        maps,
                    )
                command.startsWith(
                    "dd if=/proc/$PID/mem bs=4096 skip=",
                ) ->
                    readPage(
                        command,
                    )
                command.startsWith(
                    "dd if=/proc/$PID/mem bs=1 skip=",
                ) ->
                    RootCommandResult(
                        1,
                        ByteArray(0),
                        false,
                    )
                else ->
                    RootCommandResult(
                        1,
                        ByteArray(0),
                        false,
                    )
            }

        private fun readPage(
            command: String,
        ): RootCommandResult {
            val skip =
                command
                    .substringAfter(
                        "skip=",
                    )
                    .substringBefore(
                        ' ',
                    )
                    .toLong()
            val count =
                command
                    .substringAfter(
                        "count=",
                    )
                    .substringBefore(
                        ' ',
                    )
                    .toInt()
            val output =
                ByteArray(
                    count *
                        PAGE_BYTES,
                )
            repeat(count) {
                index ->
                val address =
                    (
                        skip +
                            index
                        ) *
                        PAGE_BYTES
                pages[address]
                    ?.copyInto(
                        output,
                        destinationOffset =
                            index *
                                PAGE_BYTES,
                    )
            }
            return RootCommandResult(
                0,
                output,
                false,
            )
        }

        private fun result(
            text: String,
        ): RootCommandResult =
            RootCommandResult(
                0,
                text.toByteArray(),
                false,
            )
    }

    companion object {
        private const val PACKAGE =
            "com.example.game"
        private const val PID =
            321
        private const val PAGE_BYTES =
            4096

        private fun putLongLe(
            buffer: ByteArray,
            offset: Int,
            value: Long,
        ) {
            repeat(8) {
                index ->
                buffer[
                    offset +
                        index
                    ] =
                    (
                        value ushr
                            (
                                index *
                                    8
                                )
                        ).toByte()
            }
        }
    }
}
