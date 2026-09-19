package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.ArtifactIndex
import io.github.ffenuss.modkit.analysis.ArtifactSource
import io.github.ffenuss.modkit.analysis.ElfLoadSegment
import io.github.ffenuss.modkit.analysis.EngineRoutingPlan
import io.github.ffenuss.modkit.analysis.EvidenceGraph
import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.UserFindingStatus
import io.github.ffenuss.modkit.domain.ProofLevel
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeModuleEvidenceTest {
    @Test
    fun confirmsUniqueLoadBiasFromIndependentPtLoadMappings() {
        val mapping = RuntimeModuleMappingResolver.resolve(
            moduleName = "libil2cpp.so",
            loadSegments = segments(),
            regions = regions(),
        )

        assertTrue(mapping.confirmed)
        assertEquals(0x70000000L, mapping.loadBias)
        assertEquals(2, mapping.matchedLoadSegments)
        assertEquals(1, mapping.matchedExecutableSegments)
        assertTrue(mapping.zeroOffsetMappingMatched)
        assertTrue(mapping.blockers.isEmpty())
    }

    @Test
    fun refusesSingleSegmentFilenameMatchAsExactMapping() {
        val mapping = RuntimeModuleMappingResolver.resolve(
            moduleName = "libil2cpp.so",
            loadSegments = segments(),
            regions = listOf(regions().first()),
        )

        assertFalse(mapping.confirmed)
        assertTrue(mapping.blockers.isNotEmpty())
    }

    @Test
    fun integratesExactBinaryTargetIntoRuntimeConfirmedAddress() {
        val mapsText = mapsText()
        val mapping = RuntimeModuleMappingResolver.resolve(
            moduleName = "libil2cpp.so",
            loadSegments = segments(),
            regions = ProcMapsParser.parse(mapsText),
        )
        val result = exactBinaryResult()
        val evidence = RuntimeEvidenceBundle(
            artifactSha256 = SHA,
            procMapsSha256 = sha256(mapsText.toByteArray()),
            moduleMappings = listOf(mapping),
            addressConfirmations = emptyList(),
            blockers = emptyList(),
        )

        val integrated = RuntimeEvidenceIntegrator.integrate(
            result = result,
            evidence = evidence,
            procMapsText = mapsText,
        )

        val target = requireNotNull(integrated.evidenceGraph).targets.single()
        assertEquals(ProofLevel.RUNTIME_CONFIRMED, target.proofLevel)
        assertEquals(UserFindingStatus.CONFIRMED, target.userStatus)
        assertEquals(0x20100L, target.rva)
        assertEquals(0x70020100L, target.runtimeVirtualAddress)
        assertTrue(
            target.facts.any {
                it.engineId == "runtime.module-map" &&
                    "runtimeVA=0x70020100" in it.summary
            },
        )
        assertEquals(
            0x70020100L,
            integrated.runtimeEvidence
                ?.addressConfirmations
                ?.single()
                ?.runtimeVirtualAddress,
        )
    }

    @Test
    fun runtimeEvidenceWithWrongArtifactShaNeverUpgradesTarget() {
        val mapsText = mapsText()
        val mapping = RuntimeModuleMappingResolver.resolve(
            moduleName = "libil2cpp.so",
            loadSegments = segments(),
            regions = ProcMapsParser.parse(mapsText),
        )
        val result = exactBinaryResult()
        val evidence = RuntimeEvidenceBundle(
            artifactSha256 = OTHER_SHA,
            procMapsSha256 = sha256(mapsText.toByteArray()),
            moduleMappings = listOf(mapping),
            addressConfirmations = emptyList(),
            blockers = emptyList(),
        )

        val integrated = RuntimeEvidenceIntegrator.integrate(
            result = result,
            evidence = evidence,
            procMapsText = mapsText,
        )

        val target = requireNotNull(integrated.evidenceGraph).targets.single()
        assertEquals(ProofLevel.EXACT_BINARY, target.proofLevel)
        assertEquals(null, target.runtimeVirtualAddress)
        assertTrue(
            integrated.engineWarnings.any {
                "artifact SHA mismatch" in it
            },
        )
    }

    private fun exactBinaryResult(): FastAnalysisResult {
        val target = EvidenceTarget(
            id = TARGET_ID,
            runtimeId = "unity_il2cpp",
            kind = EvidenceTargetKind.METHOD,
            displayName = "Game.Player.Hit",
            artifact = "base.apk:lib/arm64-v8a/libil2cpp.so",
            abi = "arm64-v8a",
            declaringType = "Game.Player",
            memberName = "Hit",
            metadataToken = 0x06000001,
            rva = null,
            binaryVirtualAddress = 0x20100,
            runtimeVirtualAddress = null,
            fileOffset = 0x20100,
            proofLevel = ProofLevel.EXACT_BINARY,
            userStatus = UserFindingStatus.CONFIRMED,
            blockers = emptyList(),
            facts = emptyList(),
        )
        return FastAnalysisResult(
            index = ArtifactIndex(
                artifactSha256 = SHA,
                sources = listOf(ArtifactSource("base.apk", 1, SHA)),
                entries = emptyList(),
            ),
            routingPlan = EngineRoutingPlan(emptyList(), emptyList()),
            elapsedMs = 1,
            evidenceGraph = EvidenceGraph(SHA, listOf(target)),
        )
    }

    private fun segments() = listOf(
        ElfLoadSegment(
            virtualAddress = 0x0,
            memorySize = 0x10000,
            fileOffset = 0x0,
            fileSize = 0x10000,
            executable = false,
        ),
        ElfLoadSegment(
            virtualAddress = 0x20000,
            memorySize = 0x20000,
            fileOffset = 0x20000,
            fileSize = 0x18000,
            executable = true,
        ),
    )

    private fun regions() = ProcMapsParser.parse(mapsText())

    private fun mapsText() = """
        70000000-70010000 r--p 00000000 103:02 42 /data/app/pkg/lib/arm64/libil2cpp.so
        70020000-70040000 r-xp 00020000 103:02 42 /data/app/pkg/lib/arm64/libil2cpp.so
    """.trimIndent()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val TARGET_ID = "il2cpp:method:Assembly-CSharp.dll:6000001:Assembly-CSharp.dll"
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val OTHER_SHA =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
