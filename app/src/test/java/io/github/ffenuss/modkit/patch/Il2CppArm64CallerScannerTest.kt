package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.ArtifactIndex
import io.github.ffenuss.modkit.analysis.ArtifactSource
import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.analysis.EngineRoutingPlan
import io.github.ffenuss.modkit.analysis.EvidenceGraph
import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.UserFindingStatus
import io.github.ffenuss.modkit.domain.ProofLevel
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Il2CppArm64CallerScannerTest {
    @Test
    fun findsExactDirectCallerInsideProvenBodySpan() {
        val root =
            Files.createTempDirectory(
                "modkit-callers-",
            ).toFile()
        try {
            val nativeDir =
                File(
                    root,
                    SHA +
                        "/il2cpp/native",
                ).apply {
                    mkdirs()
                }
            val library =
                File(
                    nativeDir,
                    "arm64-v8a-libil2cpp.so",
                )
            val bytes =
                ByteArray(64)
            putWord(
                bytes,
                0,
                0x94000004L,
            )
            putWord(
                bytes,
                4,
                0xD65F03C0L,
            )
            putWord(
                bytes,
                16,
                0xD65F03C0L,
            )
            putWord(
                bytes,
                32,
                0xD65F03C0L,
            )
            library.writeBytes(bytes)

            val source =
                target(
                    id = "source",
                    name =
                        "Game.Player.Update",
                    offset = 0,
                    address = 0x1000,
                    token = 1,
                )
            val selected =
                target(
                    id = "selected",
                    name =
                        "Game.Player.Tick",
                    offset = 16,
                    address = 0x1010,
                    token = 2,
                )
            val endMarker =
                target(
                    id = "next",
                    name =
                        "Game.Player.Next",
                    offset = 32,
                    address = 0x1020,
                    token = 3,
                )
            val result =
                analysis(
                    listOf(
                        source,
                        selected,
                        endMarker,
                    ),
                )

            val scan =
                Il2CppArm64CallerScanner
                    .scan(
                        result = result,
                        target = selected,
                        analysisResultsRoot =
                            root,
                        cancellation =
                            AtomicCancellationSignal(),
                        maxMethods = 16,
                        maxMethodBytes = 64,
                        maxResults = 16,
                    )

            assertEquals(1, scan.callers.size)
            assertEquals(
                "source",
                scan.callers.single()
                    .callerTargetIds
                    .single(),
            )
            assertEquals(
                0x1000L,
                scan.callers.single()
                    .callSiteBinaryVirtualAddress,
            )
            assertTrue(
                scan.scannedBodies >= 2,
            )
            assertFalse(
                scan.truncatedByResultLimit,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ignoresBlEncodingOutsideProvenMethodSpan() {
        val root =
            Files.createTempDirectory(
                "modkit-callers-span-",
            ).toFile()
        try {
            val nativeDir =
                File(
                    root,
                    SHA +
                        "/il2cpp/native",
                ).apply {
                    mkdirs()
                }
            val bytes =
                ByteArray(64)
            // The matching BL sits after source's proven 8-byte body.
            putWord(
                bytes,
                8,
                0x94000002L,
            )
            File(
                nativeDir,
                "arm64-v8a-libil2cpp.so",
            ).writeBytes(bytes)

            val source =
                target(
                    id = "source",
                    name =
                        "Game.Source.Run",
                    offset = 0,
                    address = 0x2000,
                    token = 1,
                )
            val boundary =
                target(
                    id = "boundary",
                    name =
                        "Game.Boundary.Run",
                    offset = 8,
                    address = 0x2008,
                    token = 2,
                )
            val selected =
                target(
                    id = "selected",
                    name =
                        "Game.Target.Run",
                    offset = 16,
                    address = 0x2010,
                    token = 3,
                )
            val next =
                target(
                    id = "next",
                    name =
                        "Game.Next.Run",
                    offset = 32,
                    address = 0x2020,
                    token = 4,
                )

            val scan =
                Il2CppArm64CallerScanner
                    .scan(
                        result =
                            analysis(
                                listOf(
                                    source,
                                    boundary,
                                    selected,
                                    next,
                                ),
                            ),
                        target = selected,
                        analysisResultsRoot =
                            root,
                        cancellation =
                            AtomicCancellationSignal(),
                    )

            assertTrue(
                scan.callers.none {
                    "source" in
                        it.callerTargetIds
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun analysis(
        targets:
            List<EvidenceTarget>,
    ) =
        FastAnalysisResult(
            index =
                ArtifactIndex(
                    artifactSha256 =
                        SHA,
                    sources =
                        listOf(
                            ArtifactSource(
                                "base.apk",
                                64,
                                SHA,
                            ),
                        ),
                    entries =
                        emptyList(),
                ),
            routingPlan =
                EngineRoutingPlan(
                    emptyList(),
                    emptyList(),
                ),
            elapsedMs = 1,
            evidenceGraph =
                EvidenceGraph(
                    artifactSha256 =
                        SHA,
                    targets =
                        targets,
                ),
        )

    private fun target(
        id: String,
        name: String,
        offset: Long,
        address: Long,
        token: Long,
    ) =
        EvidenceTarget(
            id = id,
            runtimeId =
                "unity_il2cpp",
            kind =
                EvidenceTargetKind.METHOD,
            displayName = name,
            artifact = ARTIFACT,
            abi = "arm64-v8a",
            declaringType =
                name.substringBeforeLast(
                    '.',
                ),
            memberName =
                name.substringAfterLast(
                    '.',
                ),
            metadataToken = token,
            rva =
                address - 0x1000,
            binaryVirtualAddress =
                address,
            runtimeVirtualAddress =
                null,
            fileOffset =
                offset,
            proofLevel =
                ProofLevel.EXACT_BINARY,
            userStatus =
                UserFindingStatus.CONFIRMED,
            blockers =
                emptyList(),
            facts =
                emptyList(),
        )

    private fun putWord(
        bytes: ByteArray,
        offset: Int,
        word: Long,
    ) {
        bytes[offset] =
            word.toByte()
        bytes[offset + 1] =
            (word ushr 8)
                .toByte()
        bytes[offset + 2] =
            (word ushr 16)
                .toByte()
        bytes[offset + 3] =
            (word ushr 24)
                .toByte()
    }

    companion object {
        private const val SHA =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val ARTIFACT =
            "base.apk:lib/arm64-v8a/libil2cpp.so"
    }
}
