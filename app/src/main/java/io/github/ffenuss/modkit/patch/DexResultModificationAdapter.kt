package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.modification.*
import java.io.File

/** Common RESULT/REPLACE requests lower to existing reversible DEX wrappers.
 * This backend requires a repackaged app. It is not a DEX interceptor in original Space. */
class DexResultModificationAdapter(bytes: ByteArray, private val apkIndex: Int,
    private val dexEntry: String, cancellation: CancellationSignal) : ModificationAdapter {
    private val original = bytes.copyOf()
    private val inventory = DexLocalPatchEngine.scanDex(original, apkIndex, dexEntry, false, cancellation).opportunities
    init { require(inventory.map { it.id }.distinct().size == inventory.size) { "Ambiguous DEX method identities" } }
    private val methods = inventory.associateBy { it.id }
    private val payloads = mutableMapOf<String, Pair<ModificationRequest, DexLocalOpportunity>>()
    override val capabilities = AdapterCapabilities("dex-reversible-result-v1", CodeFamily.DEX,
        ExecutionHost.REPACKAGED_APP, setOf(InterceptionPoint.RESULT), setOf(OperationKind.REPLACE),
        ScalarKind.values().toSet())

    val requests: List<ModificationRequest> get() = methods.values.filter {
        it.selectable && it.runtimeBlocker == null
    }.mapNotNull(ExistingModificationRequests::dex)

    @Synchronized override fun prepare(request: ModificationRequest): AdapterPreparation {
        val method = methods[request.id]
            ?: return AdapterPreparation.Blocked(capabilities.id, listOf("No bound DEX method"))
        val canonical = ExistingModificationRequests.dex(method)
        if (canonical != request)
            return AdapterPreparation.Blocked(capabilities.id, listOf("Request differs from supported DEX lowering"))
        if (!method.selectable || method.runtimeBlocker != null)
            return AdapterPreparation.Blocked(capabilities.id, listOf(method.runtimeBlocker ?: method.reason))
        val blockers = ModificationPlanner.blockers(request, ExecutionHost.REPACKAGED_APP, capabilities,
            BindingEvidence(true, true, true, true, true, true))
        if (blockers.isNotEmpty()) return AdapterPreparation.Blocked(capabilities.id, blockers)
        val payloadId = "${method.id}:${method.originalDexSha256}"
        payloads[payloadId] = request to method
        return AdapterPreparation.Prepared(capabilities.id, request, payloadId)
    }

    @Synchronized fun selection(prepared: AdapterPreparation.Prepared,
        recipeId: String = prepared.request.id): DexRuntimeSelection {
        require(prepared.adapterId == capabilities.id)
        val payload = requireNotNull(payloads[prepared.payloadId]) { "Unknown DEX payload" }
        require(payload.first == prepared.request) { "Prepared DEX request changed" }
        return DexRuntimeSelection(payload.second, DexRuntimeSwitchRewriter.switchId(recipeId))
    }

    fun rewrite(prepared: List<AdapterPreparation.Prepared>, destination: File,
        cancellation: CancellationSignal): DexLocalRewrite = DexRuntimeSwitchRewriter.rewrite(
        original, apkIndex, dexEntry, prepared.map { selection(it) }, destination, cancellation)
}
