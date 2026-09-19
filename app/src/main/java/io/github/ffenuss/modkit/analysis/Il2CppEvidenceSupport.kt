package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.ProofLevel

internal fun Il2CppBinaryBindingResult.toEvidenceBlockers(): List<EvidenceBlocker> =
    evidence.asSequence()
        .flatMap { it.blockers.asSequence() }
        .distinct()
        .map { code ->
            EvidenceBlocker(
                code = code,
                message = when (code) {
                    "METADATA_IMAGE_MAP_UNAVAILABLE" ->
                        "Metadata image map is unavailable for exact module association."
                    "CODE_REGISTRATION_SYMBOL_UNRESOLVED" ->
                        "CodeRegistration symbol was not resolved and no replacement proof succeeded."
                    "CODEGEN_MODULE_ARRAY_UNRESOLVED" ->
                        "Il2CppCodeGenModule array could not be resolved."
                    "AMBIGUOUS_CODEGEN_MODULE_ARRAY" ->
                        "Multiple CodeGenModule arrays satisfy the available evidence."
                    "CODEGEN_FALLBACK_SCAN_LIMIT_REACHED" ->
                        "Bounded stripped-binary recovery reached its scan limit."
                    "STRIPPED_CODEGEN_MODULE_ARRAY_UNRESOLVED" ->
                        "Stripped binary recovery did not prove a unique CodeGenModule array."
                    "NO_METHOD_TOKEN_SLOT_BINDINGS" ->
                        "No MethodDef token could be bound to an executable method-pointer slot."
                    else ->
                        "Binary confirmation is blocked: $code."
                },
                requiredFor = ProofLevel.EXACT_BINARY,
            )
        }
        .toList()
