package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.ProofLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceGraphBuilderTest {
    @Test
    fun metadataOnlyCreatesConfirmationQueueInsteadOfPretendingBinaryProof() {
        val result = baseResult().copy(
            il2cppFastDump = fastDump(),
            il2cppEvidence = EvidenceGate.evaluate(
                artifactSha256 = ARTIFACT_SHA,
                metadataIdentityExact = true,
                binaryIdentityExact = false,
                runtimeConfirmed = false,
                mutationValidated = false,
                requestedChangeReady = false,
            ),
        ).withEvidenceGraph()

        val graph = requireNotNull(result.evidenceGraph)
        assertEquals(1, graph.targets.size)
        val target = graph.targets.single()
        assertEquals(ProofLevel.EXACT_METADATA, target.proofLevel)
        assertEquals(UserFindingStatus.CONFIRMING, target.userStatus)
        assertEquals("EXECUTABLE_BINDING_MISSING", target.blockers.single().code)

        val request = result.confirmationQueue.single()
        assertEquals("il2cpp.codegen-bind", request.engineId)
        assertEquals(ProofLevel.EXACT_BINARY, request.requiredProofLevel)
        assertTrue(request.availableNow)
    }

    @Test
    fun exactBinaryCreatesConfirmedMethodTargetAndStopsStaticConfirmationQueue() {
        val binding = methodBinding()
        val binary = Il2CppBinaryBindingResult(
            evidence = listOf(
                Il2CppBinaryEvidence(
                    libraryEntry = LIBRARY_ENTRY,
                    machine = 183,
                    pointerSize = 8,
                    codeRegistrationVirtualAddress = 0x1000,
                    metadataRegistrationVirtualAddress = 0x2000,
                    codegenRegisterVirtualAddress = 0x3000,
                    moduleArrayDiscovery = "CODE_REGISTRATION_PAIR_0",
                    modules = listOf(
                        Il2CppCodeGenModuleEvidence(
                            moduleName = "Assembly-CSharp.dll",
                            moduleVirtualAddress = 0x4000,
                            methodPointerCount = 1,
                            methodPointersVirtualAddress = 0x5000,
                            sampledPointers = 1,
                            executablePointers = 1,
                        ),
                    ),
                    bindings = listOf(binding),
                    blockers = emptyList(),
                ),
            ),
            exactBindingCount = 1,
            warnings = emptyList(),
        )
        val result = baseResult().copy(
            il2cppFastDump = fastDump(),
            il2cppBinaryBinding = binary,
            il2cppEvidence = EvidenceGate.evaluate(
                artifactSha256 = ARTIFACT_SHA,
                metadataIdentityExact = true,
                binaryIdentityExact = true,
                runtimeConfirmed = false,
                mutationValidated = false,
                requestedChangeReady = false,
            ),
        ).withEvidenceGraph()

        val target = requireNotNull(result.evidenceGraph).targets.single()
        assertEquals(EvidenceTargetKind.METHOD, target.kind)
        assertEquals("Game.Player.Hit", target.displayName)
        assertEquals(ProofLevel.EXACT_BINARY, target.proofLevel)
        assertEquals(UserFindingStatus.CONFIRMED, target.userStatus)
        assertEquals(0x06000001L, target.metadataToken)
        assertEquals(0x6000L, target.binaryVirtualAddress)
        assertEquals(0x800L, target.fileOffset)
        assertEquals("arm64-v8a", target.abi)
        assertEquals("MUTATION_NOT_VALIDATED", target.blockers.single().code)
        assertTrue(result.confirmationQueue.isEmpty())
    }

    @Test
    fun failedStaticBindingStaysExplicitAndQueuesUnavailableRuntimeEscalation() {
        val blocker = EvidenceBlocker(
            code = "STRIPPED_CODEGEN_MODULE_ARRAY_UNRESOLVED",
            message = "Static CodeGenModule array was not proven.",
            requiredFor = ProofLevel.EXACT_BINARY,
        )
        val binary = Il2CppBinaryBindingResult(
            evidence = listOf(
                Il2CppBinaryEvidence(
                    libraryEntry = LIBRARY_ENTRY,
                    machine = 183,
                    pointerSize = 8,
                    codeRegistrationVirtualAddress = null,
                    metadataRegistrationVirtualAddress = null,
                    codegenRegisterVirtualAddress = null,
                    moduleArrayDiscovery = null,
                    modules = emptyList(),
                    bindings = emptyList(),
                    blockers = listOf(blocker.code),
                ),
            ),
            exactBindingCount = 0,
            warnings = listOf("No exact binding"),
        )
        val result = baseResult().copy(
            il2cppFastDump = fastDump(),
            il2cppBinaryBinding = binary,
            il2cppEvidence = EvidenceGate.evaluate(
                artifactSha256 = ARTIFACT_SHA,
                metadataIdentityExact = true,
                binaryIdentityExact = false,
                runtimeConfirmed = false,
                mutationValidated = false,
                requestedChangeReady = false,
                suppliedBlockers = listOf(blocker),
            ),
        ).withEvidenceGraph()

        val target = requireNotNull(result.evidenceGraph).targets.single()
        assertEquals(UserFindingStatus.RUNTIME_REQUIRED, target.userStatus)
        assertEquals(blocker.code, target.blockers.single().code)

        val request = result.confirmationQueue.single()
        assertEquals("runtime.il2cpp-confirm", request.engineId)
        assertEquals(ProofLevel.RUNTIME_CONFIRMED, request.requiredProofLevel)
        assertFalse(request.availableNow)
    }

    private fun baseResult(): FastAnalysisResult {
        val index = ArtifactIndex(
            artifactSha256 = ARTIFACT_SHA,
            sources = listOf(ArtifactSource("base.apk", 123, ARTIFACT_SHA)),
            entries = emptyList(),
            detectedAbis = setOf("arm64-v8a"),
            runtimeProfiles = emptyList(),
        )
        return FastAnalysisResult(
            index = index,
            routingPlan = EngineRoutingPlan(emptyList(), emptyList()),
            elapsedMs = 10,
        )
    }

    private fun fastDump() = Il2CppFastDumpResult(
        metadataEntry = "base.apk:global-metadata.dat",
        libraryEntries = listOf(LIBRARY_ENTRY),
        metadata = Il2CppMetadataModel(
            sizeBytes = 1024,
            magicValid = true,
            metadataVersion = 29,
            layoutProfile = "IL2CPP_METADATA_V27_V30",
            tableRanges = emptyList(),
            declaredTypeCount = 1,
            declaredMethodCount = 1,
            declaredFieldCount = 0,
            declaredImageCount = 1,
            images = listOf(
                Il2CppImageDefinition(
                    index = 0,
                    name = "Assembly-CSharp.dll",
                    assemblyIndex = 0,
                    typeStart = 0,
                    typeCount = 1,
                    token = 1,
                ),
            ),
            types = listOf(
                Il2CppTypeDefinition(
                    index = 0,
                    namespace = "Game",
                    name = "Player",
                    fullName = "Game.Player",
                    methodStart = 0,
                    methodCount = 1,
                    fieldStart = 0,
                    fieldCount = 0,
                    token = 0x02000001,
                ),
            ),
            methods = listOf(
                Il2CppMethodDefinition(
                    index = 0,
                    declaringTypeIndex = 0,
                    declaringType = "Game.Player",
                    name = "Hit",
                    parameterCount = 0,
                    token = 0x06000001,
                    flags = 6,
                ),
            ),
            fields = emptyList(),
            structuredSupported = true,
            truncated = false,
            warnings = emptyList(),
        ),
        dumpFilePath = "/tmp/dump.cs",
        preview = "class Player",
        warnings = emptyList(),
    )

    private fun methodBinding() = Il2CppMethodBinaryBinding(
        methodIndex = 0,
        managedIdentity = "Game.Player.Hit",
        metadataToken = 0x06000001,
        imageName = "Assembly-CSharp.dll",
        moduleName = "Assembly-CSharp.dll",
        slotIndex = 0,
        functionVirtualAddress = 0x6000,
        functionFileOffset = 0x800,
    )

    companion object {
        private const val ARTIFACT_SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val LIBRARY_ENTRY = "base.apk:lib/arm64-v8a/libil2cpp.so"
    }
}
