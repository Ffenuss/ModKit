package io.github.ffenuss.modkit.runtime

import android.content.Context
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class BehavioralScanMode {
    AUTO,
    TRAINING,
}

enum class BehavioralActionHint(
    val title: String,
    val candidateTitle: String,
    val confirmedTitle: String,
) {
    MOVEMENT(
        "Ходьба / движение",
        "Кандидат движения",
        "Скорость / движение",
    ),
    ATTACK(
        "Атака",
        "Кандидат атаки",
        "Урон / атака",
    ),
    DAMAGE_TAKEN(
        "Получение урона",
        "Кандидат здоровья",
        "Здоровье / получаемый урон",
    ),
    STAMINA(
        "Выносливость",
        "Кандидат выносливости",
        "Выносливость / энергия",
    ),
    COOLDOWN(
        "Cooldown / перезарядка",
        "Кандидат cooldown",
        "Cooldown / таймер способности",
    ),
    RESOURCE_CHANGE(
        "Покупка / продажа / ресурс",
        "Кандидат ресурса",
        "Ресурс / валюта",
    ),
    ITEM_CHANGE(
        "Предмет / подбор",
        "Кандидат количества предмета",
        "Количество предмета",
    ),
    OTHER(
        "Другое действие",
        "Кандидат действия",
        "Параметр действия",
    ),
}

data class BehavioralRuntimeCandidate(
    val id: String,
    val address: Long,
    val valueType: RuntimeValueType,
    val value: String,
    val previousValue: String?,
    val title: String,
    val confidence: Int,
    val changeCount: Int,
    val stableCount: Int,
    val increaseCount: Int,
    val decreaseCount: Int,
    val observedSamples: Int,
    val regionPath: String?,
    val activityTransitions: Int = 0,
    val recentChangeMask: Int = 0,
)

data class BehavioralScanSample(
    val packageName: String,
    val pid: Int,
    val cycle: Int,
    val sweptTypes: List<RuntimeValueType>,
    val trackedCandidates: Int,
    val visibleCandidates: List<BehavioralRuntimeCandidate>,
    val hiddenAsNoise: Int,
    val elapsedMs: Long,
    val emergingCandidates: Int = 0,
)

internal data class BehavioralTrack(
    val address: Long,
    val valueType: RuntimeValueType,
    val regionStart: Long,
    val regionEndExclusive: Long,
    val regionFileOffset: Long,
    val regionPath: String?,
    var lastBits: Long,
    var previousBits: Long? = null,
    var changeCount: Int = 1,
    var stableCount: Int = 0,
    var increaseCount: Int = 0,
    var decreaseCount: Int = 0,
    var observedSamples: Int = 1,
    var recentChangeMask: Int = 1,
    var patternSamples: Int = 1,
    val distinctBits: LinkedHashSet<Long> =
        linkedSetOf(),
) {
    init {
        distinctBits += lastBits
    }
}

class RootBehavioralScanSession internal constructor(
    val packageName: String,
    val pid: Int,
    val mode: BehavioralScanMode,
    val actionHint: BehavioralActionHint?,
    internal var baseline: RootRuntimeUnknownBaseline,
    internal val tracks: MutableMap<String, BehavioralTrack>,
    internal var cycle: Int,
    internal var rotatedAtCycle: Int,
) {
    val startedAtEpochMs: Long =
        System.currentTimeMillis()
}

/**
 * Behavioral scanner for a live root process.
 *
 * The broad phase uses one bounded raw baseline and reinterprets the same bytes
 * as Int32/Float32/Int64/Float64 over successive cycles. Existing candidates
 * are then refreshed through coalesced reads, so ModKit does not issue one root
 * command per address. A new baseline is periodically rotated so actions that
 * happen later in a long session can still enter the candidate set.
 *
 * This layer does not pretend that a changed address is automatically "health"
 * or "damage". Auto mode assigns only heuristic candidate labels. Training mode
 * adds the user's action context and therefore raises confidence without
 * changing the underlying evidence.
 */
object RootBehavioralScanCoordinator {
    const val DEFAULT_BASELINE_BYTES =
        24L * 1024L * 1024L
    const val DEFAULT_VISIBLE_CANDIDATES =
        8

    private const val MAX_TRACKS =
        12_000
    private const val MAX_NEW_HITS_PER_SWEEP =
        6_000
    private const val ROTATE_BASELINE_EVERY_CYCLES =
        8
    private const val DISTINCT_VALUE_LIMIT =
        16
    private const val AUTO_VISIBLE_THRESHOLD =
        78
    private const val TRAINING_VISIBLE_THRESHOLD =
        46

    private val sweepOrder =
        listOf(
            RuntimeValueType.INT32,
            RuntimeValueType.FLOAT32,
            RuntimeValueType.INT64,
            RuntimeValueType.FLOAT64,
        )

    fun start(
        context: Context,
        packageName: String,
        pid: Int,
        mode: BehavioralScanMode,
        actionHint: BehavioralActionHint? = null,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
        maxBaselineBytes: Long =
            DEFAULT_BASELINE_BYTES,
    ): RootBehavioralScanSession {
        require(pid > 0) {
            "PID поведенческого сканирования должен быть положительным."
        }
        require(
            packageName.matches(
                Regex(
                    "[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+",
                ),
            ),
        ) {
            "Некорректный package для поведенческого сканирования."
        }
        require(
            mode == BehavioralScanMode.AUTO ||
                actionHint != null,
        ) {
            "Для обучения действия нужен выбранный тип действия."
        }

        val snapshot =
            newBaselineFile(
                context = context,
                packageName = packageName,
                pid = pid,
            )
        val baseline =
            RootRuntimeUnknownValueCoordinator
                .captureBaseline(
                    packageName = packageName,
                    valueType =
                        RuntimeValueType.INT32,
                    alignment =
                        RuntimeScanAlignment.NATURAL,
                    snapshotFile = snapshot,
                    cancellation = cancellation,
                    runner = runner,
                    maxBytes =
                        maxBaselineBytes,
                    expectedPid = pid,
                    behavioralMode = true,
                )
        require(baseline.pid == pid) {
            RootRuntimeUnknownValueCoordinator
                .deleteBaseline(baseline)
            "PID изменился во время создания behavioral baseline."
        }

        return RootBehavioralScanSession(
            packageName = packageName,
            pid = pid,
            mode = mode,
            actionHint = actionHint,
            baseline = baseline,
            tracks =
                linkedMapOf(),
            cycle = 0,
            rotatedAtCycle = 0,
        )
    }

    fun sample(
        context: Context,
        session: RootBehavioralScanSession,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
        sweepAllTypes: Boolean = false,
        visibleLimit: Int =
            DEFAULT_VISIBLE_CANDIDATES,
    ): BehavioralScanSample {
        require(
            visibleLimit in 1..100,
        ) {
            "Некорректный лимит behavioral candidates."
        }
        val started =
            System.currentTimeMillis()

        val capture =
            RootRuntimeCaptureCoordinator
                .captureMaps(
                    packageName =
                        session.packageName,
                    cancellation =
                        cancellation,
                    runner = runner,
                    expectedPid =
                        session.pid,
                )
        require(
            capture.pid ==
                session.pid
        ) {
            "Behavioral scan PID changed."
        }
        val reader =
            RootProcMemRuntimeMemoryReader(
                pid = session.pid,
                runner = runner,
            )

        refreshExisting(
            session = session,
            cancellation = cancellation,
            reader = reader,
        )

        val sweptTypes =
            if (sweepAllTypes) {
                sweepOrder
            } else {
                listOf(
                    sweepOrder[
                        session.cycle %
                            sweepOrder.size
                    ],
                )
            }

        sweptTypes.forEach {
            type ->
            mergeBroadChanges(
                session = session,
                type = type,
                cancellation = cancellation,
                reader = reader,
            )
        }

        session.cycle++

        if (
            session.tracks.size >
            MAX_TRACKS
        ) {
            trimTracks(
                session = session,
                keep = MAX_TRACKS,
            )
        }

        val ranked =
            session.tracks
                .values
                .asSequence()
                .mapNotNull {
                    track ->
                    toCandidate(
                        track = track,
                        mode = session.mode,
                        hint =
                            session.actionHint,
                    )
                }
                .sortedWith(
                    compareByDescending<
                        BehavioralRuntimeCandidate
                    > {
                        it.confidence
                    }
                        .thenByDescending {
                            it.changeCount
                        }
                        .thenBy {
                            it.address
                        },
                )
                .toList()

        val threshold =
            if (
                session.mode ==
                BehavioralScanMode.TRAINING
            ) {
                TRAINING_VISIBLE_THRESHOLD
            } else {
                AUTO_VISIBLE_THRESHOLD
            }
        val visible =
            ranked
                .asSequence()
                .filter {
                    it.confidence >=
                        threshold
                }
                .filter {
                    candidate ->
                    when (
                        session.mode
                    ) {
                        BehavioralScanMode.AUTO ->
                            autoCandidateRelevant(
                                candidate,
                            )

                        BehavioralScanMode.TRAINING ->
                            trainingCandidateRelevant(
                                candidate,
                            )
                    }
                }
                .take(
                    visibleLimit,
                )
                .toList()

        val emerging =
            ranked.count {
                candidate ->
                candidate.confidence >=
                    52 &&
                    when (
                        session.mode
                    ) {
                        BehavioralScanMode.AUTO ->
                            emergingAutoCandidateRelevant(
                                candidate,
                            )

                        BehavioralScanMode.TRAINING ->
                            trainingCandidateRelevant(
                                candidate,
                            )
                    }
            }

        if (
            session.mode ==
                BehavioralScanMode.AUTO &&
            session.cycle -
                session.rotatedAtCycle >=
            ROTATE_BASELINE_EVERY_CYCLES
        ) {
            rotateBaseline(
                context = context,
                session = session,
                cancellation = cancellation,
                runner = runner,
            )
        }

        return BehavioralScanSample(
            packageName =
                session.packageName,
            pid = session.pid,
            cycle = session.cycle,
            sweptTypes =
                sweptTypes,
            trackedCandidates =
                session.tracks.size,
            visibleCandidates =
                visible,
            hiddenAsNoise =
                max(
                    0,
                    ranked.size -
                        visible.size,
                ),
            elapsedMs =
                System.currentTimeMillis() -
                    started,
            emergingCandidates =
                emerging,
        )
    }

    fun finishTraining(
        context: Context,
        session: RootBehavioralScanSession,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
        visibleLimit: Int =
            DEFAULT_VISIBLE_CANDIDATES,
    ): BehavioralScanSample {
        require(
            session.mode ==
                BehavioralScanMode.TRAINING,
        ) {
            "finishTraining доступен только для режима обучения."
        }
        return sample(
            context = context,
            session = session,
            cancellation = cancellation,
            runner = runner,
            sweepAllTypes = true,
            visibleLimit =
                visibleLimit,
        )
    }

    fun close(
        session:
            RootBehavioralScanSession?,
    ) {
        if (session == null) {
            return
        }
        RootRuntimeUnknownValueCoordinator
            .deleteBaseline(
                session.baseline,
            )
        session.tracks.clear()
    }

    private fun refreshExisting(
        session: RootBehavioralScanSession,
        cancellation: CancellationSignal,
        reader: RuntimeMemoryReader,
    ) {
        if (
            session.tracks.isEmpty()
        ) {
            return
        }

        session.tracks
            .values
            .groupBy {
                it.valueType
            }
            .forEach {
                    (type, typedTracks),
                ->
                val hits =
                    typedTracks.map {
                        track ->
                        RuntimeValueHit(
                            address =
                                track.address,
                            bits =
                                track.lastBits,
                            regionStart =
                                track.regionStart,
                            regionEndExclusive =
                                track
                                    .regionEndExclusive,
                            regionFileOffset =
                                track.regionFileOffset,
                            regionPath =
                                track.regionPath,
                        )
                    }
                val previous =
                    RuntimeValueScanSnapshot(
                        valueType = type,
                        hits = hits,
                        alignment =
                            RuntimeScanAlignment
                                .NATURAL,
                        scannedBytes =
                            hits.size
                                .toLong() *
                                type.byteWidth,
                        scannedRegions =
                            hits
                                .map {
                                    it.regionStart to
                                        it.regionEndExclusive
                                }
                                .distinct()
                                .size,
                        truncatedByHitLimit =
                            false,
                        truncatedByByteLimit =
                            false,
                    )
                val refreshed =
                    RuntimeValueScanner
                        .refresh(
                            previous =
                                previous,
                            reader = reader,
                            cancellation =
                                cancellation,
                        )
                val nowByAddress =
                    refreshed.hits
                        .associateBy {
                            it.address
                        }

                typedTracks.forEach {
                    track ->
                    val now =
                        nowByAddress[
                            track.address
                        ]
                    if (now == null) {
                        session.tracks.remove(
                            key(
                                type =
                                    track.valueType,
                                address =
                                    track.address,
                            ),
                        )
                        return@forEach
                    }
                    updateTrack(
                        track = track,
                        newBits = now.bits,
                    )
                }
            }
    }

    private fun mergeBroadChanges(
        session: RootBehavioralScanSession,
        type: RuntimeValueType,
        cancellation: CancellationSignal,
        reader: RuntimeMemoryReader,
    ) {
        val typedBaseline =
            session.baseline.copy(
                valueType = type,
            )
        val changed =
            RootRuntimeUnknownValueCoordinator
                .compareBaselineVerified(
                    baseline =
                        typedBaseline,
                    refinement =
                        RuntimeValueRefinement
                            .CHANGED,
                    reader = reader,
                    cancellation =
                        cancellation,
                    maxHits =
                        MAX_NEW_HITS_PER_SWEEP,
                )

        changed.snapshot.hits
            .asSequence()
            .filterNot {
                isNoiseRegion(
                    it.regionPath,
                )
            }
            .filter {
                plausible(
                    type = type,
                    bits = it.bits,
                )
            }
            .forEach {
                hit ->
                val key =
                    key(
                        type = type,
                        address =
                            hit.address,
                    )
                if (
                    key in
                    session.tracks
                ) {
                    return@forEach
                }
                if (
                    session.tracks.size >=
                    MAX_TRACKS * 2
                ) {
                    return
                }
                session.tracks[key] =
                    BehavioralTrack(
                        address =
                            hit.address,
                        valueType =
                            type,
                        regionStart =
                            hit.regionStart,
                        regionEndExclusive =
                            hit.regionEndExclusive,
                        regionFileOffset =
                            hit.regionFileOffset,
                        regionPath =
                            hit.regionPath,
                        lastBits =
                            hit.bits,
                    )
            }
    }

    private fun updateTrack(
        track: BehavioralTrack,
        newBits: Long,
    ) {
        track.observedSamples++
        val old =
            track.lastBits
        track.previousBits =
            old
        val changed =
            old != newBits
        track.recentChangeMask =
            (
                (
                    track.recentChangeMask
                        shl 1
                    ) or
                    if (changed) {
                        1
                    } else {
                        0
                    }
                ) and
                0xffff
        track.patternSamples =
            min(
                16,
                track.patternSamples +
                    1,
            )
        if (!changed) {
            track.stableCount++
        } else {
            track.changeCount++
            val comparison =
                track.valueType
                    .compare(
                        old,
                        newBits,
                    )
            when {
                comparison < 0 ->
                    track.increaseCount++
                comparison > 0 ->
                    track.decreaseCount++
            }
            track.lastBits =
                newBits
            if (
                track.distinctBits.size <
                DISTINCT_VALUE_LIMIT
            ) {
                track.distinctBits +=
                    newBits
            }
        }
    }

    private fun toCandidate(
        track: BehavioralTrack,
        mode: BehavioralScanMode,
        hint: BehavioralActionHint?,
    ): BehavioralRuntimeCandidate? {
        if (
            !plausible(
                type =
                    track.valueType,
                bits =
                    track.lastBits,
            )
        ) {
            return null
        }

        val confidence =
            confidence(
                track = track,
                mode = mode,
            )
        if (confidence <= 0) {
            return null
        }

        return BehavioralRuntimeCandidate(
            id =
                key(
                    type =
                        track.valueType,
                    address =
                        track.address,
                ),
            address =
                track.address,
            valueType =
                track.valueType,
            value =
                track.valueType
                    .display(
                        track.lastBits,
                    ),
            previousValue =
                track.previousBits
                    ?.let {
                        track.valueType
                            .display(it)
                    },
            title =
                candidateTitle(
                    track = track,
                    hint = hint,
                ),
            confidence =
                confidence,
            changeCount =
                track.changeCount,
            stableCount =
                track.stableCount,
            increaseCount =
                track.increaseCount,
            decreaseCount =
                track.decreaseCount,
            observedSamples =
                track.observedSamples,
            regionPath =
                track.regionPath,
            activityTransitions =
                activityTransitions(
                    track.recentChangeMask,
                    track.patternSamples,
                ),
            recentChangeMask =
                track.recentChangeMask,
        )
    }

    internal fun confidence(
        changeCount: Int,
        stableCount: Int,
        increaseCount: Int,
        decreaseCount: Int,
        observedSamples: Int,
        distinctValueCount: Int,
        preferredRegion: Boolean,
        plausibleValue: Boolean,
        training: Boolean,
    ): Int {
        if (
            observedSamples <= 0 ||
            !plausibleValue
        ) {
            return 0
        }
        var score = 18
        score +=
            min(
                24,
                changeCount * 5,
            )
        score +=
            min(
                12,
                stableCount * 2,
            )
        if (preferredRegion) {
            score += 12
        }
        if (training) {
            score += 12
        }

        val directional =
            increaseCount +
                decreaseCount
        if (directional >= 2) {
            val dominant =
                max(
                    increaseCount,
                    decreaseCount,
                )
            if (
                dominant.toDouble() /
                    directional.toDouble() >=
                0.8
            ) {
                score += 10
            }
        }

        val changedRatio =
            changeCount.toDouble() /
                max(
                    1,
                    observedSamples,
                )
        if (
            observedSamples >= 4 &&
            changedRatio > 0.92
        ) {
            score -= 24
        }
        if (
            distinctValueCount >=
            12 &&
            observedSamples <= 16
        ) {
            score -= 10
        }

        return score.coerceIn(
            1,
            99,
        )
    }

    private fun confidence(
        track: BehavioralTrack,
        mode: BehavioralScanMode,
    ): Int {
        var score =
            confidence(
                changeCount =
                    track.changeCount,
                stableCount =
                    track.stableCount,
                increaseCount =
                    track.increaseCount,
                decreaseCount =
                    track.decreaseCount,
                observedSamples =
                    track.observedSamples,
                distinctValueCount =
                    track.distinctBits.size,
                preferredRegion =
                    preferredRegion(
                        track.regionPath,
                    ),
                plausibleValue =
                    plausible(
                        type =
                            track.valueType,
                        bits =
                            track.lastBits,
                    ),
                training =
                    mode ==
                        BehavioralScanMode
                            .TRAINING,
            )
        val transitions =
            activityTransitions(
                track.recentChangeMask,
                track.patternSamples,
            )
        if (transitions >= 4) {
            score += 10
        } else if (transitions >= 2) {
            score += 6
        }
        if (
            track.patternSamples >= 8 &&
            transitions == 0
        ) {
            score -= 18
        }
        return score.coerceIn(
            1,
            99,
        )
    }

    private fun activityTransitions(
        mask: Int,
        samples: Int,
    ): Int {
        val count =
            samples.coerceIn(
                0,
                16,
            )
        if (count <= 1) {
            return 0
        }
        var transitions = 0
        var previous =
            mask and 1
        for (
            index in
            1 until count
        ) {
            val current =
                (
                    mask ushr
                        index
                    ) and
                    1
            if (current != previous) {
                transitions++
            }
            previous =
                current
        }
        return transitions
    }

    private fun candidateTitle(
        track: BehavioralTrack,
        hint: BehavioralActionHint?,
    ): String {
        if (hint != null) {
            return hint.candidateTitle
        }

        val directional =
            track.increaseCount +
                track.decreaseCount
        if (
            directional >= 3 &&
            track.decreaseCount >=
            track.increaseCount * 2 &&
            nonNegative(
                track.valueType,
                track.lastBits,
            )
        ) {
            return "Убывающий параметр игрока"
        }
        if (
            directional >= 3 &&
            track.increaseCount >=
            track.decreaseCount * 2
        ) {
            return "Растущий параметр игрока"
        }

        if (
            track.valueType ==
                RuntimeValueType.FLOAT32 ||
            track.valueType ==
                RuntimeValueType.FLOAT64
        ) {
            return "Параметр движения / состояния"
        }

        if (
            smallInteger(
                track.valueType,
                track.lastBits,
            ) &&
            track.distinctBits.size <= 4
        ) {
            return "Состояние / флаг"
        }

        return "Повторяющееся действие обнаружено"
    }

    internal fun trainingCandidateRelevant(
        candidate:
            BehavioralRuntimeCandidate,
    ): Boolean =
        semanticValueRelevant(
            candidate =
                candidate,
            strict = false,
        )

    internal fun emergingAutoCandidateRelevant(
        candidate:
            BehavioralRuntimeCandidate,
    ): Boolean {
        if (
            candidate.observedSamples < 3 ||
            candidate.changeCount < 2
        ) {
            return false
        }
        return semanticValueRelevant(
            candidate =
                candidate,
            strict = true,
        )
    }

    private fun autoCandidateRelevant(
        candidate:
            BehavioralRuntimeCandidate,
    ): Boolean {
        if (
            candidate.observedSamples < 6 ||
            candidate.changeCount < 4 ||
            candidate.stableCount < 1 ||
            candidate.activityTransitions < 2
        ) {
            return false
        }

        return semanticValueRelevant(
            candidate =
                candidate,
            strict = true,
        )
    }

    private fun semanticValueRelevant(
        candidate:
            BehavioralRuntimeCandidate,
        strict: Boolean,
    ): Boolean =
        when (
            candidate.valueType
        ) {
            RuntimeValueType.INT32 -> {
                val value =
                    candidate.value
                        .toIntOrNull()
                        ?: return false
                val limit =
                    if (strict) {
                        20_000_000L
                    } else {
                        50_000_000L
                    }
                val negativeLimit =
                    if (strict) {
                        250_000L
                    } else {
                        1_000_000L
                    }
                kotlin.math.abs(
                    value.toLong(),
                ) <=
                    limit &&
                    (
                        value >= 0 ||
                            kotlin.math.abs(
                                value.toLong(),
                            ) <=
                            negativeLimit
                        )
            }

            RuntimeValueType.INT64 -> {
                val value =
                    candidate.value
                        .toLongOrNull()
                        ?: return false
                val magnitude =
                    kotlin.math.abs(
                        value.toDouble(),
                    )
                val limit =
                    if (strict) {
                        1.0e9
                    } else {
                        1.0e10
                    }
                val negativeLimit =
                    if (strict) {
                        5.0e5
                    } else {
                        2.0e6
                    }
                magnitude <=
                    limit &&
                    (
                        value >= 0L ||
                            magnitude <=
                            negativeLimit
                        )
            }

            RuntimeValueType.FLOAT32,
            RuntimeValueType.FLOAT64,
            -> {
                val value =
                    candidate.value
                        .toDoubleOrNull()
                        ?: return false
                value.isFinite() &&
                    (
                        value == 0.0 ||
                            kotlin.math.abs(
                                value,
                            ) >=
                            1.0e-7
                        ) &&
                    kotlin.math.abs(
                        value,
                    ) <=
                    if (strict) {
                        100_000.0
                    } else {
                        1_000_000.0
                    }
            }
        }

    private fun plausible(
        type: RuntimeValueType,
        bits: Long,
    ): Boolean =
        when (type) {
            RuntimeValueType.INT32 -> {
                val value =
                    bits.toInt()
                value !=
                    Int.MIN_VALUE
            }

            RuntimeValueType.INT64 -> {
                val value = bits
                abs(
                    value.toDouble(),
                ) <=
                    9.0e15
            }

            RuntimeValueType.FLOAT32 -> {
                val value =
                    Float.fromBits(
                        bits.toInt(),
                    )
                value.isFinite() &&
                    (
                        value == 0f ||
                            abs(value) >=
                            1.0e-8f
                        ) &&
                    abs(value) <=
                    1.0e9f
            }

            RuntimeValueType.FLOAT64 -> {
                val value =
                    Double.fromBits(
                        bits,
                    )
                value.isFinite() &&
                    (
                        value == 0.0 ||
                            abs(value) >=
                            1.0e-12
                        ) &&
                    abs(value) <=
                    1.0e12
            }
        }

    private fun nonNegative(
        type: RuntimeValueType,
        bits: Long,
    ): Boolean =
        when (type) {
            RuntimeValueType.INT32 ->
                bits.toInt() >= 0
            RuntimeValueType.INT64 ->
                bits >= 0L
            RuntimeValueType.FLOAT32 ->
                Float.fromBits(
                    bits.toInt(),
                ) >= 0f
            RuntimeValueType.FLOAT64 ->
                Double.fromBits(
                    bits,
                ) >= 0.0
        }

    private fun smallInteger(
        type: RuntimeValueType,
        bits: Long,
    ): Boolean =
        when (type) {
            RuntimeValueType.INT32 ->
                abs(
                    bits.toInt()
                        .toLong(),
                ) <= 64L
            RuntimeValueType.INT64 ->
                abs(
                    bits.toDouble(),
                ) <= 64.0
            else -> false
        }

    private fun isNoiseRegion(
        path: String?,
    ): Boolean {
        val normalized =
            path.orEmpty()
                .lowercase()
        return normalized.startsWith(
            "[stack",
        ) ||
            normalized.contains(
                "jit-cache",
            ) ||
            normalized.contains(
                "dalvik-jit",
            ) ||
            normalized.contains(
                "gralloc",
            ) ||
            normalized.contains(
                "kgsl",
            ) ||
            normalized.contains(
                "dmabuf",
            ) ||
            normalized.startsWith(
                "/dev/",
            )
    }

    private fun preferredRegion(
        path: String?,
    ): Boolean {
        if (path == null) {
            return true
        }
        val normalized =
            path.lowercase()
        return normalized ==
            "[heap]" ||
            normalized.startsWith(
                "[anon:",
            ) ||
            normalized.contains(
                "libil2cpp",
            ) ||
            normalized.contains(
                "unity",
            )
    }

    private fun trimTracks(
        session: RootBehavioralScanSession,
        keep: Int,
    ) {
        val retained =
            session.tracks
                .values
                .sortedByDescending {
                    confidence(
                        track = it,
                        mode =
                            session.mode,
                    )
                }
                .take(keep)
        session.tracks.clear()
        retained.forEach {
            track ->
            session.tracks[
                key(
                    type =
                        track.valueType,
                    address =
                        track.address,
                ),
            ] = track
        }
    }

    private fun rotateBaseline(
        context: Context,
        session: RootBehavioralScanSession,
        cancellation: CancellationSignal,
        runner: RootCommandRunner,
    ) {
        val previous =
            session.baseline
        val replacement =
            RootRuntimeUnknownValueCoordinator
                .captureBaseline(
                    packageName =
                        session.packageName,
                    valueType =
                        RuntimeValueType.INT32,
                    alignment =
                        RuntimeScanAlignment.NATURAL,
                    snapshotFile =
                        newBaselineFile(
                            context = context,
                            packageName =
                                session.packageName,
                            pid = session.pid,
                        ),
                    cancellation =
                        cancellation,
                    runner = runner,
                    maxBytes =
                        previous.capturedBytes
                            .coerceAtLeast(
                                4L * 1024L * 1024L,
                            ),
                    expectedPid =
                        session.pid,
                    behavioralMode =
                        true,
                )
        session.baseline =
            replacement
        session.rotatedAtCycle =
            session.cycle
        RootRuntimeUnknownValueCoordinator
            .deleteBaseline(
                previous,
            )
    }

    private fun newBaselineFile(
        context: Context,
        packageName: String,
        pid: Int,
    ): File {
        val root =
            File(
                context.cacheDir,
                "behavioral-scans",
            ).apply {
                mkdirs()
            }
        val safePackage =
            packageName.replace(
                Regex(
                    "[^A-Za-z0-9._-]",
                ),
                "_",
            )
        return File(
            root,
            safePackage +
                "-" +
                pid +
                "-" +
                System.nanoTime() +
                ".baseline",
        )
    }

    private fun key(
        type: RuntimeValueType,
        address: Long,
    ): String =
        type.name +
            ":" +
            address.toString(
                16,
            )
}
