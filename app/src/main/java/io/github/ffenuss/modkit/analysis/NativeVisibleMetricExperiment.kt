package io.github.ffenuss.modkit.analysis

import java.security.MessageDigest

/**
 * Experimental, read-only association between one user-chosen UI counter and
 * one native export candidate. It is NOT proof of that function's meaning and
 * never authorizes a runtime patch or an automatic mod.
 *
 * The observation source is supplied by the caller. These data structures
 * cannot themselves attest that Android UI hierarchy collection happened.
 */
data class NativeMetricExperimentPlan(
    val originalArtifactSha256: String,
    val packageName: String,
    val moduleName: String,
    val actionLabel: String,
    val metricLabel: String,
    val candidate: NativeScalarExportCandidate,
    val proposedPatch: NativeScalarExportPatch,
    val replacementValue: Int,
    val fingerprint: String,
)

data class NativeVisibleMetricObservation(
    val phase: NativeScalarEffectPhase,
    val sourcePackage: String,
    val nodeText: String,
    val elapsedRealtimeMs: Long,
)

data class NativeMetricAssociation(
    val fingerprint: String,
    val packageName: String,
    val actionLabel: String,
    val metricLabel: String,
    val observedValues: List<Long>,
    val observedDeltas: List<Long>,
    val baselineDelta: Long,
    val interventionDelta: Long,
) {
    // Correlation of a UI counter is not an authorization to patch other apps.
    val status: String = "REPLICATED_UI_METRIC_ASSOCIATION_ONLY"
    val selectable: Boolean = false
    val blocker: String =
        "UI text correlation is not verified code-to-gameplay causality; " +
            "runtime bytes, provenance and target semantics require separate proof."
    val baselineActions: Int = 2
    val repeatedOnOffCycles: Int = 2
}

/**
 * Bounded, label-exact parser: no OCR, fuzzy UI matching, floating point
 * rounding or numeric guessing from arbitrary visible text.
 */
object NativeVisibleCounterParser {
    private val labelPattern = Regex("[\\p{L}\\p{N} _./()%-]{1,80}")

    fun requireLabel(label: String) {
        require(label == label.trim() && labelPattern.matches(label) &&
            label.isNotBlank()) {
            "Choose one bounded, literal UI metric label"
        }
    }

    fun parseSingle(label: String, nodeText: String): Long {
        requireLabel(label)
        require(nodeText.length <= 160 && '\n' !in nodeText && '\r' !in nodeText) {
            "UI metric text exceeds the allowed single-line node"
        }
        val match = Regex(
            "^" + Regex.escape(label) + "\\s*[:=]\\s*([+-]?[0-9]{1,10})\\s*$",
        ).matchEntire(nodeText)
            ?: throw IllegalArgumentException("UI node does not contain the selected metric")
        val number = match.groupValues[1].toLongOrNull()
            ?: throw IllegalArgumentException("Unparseable UI metric integer")
        require(number in -1_000_000_000L..1_000_000_000L) {
            "UI metric is outside the bounded integer range"
        }
        return number
    }

    fun parseUnique(label: String, visibleNodeTexts: List<String>): Long {
        require(visibleNodeTexts.size <= 2048) { "Unbounded UI hierarchy" }
        val prefix = Regex("^" + Regex.escape(label) + "\\s*[:=]")
        val candidates = visibleNodeTexts.filter { prefix.containsMatchIn(it) }
        require(candidates.size == 1) {
            "The chosen UI metric must appear on exactly one visible node"
        }
        return parseSingle(label, candidates.single())
    }
}

/**
 * This generic evaluator supports any package with explicit user-selected
 * action and numeric UI metric, but does not drive UI, inject code, or trust
 * unverified observations as provenance. Its output is read-only.
 */
object NativeVisibleMetricExperiment {
    private val sha = Regex("[0-9a-f]{64}")
    private val pkg = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val module = Regex("[A-Za-z0-9_.-]{1,120}\\.so")
    private val requiredPhases = NativeScalarEffectPhase.entries

    fun prepare(
        originalArtifactSha256: String,
        packageName: String,
        moduleName: String,
        actionLabel: String,
        metricLabel: String,
        candidate: NativeScalarExportCandidate,
        replacementValue: Int,
    ): NativeMetricExperimentPlan {
        require(sha.matches(originalArtifactSha256)) { "Invalid original APK-set SHA-256" }
        require(packageName.length <= 200 && pkg.matches(packageName)) {
            "Missing game package name"
        }
        require(module.matches(moduleName)) { "Invalid native module identity" }
        require(actionLabel.length in 1..120 && actionLabel.isNotBlank() &&
            actionLabel == actionLabel.trim() &&
            '\n' !in actionLabel && '\r' !in actionLabel) {
            "Choose a bounded visible action label before observation"
        }
        NativeVisibleCounterParser.requireLabel(metricLabel)
        require(!candidate.selectable && candidate.abi in setOf("arm64-v8a", "x86_64")) {
            "Only nonselectable, supported structural candidates can be studied"
        }
        require(candidate.symbol.isNotBlank() && candidate.symbol.length <= 512 &&
            candidate.patchVirtualAddress > 0L) {
            "Missing structural native export identity"
        }
        // Reject malformed original opcodes or no-op proposal, but do not
        // execute it. Actual APK identity and executable bytes are a
        // responsibility of the caller's separate runtime verification.
        val proposal = NativeScalarExportDiscovery.propose(candidate, replacementValue)
        val source = listOf(
            originalArtifactSha256, packageName, moduleName, candidate.abi,
            candidate.symbol, candidate.patchVirtualAddress.toString(),
            candidate.expectedPatchHex, proposal.replacementHex,
            actionLabel, metricLabel,
        ).joinToString("\u001f")
        val id = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return NativeMetricExperimentPlan(
            originalArtifactSha256, packageName, moduleName,
            actionLabel, metricLabel, candidate, proposal, replacementValue, id,
        )
    }

    fun assess(
        plan: NativeMetricExperimentPlan,
        observations: List<NativeVisibleMetricObservation>,
    ): NativeMetricAssociation {
        require(sha.matches(plan.originalArtifactSha256) &&
            pkg.matches(plan.packageName) && module.matches(plan.moduleName)) {
            "Source identity mismatch"
        }
        // A plan is never trusted solely because it is a data class; recheck
        // every caller-supplied identity and byte binding.
        val expected = prepare(
            plan.originalArtifactSha256, plan.packageName, plan.moduleName,
            plan.actionLabel, plan.metricLabel, plan.candidate, plan.replacementValue,
        )
        require(plan == expected) { "Experiment plan has been modified or is stale" }
        require(observations.size == requiredPhases.size &&
            observations.map { it.phase } == requiredPhases) {
            "Two control actions, two ON/OFF cycles and close are required"
        }
        require(observations.all {
            it.sourcePackage == plan.packageName && it.elapsedRealtimeMs > 0L
        }) { "Observations must belong to the exact game package" }
        require(observations.zipWithNext().all { (a, b) ->
            b.elapsedRealtimeMs > a.elapsedRealtimeMs
        }) { "Counter observations are not chronologically ordered" }
        val values = observations.map {
            NativeVisibleCounterParser.parseSingle(plan.metricLabel, it.nodeText)
        }
        val deltas = values.zipWithNext().map { (a, b) -> b - a }
        require(deltas.all { it in -1_000_000_000L..1_000_000_000L }) {
            "Unbounded counter action delta"
        }
        val baseline = deltas[0]
        val intervention = deltas[2]
        require(
            deltas[1] == baseline &&
                deltas[3] == baseline &&
                deltas[5] == baseline &&
                deltas[6] == baseline &&
                deltas[4] == intervention &&
                intervention != baseline,
        ) {
            "No replicated, reversible change in the user-selected UI metric"
        }
        return NativeMetricAssociation(
            fingerprint = plan.fingerprint,
            packageName = plan.packageName,
            actionLabel = plan.actionLabel,
            metricLabel = plan.metricLabel,
            observedValues = values,
            observedDeltas = deltas,
            baselineDelta = baseline,
            interventionDelta = intervention,
        )
    }
}


/**
 * Bridges a real analyzed APK/ELF inventory to an explicitly selected
 * experiment, instead of accepting an invented symbol or an arbitrary VA.
 *
 * This only prepares a read-only hypothesis and an exact byte proposal;
 * it does not run a third-party game, request permissions or modify code.
 */
object NativeMetricExperimentPlanner {
    fun fromAnalysis(
        result: FastAnalysisResult,
        packageName: String,
        sourceContainer: String,
        elfEntryPath: String,
        abi: String,
        symbol: String,
        actionLabel: String,
        metricLabel: String,
        replacementValue: Int,
    ): NativeMetricExperimentPlan {
        require(sourceContainer.isNotBlank() && elfEntryPath.isNotBlank() &&
            symbol.isNotBlank()) {
            "Select a concrete analyzed ELF export"
        }
        val record = result.elfInventory?.records.orEmpty().singleOrNull {
            it.container == sourceContainer &&
                it.entryPath == elfEntryPath &&
                it.architecture == abi &&
                it.indexedAbi == abi &&
                !it.abiPathMismatch
        } ?: throw IllegalArgumentException("Unique architecture-matched ELF was not indexed")
        require(elfEntryPath.startsWith("lib/" + abi + "/") &&
            elfEntryPath.count { it == '/' } == 2) {
            "Export does not belong to the selected Android library ABI path"
        }
        val candidate = record.scalarExportCandidates.singleOrNull {
            it.symbol == symbol && it.abi == abi && !it.selectable
        } ?: throw IllegalArgumentException("Export is ambiguous, unavailable or already selected")
        return NativeVisibleMetricExperiment.prepare(
            originalArtifactSha256 = result.index.artifactSha256,
            packageName = packageName,
            moduleName = elfEntryPath.substringAfterLast('/'),
            actionLabel = actionLabel,
            metricLabel = metricLabel,
            candidate = candidate,
            replacementValue = replacementValue,
        )
    }
}
