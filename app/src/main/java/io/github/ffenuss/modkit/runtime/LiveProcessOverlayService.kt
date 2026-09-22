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
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.data.InstalledAppRepository
import io.github.ffenuss.modkit.patch.RootModDiscoveryCoordinator
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

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
    private val codeTraceBusy =
        AtomicBoolean(false)
    private val codePatchBusy =
        AtomicBoolean(false)
    private val staticEnrichmentBusy =
        AtomicBoolean(false)
    private var staticEnrichmentAttempted =
        false
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
    private var pageContainer:
        LinearLayout? = null
    private var currentPage =
        OverlayPage.HOME
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
    private var codeAccessList:
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
    private var renameValue:
        EditText? = null
    private var freezeButton:
        Button? = null
    private var codePatchButton:
        Button? = null

    private var autoSession:
        RootBehavioralScanSession? = null
    private var autoTask:
        ScheduledFuture<*>? = null
    private var processWatchTask:
        ScheduledFuture<*>? = null
    private var awaitingReattach =
        false
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
    private val autoCodeTraceAttempted =
        linkedSetOf<String>()
    private val behavioralCodeSites =
        mutableMapOf<
            String,
            List<RootCodeAccessSite>
        >()

    private var manualType =
        RuntimeValueType.INT32
    private var manualAutoType =
        true
    private var manualUnknownAuto =
        false
    private var manualFullScan =
        false
    private var manualScan:
        RootRuntimeValueScanResult? = null
    private var manualBaseline:
        RootRuntimeUnknownBaseline? = null

    private var selectedCandidate:
        EditableRuntimeCandidate? = null
    private var codeAccessSites =
        emptyList<RootCodeAccessSite>()
    private var selectedCodeSite:
        RootCodeAccessSite? = null
    private var activeCodePatch:
        ActiveCodePatch? = null
    private var freezeTask:
        ScheduledFuture<*>? = null
    private var freezeTarget:
        EditableRuntimeCandidate? = null
    private var freezeValue:
        String? = null

    private enum class OverlayPage {
        HOME,
        AUTO,
        TRAINING,
        MANUAL,
        MODS,
        CANDIDATE,
        EXPERT,
    }

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

        stopProcessWatch()
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
        scheduleProcessWatch()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopProcessWatch()
        rollbackActiveCodePatchBestEffort()
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
            val opening =
                builtPanel.visibility !=
                    View.VISIBLE
            setPanelVisible(
                opening,
            )
            if (
                opening &&
                trainingSession != null
            ) {
                finishTraining()
            } else if (
                opening &&
                manualUnknownAuto &&
                (
                    manualBaseline != null ||
                        manualScan != null
                    )
            ) {
                advanceManualUnknownAuto()
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
                                    246,
                                    24,
                                    24,
                                    29,
                                ),
                            )
                            cornerRadius =
                                dp(14)
                                    .toFloat()
                        }
            }

        val header =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }
        header.addView(
            TextView(this).apply {
                text =
                    "ModKit · " +
                        config.label
                setTextColor(
                    Color.WHITE,
                )
                textSize = 17f
            },
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams
                    .WRAP_CONTENT,
                1f,
            ),
        )
        header.addView(
            Button(this).apply {
                text = "—"
                minWidth = dp(48)
                minimumWidth = dp(48)
                setOnClickListener {
                    setPanelVisible(
                        false,
                    )
                }
            },
            LinearLayout.LayoutParams(
                dp(54),
                LinearLayout.LayoutParams
                    .WRAP_CONTENT,
            ),
        )
        body.addView(
            header,
            matchWidth(),
        )

        statusView =
            TextView(this).apply {
                text =
                    "Подключено · PID " +
                        config.pid
                setTextColor(
                    Color.LTGRAY,
                )
                textSize = 11f
                setPadding(
                    0,
                    dp(4),
                    0,
                    dp(6),
                )
            }
        body.addView(
            statusView,
            matchWidth(),
        )

        val content =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }
        pageContainer =
            content

        body.addView(
            ScrollView(this).apply {
                isFillViewport =
                    true
                addView(
                    content,
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
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams
                    .MATCH_PARENT,
                0,
                1f,
            ),
        )

        currentPage =
            OverlayPage.HOME
        renderCurrentPage()
        return body
    }

    private fun navigate(
        page: OverlayPage,
    ) {
        currentPage = page
        renderCurrentPage()
    }

    private fun renderCurrentPage() {
        val container =
            pageContainer
                ?: return
        container.removeAllViews()
        learnedList = null
        behavioralList = null
        manualList = null
        codeAccessList = null
        manualCount = null
        autoButton = null
        manualTypeButton = null
        manualQuery = null
        editorTitle = null
        editorValue = null
        renameValue = null
        freezeButton = null
        codePatchButton = null

        when (currentPage) {
            OverlayPage.HOME ->
                renderHomePage(
                    container,
                )
            OverlayPage.AUTO ->
                renderAutoPage(
                    container,
                )
            OverlayPage.TRAINING ->
                renderTrainingPage(
                    container,
                )
            OverlayPage.MANUAL ->
                renderManualPage(
                    container,
                )
            OverlayPage.MODS ->
                renderModsPage(
                    container,
                )
            OverlayPage.CANDIDATE ->
                renderCandidatePage(
                    container,
                )
            OverlayPage.EXPERT ->
                renderExpertPage(
                    container,
                )
        }
    }

    private fun renderHomePage(
        container: LinearLayout,
    ) {
        container.addView(
            pageTitle(
                "Что хочешь сделать?",
                "Основные действия вынесены отдельно. Технические параметры спрятаны в «Эксперт».",
                showBack = false,
            ),
        )

        container.addView(
            pageButton(
                "🔎  Автопоиск модов",
                "Играй как обычно и повторяй действия несколько раз. ModKit покажет только устойчивые кандидаты.",
            ) {
                navigate(
                    OverlayPage.AUTO,
                )
            },
        )
        container.addView(
            pageButton(
                "🎯  Обучить действие",
                "Скажи ModKit, что именно собираешься делать: получать урон, атаковать, двигаться и т. д.",
            ) {
                navigate(
                    OverlayPage.TRAINING,
                )
            },
        )
        container.addView(
            pageButton(
                "⌨  Ручной поиск",
                "Простой сценарий: было число → найти → изменить в игре → уточнить.",
            ) {
                manualAutoType =
                    true
                navigate(
                    OverlayPage.MANUAL,
                )
            },
        )
        container.addView(
            pageButton(
                "★  Мои моды · " +
                    learnedCandidates.size,
                "Сохранённые и восстановленные привязки этой игры.",
            ) {
                navigate(
                    OverlayPage.MODS,
                )
            },
        )

        if (
            autoSession != null
        ) {
            container.addView(
                hintText(
                    "Автоскан сейчас работает в фоне.",
                ),
            )
        }

        container.addView(
            Button(this).apply {
                text =
                    "Экспертные инструменты"
                setOnClickListener {
                    navigate(
                        OverlayPage.EXPERT,
                    )
                }
            },
            matchWidth(),
        )
        container.addView(
            Button(this).apply {
                text =
                    "Закрыть MK и остановить сканеры"
                setOnClickListener {
                    stopSelf()
                }
            },
            matchWidth(),
        )
    }

    private fun renderAutoPage(
        container: LinearLayout,
    ) {
        container.addView(
            pageTitle(
                "Автопоиск модов",
                "Нажми старт, сверни MK и несколько раз повтори интересующие действия. Необязательно делать только одно действие.",
            ),
        )

        val auto =
            Button(this).apply {
                text =
                    if (
                        autoSession ==
                        null
                    ) {
                        "▶ Начать скан"
                    } else {
                        "■ Остановить скан"
                    }
                setOnClickListener {
                    if (
                        autoSession ==
                        null
                    ) {
                        startAutoScan()
                        setPanelVisible(
                            false,
                        )
                    } else {
                        stopAutoScan(
                            userRequested =
                                true,
                        )
                        renderCurrentPage()
                    }
                }
            }
        autoButton = auto
        container.addView(
            auto,
            matchWidth(),
        )

        container.addView(
            hintText(
                "ModKit скрывает значения, которые выглядят как фоновые счётчики, указатели, случайные битовые шаблоны или меняются без устойчивой связи с действиями.",
            ),
        )

        container.addView(
            sectionTitle(
                "Подтверждаемые находки",
            ),
        )
        behavioralList =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }
        container.addView(
            requireNotNull(
                behavioralList,
            ),
            matchWidth(),
        )
        rebuildBehavioralList()
    }

    private fun renderTrainingPage(
        container: LinearLayout,
    ) {
        container.addView(
            pageTitle(
                "Обучить действие",
                "Выбери действие. MK свернётся: повтори действие 3–5 раз и снова нажми MK. Повторный раунд того же действия сужает список.",
            ),
        )

        val actions =
            listOf(
                "❤️ Здоровье / получаю урон" to
                    BehavioralActionHint
                        .DAMAGE_TAKEN,
                "⚔ Урон моей атаки" to
                    BehavioralActionHint
                        .ATTACK,
                "🏃 Движение / скорость" to
                    BehavioralActionHint
                        .MOVEMENT,
                "⚡ Выносливость" to
                    BehavioralActionHint
                        .STAMINA,
                "⏱ Cooldown / перезарядка" to
                    BehavioralActionHint
                        .COOLDOWN,
                "💰 Валюта / ресурс" to
                    BehavioralActionHint
                        .RESOURCE_CHANGE,
                "🎒 Предмет / количество" to
                    BehavioralActionHint
                        .ITEM_CHANGE,
                "🎯 Другое действие" to
                    BehavioralActionHint
                        .OTHER,
            )
        actions.forEach {
            (title, hint) ->
            container.addView(
                Button(this).apply {
                    text = title
                    setOnClickListener {
                        startTraining(
                            hint,
                        )
                    }
                },
                matchWidth(),
            )
        }

        if (
            behavioralSource ==
                LearnedCandidateSource
                    .TRAINING &&
            behavioralCandidates
                .isNotEmpty()
        ) {
            container.addView(
                sectionTitle(
                    "Результат обучения",
                ),
            )
            behavioralList =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.VERTICAL
                }
            container.addView(
                requireNotNull(
                    behavioralList,
                ),
                matchWidth(),
            )
            rebuildBehavioralList()
        }
    }

    private fun renderManualPage(
        container: LinearLayout,
    ) {
        manualAutoType = true
        container.addView(
            pageTitle(
                "Ручной поиск",
                "Тип данных выбирается автоматически. Для обычного количества предметов вроде 28 сначала ищется Int32, а не Double.",
            ),
        )

        if (
            manualBaseline !=
                null &&
            manualUnknownAuto
        ) {
            container.addView(
                hintText(
                    "Снимок памяти готов. Вернись в игру, измени интересующий параметр, затем снова открой MK — ModKit сам оставит изменившиеся значения.",
                ),
            )
            container.addView(
                Button(this).apply {
                    text =
                        "Вернуться в игру"
                    setOnClickListener {
                        setPanelVisible(
                            false,
                        )
                    }
                },
                matchWidth(),
            )
            container.addView(
                Button(this).apply {
                    text =
                        "Отменить неизвестный поиск"
                    setOnClickListener {
                        clearManualSearch()
                        manualUnknownAuto =
                            false
                        renderCurrentPage()
                    }
                },
                matchWidth(),
            )
            return
        }

        if (
            manualUnknownAuto &&
            manualScan != null
        ) {
            manualCount =
                TextView(this).apply {
                    setTextColor(
                        Color.WHITE,
                    )
                    textSize = 12f
                }
            container.addView(
                requireNotNull(
                    manualCount,
                ),
            )
            manualList =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.VERTICAL
                }
            container.addView(
                requireNotNull(
                    manualList,
                ),
                matchWidth(),
            )
            renderManualScan()

            val count =
                manualScan
                    ?.snapshot
                    ?.hits
                    ?.size
                    ?: 0
            container.addView(
                hintText(
                    if (count > 5) {
                        "Результатов ещё много. Вернись в игру и снова измени интересующий параметр; при следующем открытии MK список автоматически сузится."
                    } else {
                        "Осталось мало кандидатов. Нажми подходящий результат, чтобы проверить и изменить его."
                    },
                ),
            )
            if (count > 5) {
                container.addView(
                    Button(this).apply {
                        text =
                            "Продолжить наблюдение"
                        setOnClickListener {
                            setPanelVisible(
                                false,
                            )
                        }
                    },
                    matchWidth(),
                )
            }
            container.addView(
                Button(this).apply {
                    text =
                        "Начать заново"
                    setOnClickListener {
                        clearManualSearch()
                        manualUnknownAuto =
                            false
                        renderCurrentPage()
                    }
                },
                matchWidth(),
            )
            return
        }

        manualQuery =
            EditText(this).apply {
                hint =
                    if (
                        manualScan ==
                        null
                    ) {
                        "Какое значение сейчас? Например 28"
                    } else {
                        "Новое значение после изменения"
                    }
                setSingleLine(true)
                setTextColor(
                    Color.WHITE,
                )
                setHintTextColor(
                    Color.GRAY,
                )
            }
        container.addView(
            requireNotNull(
                manualQuery,
            ),
            matchWidth(),
        )

        if (
            manualScan == null
        ) {
            container.addView(
                Button(this).apply {
                    text =
                        "Найти значение"
                    setOnClickListener {
                        manualExact(
                            refine = false,
                        )
                    }
                },
                matchWidth(),
            )
            container.addView(
                Button(this).apply {
                    text =
                        "Не знаю число — наблюдать изменения"
                    setOnClickListener {
                        manualUnknownAuto =
                            true
                        manualType =
                            RuntimeValueType
                                .INT32
                        manualUnknownBaseline()
                    }
                },
                matchWidth(),
            )
            container.addView(
                hintText(
                    "Обычный поиск использует быстрый native scanner. Полный диапазон памяти, типы Float/Double вручную и технические фильтры находятся в «Эксперт».",
                ),
            )
            return
        }

        manualCount =
            TextView(this).apply {
                setTextColor(
                    Color.WHITE,
                )
                textSize = 12f
            }
        container.addView(
            requireNotNull(
                manualCount,
            ),
        )
        manualList =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }
        container.addView(
            requireNotNull(
                manualList,
            ),
            matchWidth(),
        )
        renderManualScan()

        val hitCount =
            manualScan
                ?.snapshot
                ?.hits
                ?.size
                ?: 0
        if (hitCount > 5) {
            container.addView(
                hintText(
                    "Теперь измени это число в игре, вернись сюда, введи новое значение и нажми «Уточнить». Повторяй, пока не останется несколько результатов.",
                ),
            )
            container.addView(
                Button(this).apply {
                    text = "Уточнить"
                    setOnClickListener {
                        manualExact(
                            refine = true,
                        )
                    }
                },
                matchWidth(),
            )
            container.addView(
                Button(this).apply {
                    text =
                        "Свернуть и изменить значение в игре"
                    setOnClickListener {
                        setPanelVisible(
                            false,
                        )
                    }
                },
                matchWidth(),
            )
        } else if (
            hitCount in 1..5
        ) {
            container.addView(
                hintText(
                    "Осталось мало результатов. Нажми подходящий результат — откроется изменение, Freeze, code trace и сохранение мода.",
                ),
            )
        } else {
            container.addView(
                hintText(
                    "Совпадений нет. Проверь число и начни поиск заново. Для редких типов можно открыть «Эксперт».",
                ),
            )
        }

        container.addView(
            Button(this).apply {
                text =
                    "Начать поиск заново"
                setOnClickListener {
                    clearManualSearch()
                    renderCurrentPage()
                }
            },
            matchWidth(),
        )
    }

    private fun renderModsPage(
        container: LinearLayout,
    ) {
        container.addView(
            pageTitle(
                "Мои моды",
                "Здесь только сохранённые привязки. Ошибочную привязку можно открыть, перепроверить, переименовать или удалить.",
            ),
        )
        learnedList =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }
        container.addView(
            requireNotNull(
                learnedList,
            ),
            matchWidth(),
        )
        rebuildLearnedList()
    }

    private fun renderCandidatePage(
        container: LinearLayout,
    ) {
        val candidate =
            selectedCandidate
        if (candidate == null) {
            navigate(
                OverlayPage.HOME,
            )
            return
        }

        container.addView(
            pageTitle(
                candidateDisplayTitle(
                    candidate,
                ),
                candidate.subtitle,
            ),
        )

        editorTitle =
            TextView(this).apply {
                text =
                    "Сейчас: " +
                        candidate.value +
                        " · " +
                        candidate.valueType
                            .title +
                        (
                            candidate.confidence
                                ?.let {
                                    " · уверенность " +
                                        it +
                                        "%"
                                }
                                ?: ""
                            )
                setTextColor(
                    Color.WHITE,
                )
                textSize = 13f
            }
        container.addView(
            requireNotNull(
                editorTitle,
            ),
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
                setText(
                    candidate.value,
                )
            }
        container.addView(
            requireNotNull(
                editorValue,
            ),
            matchWidth(),
        )

        container.addView(
            Button(this).apply {
                text =
                    "Изменить значение"
                setOnClickListener {
                    writeSelected()
                }
            },
            matchWidth(),
        )
        val freeze =
            Button(this).apply {
                text =
                    if (
                        freezeTask ==
                        null
                    ) {
                        "Заморозить значение"
                    } else {
                        "Остановить заморозку"
                    }
                setOnClickListener {
                    toggleFreeze()
                    renderCurrentPage()
                }
            }
        freezeButton = freeze
        container.addView(
            freeze,
            matchWidth(),
        )

        container.addView(
            sectionTitle(
                "Что изменяет это значение",
            ),
        )
        container.addView(
            Button(this).apply {
                text =
                    "Найти код, который меняет значение · 5 сек"
                setOnClickListener {
                    traceSelectedCodeAccess()
                }
            },
            matchWidth(),
        )
        codeAccessList =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }
        container.addView(
            requireNotNull(
                codeAccessList,
            ),
            matchWidth(),
        )
        rebuildCodeAccessList()

        val writer =
            Button(this).apply {
                text =
                    writerActionTitle(
                        candidate,
                    )
                setOnClickListener {
                    toggleSelectedWriterBlock()
                }
            }
        codePatchButton =
            writer
        container.addView(
            writer,
            matchWidth(),
        )

        container.addView(
            Button(this).apply {
                text =
                    if (
                        candidate.persistent
                    ) {
                        "Перепроверить и обновить привязку"
                    } else {
                        "Сохранить в «Мои моды»"
                    }
                setOnClickListener {
                    persistSelectedCandidate()
                }
            },
            matchWidth(),
        )

        if (candidate.persistent) {
            renameValue =
                EditText(this).apply {
                    hint =
                        "Новое имя мода"
                    setSingleLine(true)
                    setTextColor(
                        Color.WHITE,
                    )
                    setHintTextColor(
                        Color.GRAY,
                    )
                    setText(
                        candidate.title,
                    )
                }
            container.addView(
                requireNotNull(
                    renameValue,
                ),
                matchWidth(),
            )
            container.addView(
                Button(this).apply {
                    text =
                        "Переименовать мод"
                    setOnClickListener {
                        renameSelectedPersistentCandidate()
                    }
                },
                matchWidth(),
            )
            container.addView(
                Button(this).apply {
                    text =
                        "Удалить эту привязку"
                    setOnClickListener {
                        removeSelectedPersistentCandidate()
                    }
                },
                matchWidth(),
            )
        }

        container.addView(
            Button(this).apply {
                text =
                    "Технические детали"
                setOnClickListener {
                    navigate(
                        OverlayPage.EXPERT,
                    )
                }
            },
            matchWidth(),
        )
    }

    private fun renderExpertPage(
        container: LinearLayout,
    ) {
        manualAutoType = false
        container.addView(
            pageTitle(
                "Эксперт",
                "Ручной выбор типа, полный scan, диапазоны, fuzzy/group и фильтры неизвестного значения. Обычный пользователь сюда заходить не обязан.",
            ),
        )

        val top =
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
        top.addView(
            requireNotNull(
                manualTypeButton,
            ),
            LinearLayout.LayoutParams(
                dp(112),
                LinearLayout.LayoutParams
                    .WRAP_CONTENT,
            ),
        )
        manualQuery =
            EditText(this).apply {
                hint =
                    "100 · 10..20 · 1.0~0.1 · 10,20"
                setSingleLine(true)
                setTextColor(
                    Color.WHITE,
                )
                setHintTextColor(
                    Color.GRAY,
                )
            }
        top.addView(
            requireNotNull(
                manualQuery,
            ),
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams
                    .WRAP_CONTENT,
                1f,
            ),
        )
        container.addView(
            top,
            matchWidth(),
        )

        container.addView(
            Button(this).apply {
                text =
                    if (
                        manualFullScan
                    ) {
                        "Объём: полный"
                    } else {
                        "Объём: быстрый"
                    }
                setOnClickListener {
                    manualFullScan =
                        !manualFullScan
                    clearManualSearch()
                    renderCurrentPage()
                }
            },
            matchWidth(),
        )

        val exact =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }
        exact.addView(
            Button(this).apply {
                text = "Новый"
                setOnClickListener {
                    manualExact(
                        refine = false,
                    )
                }
            },
            weighted(),
        )
        exact.addView(
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
        exact.addView(
            Button(this).apply {
                text = "Неизвестно"
                setOnClickListener {
                    manualUnknownAuto =
                        false
                    manualUnknownBaseline()
                }
            },
            weighted(),
        )
        container.addView(
            exact,
            matchWidth(),
        )

        val filters =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }
        filters.addView(
            refineButton(
                "Изм.",
                RuntimeValueRefinement
                    .CHANGED,
            ),
            weighted(),
        )
        filters.addView(
            refineButton(
                "Не изм.",
                RuntimeValueRefinement
                    .UNCHANGED,
            ),
            weighted(),
        )
        filters.addView(
            refineButton(
                "↑",
                RuntimeValueRefinement
                    .INCREASED,
            ),
            weighted(),
        )
        filters.addView(
            refineButton(
                "↓",
                RuntimeValueRefinement
                    .DECREASED,
            ),
            weighted(),
        )
        container.addView(
            filters,
            matchWidth(),
        )

        manualCount =
            TextView(this).apply {
                setTextColor(
                    Color.LTGRAY,
                )
                textSize = 11f
            }
        container.addView(
            requireNotNull(
                manualCount,
            ),
        )
        manualList =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }
        container.addView(
            requireNotNull(
                manualList,
            ),
            matchWidth(),
        )
        if (manualScan != null) {
            renderManualScan()
        }

        selectedCandidate
            ?.let {
                candidate ->
                container.addView(
                    sectionTitle(
                        "Выбрано: " +
                            candidate.title,
                    ),
                )
                container.addView(
                    hintText(
                        "0x" +
                            candidate.address
                                .toString(
                                    16,
                                ) +
                            " · " +
                            candidate.valueType
                                .title +
                            " · " +
                            candidate.value,
                    ),
                )
            }
    }

    private fun pageTitle(
        title: String,
        subtitle: String,
        showBack: Boolean = true,
    ): View {
        val group =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    0,
                    dp(4),
                    0,
                    dp(8),
                )
            }
        if (showBack) {
            group.addView(
                Button(this).apply {
                    text = "← Назад"
                    setOnClickListener {
                        navigate(
                            OverlayPage.HOME,
                        )
                    }
                },
                matchWidth(),
            )
        }
        group.addView(
            TextView(this).apply {
                text = title
                setTextColor(
                    Color.WHITE,
                )
                textSize = 18f
            },
        )
        group.addView(
            TextView(this).apply {
                text = subtitle
                setTextColor(
                    Color.LTGRAY,
                )
                textSize = 11f
            },
        )
        return group
    }

    private fun pageButton(
        title: String,
        subtitle: String,
        onClick: () -> Unit,
    ): View {
        val group =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    0,
                    dp(4),
                    0,
                    dp(4),
                )
            }
        group.addView(
            Button(this).apply {
                text = title
                setOnClickListener {
                    onClick()
                }
            },
            matchWidth(),
        )
        group.addView(
            hintText(
                subtitle,
            ),
        )
        return group
    }

    private fun candidateDisplayTitle(
        candidate: EditableRuntimeCandidate,
    ): String =
        when {
            candidate.actionHint ==
                BehavioralActionHint
                    .DAMAGE_TAKEN ->
                "❤️ " +
                    candidate.title
            candidate.actionHint ==
                BehavioralActionHint
                    .ATTACK ->
                "⚔ " +
                    candidate.title
            candidate.actionHint ==
                BehavioralActionHint
                    .MOVEMENT ->
                "🏃 " +
                    candidate.title
            candidate.actionHint ==
                BehavioralActionHint
                    .STAMINA ->
                "⚡ " +
                    candidate.title
            candidate.actionHint ==
                BehavioralActionHint
                    .COOLDOWN ->
                "⏱ " +
                    candidate.title
            candidate.actionHint ==
                BehavioralActionHint
                    .RESOURCE_CHANGE ->
                "💰 " +
                    candidate.title
            candidate.actionHint ==
                BehavioralActionHint
                    .ITEM_CHANGE ->
                "🎒 " +
                    candidate.title
            else ->
                candidate.title
        }

    private fun writerActionTitle(
        candidate:
            EditableRuntimeCandidate,
    ): String {
        val active =
            activeCodePatch
        val enabled =
            active != null &&
                active.candidateId ==
                candidate.id
        val state =
            if (enabled) {
                "ON"
            } else {
                "OFF"
            }
        return when (
            candidate.actionHint
        ) {
            BehavioralActionHint
                .DAMAGE_TAKEN ->
                "Не получать урон: " +
                    state
            else ->
                "Блокировать изменение: " +
                    state
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

        if (
            aggregates.isEmpty() &&
            sample.visibleCandidates
                .isNotEmpty()
        ) {
            trainingRounds[
                hint
            ] = 1
            sample.visibleCandidates
                .forEach {
                    candidate ->
                    aggregates[
                        candidate.id
                    ] =
                        TrainingAggregate(
                            candidate =
                                candidate,
                            seenRounds = 1,
                        )
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

    private fun collapseAutoActivityGroups(
        candidates:
            List<BehavioralRuntimeCandidate>,
    ): List<BehavioralRuntimeCandidate> {
        val selected =
            mutableListOf<
                BehavioralRuntimeCandidate
            >()
        candidates
            .sortedWith(
                compareByDescending<
                    BehavioralRuntimeCandidate
                > {
                    it.confidence
                }.thenByDescending {
                    it.changeCount
                },
            )
            .forEach {
                candidate ->
                val duplicatePattern =
                    selected.any {
                        existing ->
                        Integer.bitCount(
                            existing
                                .recentChangeMask xor
                                candidate
                                    .recentChangeMask,
                        ) <= 2 &&
                            existing.valueType
                                .byteWidth ==
                            candidate.valueType
                                .byteWidth
                    }
                if (!duplicatePattern) {
                    selected +=
                        candidate
                }
            }
        return selected.take(5)
    }

    private fun applyBehavioralSample(
        sample: BehavioralScanSample,
        source: LearnedCandidateSource,
        actionHint: BehavioralActionHint?,
    ) {
        behavioralCandidates =
            if (
                source ==
                LearnedCandidateSource.AUTO
            ) {
                collapseAutoActivityGroups(
                    sample.visibleCandidates,
                )
            } else {
                sample.visibleCandidates
            }
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
            if (
                source ==
                LearnedCandidateSource.TRAINING
            ) {
                "Обучение: найдено " +
                    sample.visibleCandidates
                        .size +
                    " устойчивых кандидатов."
            } else {
                "Автоскан: подтверждаемых " +
                    behavioralCandidates
                        .size +
                    " · скрыто шумных " +
                    sample.hiddenAsNoise +
                    "."
            },
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
                LearnedCandidateSource.AUTO &&
            panel?.visibility !=
                View.VISIBLE
        ) {
            sample.visibleCandidates
                .firstOrNull {
                    candidate ->
                    candidate.confidence >=
                        88 &&
                        candidate.changeCount >=
                        3 &&
                        candidate.id !in
                        autoCodeTraceAttempted
                }
                ?.let {
                    candidate ->
                    startAutomaticCodeTrace(
                        candidate,
                    )
                }
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
            .take(5)
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
                                    "% · повторений изменения " +
                                    candidate.changeCount +
                                    " · " +
                                    candidate
                                        .valueType
                                        .title +
                                    (
                                        behavioralCodeSites[
                                            candidate.id
                                        ]
                                            ?.takeIf {
                                                it.isNotEmpty()
                                            }
                                            ?.let {
                                                " · code " +
                                                    it.size
                                            }
                                            ?: ""
                                        ),
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
                "Введи значение для поиска.",
            )
            return
        }

        setStatus(
            if (refine) {
                "Уточняем результаты по новому значению " +
                    query +
                    "…"
            } else if (
                manualAutoType
            ) {
                "Быстрый поиск " +
                    query +
                    " · тип определится автоматически…"
            } else {
                "Поиск " +
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
                        manualType to
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
                    } else if (
                        manualAutoType
                    ) {
                        fastAutoExactScan(
                            cfg = cfg,
                            query = query,
                        )
                    } else {
                        manualType to
                            expertInitialScan(
                                cfg = cfg,
                                query = query,
                            )
                    }
                }

            main.post {
                result.onSuccess {
                    (resolvedType, scan) ->
                    manualType =
                        resolvedType
                    manualScan =
                        scan
                    manualBaseline?.let {
                        RootRuntimeUnknownValueCoordinator
                            .deleteBaseline(
                                it,
                            )
                    }
                    manualBaseline = null
                    manualUnknownAuto =
                        false
                    setStatus(
                        "Найдено: " +
                            scan.snapshot
                                .hits
                                .size +
                            " · " +
                            resolvedType.title +
                            " · просмотрено " +
                            humanBytes(
                                scan.snapshot
                                    .scannedBytes,
                            ),
                    )
                    if (
                        currentPage ==
                        OverlayPage.MANUAL ||
                        currentPage ==
                        OverlayPage.EXPERT
                    ) {
                        renderCurrentPage()
                    }
                }.onFailure {
                    failure ->
                    setStatus(
                        "Поиск не выполнен: " +
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

    private fun fastAutoExactScan(
        cfg: ProcessOverlayConfig,
        query: String,
    ): Pair<
        RuntimeValueType,
        RootRuntimeValueScanResult
    > {
        val types =
            autoManualTypes(
                query,
            )
        var last:
            RootRuntimeValueScanResult? =
            null
        var lastType =
            types.first()

        for (type in types) {
            val scan =
                runCatching {
                    RootFastValueScanCoordinator
                        .scanExact(
                            context =
                                applicationContext,
                            packageName =
                                cfg.packageName,
                            pid = cfg.pid,
                            valueType =
                                type,
                            query = query,
                            cancellation =
                                AtomicCancellationSignal(),
                            maxScanBytes =
                                RootFastValueScanCoordinator
                                    .QUICK_MAX_BYTES,
                        )
                }.getOrElse {
                    RootRuntimeValueScanCoordinator
                        .scanExact(
                            packageName =
                                cfg.packageName,
                            valueType =
                                type,
                            query = query,
                            cancellation =
                                AtomicCancellationSignal(),
                            maxScanBytes =
                                RootFastValueScanCoordinator
                                    .QUICK_MAX_BYTES,
                            expectedPid =
                                cfg.pid,
                        )
                }
            last =
                scan
            lastType =
                type
            if (
                scan.snapshot
                    .hits
                    .isNotEmpty()
            ) {
                return type to
                    scan
            }
        }
        return lastType to
            requireNotNull(last)
    }

    private fun autoManualTypes(
        query: String,
    ): List<RuntimeValueType> {
        val normalized =
            query.trim()
        val integer =
            normalized.toLongOrNull()
        if (integer != null) {
            return if (
                integer in
                Int.MIN_VALUE
                    .toLong()..
                    Int.MAX_VALUE
                        .toLong()
            ) {
                listOf(
                    RuntimeValueType
                        .INT32,
                    RuntimeValueType
                        .INT64,
                )
            } else {
                listOf(
                    RuntimeValueType
                        .INT64,
                )
            }
        }
        val decimal =
            normalized
                .toDoubleOrNull()
                ?: error(
                    "Не удалось распознать число.",
                )
        require(
            decimal.isFinite()
        ) {
            "NaN/Infinity не поддерживаются."
        }
        return listOf(
            RuntimeValueType.FLOAT32,
            RuntimeValueType.FLOAT64,
        )
    }

    private fun expertInitialScan(
        cfg: ProcessOverlayConfig,
        query: String,
    ): RootRuntimeValueScanResult =
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
                    "Диапазон: минимум..максимум, например 90..110."
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
                    "Fuzzy: значение~допуск, например 1.0~0.05."
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
                runCatching {
                    RootFastValueScanCoordinator
                        .scanExact(
                            context =
                                applicationContext,
                            packageName =
                                cfg.packageName,
                            pid = cfg.pid,
                            valueType =
                                manualType,
                            query = query,
                            cancellation =
                                AtomicCancellationSignal(),
                            maxScanBytes =
                                manualScanByteLimit(),
                        )
                }.getOrElse {
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

    private fun humanBytes(
        bytes: Long,
    ): String =
        when {
            bytes >=
                1024L *
                    1024L *
                    1024L ->
                String.format(
                    java.util.Locale.US,
                    "%.1f GiB",
                    bytes.toDouble() /
                        (
                            1024.0 *
                                1024.0 *
                                1024.0
                            ),
                )
            bytes >=
                1024L *
                    1024L ->
                String.format(
                    java.util.Locale.US,
                    "%.1f MiB",
                    bytes.toDouble() /
                        (
                            1024.0 *
                                1024.0
                            ),
                )
            else ->
                (
                    bytes /
                        1024L
                    ).toString() +
                    " KiB"
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
            if (manualUnknownAuto) {
                "Запоминаем текущее состояние. После этого измени интересующий параметр в игре."
            } else {
                "Сохраняем baseline неизвестного " +
                    manualType.title +
                    "…"
            },
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
                        if (manualUnknownAuto) {
                            "Снимок готов. Измени параметр в игре и снова открой MK."
                        } else {
                            "Baseline готов. Измени значение в игре, затем выбери фильтр."
                        },
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

    private fun advanceManualUnknownAuto() {
        if (!manualUnknownAuto) {
            return
        }
        val baseline =
            manualBaseline
        val previous =
            manualScan
        if (
            baseline == null &&
            previous == null
        ) {
            return
        }
        setStatus(
            "Сравниваем изменения с предыдущим состоянием…",
        )
        executor.execute {
            val result =
                runCatching {
                    if (baseline != null) {
                        RootRuntimeUnknownValueCoordinator
                            .compareBaseline(
                                baseline =
                                    baseline,
                                refinement =
                                    RuntimeValueRefinement
                                        .CHANGED,
                                cancellation =
                                    AtomicCancellationSignal(),
                            )
                    } else {
                        RootRuntimeValueScanCoordinator
                            .refine(
                                previous =
                                    requireNotNull(
                                        previous,
                                    ),
                                refinement =
                                    RuntimeValueRefinement
                                        .CHANGED,
                                cancellation =
                                    AtomicCancellationSignal(),
                            )
                    }
                }
            main.post {
                result.onSuccess {
                    updated ->
                    val filtered =
                        updated.copy(
                            snapshot =
                                updated.snapshot
                                    .copy(
                                        hits =
                                            updated.snapshot
                                                .hits
                                                .filter {
                                                    unknownAutoUseful(
                                                        updated.snapshot
                                                            .valueType,
                                                        it.bits,
                                                    )
                                                }
                                                .take(
                                                    5_000,
                                                ),
                                    ),
                        )
                    manualScan =
                        filtered
                    manualBaseline?.let {
                        RootRuntimeUnknownValueCoordinator
                            .deleteBaseline(
                                it,
                            )
                    }
                    manualBaseline = null
                    currentPage =
                        OverlayPage.MANUAL
                    setStatus(
                        "Неизвестный параметр: осталось " +
                            filtered.snapshot
                                .hits
                                .size +
                            ". Повтори изменение ещё раз, если результатов много.",
                    )
                    renderCurrentPage()
                }.onFailure {
                    failure ->
                    setStatus(
                        "Автоматическое сравнение не выполнено: " +
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

    private fun unknownAutoUseful(
        type: RuntimeValueType,
        bits: Long,
    ): Boolean =
        when (type) {
            RuntimeValueType.INT32 -> {
                val integer =
                    bits.toInt()
                        .toLong()
                val float =
                    Float.fromBits(
                        bits.toInt(),
                    )
                val usefulInteger =
                    kotlin.math.abs(
                        integer,
                    ) <=
                        100_000_000L &&
                        !(
                            integer < 0L &&
                                kotlin.math.abs(
                                    integer,
                                ) >
                                1_000_000L
                            )
                val usefulFloat =
                    float.isFinite() &&
                        (
                            float == 0f ||
                                kotlin.math.abs(
                                    float,
                                ) in
                                1.0e-6f..1.0e6f
                            )
                usefulInteger ||
                    usefulFloat
            }

            RuntimeValueType.INT64 -> {
                val integer =
                    bits
                val double =
                    Double.fromBits(
                        bits,
                    )
                kotlin.math.abs(
                    integer.toDouble(),
                ) <=
                    1.0e11 ||
                    (
                        double.isFinite() &&
                            (
                                double == 0.0 ||
                                    kotlin.math.abs(
                                        double,
                                    ) in
                                    1.0e-9..1.0e9
                                )
                        )
            }

            RuntimeValueType.FLOAT32 ->
                Float.fromBits(
                    bits.toInt(),
                ).let {
                    it.isFinite() &&
                        kotlin.math.abs(
                            it,
                        ) <=
                        1.0e6f
                }

            RuntimeValueType.FLOAT64 ->
                Double.fromBits(
                    bits,
                ).let {
                    it.isFinite() &&
                        kotlin.math.abs(
                            it,
                        ) <=
                        1.0e9
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
                    if (
                        currentPage ==
                        OverlayPage.MANUAL ||
                        currentPage ==
                        OverlayPage.EXPERT
                    ) {
                        renderCurrentPage()
                    }
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
            "Найдено: " +
                hits.size +
                " · " +
                scan.snapshot
                    .valueType
                    .title +
                (
                    if (
                        scan.snapshot
                            .truncatedByHitLimit ||
                        scan.snapshot
                            .truncatedByByteLimit
                    ) {
                        " · быстрый проход"
                    } else {
                        ""
                    }
                    )

        val list =
            manualList
                ?: return
        list.removeAllViews()
        hits.take(
            if (
                currentPage ==
                OverlayPage.EXPERT
            ) {
                20
            } else {
                8
            },
        ).forEach {
            hit ->
            val interpreted =
                interpretManualHit(
                    scan.snapshot
                        .valueType,
                    hit,
                )
            list.addView(
                candidateRow(
                    EditableRuntimeCandidate(
                        id =
                            "manual:" +
                                interpreted.first
                                    .name +
                                ":" +
                                hit.address
                                    .toString(
                                        16,
                                    ),
                        title =
                            if (
                                manualUnknownAuto
                            ) {
                                "Возможный параметр"
                            } else {
                                "Найденное значение"
                            },
                        address =
                            hit.address,
                        valueType =
                            interpreted.first,
                        value =
                            interpreted.second,
                        subtitle =
                            if (
                                currentPage ==
                                OverlayPage.EXPERT
                            ) {
                                "0x" +
                                    hit.address
                                        .toString(
                                            16,
                                        ) +
                                    " · " +
                                    interpreted.first
                                        .title
                            } else {
                                interpreted.first
                                    .title +
                                    " · нажми, чтобы проверить"
                            },
                        source =
                            LearnedCandidateSource
                                .MANUAL,
                    ),
                ),
            )
        }
    }

    private fun interpretManualHit(
        scanType: RuntimeValueType,
        hit: RuntimeValueHit,
    ): Pair<
        RuntimeValueType,
        String
    > {
        if (
            !manualUnknownAuto
        ) {
            return scanType to
                hit.displayValue(
                    scanType,
                )
        }

        if (
            scanType ==
            RuntimeValueType.INT32
        ) {
            val integer =
                hit.bits.toInt()
            val float =
                Float.fromBits(
                    hit.bits.toInt(),
                )
            val integerLooksUseful =
                kotlin.math.abs(
                    integer.toLong(),
                ) <=
                    1_000_000L
            val floatLooksUseful =
                float.isFinite() &&
                    (
                        float == 0f ||
                            kotlin.math.abs(
                                float,
                            ) in
                            1.0e-5f..100_000f
                        )
            if (
                !integerLooksUseful &&
                floatLooksUseful
            ) {
                return RuntimeValueType
                    .FLOAT32 to
                    float.toString()
            }
        }

        if (
            scanType ==
            RuntimeValueType.INT64
        ) {
            val integer =
                hit.bits
            val double =
                Double.fromBits(
                    hit.bits,
                )
            val integerLooksUseful =
                kotlin.math.abs(
                    integer.toDouble(),
                ) <=
                    1.0e9
            val doubleLooksUseful =
                double.isFinite() &&
                    (
                        double == 0.0 ||
                            kotlin.math.abs(
                                double,
                            ) in
                            1.0e-8..1.0e8
                        )
            if (
                !integerLooksUseful &&
                doubleLooksUseful
            ) {
                return RuntimeValueType
                    .FLOAT64 to
                    double.toString()
            }
        }

        return scanType to
            hit.displayValue(
                scanType,
            )
    }

    private fun selectCandidate(
        candidate:
            EditableRuntimeCandidate,
    ) {
        val active =
            activeCodePatch
        if (
            active != null &&
            active.candidateId !=
            candidate.id
        ) {
            setStatus(
                "Сначала отключи Writer block у текущего параметра.",
            )
            return
        }
        selectedCandidate =
            candidate
        val capturedSites =
            behavioralCodeSites[
                candidate.id
            ].orEmpty()
        codeAccessSites =
            capturedSites
        selectedCodeSite =
            capturedSites
                .firstOrNull {
                    eligibleWriterSite(
                        it,
                    )
                }
        rebuildCodeAccessList()
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
        currentPage =
            OverlayPage.CANDIDATE
        renderCurrentPage()
        if (
            candidate.learnedCodeSites
                .isNotEmpty() &&
            !candidate
                .requiresConfirmation
        ) {
            resolvePersistedCodeSites(
                candidate,
            )
        }
    }

    private fun resolvePersistedCodeSites(
        candidate:
            EditableRuntimeCandidate,
    ) {
        val cfg =
            config ?: return
        executor.execute {
            val resolved =
                candidate
                    .learnedCodeSites
                    .mapNotNull {
                        site ->
                        runCatching {
                            RootRuntimeCodeToggleCoordinator
                                .resolveLearnedSite(
                                    packageName =
                                        cfg.packageName,
                                    pid = cfg.pid,
                                    site = site,
                                    cancellation =
                                        AtomicCancellationSignal(),
                                )
                        }.getOrNull()
                    }
            main.post {
                if (
                    selectedCandidate
                        ?.id !=
                    candidate.id
                ) {
                    return@post
                }
                codeAccessSites =
                    resolved
                selectedCodeSite =
                    resolved
                        .firstOrNull {
                            eligibleWriterSite(
                                it,
                            )
                        }
                rebuildCodeAccessList()
                if (
                    candidate.learnedCodeSites
                        .isNotEmpty() &&
                    resolved.isEmpty()
                ) {
                    setStatus(
                        "Сохранённые code-sites больше не подтверждаются; выполни новый code trace.",
                    )
                }
            }
        }
    }

    private fun startAutomaticCodeTrace(
        candidate:
            BehavioralRuntimeCandidate,
    ) {
        val cfg =
            config ?: return
        if (
            candidate.id in
            autoCodeTraceAttempted
        ) {
            return
        }
        if (
            !codeTraceBusy
                .compareAndSet(
                    false,
                    true,
                )
        ) {
            return
        }
        if (
            !autoCodeTraceAttempted
                .add(
                    candidate.id,
                )
        ) {
            codeTraceBusy.set(
                false,
            )
            return
        }

        val hadAutoSession =
            autoSession != null
        autoTask?.cancel(
            false,
        )
        autoTask = null

        executor.execute {
            val result =
                runCatching {
                    RootMemoryWatchCoordinator
                        .trace(
                            context =
                                applicationContext,
                            packageName =
                                cfg.packageName,
                            pid = cfg.pid,
                            targetAddress =
                                candidate.address,
                            width =
                                candidate
                                    .valueType
                                    .byteWidth,
                            cancellation =
                                AtomicCancellationSignal(),
                            durationMs =
                                3_500,
                        )
                }
            main.post {
                codeTraceBusy.set(
                    false,
                )
                result.onSuccess {
                    trace ->
                    if (
                        trace.sites
                            .isNotEmpty()
                    ) {
                        behavioralCodeSites[
                            candidate.id
                        ] =
                            trace.sites
                        rebuildBehavioralList()
                        val writers =
                            trace.sites
                                .count {
                                    it.accessKind ==
                                        RuntimeCodeAccessKind
                                            .WRITE
                                }
                        Toast.makeText(
                            this,
                            "ModKit: для «" +
                                candidate.title +
                                "» найдено code-sites " +
                                trace.sites.size +
                                " · writers " +
                                writers,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                if (
                    hadAutoSession &&
                    autoSession !=
                        null &&
                    autoTask == null
                ) {
                    scheduleAutoSamples()
                }
            }
        }
    }

    private fun traceSelectedCodeAccess() {
        val cfg =
            config ?: return
        if (activeCodePatch != null) {
            setStatus(
                "Отключи Writer block перед новым code trace.",
            )
            return
        }
        val candidate =
            selectedCandidate
                ?: run {
                    setStatus(
                        "Сначала выбери найденное значение.",
                    )
                    return
                }
        if (
            !codeTraceBusy
                .compareAndSet(
                    false,
                    true,
                )
        ) {
            setStatus(
                "Code trace уже выполняется.",
            )
            return
        }

        stopFreeze()
        if (autoSession != null) {
            stopAutoScan(
                userRequested =
                    false,
            )
        }
        setStatus(
            "Hardware watch активен 5 секунд. Сверни MK и выполни действие, которое должно читать/менять выбранное значение.",
        )
        setPanelVisible(
            false,
        )

        executor.execute {
            val result =
                runCatching {
                    val address =
                        resolveCandidateAddress(
                            candidate =
                                candidate,
                            cfg = cfg,
                        )
                    RootMemoryWatchCoordinator
                        .trace(
                            context =
                                applicationContext,
                            packageName =
                                cfg.packageName,
                            pid =
                                cfg.pid,
                            targetAddress =
                                address,
                            width =
                                candidate
                                    .valueType
                                    .byteWidth,
                            cancellation =
                                AtomicCancellationSignal(),
                            durationMs =
                                5_000,
                        )
                }
            main.post {
                codeTraceBusy.set(
                    false,
                )
                result.onSuccess {
                    trace ->
                    codeAccessSites =
                        trace.sites
                    selectedCodeSite =
                        trace.sites
                            .firstOrNull {
                                eligibleWriterSite(
                                    it,
                                )
                            }
                    val learnedSites =
                        trace.sites
                            .asSequence()
                            .mapNotNull {
                                site ->
                                val offset =
                                    site.moduleFileOffset
                                        ?: return@mapNotNull null
                                if (
                                    site.moduleName
                                        .startsWith(
                                            "<",
                                        )
                                ) {
                                    return@mapNotNull null
                                }
                                LearnedCodeAccessSite(
                                    moduleIdentity =
                                        site.moduleName,
                                    moduleFileOffset =
                                        offset,
                                    accessKind =
                                        site.accessKind,
                                    instructionWord =
                                        site.instructionWord,
                                    instructionText =
                                        site.instructionText,
                                    managedMethodCandidate =
                                        site.managedMethodCandidate,
                                    observedCount =
                                        site.count,
                                )
                            }
                            .distinctBy {
                                it.moduleIdentity +
                                    ":" +
                                    it.moduleFileOffset
                            }
                            .take(16)
                            .toList()
                    val updatedCandidate =
                        candidate.copy(
                            learnedCodeSites =
                                learnedSites,
                            requiresConfirmation =
                                false,
                        )
                    selectedCandidate =
                        updatedCandidate
                    rebuildCodeAccessList()
                    if (
                        learnedSites.isNotEmpty()
                    ) {
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
                    }
                    maybeEnrichIl2CppCodeSites(
                        candidate =
                            updatedCandidate,
                        sites =
                            trace.sites,
                    )
                    val writers =
                        trace.sites
                            .count {
                                it.accessKind ==
                                    RuntimeCodeAccessKind
                                        .WRITE
                            }
                    val readers =
                        trace.sites
                            .count {
                                it.accessKind ==
                                    RuntimeCodeAccessKind
                                        .READ
                            }
                    setStatus(
                        "Code trace: " +
                            trace.totalTraps +
                            " обращений · writers " +
                            writers +
                            " · readers " +
                            readers +
                            " · потоков " +
                            trace.watchedThreads +
                            ".",
                    )
                    Toast.makeText(
                        this,
                        if (
                            trace.sites
                                .isEmpty()
                        ) {
                            "ModKit: обращений к значению за окно trace не найдено."
                        } else {
                            "ModKit: найден код, использующий выбранное значение — " +
                                trace.sites.size +
                                " участков."
                        },
                        Toast.LENGTH_LONG,
                    ).show()
                }.onFailure {
                    failure ->
                    codeAccessSites =
                        emptyList()
                    selectedCodeSite =
                        null
                    rebuildCodeAccessList()
                    setStatus(
                        "Code trace недоступен: " +
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

    private fun rebuildCodeAccessList() {
        val list =
            codeAccessList
                ?: return
        list.removeAllViews()
        if (
            codeAccessSites
                .isEmpty()
        ) {
            list.addView(
                hintText(
                    "После trace здесь появится код, который читает или изменяет выбранное значение.",
                ),
            )
            return
        }

        codeAccessSites
            .take(8)
            .forEach {
                site ->
                val readableKind =
                    when (
                        site.accessKind
                    ) {
                        RuntimeCodeAccessKind.WRITE ->
                            "Изменяет значение"
                        RuntimeCodeAccessKind.READ ->
                            "Читает значение"
                        RuntimeCodeAccessKind.UNKNOWN ->
                            "Обращается к значению"
                    }
                val method =
                    site.managedMethodCandidate
                        ?.takeIf {
                            it.isNotBlank()
                        }
                val technical =
                    if (
                        currentPage ==
                        OverlayPage.EXPERT
                    ) {
                        "\n" +
                            site.moduleName +
                            (
                                site.moduleFileOffset
                                    ?.let {
                                        " +0x" +
                                            it.toString(
                                                16,
                                            )
                                    }
                                    ?: ""
                                ) +
                            (
                                site.instructionText
                                    ?.let {
                                        " · " +
                                            it
                                    }
                                    ?: ""
                                )
                    } else {
                        ""
                    }
                list.addView(
                    TextView(this).apply {
                        text =
                            readableKind +
                                (
                                    method
                                        ?.let {
                                            "\n" +
                                                it
                                        }
                                        ?: ""
                                    ) +
                                " · срабатываний " +
                                site.count +
                                technical +
                                (
                                    if (
                                        selectedCodeSite ==
                                        site
                                    ) {
                                        "\n✓ Выбран"
                                    } else {
                                        ""
                                    }
                                    )
                        setTextColor(
                            if (
                                site.accessKind ==
                                RuntimeCodeAccessKind.WRITE
                            ) {
                                Color.WHITE
                            } else {
                                Color.LTGRAY
                            },
                        )
                        textSize = 11f
                        setPadding(
                            dp(6),
                            dp(6),
                            dp(6),
                            dp(6),
                        )
                        if (
                            eligibleWriterSite(
                                site,
                            )
                        ) {
                            setOnClickListener {
                                if (
                                    activeCodePatch ==
                                    null
                                ) {
                                    selectedCodeSite =
                                        site
                                    rebuildCodeAccessList()
                                    setStatus(
                                        "Выбран код, который изменяет значение. Теперь можно временно заблокировать это изменение.",
                                    )
                                    if (
                                        currentPage ==
                                        OverlayPage.CANDIDATE
                                    ) {
                                        codePatchButton
                                            ?.text =
                                            selectedCandidate
                                                ?.let {
                                                    writerActionTitle(
                                                        it,
                                                    )
                                                }
                                                ?: "Блокировать изменение"
                                    }
                                }
                            }
                        }
                    },
                )
            }
    }

    private fun maybeEnrichIl2CppCodeSites(
        candidate:
            EditableRuntimeCandidate,
        sites:
            List<RootCodeAccessSite>,
    ) {
        val cfg =
            config ?: return
        if (
            staticEnrichmentAttempted ||
            !sites.any {
                it.moduleName.equals(
                    "libil2cpp.so",
                    ignoreCase = true,
                ) &&
                    it.moduleFileOffset !=
                    null &&
                    it.managedMethodCandidate ==
                    null
            } ||
            !staticEnrichmentBusy
                .compareAndSet(
                    false,
                    true,
                )
        ) {
            return
        }
        staticEnrichmentAttempted =
            true
        setStatus(
            "Native writer найден. В фоне достраиваем IL2CPP bindings, чтобы определить managed-метод…",
        )

        executor.execute {
            val result =
                runCatching {
                    val app =
                        InstalledAppRepository(
                            applicationContext,
                        ).find(
                            cfg.packageName,
                        ) ?: error(
                            "Установленный APK недоступен для IL2CPP correlation.",
                        )
                    val discovery =
                        runBlocking {
                            RootModDiscoveryCoordinator
                                .discover(
                                    context =
                                        applicationContext,
                                    app = app,
                                    cancellation =
                                        AtomicCancellationSignal(),
                                    progress =
                                        ProgressSink {
                                            // Static enrichment intentionally
                                            // stays in the background; the
                                            // overlay remains responsive.
                                        },
                                )
                        }
                    sites.map {
                        site ->
                        val offset =
                            site.moduleFileOffset
                        if (
                            offset == null ||
                            site.managedMethodCandidate !=
                            null
                        ) {
                            site
                        } else {
                            site.copy(
                                managedMethodCandidate =
                                    RootMemoryWatchCoordinator
                                        .correlateManagedMethod(
                                            cachedAnalysis =
                                                discovery
                                                    .analysisResult,
                                            moduleName =
                                                site.moduleName,
                                            fileOffset =
                                                offset,
                                        ),
                            )
                        }
                    }
                }

            main.post {
                staticEnrichmentBusy.set(
                    false,
                )
                result.onSuccess {
                    enriched ->
                    val current =
                        selectedCandidate
                    if (
                        current == null ||
                        current.id !=
                        candidate.id
                    ) {
                        return@onSuccess
                    }
                    codeAccessSites =
                        enriched
                    selectedCodeSite =
                        enriched
                            .firstOrNull {
                                eligibleWriterSite(
                                    it,
                                )
                            }
                    val learned =
                        enriched
                            .mapNotNull {
                                site ->
                                val offset =
                                    site.moduleFileOffset
                                        ?: return@mapNotNull null
                                if (
                                    site.moduleName
                                        .startsWith(
                                            "<",
                                        )
                                ) {
                                    return@mapNotNull null
                                }
                                LearnedCodeAccessSite(
                                    moduleIdentity =
                                        site.moduleName,
                                    moduleFileOffset =
                                        offset,
                                    accessKind =
                                        site.accessKind,
                                    instructionWord =
                                        site.instructionWord,
                                    instructionText =
                                        site.instructionText,
                                    managedMethodCandidate =
                                        site.managedMethodCandidate,
                                    observedCount =
                                        site.count,
                                )
                            }
                            .distinctBy {
                                it.moduleIdentity +
                                    ":" +
                                    it.moduleFileOffset
                            }
                            .take(16)
                    val updated =
                        current.copy(
                            learnedCodeSites =
                                learned,
                        )
                    selectedCandidate =
                        updated
                    rebuildCodeAccessList()
                    if (
                        learned.any {
                            it.managedMethodCandidate !=
                                null
                        }
                    ) {
                        setStatus(
                            "IL2CPP correlation готов: найденные native reader/writer подписаны managed-методами там, где binding однозначен.",
                        )
                        stabilizeEditableCandidate(
                            candidate =
                                updated,
                            source =
                                updated.source
                                    ?: LearnedCandidateSource
                                        .MANUAL,
                            actionHint =
                                updated.actionHint,
                            force = true,
                        )
                    } else {
                        setStatus(
                            "Code trace подтверждён, но однозначный managed-метод для этих offsets не доказан.",
                        )
                    }
                }.onFailure {
                    failure ->
                    setStatus(
                        "Code trace сохранён; IL2CPP-подпись не достроена: " +
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

    private fun eligibleWriterSite(
        site: RootCodeAccessSite,
    ): Boolean {
        val mnemonic =
            site.instructionText
                ?.substringAfter(
                    ": ",
                )
                ?.substringBefore(
                    ' ',
                )
                ?.lowercase()
                .orEmpty()
        return site.accessKind ==
            RuntimeCodeAccessKind.WRITE &&
            site.instructionWord !=
            null &&
            site.instructionAddress >
            0L &&
            site.moduleFileOffset !=
            null &&
            (
                mnemonic == "str" ||
                    mnemonic == "strb" ||
                    mnemonic == "strh"
                )
    }

    private fun toggleSelectedWriterBlock() {
        val cfg =
            config ?: return
        if (autoSession != null) {
            stopAutoScan(
                userRequested =
                    false,
            )
        }
        if (
            !codePatchBusy
                .compareAndSet(
                    false,
                    true,
                )
        ) {
            return
        }

        val active =
            activeCodePatch
        if (active != null) {
            executor.execute {
                val result =
                    runCatching {
                        RootRuntimeCodeToggleCoordinator
                            .setNopEnabled(
                                packageName =
                                    cfg.packageName,
                                pid = cfg.pid,
                                target =
                                    active.target,
                                enabled = false,
                                cancellation =
                                    AtomicCancellationSignal(),
                            )
                    }
                main.post {
                    codePatchBusy.set(
                        false,
                    )
                    result.onSuccess {
                        activeCodePatch =
                            null
                        setStatus(
                            "Блокировка изменения отключена; исходная инструкция восстановлена.",
                        )
                        if (
                            currentPage ==
                            OverlayPage.CANDIDATE
                        ) {
                            renderCurrentPage()
                        }
                    }.onFailure {
                        failure ->
                        setStatus(
                            "Не удалось подтвердить откат Writer block: " +
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
            return
        }

        val candidate =
            selectedCandidate
        val site =
            selectedCodeSite
        if (
            candidate == null ||
            site == null ||
            !eligibleWriterSite(
                site,
            )
        ) {
            codePatchBusy.set(
                false,
            )
            setStatus(
                "Сначала выбери подтверждённый WRITE/STR участок из code trace.",
            )
            return
        }

        val word =
            requireNotNull(
                site.instructionWord,
            )
        val target =
            RootRuntimeCodeToggleTarget(
                id =
                    "writer:" +
                        candidate.id +
                        ":" +
                        site.instructionAddress
                            .toString(
                                16,
                            ),
                title =
                    candidate.title +
                        " writer",
                runtimeAddress =
                    site.instructionAddress,
                mappedPath =
                    site.mappedPath,
                originalWord =
                    word,
            )

        executor.execute {
            val result =
                runCatching {
                    RootRuntimeCodeToggleCoordinator
                        .setNopEnabled(
                            packageName =
                                cfg.packageName,
                            pid = cfg.pid,
                            target =
                                target,
                            enabled = true,
                            cancellation =
                                AtomicCancellationSignal(),
                        )
                }
            main.post {
                codePatchBusy.set(
                    false,
                )
                result.onSuccess {
                    activeCodePatch =
                        ActiveCodePatch(
                            candidateId =
                                candidate.id,
                            target =
                                target,
                        )
                    setStatus(
                        "Блокировка изменения включена. Подтверждённый writer временно отключён; нажми ещё раз для восстановления.",
                    )
                    if (
                        currentPage ==
                        OverlayPage.CANDIDATE
                    ) {
                        renderCurrentPage()
                    }
                }.onFailure {
                    failure ->
                    setStatus(
                        "Writer block не применён: " +
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

    private fun rollbackActiveCodePatchBestEffort() {
        val cfg =
            config ?: return
        val active =
            activeCodePatch
                ?: return
        runCatching {
            RootRuntimeCodeToggleCoordinator
                .setNopEnabled(
                    packageName =
                        cfg.packageName,
                    pid = cfg.pid,
                    target =
                        active.target,
                    enabled = false,
                    cancellation =
                        AtomicCancellationSignal(),
                    runner =
                        AndroidRootCommandRunner(
                            timeoutMs =
                                2_500L,
                        ),
                )
        }
        activeCodePatch = null
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
                    setStatus(
                        "Значение изменено: " +
                            written.oldValue +
                            " → " +
                            written.newValue +
                            ".",
                    )
                    if (
                        currentPage ==
                        OverlayPage.CANDIDATE
                    ) {
                        renderCurrentPage()
                    }
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
            "Остановить заморозку"
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
            "Заморозить значение"
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
        manualAutoType = true
        manualUnknownAuto = false
        manualFullScan = false
        currentPage =
            OverlayPage.HOME
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
        autoCodeTraceAttempted.clear()
        behavioralCodeSites.clear()
        selectedCandidate = null
        codeAccessSites =
            emptyList()
        selectedCodeSite = null
        activeCodePatch = null
        codeTraceBusy.set(
            false,
        )
        codePatchBusy.set(
            false,
        )
        staticEnrichmentBusy.set(
            false,
        )
        staticEnrichmentAttempted =
            false
        codePatchButton?.text =
            "Writer block: OFF"
        codeAccessList
            ?.removeAllViews()
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
                                        learnedCodeSites =
                                            if (
                                                migrated
                                            ) {
                                                emptyList()
                                            } else {
                                                saved.codeAccessSites
                                            },
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

    private fun renameSelectedPersistentCandidate() {
        val cfg =
            config ?: return
        val candidate =
            selectedCandidate
                ?: return
        val anchor =
            candidate.anchor
                ?: run {
                    setStatus(
                        "У выбранного мода нет сохранённой привязки.",
                    )
                    return
                }
        val newTitle =
            renameValue
                ?.text
                ?.toString()
                .orEmpty()
                .trim()
        if (
            newTitle.length !in
            2..60
        ) {
            setStatus(
                "Имя мода должно быть от 2 до 60 символов.",
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
                    val profile =
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
                        } ?: error(
                            "Профиль этой версии не найден.",
                        )
                    val saved =
                        profile.candidates
                            .singleOrNull {
                                it.anchor ==
                                    anchor
                            } ?: error(
                            "Сохранённая привязка не найдена.",
                        )
                    synchronized(
                        profileLock,
                    ) {
                        profileStore
                            .saveCandidate(
                                identity =
                                    identity,
                                candidate =
                                    saved.copy(
                                        title =
                                            newTitle,
                                        updatedAtEpochMs =
                                            System
                                                .currentTimeMillis(),
                                    ),
                            )
                    }
                    newTitle
                }
            main.post {
                result.onSuccess {
                    title ->
                    learnedCandidates =
                        learnedCandidates
                            .map {
                                item ->
                                if (
                                    item.anchor ==
                                    anchor
                                ) {
                                    item.copy(
                                        title = title,
                                    )
                                } else {
                                    item
                                }
                            }
                    selectedCandidate =
                        candidate.copy(
                            title = title,
                        )
                    setStatus(
                        "Мод переименован.",
                    )
                    renderCurrentPage()
                }.onFailure {
                    failure ->
                    setStatus(
                        "Не удалось переименовать мод: " +
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
                        currentPage =
                            OverlayPage.MODS
                        setStatus(
                            "Сохранённая привязка удалена.",
                        )
                        renderCurrentPage()
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
                    val anchor =
                        candidate.anchor
                            ?: RootRuntimePointerChainCoordinator
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
                                .stableAnchor
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
                            codeAccessSites =
                                candidate
                                    .learnedCodeSites,
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
                    if (
                        selectedCandidate
                            ?.id ==
                        saved.id
                    ) {
                        selectedCandidate =
                            saved
                    }
                    setStatus(
                        "Мод сохранён и будет восстановлен при следующем запуске.",
                    )
                    if (
                        currentPage ==
                        OverlayPage.CANDIDATE ||
                        currentPage ==
                        OverlayPage.MODS
                    ) {
                        renderCurrentPage()
                    }
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
                    candidateDisplayTitle(
                        candidate,
                    ) +
                        "\nСейчас: " +
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

    private data class ActiveCodePatch(
        val candidateId: String,
        val target:
            RootRuntimeCodeToggleTarget,
    )

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
        val learnedCodeSites:
            List<LearnedCodeAccessSite> =
            emptyList(),
    )

    companion object {
        private const val CHANNEL_ID =
            "modkit_live_overlay"
        private const val NOTIFICATION_ID =
            7210
    }
}
