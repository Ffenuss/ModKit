package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineScheduleClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRouterTest {
    @Test
    fun il2cppRoutesFastDumpWithoutRunningUnrelatedBackends() {
        val index = ArtifactIndex(
            artifactSha256 = "sha",
            sources = listOf(ArtifactSource("app.apk", 1, "sha")),
            entries = emptyList(),
            runtimeProfiles = listOf(
                RuntimeProfile(
                    runtimeId = "unity_il2cpp",
                    title = "Unity / IL2CPP",
                    status = DetectionStatus.CONFIRMED,
                    confidence = DetectionConfidence.HIGH,
                    evidence = listOf("metadata", "libil2cpp"),
                ),
            ),
        )

        val plan = EngineRouter.plan(index)
        assertTrue(plan.targeted.any { it.id == "il2cpp.fast-dump" })
        assertFalse(plan.engines.any { it.id == "flutter.dart-aot" })
        assertTrue(plan.engines.first { it.id == "il2cpp.fast-dump" }.scheduleClass == EngineScheduleClass.TARGETED)
        assertTrue(plan.missingCapabilities.any { "IL2CPP" in it })
    }
}
