package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.AnalysisWorkspace
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.File

data class RepackedRuntimePassiveTraceValidationExecution(
    val trace: RepackedRuntimePassiveTraceResult,
    val mapsCapture: RepackedRuntimeProbeCaptureResult,
    val runtimeEvidence: RuntimeEvidenceBundle,
    val validation: RuntimeNativeTraceValidationResult,
    val attachedEvidence: RuntimeEvidenceBundle,
)

/**
 * Stops a signed repacked passive trace, captures fresh maps for the same PID,
 * proves each statically resolvable ELF mapping, and validates positive events.
 *
 * Mapping failures are event-local: they prevent confirmation for that module
 * but do not invalidate independently proven observations from other modules.
 */
object RepackedRuntimePassiveTraceCoordinator {
    fun stopAndValidate(
        build: RepackedRuntimeBuildResult,
        session: RepackedRuntimePassiveTraceSession,
        workspace: AnalysisWorkspace,
        transport: RepackedRuntimePassiveTraceTransport,
        tempRoot: File,
        cancellation: CancellationSignal,
    ): RepackedRuntimePassiveTraceValidationExecution {
        require(
            build.artifactSha256.equals(
                workspace.index.artifactSha256,
                ignoreCase = true,
            ),
        ) {
            "Passive trace build artifact SHA does not match active workspace."
        }

        val trace =
            RepackedRuntimePassiveTraceSessionCapture.stopAndRead(
                build = build,
                session = session,
                transport = transport,
                cancellation = cancellation,
            )

        checkCancelled(cancellation)
        val mapsCapture = RepackedRuntimeEvidenceCapture.capture(
            build = build,
            transport = transport,
            cancellation = cancellation,
        )
        require(mapsCapture.pid == session.pid) {
            "Passive trace PID changed before bound maps validation."
        }
        require(
            mapsCapture.processIdentity == build.packageName,
        ) {
            "Passive trace maps process identity mismatch."
        }

        val parsed =
            RuntimeNativeTraceParser.parse(trace.capture)
        val descriptive = mapsCapture.toEvidenceBundle(
            artifactSha256 =
                workspace.index.artifactSha256,
            artifactEntries =
                workspace.index.entries,
        )

        val mappings =
            mutableListOf<RuntimeModuleMappingEvidence>()
        val mappingBlockers = mutableListOf<String>()
        parsed.events
            .map { it.moduleName }
            .distinct()
            .forEach { moduleName ->
                checkCancelled(cancellation)
                try {
                    val resolved =
                        RepackedRuntimeNativeLookupCoordinator
                            .collectModuleEvidence(
                                workspace = workspace,
                                mapsCapture = mapsCapture,
                                moduleName = moduleName,
                                tempRoot = tempRoot,
                                cancellation = cancellation,
                            )
                    mappings +=
                        resolved.evidence.moduleMappings
                } catch (failure: AnalysisCancelledException) {
                    throw failure
                } catch (failure: Throwable) {
                    mappingBlockers +=
                        "module=$moduleName: " +
                            (
                                failure.message
                                    ?: failure.javaClass.simpleName
                                )
                }
            }

        val producerBlockers = buildList {
            if (trace.status.producerIncomplete) {
                add(
                    "Passive dlsym producer reported incomplete hook coverage; " +
                        "no negative proof may be inferred from missing events.",
                )
            }
        }
        val evidence = descriptive.copy(
            moduleMappings =
                mappings.distinctBy {
                    it.moduleName to it.mappedPath
                },
            blockers = (
                descriptive.blockers +
                    mappingBlockers +
                    producerBlockers
                ).distinct(),
            processIdentity =
                mapsCapture.processIdentity,
            processIdentityConfirmed = true,
        )

        val validation =
            RuntimeNativeLookupValidator.validate(
                capture = trace.capture,
                runtimeEvidence = evidence,
                procMapsText = mapsCapture.maps.text,
            )
        val attached =
            RuntimeNativeLookupValidator.attach(
                runtimeEvidence = evidence,
                validation = validation,
            )

        return RepackedRuntimePassiveTraceValidationExecution(
            trace = trace,
            mapsCapture = mapsCapture,
            runtimeEvidence = evidence,
            validation = validation,
            attachedEvidence = attached,
        )
    }

    private fun checkCancelled(
        cancellation: CancellationSignal,
    ) {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
    }
}
