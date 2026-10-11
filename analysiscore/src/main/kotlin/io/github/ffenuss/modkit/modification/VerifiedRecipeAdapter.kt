package io.github.ffenuss.modkit.modification

/** A backend may register only an already verified lowering, never a keyword/name match.
 * payloadId refers to native bytes or a DEX rewrite owned by that backend; it is not executable code.
 * This adapter validates preparation. Installing/removing the payload remains the backend's job. */
data class VerifiedLowering(
    val request: ModificationRequest,
    val payloadId: String,
    val evidence: BindingEvidence,
) {
    init { require(payloadId.isNotBlank()) }
}

class VerifiedRecipeAdapter(
    override val capabilities: AdapterCapabilities,
    lowerings: List<VerifiedLowering>,
) : ModificationAdapter {
    private val loweringsById: Map<String, VerifiedLowering>
    init {
        require(lowerings.map { it.request.id }.distinct().size == lowerings.size) { "Ambiguous lowering id" }
        loweringsById = lowerings.associateBy { it.request.id }
    }
    override fun prepare(request: ModificationRequest): AdapterPreparation {
        val lowering = loweringsById[request.id]
            ?: return AdapterPreparation.Blocked(capabilities.id, listOf("No verified lowering for this request"))
        if (lowering.request != request)
            return AdapterPreparation.Blocked(capabilities.id, listOf("Request changed; lower and verify new code first"))
        val blockers = ModificationPlanner.blockers(request, capabilities.host, capabilities, lowering.evidence)
        return if (blockers.isEmpty()) AdapterPreparation.Prepared(capabilities.id, request, lowering.payloadId)
            else AdapterPreparation.Blocked(capabilities.id, blockers)
    }
}
