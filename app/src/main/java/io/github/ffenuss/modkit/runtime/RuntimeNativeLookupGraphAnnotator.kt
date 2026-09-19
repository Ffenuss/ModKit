package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.EvidenceFact
import io.github.ffenuss.modkit.analysis.EvidenceGraph
import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
import io.github.ffenuss.modkit.analysis.FastAnalysisResult

/**
 * Adds proof-neutral native lookup facts to already-existing exact targets.
 *
 * It never creates a target, never changes ProofLevel/UserFindingStatus and
 * never fills RVA/runtimeVirtualAddress. A dlsym observation is not method
 * execution proof.
 */
object RuntimeNativeLookupGraphAnnotator {
    fun annotate(
        result: FastAnalysisResult,
        moduleName: String,
        symbolName: String,
        validation: RuntimeNativeTraceValidationResult,
    ): FastAnalysisResult {
        val graph = result.evidenceGraph ?: return result
        val confirmed = validation.observations.filter {
            it.kind ==
                RuntimeEvidenceObservationKind.JNI_DLSYM_OBSERVED &&
                it.independentlyConfirmed &&
                it.subjectId == symbolName
        }
        if (confirmed.isEmpty()) return result

        var changed = false
        val updated = graph.targets.map { target ->
            val artifactModule =
                target.artifact?.substringAfterLast('/')
            val symbolMatches =
                target.memberName == symbolName ||
                    (
                        target.kind ==
                            EvidenceTargetKind.NATIVE_FUNCTION &&
                            target.displayName == symbolName
                        )
            if (
                artifactModule != moduleName ||
                !symbolMatches
            ) {
                return@map target
            }

            val facts = confirmed.map { observation ->
                EvidenceFact(
                    engineId = "runtime.native-lookup",
                    kind = "dlsym-observed",
                    summary = observation.summary,
                )
            }
            val merged = (target.facts + facts)
                .distinctBy {
                    Triple(
                        it.engineId,
                        it.kind,
                        it.summary,
                    )
                }
            if (merged != target.facts) changed = true
            target.copy(facts = merged)
        }

        if (!changed) return result
        return result.copy(
            evidenceGraph = EvidenceGraph(
                artifactSha256 = graph.artifactSha256,
                targets = updated,
            ),
        )
    }
}
