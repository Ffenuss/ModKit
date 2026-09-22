package io.github.ffenuss.modkit.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.ffenuss.modkit.R
import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class LiveProcessOverlayService : Service() {
    private val executor =
        Executors.newScheduledThreadPool(
            3,
        )
    private val main =
        android.os.Handler(
            Looper.getMainLooper(),
        )
    private val behavioralBusy =
        AtomicBoolean(false)
    private val stabilizationBusy =
        AtomicBoolean(false)
    private val profileStore by lazy {
        BehavioralProfileStore(
            applicationContext,
        )
    }
    private val profileLock =
        Any()
    private val stabilizationAttempted =
        ConcurrentHashMap
            .newKeySet<String>()

    @Volatile
    private var artifactIdentity:
        BehavioralArtifactIdentity? = null

    private var config:
        ProcessOverlayConfig? = null
    private var windowManager:
        WindowManager? = null
    private var overlayView:
        View? = null
    private var windowParams:
        WindowManager.LayoutParams? = null
    private var panel:
        View? = null
    private var floating:
        Button? = null

    private var statusView:
        TextView? = null
    private var learnedList:
        LinearLayout? = null
    private var behavioralList:
        LinearLayout? = null
    private var manualList:
        LinearLayout? = null
    private var manualCount:
        TextView? = null
    private var autoButton:
        Button? = null
    private var manualTypeButton:
        Button? = null
    private var manualQuery:
        EditText? = null
    private var editorTitle:
        TextView? = null
    private var editorValue:
        EditText? = null
    private var freezeButton:
        Button? = null

    private var autoSession:
        RootBehavioralScanSession? = null
    private var autoTask:
        ScheduledFuture<*>? = null
    private var trainingSession:
        RootBehavioralScanSession? = null
    private var trainingWasAuto =
        false

    private var learnedCandidates =
        emptyList<EditableRuntimeCandidate>()
    private var behavioralCandidates =
        emptyList<BehavioralRuntimeCandidate>()
    private var behavioralSource =
        LearnedCandidateSource.AUTO
    private var behavioralActionHint:
        BehavioralActionHint? = null
    private val trainingRounds =
        mutableMapOf<
            BehavioralActionHint,
            Int
        >()
    private val trainingAggregates =
        mutableMapOf<
            BehavioralActionHint,
            MutableMap<
                String,
                TrainingAggregate
            >
        >()
    private val announcedIds =
        linkedSetOf<String>()

    private var manualType =
        RuntimeValueType.INT32
    private var manualFullScan =
        false
    private var manualScan:
        RootRuntimeValueScanResult? = null
    private var manualBaseline:
        RootRuntimeUnknownBaseline? = null

    private var selectedCandidate:
        EditableRuntimeCandidate? = null
    private var freezeTask:
        ScheduledFuture<*>? = null
    private var freezeTarget:
        EditableRuntimeCandidate? = null
    private var freezeValue:
        String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                "Подготовка live overlay…",
            ),
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val encoded =
            intent?.getStringExtra(
                RootProcessOverlayLauncher
                    .CONFIG_EXTRA,
            )
        if (encoded.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val parsed =
            runCatching {
                ProcessOverlayConfigCodec
                    .decode(encoded)
            }.getOrElse {
                stopSelf()
                return START_NOT_STICKY
            }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        resetRuntimeState()
        config = parsed
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                parsed.label +
                    " · PID " +
                    parsed.pid,
            ),
        )
        showOverlay(parsed)
        loadLearnedProfile(
            parsed,
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        resetRuntimeState()
        removeOverlay()
        executor.shutdownNow()
        stopForeground(
            STOP_FOREGROUND_REMOVE,
        )
        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?,
    ): IBinder? = null

    private fun showOverlay(
        config: ProcessOverlayConfig,
    ) {
        removeOverlay()

        val wm =
            getSystemService(
                WINDOW_SERVICE,
            ) as WindowManager
        windowManager = wm

        val root =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    dp(4),
                    dp(4),
                    dp(4),
                    dp(4),
                )
            }

        val mk =
            Button(this).apply {
                text = "MK"
                textSize = 12f
                minWidth = dp(56)
                minimumWidth = dp(56)
                minHeight = dp(48)
                minimumHeight = dp(48)
            }
        floating = mk
        root.addView(
            mk,
            LinearLayout.LayoutParams(
                dp(64),
                dp(52),
            ),
        )

        val builtPanel =
            buildPanel(config).apply {
                visibility =
                    View.GONE
            }
        panel = builtPanel
        val display =
            resources.displayMetrics
        val panelWidth =
            kotlin.math.min(
                dp(340),
                (
                    display.widthPixels -
                        dp(20)
                    ).coerceAtLeast(
                    dp(96),
                ),
            )
        val panelHeight =
            kotlin.math.min(
                dp(590),
                (
                    display.heightPixels -
                        dp(170)
                    ).coerceAtLeast(
                    dp(64),
                ),
            )
        root.addView(
            builtPanel,
            LinearLayout.LayoutParams(
                panelWidth,
                panelHeight,
            ).apply {
                topMargin =
                    dp(4)
            },
        )

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams
                    .WRAP_CONTENT,
                WindowManager.LayoutParams
                    .WRAP_CONTENT,
                WindowManager.LayoutParams
                    .TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams
                    .FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams
                        .FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity =
                    Gravity.TOP or
                        Gravity.START
                x = dp(10)
                y = dp(92)
            }
        windowParams = params

        mk.setOnClickListener {
            val showing =
                builtPanel.visibility ==
                    View.VISIBLE
            setPanelVisible(
                !showing,
            )
            if (
                showing.not() &&
                trainingSession != null
            ) {
                finishTraining()
            }
        }

        installDrag(
            handle = mk,
            root = root,
            params = params,
            windowManager = wm,
        )

        runCatching {
            wm.addView(
                root,
                params,
            )
        }.onFailure {
            stopSelf()
            return
        }
        overlayView = root
    }

    private fun buildPanel(
        config: ProcessOverlayConfig,
    ): View {
        val body =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    dp(12),
                    dp(10),
                    dp(12),
                    dp(10),
                )
                background =
                    GradientDrawable()
                        .apply {
                            setColor(
                                Color.argb(
                                    244,
                                    25,
                                    25,
                                    30,
                                ),
                            )
                            cornerRadius =
                                dp(14)
                                    .toFloat()
                        }
            }

        body.addView(
            TextView(this).apply {
                text =
                    "ModKit Live · " +
                        config.label
                setTextColor(
                    Color.WHITE,
                )
                textSize = 18f
            },
        )
        body.addView(
            TextView(this).apply {
                text =
                    config.packageName +
                        " · PID " +
                        config.pid
                setTextColor(
                    Color.LTGRAY,
                )
                textSize = 11f
            },
        )

        statusView =
            TextView(this).apply {
                text =
                    "Подключено. Автоскан ещё не запущен."
                setTextColor(
                    Color.WHITE,
                )
                textSize = 12f
                setPadding(
                    0,
                    dp(8),
                    0,
                    dp(8),
                )
            }
        body.addView(
            statusView,
        )

        body.addView(
            sectionTitle(
                "Сохранённые моды",
            ),
        )
        learnedList =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }
        body.addView(
            learnedList,
            matchWidth(),
        )
        rebuildLearnedList()

        val auto =
            Button(this).apply {
                text =
                    "▶ Автоскан"
                setOnClickListener {
                    if (
                        autoSession ==
                        null
                    ) {
                        startAutoScan()
                    } else {
                        stopAutoScan(
                            userRequested =
                                true,
                        )
                    }
                }
            }
        autoButton = auto
        body.addView(
            auto,
            matchWidth(),
        )

        body.addView(
            sectionTitle(
                "Обучить действие",
            ),
        )
        val trainingRow1 =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }
        trainingRow1.addView(
            trainingButton(
                "Ходьба",
                BehavioralActionHint
                    .MOVEMENT,
            ),
            weighted(),
        )
        trainingRow1.addView(
            trainingButton(
                "Атака",
                BehavioralActionHint
                    .ATTACK,
            ),
            weighted(),
        )
        body.addView(
            trainingRow1,
            matchWidth(),
        )

        val trainingRow2 =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }
        trainingRow2.addView(
            trainingButton(
                "Получаю урон",
                BehavioralActionHint
                    .DAMAGE_TAKEN,
            ),
            weighted(),
        )
        trainingRow2.addView(
            trainingButton(
                "Ресурс",
                BehavioralActionHint
                    .RESOURCE_CHANGE,
            ),
            weighted(),
        )
        body.addView(
            trainingRow2,
            matchWidth(),
        )

        val trainingRow3 =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }
        trainingRow3.addView(
            trainingButton(
                "Предмет",
                BehavioralActionHint
                    .ITEM_CHANGE,
            ),
            weighted(),
        )
        trainingRow3.addView(
            trainingButton(
                "Другое",
                BehavioralActionHint
                    .OTHER,
            ),
            weighted(),
        )
        body.addView(
            trainingRow3,
            matchWidth(),
        )

        body.addView(
            sectionTitle(
                "Кандидаты автосканирования",
            ),
        )
        behavioralList =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }
        body.addView(
            behavioralList,
            matchWidth(),
        )

        body.addView(
            sectionTitle(
                "Ручной поиск значений",
            ),
        )

        val manualTop =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }
        manualTypeButton =
            Button(this).apply {
                text =
                    manualType.title
                setOnClickListener {
                    manualType =
                        manualType.next()
                    text =
                        manualType.title
                    clearManualSearch()
                }
            }
        manualTop.addView(
            manualTypeButton,
            LinearLayout.LayoutParams(
                dp(112),
                LinearLayout.LayoutParams
                    .WRAP_CONTENT,
            ),
        )
        manualQuery =
            EditText(this).apply {
                hint = "100 · 10..20 · 1.0~0.1 · 10,20"
                setSingleLine(true)
                setTextColor(
                    Color.WHITE,
                )
                setHintTextColor(
                    Color.GRAY,
                )
            }
        manualTop.addView(
            manualQuery,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams
                    .WRAP_CONTENT,
                1f,
            ),
        )
        body.addView(
            manualTop,
            matchWidth(),
        )

        body.addView(
            Button(this).apply {
                text =
                    "Объём: быстрый"
                setOnClickListener {
                    manualFullScan =
                        !manualFullScan
                    text =
                        if (
                            manualFullScan
                        ) {
                            "Объём: полный"
                        } else {
                            "Объём: быстрый"
                        }
                    clearManualSearch()
                }
            },
            matchWidth(),
        )

        val exactRow =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }
        exactRow.addView(
            Button(this).apply {
                text = "Новый поиск"
                setOnClickListener {
                    manualExact(
                        refine = false,
                    )
                }
            },
            weighted(),
        )
        exactRow.addView(
            Button(this).apply {
                text = "Уточнить ="
                setOnClickListener {
                    manualExact(
                        refine = true,
                    )
                }
            },
            weighted(),
        )
        exactRow.addView(
            Button(this).apply {
                text = "Неизвестно"
                setOnClickListener {
                    manualUnknownBaseline()
                }
            },
            weighted(),
        )
        body.addView(
            exactRow,
            matchWidth(),
        )

        val refineRow1 =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }
        refineRow1.addView(
            refineButton(
                "Изм.",
                RuntimeValueRefinement
                    .CHANGED,
            ),
            weighted(),
        )
        refineRow1.addView(
            refineButton(
                "Не изм.",
                RuntimeValueRefinement
                    .UNCHANGED,
            ),
            weighted(),
        )
        refineRow1.addView(
            refineButton(
                "↑",
                RuntimeValueRefinement
                    .INCREASED,
            ),
            weighted(),
        )
        refineRow1.addView(
            refineButton(
                "↓",
                RuntimeValueRefinement
                    .DECREASED,
            ),
            weighted(),
        )
        body.addView(
            refineRow1,
            matchWidth(),
        )

        manualCount =
            TextView(this).apply {
                text = "Результатов: 0"
                setTextColor(
                    Color.LTGRAY,
                )
                textSize = 11f
            }
        body.addView(
            manualCount,
        )
        manualList =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }
        body.addView(
            manualList,
            matchWidth(),
        )

        body.addView(
            sectionTitle(
                "Изменить выбранное",
            ),
        )
        editorTitle =
            TextView(this).apply {
                text =
                    "Кандидат не выбран"
                setTextColor(
                    Color.LTGRAY,
                )
                textSize = 11f
            }
        body.addView(
            editorTitle,
        )
        editorValue =
            EditText(this).apply {
                hint =
                    "Новое значение"
                setSingleLine(true)
                setTextColor(
                    Color.WHITE,
                )
                setHintTextColor(
                    Color.GRAY,
                )
            }
        body.addView(
            editorValue,
            matchWidth(),
        )

        val writeRow =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }
        writeRow.addView(
            Button(this).apply {
                text = "Записать"
                setOnClickListener {
                    writeSelected()
                }
            },
            weighted(),
        )
        val freeze =
            Button(this).apply {
                text = "Freeze: OFF"
                setOnClickListener {
                    toggleFreeze()
                }
            }
        freezeButton = freeze
        writeRow.addView(
            freeze,
            weighted(),
        )
        body.addView(
            writeRow,
            matchWidth(),
        )

        body.addView(
            Button(this).apply {
                text =
                    "Закрепить для следующих запусков"
                setOnClickListener {
                    persistSelectedCandidate()
                }
            },
            matchWidth(),
        )
        body.addView(
            Button(this).apply {
                text =
                    "Удалить выбранное из сохранённых"
                setOnClickListener {
                    removeSelectedPersistentCandidate()
                }
            },
            matchWidth(),
        )

        val collapse =
            Button(this).apply {
                text =
                    "Свернуть MK"
                setOnClickListener {
                    setPanelVisible(
                        false,
                    )
                }
            }
        body.addView(
            collapse,
            matchWidth(),
        )

        body.addView(
            Button(this).apply {
                text =
                    "Закрыть MK и остановить сканеры"
                setOnClickListener {
                    stopSelf()
                }
            },
            matchWidth(),
        )

        return ScrollView(this).apply {
            isFillViewport =
                true
            addView(
                body,
                android.view.ViewGroup
                    .LayoutParams(
                        android.view.ViewGroup
                            .LayoutParams
                            .MATCH_PARENT,
                        android.view.ViewGroup
                            .LayoutParams
                            .WRAP_CONTENT,
                    ),
            )
        }
    }

    private fun startAutoScan() {
        val cfg =
            config ?: return
        if (
            autoSession != null ||
            behavioralBusy
                .getAndSet(true)
        ) {
            return
        }
        setStatus(
            "Создаём baseline автосканирования…",
        )
        autoButton?.isEnabled =
            false
        executor.execute {
            val result =
                runCatching {
                    RootBehavioralScanCoordinator
                        .start(
                            context =
                                applicationContext,
                            packageName =
                                cfg.packageName,
                            pid = cfg.pid,
                            mode =
                                BehavioralScanMode
                                    .AUTO,
                            cancellation =
                                AtomicCancellationSignal(),
                        )
                }
            main.post {
                behavioralBusy.set(
                    false,
                )
                autoButton?.isEnabled =
                    true
                result.onSuccess {
                    session ->
                    autoSession =
                        session
                    autoButton?.text =
                        "■ Остановить автоскан"
                    setStatus(
                        "Автоскан активен. Играй как обычно — ModKit будет накапливать подтверждения.",
                    )
                    scheduleAutoSamples()
                }.onFailure {
                    failure ->
                    setStatus(
                        "Автоскан не запущен: " +
                            (
                                failure.message
                                    ?: failure
                                        .javaClass
                                        .simpleName
                                ),
                    )
                }
            }
        }
    }

    private fun scheduleAutoSamples() {
        autoTask?.cancel(
            false,
        )
        autoTask =
            executor.scheduleWithFixedDelay(
                {
                    val session =
                        autoSession
                            ?: return@scheduleWithFixedDelay
                    if (
                        !behavioralBusy
                            .compareAndSet(
                                false,
                                true,
                            )
                    ) {
                        return@scheduleWithFixedDelay
                    }
                    val result =
                        runCatching {
                            RootBehavioralScanCoordinator
                                .sample(
                                    context =
                                        applicationContext,
                                    session =
                                        session,
                                    cancellation =
                                        AtomicCancellationSignal(),
                                )
                        }
                    behavioralBusy.set(
                        false,
                    )
                    main.post {
                        result.onSuccess {
                            sample ->
                            applyBehavioralSample(
                                sample = sample,
                                source =
                                    LearnedCandidateSource
                                        .AUTO,
                                actionHint = null,
                            )
                        }.onFailure {
                            failure ->
                            setStatus(
                                "Автоскан остановлен: " +
                                    (
                                        failure.message
                                            ?: failure
                                                .javaClass
                                                .simpleName
                                        ),
                            )
                            stopAutoScan(
                                userRequested =
                                    false,
                            )
                        }
                    }
                },
                1200L,
                2400L,
                TimeUnit.MILLISECONDS,
            )
    }

    private fun stopAutoScan(
        userRequested: Boolean,
    ) {
        autoTask?.cancel(
            false,
        )
        autoTask = null
        val session =
            autoSession
        autoSession = null
        if (session != null) {
            RootBehavioralScanCoordinator
                .close(session)
        }
        autoButton?.text =
            "▶ Автоскан"
        if (userRequested) {
            setStatus(
                "Автоскан остановлен. Найденные кандидаты оставлены в меню.",
            )
        }
    }

    private fun startTraining(
        hint: BehavioralActionHint,
    ) {
        val cfg =
            config ?: return
        if (
            trainingSession != null ||
            behavioralBusy
                .getAndSet(true)
        ) {
            return
        }
        trainingWasAuto =
            autoSession != null
        if (trainingWasAuto) {
            stopAutoScan(
                userRequested =
                    false,
            )
        }
        setStatus(
            "Готовим обучение: " +
                hint.title +
                "…",
        )
        executor.execute {
            val result =
                runCatching {
                    RootBehavioralScanCoordinator
                        .start(
                            context =
                                applicationContext,
                            packageName =
                                cfg.packageName,
                            pid = cfg.pid,
                            mode =
                                BehavioralScanMode
                                    .TRAINING,
                            actionHint =
                                hint,
                            cancellation =
                                AtomicCancellationSignal(),
                        )
                }
            main.post {
                behavioralBusy.set(
                    false,
                )
                result.onSuccess {
                    session ->
                    trainingSession =
                        session
                    setStatus(
                        "Обучение «" +
                            hint.title +
                            "»: сверни MK, выполни действие несколько раз и снова нажми MK.",
                    )
                    setPanelVisible(
                        false,
                    )
                }.onFailure {
                    failure ->
                    setStatus(
                        "Не удалось начать обучение: " +
                            (
                                failure.message
                                    ?: failure
                                        .javaClass
                                        .simpleName
                                ),
                    )
                    if (trainingWasAuto) {
                        startAutoScan()
                    }
                }
            }
        }
    }

    private fun finishTraining() {
        val session =
            trainingSession
                ?: return
        if (
            !behavioralBusy
                .compareAndSet(
                    false,
                    true,
                )
        ) {
            return
        }
        trainingSession = null
        setStatus(
            "Сопоставляем изменения с действием «" +
                (
                    session.actionHint
                        ?.title
                        ?: "действие"
                    ) +
                "»…",
        )
        executor.execute {
            val result =
                runCatching {
                    RootBehavioralScanCoordinator
                        .finishTraining(
                            context =
                                applicationContext,
                            session =
                                session,
                            cancellation =
                                AtomicCancellationSignal(),
                            visibleLimit =
                                64,
                        )
                }
            RootBehavioralScanCoordinator
                .close(session)
            main.post {
                behavioralBusy.set(
                    false,
                )
                result.onSuccess {
                    sample ->
                    val hint =
                        requireNotNull(
                            session.actionHint,
                        )
                    val consolidated =
                        consolidateTrainingSample(
                            sample = sample,
                            hint = hint,
                        )
                    applyBehavioralSample(
                        sample =
                            consolidated,
                        source =
                            LearnedCandidateSource
                                .TRAINING,
                        actionHint =
                            hint,
                    )
                    setStatus(
                        "Обучение «" +
                            hint.title +
                            "»: раунд " +
                            (
                                trainingRounds[
                                    hint
                                ] ?: 1
                                ) +
                            " · после фильтра " +
                            consolidated
                                .visibleCandidates
                                .size +
                            " кандидатов из " +
                            sample
                                .visibleCandidates
                                .size +
                            ". Повтори то же действие, чтобы сузить список дальше.",
                    )
                }.onFailure {
                    failure ->
                    setStatus(
                        "Обучение не завершено: " +
                            (
                                failure.message
                                    ?: failure
                                        .javaClass
                                        .simpleName
                                ),
                    )
                }
                if (trainingWasAuto) {
                    trainingWasAuto =
                        false
                    startAutoScan()
                }
            }
        }
    }

    private fun consolidateTrainingSample(
        sample: BehavioralScanSample,
        hint: BehavioralActionHint,
    ): BehavioralScanSample {
        val round =
            (
                trainingRounds[
                    hint
                ] ?: 0
                ) +
                1
        trainingRounds[
            hint
        ] = round

        val aggregates =
            trainingAggregates
                .getOrPut(
                    hint,
                ) {
                    linkedMapOf()
                }
        sample.visibleCandidates
            .forEach {
                candidate ->
                val previous =
                    aggregates[
                        candidate.id
                    ]
                val seenRounds =
                    (
                        previous
                            ?.seenRounds
                            ?: 0
                        ) +
                        1
                val boosted =
                    candidate.copy(
                        confidence =
                            (
                                maxOf(
                                    candidate
                                        .confidence,
                                    previous
                                        ?.candidate
                                        ?.confidence
                                        ?: 0,
                                ) +
                                    minOf(
                                        24,
                                        (
                                            seenRounds -
                                                1
                                            ) *
                                            8,
                                    )
                                ).coerceAtMost(
                                99,
                            ),
                        changeCount =
                            candidate
                                .changeCount +
                                (
                                    previous
                                        ?.candidate
                                        ?.changeCount
                                        ?: 0
                                    ),
                        stableCount =
                            candidate
                                .stableCount +
                                (
                                    previous
                                        ?.candidate
                                        ?.stableCount
                                        ?: 0
                                    ),
                        observedSamples =
                            candidate
                                .observedSamples +
                                (
                                    previous
                                        ?.candidate
                                        ?.observedSamples
                                        ?: 0
                                    ),
                    )
                aggregates[
                    candidate.id
                ] =
                    TrainingAggregate(
                        candidate =
                            boosted,
                        seenRounds =
                            seenRounds,
                    )
            }

        if (round > 1) {
            val minimumSeen =
                if (round <= 2) {
                    round
                } else {
                    round - 1
                }
            val iterator =
                aggregates
                    .entries
                    .iterator()
            while (
                iterator.hasNext()
            ) {
                val entry =
                    iterator.next()
                if (
                    entry.value
                        .seenRounds <
                    minimumSeen
                ) {
                    iterator.remove()
                }
            }
        }

        val visible =
            aggregates
                .values
                .sortedWith(
                    compareByDescending<
                        TrainingAggregate
                    > {
                        it.seenRounds
                    }.thenByDescending {
                        it.candidate
                            .confidence
                    },
                )
                .map {
                    it.candidate
                }
                .take(8)

        return sample.copy(
            visibleCandidates =
                visible,
            hiddenAsNoise =
                sample.hiddenAsNoise +
                    maxOf(
                        0,
                        sample
                            .visibleCandidates
                            .size -
                            visible.size,
                    ),
        )
    }

    private fun applyBehavioralSample(
        sample: BehavioralScanSample,
        source: LearnedCandidateSource,
        actionHint: BehavioralActionHint?,
    ) {
        behavioralCandidates =
            sample.visibleCandidates
        behavioralSource =
            source
        behavioralActionHint =
            actionHint
        rebuildBehavioralList()
        floating?.text =
            if (
                behavioralCandidates
                    .isEmpty()
            ) {
                "MK"
            } else {
                "MK · " +
                    behavioralCandidates
                        .size
            }
        setStatus(
            "Автоскан: цикл " +
                sample.cycle +
                " · отслеживается " +
                sample.trackedCandidates +
                " · показано " +
                sample.visibleCandidates
                    .size +
                " · скрыто как шум " +
                sample.hiddenAsNoise +
                " · " +
                sample.elapsedMs +
                " мс",
        )

        val fresh =
            sample.visibleCandidates
                .firstOrNull {
                    it.confidence >=
                        70 &&
                        announcedIds.add(
                            it.id,
                        )
                }
        if (fresh != null) {
            Toast.makeText(
                this,
                "ModKit: " +
                    fresh.title +
                    " · " +
                    fresh.confidence +
                    "%",
                Toast.LENGTH_SHORT,
            ).show()
        }

        if (
            source ==
            LearnedCandidateSource.TRAINING
        ) {
            sample.visibleCandidates
                .firstOrNull {
                    candidate ->
                    candidate.confidence >=
                        62 &&
                        !stabilizationAttempted
                            .contains(
                                candidate.id,
                            )
                }
                ?.let {
                    candidate ->
                    stabilizeBehavioralCandidate(
                        candidate = candidate,
                        source = source,
                        actionHint =
                            actionHint,
                    )
                }
        }
    }

    private fun rebuildBehavioralList() {
        val list =
            behavioralList
                ?: return
        list.removeAllViews()
        if (
            behavioralCandidates
                .isEmpty()
        ) {
            list.addView(
                hintText(
                    "Пока нет кандидатов выше порога уверенности.",
                ),
            )
            return
        }
        behavioralCandidates
            .take(12)
            .forEach {
                candidate ->
                list.addView(
                    candidateRow(
                        EditableRuntimeCandidate(
                            id =
                                candidate.id,
                            title =
                                candidate.title,
                            address =
                                candidate.address,
                            valueType =
                                candidate
                                    .valueType,
                            value =
                                candidate.value,
                            subtitle =
                                "Уверенность " +
                                    candidate.confidence +
                                    "% · изменений " +
                                    candidate.changeCount +
                                    " · " +
                                    candidate
                                        .valueType
                                        .title,
                            confidence =
                                candidate.confidence,
                            source =
                                behavioralSource,
                            actionHint =
                                behavioralActionHint,
                        ),
                    ),
                )
            }
    }

    private fun manualExact(
        refine: Boolean,
    ) {
        val cfg =
            config ?: return
        if (autoSession != null) {
            stopAutoScan(
                userRequested = false,
            )
        }
        val query =
            manualQuery
                ?.text
                ?.toString()
                .orEmpty()
                .trim()
        if (query.isBlank()) {
            setStatus(
                "Введите значение для ручного поиска.",
            )
            return
        }
        setStatus(
            if (refine) {
                "Уточняем ручной поиск…"
            } else {
                "Ищем " +
                    manualType.title +
                    " = " +
                    query +
                    "…"
            },
        )
        executor.execute {
            val result =
                runCatching {
                    if (
                        refine &&
                        manualScan !=
                        null
                    ) {
                        RootRuntimeValueScanCoordinator
                            .refineExact(
                                previous =
                                    requireNotNull(
                                        manualScan,
                                    ),
                                query =
                                    query,
                                cancellation =
                                    AtomicCancellationSignal(),
                            )
                    } else {
                        when {
                            "," in query -> {
                                val values =
                                    query
                                        .split(",")
                                        .map {
                                            it.trim()
                                        }
                                        .filter {
                                            it.isNotBlank()
                                        }
                                RootRuntimeAdvancedValueScanCoordinator
                                    .scanGroup(
                                        packageName =
                                            cfg.packageName,
                                        expectedPid =
                                            cfg.pid,
                                        valueType =
                                            manualType,
                                        queryTexts =
                                            values,
                                        cancellation =
                                            AtomicCancellationSignal(),
                                        maxScanBytes =
                                            manualScanByteLimit(),

                                    )
                            }

                            ".." in query -> {
                                val bounds =
                                    query.split(
                                        "..",
                                        limit = 2,
                                    )
                                require(
                                    bounds.size == 2 &&
                                        bounds.all {
                                            it.trim()
                                                .isNotBlank()
                                        },
                                ) {
                                    "Диапазон вводится как минимум..максимум, например 90..110."
                                }
                                RootRuntimeAdvancedValueScanCoordinator
                                    .scanRange(
                                        packageName =
                                            cfg.packageName,
                                        expectedPid =
                                            cfg.pid,
                                        valueType =
                                            manualType,
                                        minText =
                                            bounds[0],
                                        maxText =
                                            bounds[1],
                                        cancellation =
                                            AtomicCancellationSignal(),
                                        maxScanBytes =
                                            manualScanByteLimit(),

                                    )
                            }

                            "~" in query -> {
                                val fuzzy =
                                    query.split(
                                        "~",
                                        limit = 2,
                                    )
                                require(
                                    fuzzy.size == 2 &&
                                        fuzzy.all {
                                            it.trim()
                                                .isNotBlank()
                                        },
                                ) {
                                    "Fuzzy вводится как значение~допуск, например 1.0~0.05."
                                }
                                RootRuntimeAdvancedValueScanCoordinator
                                    .scanFuzzy(
                                        packageName =
                                            cfg.packageName,
                                        expectedPid =
                                            cfg.pid,
                                        valueType =
                                            manualType,
                                        queryText =
                                            fuzzy[0],
                                        toleranceText =
                                            fuzzy[1],
                                        cancellation =
                                            AtomicCancellationSignal(),
                                        maxScanBytes =
                                            manualScanByteLimit(),

                                    )
                            }

                            else ->
                                RootRuntimeValueScanCoordinator
                                    .scanExact(
                                        packageName =
                                            cfg.packageName,
                                        valueType =
                                            manualType,
                                        query = query,
                                        cancellation =
                                            AtomicCancellationSignal(),
                                        maxScanBytes =
                                            manualScanByteLimit(),
                                        expectedPid =
                                            cfg.pid,
                                    )
                        }
                    }
                }
            main.post {
                result.onSuccess {
                    scan ->
                    manualBaseline?.let {
                        RootRuntimeUnknownValueCoordinator
                            .deleteBaseline(
                                it,
                            )
                    }
                    manualBaseline = null
                    manualScan = scan
                    renderManualScan()
                }.onFailure {
                    failure ->
                    setStatus(
                        "Ручной поиск: " +
                            (
                                failure.message
                                    ?: failure
                                        .javaClass
                                        .simpleName
                                ),
                    )
                }
            }
        }
    }

    private fun manualUnknownBaseline() {
        val cfg =
            config ?: return
        if (autoSession != null) {
            stopAutoScan(
                userRequested = false,
            )
        }
        clearManualSearch()
        setStatus(
            "Сохраняем baseline неизвестного " +
                manualType.title +
                "…",
        )
        executor.execute {
            val result =
                runCatching {
                    val file =
                        File(
                            cacheDir,
                            "overlay-manual/" +
                                cfg.packageName
                                    .replace(
                                        Regex(
                                            "[^A-Za-z0-9._-]",
                                        ),
                                        "_",
                                    ) +
                                "-" +
                                cfg.pid +
                                "-" +
                                System.nanoTime() +
                                ".baseline",
                        )
                    RootRuntimeUnknownValueCoordinator
                        .captureBaseline(
                            packageName =
                                cfg.packageName,
                            valueType =
                                manualType,
                            alignment =
                                RuntimeScanAlignment
                                    .NATURAL,
                            snapshotFile =
                                file,
                            cancellation =
                                AtomicCancellationSignal(),
                            maxBytes =
                                if (
                                    manualFullScan
                                ) {
                                    null
                                } else {
                                    RootRuntimeUnknownValueCoordinator
                                        .QUICK_MAX_BASELINE_BYTES
                                },
                            expectedPid =
                                cfg.pid,
                        )
                }
            main.post {
                result.onSuccess {
                    baseline ->
                    manualBaseline =
                        baseline
                    setStatus(
                        "Baseline готов. Измени значение в игре, затем нажми Изм./Не изм./↑/↓.",
                    )
                    setPanelVisible(
                        false,
                    )
                }.onFailure {
                    failure ->
                    setStatus(
                        "Baseline не создан: " +
                            (
                                failure.message
                                    ?: failure
                                        .javaClass
                                        .simpleName
                                ),
                    )
                }
            }
        }
    }

    private fun refineManual(
        refinement:
            RuntimeValueRefinement,
    ) {
        if (autoSession != null) {
            stopAutoScan(
                userRequested = false,
            )
        }
        val scan =
            manualScan
        val baseline =
            manualBaseline
        if (
            scan == null &&
            baseline == null
        ) {
            setStatus(
                "Сначала запусти ручной поиск или «Неизвестно».",
            )
            return
        }
        setStatus(
            "Уточнение: " +
                refinement.title +
                "…",
        )
        executor.execute {
            val result =
                runCatching {
                    if (scan != null) {
                        RootRuntimeValueScanCoordinator
                            .refine(
                                previous = scan,
                                refinement =
                                    refinement,
                                cancellation =
                                    AtomicCancellationSignal(),
                            )
                    } else {
                        RootRuntimeUnknownValueCoordinator
                            .compareBaseline(
                                baseline =
                                    requireNotNull(
                                        baseline,
                                    ),
                                refinement =
                                    refinement,
                                cancellation =
                                    AtomicCancellationSignal(),
                            )
                    }
                }
            main.post {
                result.onSuccess {
                    updated ->
                    manualScan =
                        updated
                    manualBaseline?.let {
                        RootRuntimeUnknownValueCoordinator
                            .deleteBaseline(
                                it,
                            )
                    }
                    manualBaseline = null
                    renderManualScan()
                }.onFailure {
                    failure ->
                    setStatus(
                        "Уточнение не выполнено: " +
                            (
                                failure.message
                                    ?: failure
                                        .javaClass
                                        .simpleName
                                ),
                    )
                }
            }
        }
    }

    private fun renderManualScan() {
        val scan =
            manualScan
                ?: return
        val hits =
            scan.snapshot.hits
        manualCount?.text =
            "Результатов: " +
                hits.size +
                (
                    if (
                        scan.snapshot
                            .truncatedByHitLimit ||
                        scan.snapshot
                            .truncatedByByteLimit
                    ) {
                        " (поиск ограничен)"
                    } else {
                        ""
                    }
                    )
        val list =
            manualList
                ?: return
        list.removeAllViews()
        hits.take(12)
            .forEach {
                hit ->
                list.addView(
                    candidateRow(
                        EditableRuntimeCandidate(
                            id =
                                "manual:" +
                                    scan.snapshot
                                        .valueType
                                        .name +
                                    ":" +
                                    hit.address
                                        .toString(
                                            16,
                                        ),
                            title =
                                "Ручной результат",
                            address =
                                hit.address,
                            valueType =
                                scan.snapshot
                                    .valueType,
                            value =
                                hit.displayValue(
                                    scan.snapshot
                                        .valueType,
                                ),
                            subtitle =
                                "0x" +
                                    hit.address
                                        .toString(
                                            16,
                                        ) +
                                    " · " +
                                    scan.snapshot
                                        .valueType
                                        .title,
                            source =
                                LearnedCandidateSource
                                    .MANUAL,
                        ),
                    ),
                )
            }
        setStatus(
            "Ручной поиск: найдено " +
                hits.size +
                ". Измени состояние игры и уточняй поиск.",
        )
    }

    private fun selectCandidate(
        candidate:
            EditableRuntimeCandidate,
    ) {
        selectedCandidate =
            candidate
        editorTitle?.text =
            candidate.title +
                "\n0x" +
                candidate.address
                    .toString(
                        16,
                    ) +
                " · " +
                candidate.valueType
                    .title +
                " · сейчас " +
                candidate.value
        editorValue?.setText(
            candidate.value,
        )
    }

    private fun writeSelected() {
        val cfg =
            config ?: return
        val candidate =
            selectedCandidate
                ?: run {
                    setStatus(
                        "Сначала выбери найденный кандидат.",
                    )
                    return
                }
        if (
            candidate
                .requiresConfirmation
        ) {
            setStatus(
                "После обновления этот перенос-кандидат нужно сначала подтвердить кнопкой «Закрепить для следующих запусков».",
            )
            return
        }
        val value =
            editorValue
                ?.text
                ?.toString()
                .orEmpty()
                .trim()
        if (value.isBlank()) {
            setStatus(
                "Введите новое значение.",
            )
            return
        }
        setStatus(
            "Записываем " +
                value +
                "…",
        )
        executor.execute {
            val result =
                runCatching {
                    val resolvedAddress =
                        resolveCandidateAddress(
                            candidate =
                                candidate,
                            cfg = cfg,
                        )
                    RootRuntimeDirectValueCoordinator
                        .writeValue(
                            packageName =
                                cfg.packageName,
                            pid = cfg.pid,
                            address =
                                resolvedAddress,
                            valueType =
                                candidate.valueType,
                            valueText =
                                value,
                            cancellation =
                                AtomicCancellationSignal(),
                        )
                }
            main.post {
                result.onSuccess {
                    written ->
                    val updatedCandidate =
                        candidate.copy(
                            address =
                                written.address,
                            value =
                                written.newValue,
                        )
                    selectedCandidate =
                        updatedCandidate
                    stabilizeEditableCandidate(
                        candidate =
                            updatedCandidate,
                        source =
                            candidate.source
                                ?: LearnedCandidateSource
                                    .MANUAL,
                        actionHint =
                            candidate.actionHint,
                        force = true,
                    )
                    editorTitle?.text =
                        candidate.title +
                            "\n0x" +
                            candidate.address
                                .toString(
                                    16,
                                ) +
                            " · " +
                            candidate.valueType
                                .title +
                            " · сейчас " +
                            written.newValue
                    setStatus(
                        "Записано: " +
                            written.oldValue +
                            " → " +
                            written.newValue +
                            ". Read-back подтверждён.",
                    )
                }.onFailure {
                    failure ->
                    setStatus(
                        "Запись не выполнена: " +
                            (
                                failure.message
                                    ?: failure
                                        .javaClass
                                        .simpleName
                                ),
                    )
                }
            }
        }
    }

    private fun toggleFreeze() {
        if (freezeTask != null) {
            stopFreeze()
            return
        }
        val candidate =
            selectedCandidate
                ?: run {
                    setStatus(
                        "Сначала выбери значение для Freeze.",
                    )
                    return
                }
        if (
            candidate
                .requiresConfirmation
        ) {
            setStatus(
                "После обновления перенос-кандидат сначала нужно закрепить повторно.",
            )
            return
        }
        val value =
            editorValue
                ?.text
                ?.toString()
                .orEmpty()
                .trim()
        if (value.isBlank()) {
            setStatus(
                "Введите значение для Freeze.",
            )
            return
        }
        val cfg =
            config ?: return
        if (autoSession != null) {
            stopAutoScan(
                userRequested = false,
            )
        }
        freezeTarget =
            candidate
        freezeValue =
            value
        freezeButton?.text =
            "Freeze: ON"
        setStatus(
            "Freeze включён: " +
                candidate.title +
                " = " +
                value +
                ". Автоскан на время Freeze не выполняется.",
        )
        freezeTask =
            executor.scheduleWithFixedDelay(
                {
                    val target =
                        freezeTarget
                            ?: return@scheduleWithFixedDelay
                    val frozen =
                        freezeValue
                            ?: return@scheduleWithFixedDelay
                    val result =
                        runCatching {
                            val resolvedAddress =
                                resolveCandidateAddress(
                                    candidate =
                                        target,
                                    cfg = cfg,
                                )
                            RootRuntimeDirectValueCoordinator
                                .writeValue(
                                    packageName =
                                        cfg.packageName,
                                    pid = cfg.pid,
                                    address =
                                        resolvedAddress,
                                    valueType =
                                        target.valueType,
                                    valueText =
                                        frozen,
                                    cancellation =
                                        AtomicCancellationSignal(),
                                )
                        }
                    if (result.isFailure) {
                        main.post {
                            setStatus(
                                "Freeze остановлен: " +
                                    (
                                        result
                                            .exceptionOrNull()
                                            ?.message
                                            ?: "ошибка записи"
                                        ),
                            )
                            stopFreeze()
                        }
                    }
                },
                0L,
                650L,
                TimeUnit.MILLISECONDS,
            )
    }

    private fun stopFreeze() {
        freezeTask?.cancel(
            false,
        )
        freezeTask = null
        freezeTarget = null
        freezeValue = null
        freezeButton?.text =
            "Freeze: OFF"
    }

    private fun clearManualSearch() {
        manualScan = null
        manualBaseline?.let {
            RootRuntimeUnknownValueCoordinator
                .deleteBaseline(it)
        }
        manualBaseline = null
        manualList?.removeAllViews()
        manualCount?.text =
            "Результатов: 0"
    }

    private fun resetRuntimeState() {
        stopFreeze()
        autoTask?.cancel(
            false,
        )
        autoTask = null
        RootBehavioralScanCoordinator
            .close(
                autoSession,
            )
        autoSession = null
        RootBehavioralScanCoordinator
            .close(
                trainingSession,
            )
        trainingSession = null
        manualBaseline?.let {
            RootRuntimeUnknownValueCoordinator
                .deleteBaseline(it)
        }
        manualBaseline = null
        manualScan = null
        manualFullScan = false
        learnedCandidates =
            emptyList()
        behavioralCandidates =
            emptyList()
        artifactIdentity = null
        stabilizationBusy.set(
            false,
        )
        stabilizationAttempted.clear()
        behavioralSource =
            LearnedCandidateSource.AUTO
        behavioralActionHint = null
        trainingRounds.clear()
        trainingAggregates.clear()
        announcedIds.clear()
        selectedCandidate = null
    }

    private fun loadLearnedProfile(
        cfg: ProcessOverlayConfig,
    ) {
        setStatus(
            "Подключено. Проверяем сохранённые моды этой версии…",
        )
        executor.execute {
            val result =
                runCatching {
                    val identity =
                        synchronized(
                            profileLock,
                        ) {
                            artifactIdentity
                                ?: profileStore
                                    .computeIdentity(
                                        packageName =
                                            cfg.packageName,
                                        cancellation =
                                            AtomicCancellationSignal(),
                                    )
                                    .also {
                                        artifactIdentity =
                                            it
                                    }
                        }
                    val exact =
                        synchronized(
                            profileLock,
                        ) {
                            profileStore
                                .loadExact(
                                    packageName =
                                        cfg.packageName,
                                    artifactSha256 =
                                        identity
                                            .artifactSha256,
                                )
                        }
                    val sourceProfile =
                        exact
                            ?: synchronized(
                                profileLock,
                            ) {
                                profileStore
                                    .loadLatest(
                                        cfg.packageName,
                                    )
                            }
                    if (sourceProfile == null) {
                        return@runCatching (
                            identity to
                                emptyList<
                                    EditableRuntimeCandidate
                                >()
                            )
                    }

                    val migrated =
                        sourceProfile
                            .artifactSha256 !=
                            identity
                                .artifactSha256
                    val resolved =
                        sourceProfile
                            .candidates
                            .mapNotNull {
                                saved ->
                                runCatching {
                                    val target =
                                        RootRuntimePointerChainCoordinator
                                            .resolve(
                                                packageName =
                                                    cfg.packageName,
                                                pid =
                                                    cfg.pid,
                                                anchor =
                                                    saved.anchor,
                                                cancellation =
                                                    AtomicCancellationSignal(),
                                            )
                                    val value =
                                        readRuntimeValue(
                                            cfg = cfg,
                                            address =
                                                target
                                                    .targetAddress,
                                            type =
                                                saved.valueType,
                                        )
                                    EditableRuntimeCandidate(
                                        id =
                                            "saved:" +
                                                saved.id,
                                        title =
                                            saved.title,
                                        address =
                                            target
                                                .targetAddress,
                                        valueType =
                                            saved.valueType,
                                        value =
                                            value,
                                        subtitle =
                                            (
                                                if (
                                                    migrated
                                                ) {
                                                    "После обновления: pointer-chain найден, требуется подтверждение"
                                                } else {
                                                    "Сохранено"
                                                }
                                                ) +
                                                " · " +
                                                (
                                                    if (
                                                        migrated
                                                    ) {
                                                        (
                                                            saved
                                                                .confidence -
                                                                10
                                                            ).coerceAtLeast(
                                                            50,
                                                        )
                                                    } else {
                                                        saved
                                                            .confidence
                                                    }
                                                    ) +
                                                "% · " +
                                                saved
                                                    .anchor
                                                    .moduleIdentity,
                                        confidence =
                                            if (
                                                migrated
                                            ) {
                                                (
                                                    saved
                                                        .confidence -
                                                        10
                                                    ).coerceAtLeast(
                                                    50,
                                                )
                                            } else {
                                                saved
                                                    .confidence
                                            },
                                        source =
                                            saved.source,
                                        actionHint =
                                            saved.actionHint,
                                        anchor =
                                            saved.anchor,
                                        persistent =
                                            !migrated,
                                        requiresConfirmation =
                                            migrated,
                                    )
                                }.getOrNull()
                            }
                    identity to resolved
                }
            main.post {
                result.onSuccess {
                    (_, resolved) ->
                    learnedCandidates =
                        resolved
                    rebuildLearnedList()
                    setStatus(
                        if (
                            resolved.isEmpty()
                        ) {
                            "Подключено. Сохранённых модов для этого процесса пока нет."
                        } else {
                            "Восстановлено сохранённых модов: " +
                                resolved.size +
                                ". Адреса заново разрешены через pointer-chain."
                        },
                    )
                }.onFailure {
                    failure ->
                    setStatus(
                        "Подключено. Профиль не восстановлен: " +
                            (
                                failure.message
                                    ?: failure
                                        .javaClass
                                        .simpleName
                                ),
                    )
                }
            }
        }
    }

    private fun rebuildLearnedList() {
        val list =
            learnedList
                ?: return
        list.removeAllViews()
        if (
            learnedCandidates
                .isEmpty()
        ) {
            list.addView(
                hintText(
                    "Пока пусто. Подтверждённые находки ModKit закрепит через pointer-chain.",
                ),
            )
            return
        }
        learnedCandidates
            .take(12)
            .forEach {
                candidate ->
                list.addView(
                    candidateRow(
                        candidate,
                    ),
                )
            }
    }

    private fun stabilizeBehavioralCandidate(
        candidate: BehavioralRuntimeCandidate,
        source: LearnedCandidateSource,
        actionHint: BehavioralActionHint?,
    ) {
        if (
            !stabilizationAttempted
                .add(
                    candidate.id,
                )
        ) {
            return
        }
        stabilizeEditableCandidate(
            candidate =
                EditableRuntimeCandidate(
                    id =
                        candidate.id,
                    title =
                        candidate.title,
                    address =
                        candidate.address,
                    valueType =
                        candidate.valueType,
                    value =
                        candidate.value,
                    subtitle =
                        "Уверенность " +
                            candidate.confidence +
                            "%",
                    confidence =
                        candidate.confidence,
                    source =
                        source,
                    actionHint =
                        actionHint,
                ),
            source = source,
            actionHint =
                actionHint,
            force = false,
        )
    }

    private fun removeSelectedPersistentCandidate() {
        val cfg =
            config ?: return
        val candidate =
            selectedCandidate
                ?: run {
                    setStatus(
                        "Сначала выбери сохранённый параметр.",
                    )
                    return
                }
        val anchor =
            candidate.anchor
                ?: run {
                    setStatus(
                        "У выбранного параметра нет сохранённого pointer-chain.",
                    )
                    return
                }
        if (
            !candidate.persistent ||
            candidate
                .requiresConfirmation
        ) {
            setStatus(
                "Этот кандидат ещё не сохранён для текущей версии игры.",
            )
            return
        }

        executor.execute {
            val result =
                runCatching {
                    val identity =
                        synchronized(
                            profileLock,
                        ) {
                            artifactIdentity
                                ?: profileStore
                                    .computeIdentity(
                                        packageName =
                                            cfg.packageName,
                                        cancellation =
                                            AtomicCancellationSignal(),
                                    )
                                    .also {
                                        artifactIdentity =
                                            it
                                    }
                        }
                    synchronized(
                        profileLock,
                    ) {
                        profileStore
                            .removeCandidate(
                                identity =
                                    identity,
                                anchor = anchor,
                            )
                    }
                }
            main.post {
                result.onSuccess {
                    removed ->
                    if (removed) {
                        learnedCandidates =
                            learnedCandidates
                                .filterNot {
                                    it.anchor ==
                                        anchor
                                }
                        selectedCandidate =
                            null
                        editorTitle?.text =
                            "Кандидат не выбран"
                        editorValue?.setText(
                            "",
                        )
                        rebuildLearnedList()
                        setStatus(
                            "Сохранённый параметр удалён из профиля этой версии.",
                        )
                    } else {
                        setStatus(
                            "Сохранённый параметр уже отсутствует.",
                        )
                    }
                }.onFailure {
                    failure ->
                    setStatus(
                        "Не удалось удалить сохранённый параметр: " +
                            (
                                failure.message
                                    ?: failure
                                        .javaClass
                                        .simpleName
                                ),
                    )
                }
            }
        }
    }

    private fun persistSelectedCandidate() {
        val candidate =
            selectedCandidate
                ?: run {
                    setStatus(
                        "Сначала выбери найденный параметр.",
                    )
                    return
                }
        stabilizeEditableCandidate(
            candidate =
                candidate,
            source =
                candidate.source
                    ?: LearnedCandidateSource
                        .MANUAL,
            actionHint =
                candidate.actionHint,
            force = true,
        )
    }

    private fun stabilizeEditableCandidate(
        candidate: EditableRuntimeCandidate,
        source: LearnedCandidateSource,
        actionHint: BehavioralActionHint?,
        force: Boolean,
    ) {
        val cfg =
            config ?: return
        if (
            !stabilizationBusy
                .compareAndSet(
                    false,
                    true,
                )
        ) {
            if (force) {
                setStatus(
                    "Pointer-chain уже ищется для другой находки. Повтори закрепление через несколько секунд.",
                )
            }
            return
        }
        val key =
            "persist:" +
                candidate.id
        if (
            !force &&
            !stabilizationAttempted
                .add(key)
        ) {
            stabilizationBusy.set(
                false,
            )
            return
        }
        setStatus(
            "Ищем устойчивый pointer-chain для «" +
                candidate.title +
                "»…",
        )
        executor.execute {
            val result =
                runCatching {
                    val originalTarget =
                        resolveCandidateAddress(
                            candidate =
                                candidate,
                            cfg = cfg,
                        )
                    val chain =
                        RootRuntimePointerChainCoordinator
                            .discover(
                                packageName =
                                    cfg.packageName,
                                pid = cfg.pid,
                                targetAddress =
                                    originalTarget,
                                cancellation =
                                    AtomicCancellationSignal(),
                                maxScanBytesPerDepth =
                                    if (force) {
                                        96L *
                                            1024L *
                                            1024L
                                    } else {
                                        40L *
                                            1024L *
                                            1024L
                                    },
                            )
                    val anchor =
                        chain.stableAnchor
                            ?: error(
                                "Стабильный module-root pointer-chain пока не найден.",
                            )
                    val identity =
                        synchronized(
                            profileLock,
                        ) {
                            artifactIdentity
                                ?: profileStore
                                    .computeIdentity(
                                        packageName =
                                            cfg.packageName,
                                        cancellation =
                                            AtomicCancellationSignal(),
                                    )
                                    .also {
                                        artifactIdentity =
                                            it
                                    }
                        }
                    val learned =
                        LearnedRuntimeCandidate(
                            id =
                                candidate.id,
                            title =
                                candidate.title,
                            valueType =
                                candidate.valueType,
                            confidence =
                                (
                                    candidate
                                        .confidence
                                        ?: if (
                                            force
                                        ) {
                                            90
                                        } else {
                                            75
                                        }
                                    ).coerceIn(
                                    1,
                                    99,
                                ),
                            source =
                                source,
                            actionHint =
                                actionHint,
                            lastKnownValue =
                                candidate.value,
                            anchor =
                                anchor,
                            updatedAtEpochMs =
                                System
                                    .currentTimeMillis(),
                        )
                    synchronized(
                        profileLock,
                    ) {
                        profileStore
                            .saveCandidate(
                                identity =
                                    identity,
                                candidate =
                                    learned,
                            )
                    }
                    val resolved =
                        RootRuntimePointerChainCoordinator
                            .resolve(
                                packageName =
                                    cfg.packageName,
                                pid = cfg.pid,
                                anchor =
                                    anchor,
                                cancellation =
                                    AtomicCancellationSignal(),
                            )
                    require(
                        resolved.targetAddress ==
                            originalTarget
                    ) {
                        "Pointer-chain did not resolve back to the confirmed runtime target."
                    }
                    candidate.copy(
                        address =
                            resolved
                                .targetAddress,
                        subtitle =
                            "Сохранено · " +
                                learned.confidence +
                                "% · " +
                                anchor
                                    .moduleIdentity,
                        confidence =
                            learned.confidence,
                        source =
                            source,
                        actionHint =
                            actionHint,
                        anchor =
                            anchor,
                        persistent =
                            true,
                        requiresConfirmation =
                            false,
                    )
                }
            main.post {
                stabilizationBusy.set(
                    false,
                )
                result.onSuccess {
                    saved ->
                    learnedCandidates =
                        (
                            learnedCandidates
                                .filterNot {
                                    it.anchor !=
                                        null &&
                                        saved.anchor !=
                                        null &&
                                        it.anchor ==
                                        saved.anchor
                                } +
                                saved
                            )
                            .sortedByDescending {
                                it.confidence
                                    ?: 0
                            }
                    rebuildLearnedList()
                    Toast.makeText(
                        this,
                        "ModKit: мод закреплён и будет восстановлен при следующем запуске.",
                        Toast.LENGTH_LONG,
                    ).show()
                    setStatus(
                        "Pointer-chain сохранён: " +
                            (
                                saved.anchor
                                    ?.moduleIdentity
                                    ?: "module"
                                ) +
                            ".",
                    )
                }.onFailure {
                    failure ->
                    if (force) {
                        setStatus(
                            "Не удалось закрепить параметр: " +
                                (
                                    failure.message
                                        ?: failure
                                            .javaClass
                                            .simpleName
                                    ),
                        )
                    }
                }
            }
        }
    }

    private fun resolveCandidateAddress(
        candidate: EditableRuntimeCandidate,
        cfg: ProcessOverlayConfig,
    ): Long {
        val anchor =
            candidate.anchor
                ?: return candidate.address
        return RootRuntimePointerChainCoordinator
            .resolve(
                packageName =
                    cfg.packageName,
                pid = cfg.pid,
                anchor = anchor,
                cancellation =
                    AtomicCancellationSignal(),
            ).targetAddress
    }

    private fun readRuntimeValue(
        cfg: ProcessOverlayConfig,
        address: Long,
        type: RuntimeValueType,
    ): String {
        val bytes =
            RootProcMemRuntimeMemoryReader(
                pid = cfg.pid,
            ).read(
                address = address,
                size = type.byteWidth,
                cancellation =
                    AtomicCancellationSignal(),
            ) ?: error(
                "Resolved learned value is unreadable.",
            )
        require(
            bytes.size ==
                type.byteWidth,
        ) {
            "Resolved learned value read is truncated."
        }
        return type.display(
            type.readBits(
                bytes,
                0,
            ),
        )
    }

    private fun manualScanByteLimit():
        Long? =
        if (manualFullScan) {
            null
        } else {
            64L *
                1024L *
                1024L
        }

    private fun trainingButton(
        title: String,
        hint: BehavioralActionHint,
    ): Button =
        Button(this).apply {
            text = title
            setOnClickListener {
                startTraining(
                    hint,
                )
            }
        }

    private fun refineButton(
        title: String,
        refinement:
            RuntimeValueRefinement,
    ): Button =
        Button(this).apply {
            text = title
            setOnClickListener {
                refineManual(
                    refinement,
                )
            }
        }

    private fun candidateRow(
        candidate:
            EditableRuntimeCandidate,
    ): View {
        val row =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    dp(6),
                    dp(5),
                    dp(6),
                    dp(5),
                )
                background =
                    GradientDrawable()
                        .apply {
                            setColor(
                                Color.argb(
                                    90,
                                    255,
                                    255,
                                    255,
                                ),
                            )
                            cornerRadius =
                                dp(8)
                                    .toFloat()
                        }
                setOnClickListener {
                    selectCandidate(
                        candidate,
                    )
                }
            }
        row.addView(
            TextView(this).apply {
                text =
                    candidate.title +
                        " · " +
                        candidate.value
                setTextColor(
                    Color.WHITE,
                )
                textSize = 12f
            },
        )
        row.addView(
            TextView(this).apply {
                text =
                    candidate.subtitle
                setTextColor(
                    Color.LTGRAY,
                )
                textSize = 10f
            },
        )
        return row
    }

    private fun sectionTitle(
        value: String,
    ): TextView =
        TextView(this).apply {
            text = value
            setTextColor(
                Color.WHITE,
            )
            textSize = 13f
            setPadding(
                0,
                dp(9),
                0,
                dp(4),
            )
        }

    private fun hintText(
        value: String,
    ): TextView =
        TextView(this).apply {
            text = value
            setTextColor(
                Color.GRAY,
            )
            textSize = 10f
        }

    private fun setStatus(
        value: String,
    ) {
        statusView?.text =
            value
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                value.take(
                    120,
                ),
            ),
        )
    }

    private fun setPanelVisible(
        visible: Boolean,
    ) {
        val panel =
            panel ?: return
        val params =
            windowParams
                ?: return
        val root =
            overlayView
                ?: return
        val wm =
            windowManager
                ?: return

        panel.visibility =
            if (visible) {
                View.VISIBLE
            } else {
                View.GONE
            }

        params.flags =
            if (visible) {
                WindowManager.LayoutParams
                    .FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams
                        .FLAG_NOT_TOUCH_MODAL
            } else {
                WindowManager.LayoutParams
                    .FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams
                        .FLAG_LAYOUT_IN_SCREEN
            }
        runCatching {
            wm.updateViewLayout(
                root,
                params,
            )
        }
        if (!visible) {
            val imm =
                getSystemService(
                    INPUT_METHOD_SERVICE,
                ) as InputMethodManager
            imm.hideSoftInputFromWindow(
                root.windowToken,
                0,
            )
        }
    }

    private fun installDrag(
        handle: View,
        root: View,
        params: WindowManager.LayoutParams,
        windowManager: WindowManager,
    ) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        handle.setOnTouchListener {
                _,
                event,
            ->
            when (
                event.actionMasked
            ) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX =
                        event.rawX
                    downRawY =
                        event.rawY
                    startX =
                        params.x
                    startY =
                        params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx =
                        event.rawX -
                            downRawX
                    val dy =
                        event.rawY -
                            downRawY
                    if (
                        kotlin.math.abs(
                            dx,
                        ) >
                        dp(4) ||
                        kotlin.math.abs(
                            dy,
                        ) >
                        dp(4)
                    ) {
                        moved = true
                    }
                    params.x =
                        startX +
                            dx.toInt()
                    params.y =
                        startY +
                            dy.toInt()
                    runCatching {
                        windowManager
                            .updateViewLayout(
                                root,
                                params,
                            )
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        handle.performClick()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun removeOverlay() {
        val view =
            overlayView
        val wm =
            windowManager
        overlayView = null
        panel = null
        floating = null
        if (
            view != null &&
            wm != null
        ) {
            runCatching {
                wm.removeView(
                    view,
                )
            }
        }
    }

    private fun createNotificationChannels() {
        val manager =
            getSystemService(
                NOTIFICATION_SERVICE,
            ) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "ModKit Live",
                NotificationManager
                    .IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(
        text: String,
    ): Notification =
        Notification.Builder(
            this,
            CHANNEL_ID,
        )
            .setContentTitle(
                "ModKit Live",
            )
            .setContentText(
                text,
            )
            .setSmallIcon(
                R.drawable
                    .ic_modkit_notification,
            )
            .setOngoing(true)
            .build()

    private fun matchWidth():
        LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams
                .MATCH_PARENT,
            LinearLayout.LayoutParams
                .WRAP_CONTENT,
        )

    private fun weighted():
        LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams
                .WRAP_CONTENT,
            1f,
        )

    private fun dp(
        value: Int,
    ): Int =
        (
            value *
                resources.displayMetrics
                    .density
            ).toInt()

    private data class TrainingAggregate(
        val candidate:
            BehavioralRuntimeCandidate,
        val seenRounds: Int,
    )

    private data class EditableRuntimeCandidate(
        val id: String,
        val title: String,
        val address: Long,
        val valueType: RuntimeValueType,
        val value: String,
        val subtitle: String,
        val confidence: Int? = null,
        val source: LearnedCandidateSource? = null,
        val actionHint: BehavioralActionHint? = null,
        val anchor: StableRuntimePointerAnchor? = null,
        val persistent: Boolean = false,
        val requiresConfirmation: Boolean = false,
    )

    companion object {
        private const val CHANNEL_ID =
            "modkit_live_overlay"
        private const val NOTIFICATION_ID =
            7210
    }
}
