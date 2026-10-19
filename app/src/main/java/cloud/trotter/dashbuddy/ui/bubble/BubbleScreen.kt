package cloud.trotter.dashbuddy.ui.bubble

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.feature.bubble.hud.*
import cloud.trotter.dashbuddy.ui.main.MainActivity
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.domain.model.vehicle.MileageReconciliation
import cloud.trotter.dashbuddy.domain.model.vehicle.OdometerCheckInput
import cloud.trotter.dashbuddy.domain.model.vehicle.OdometerReadingCheck
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.*
import kotlinx.coroutines.delay

@Composable
fun BubbleScreen(viewModel: BubbleViewModel = hiltViewModel()) {
    val appState by viewModel.appState.collectAsStateWithLifecycle()
    val platform by viewModel.focusedPlatform.collectAsStateWithLifecycle()
    val region by viewModel.focusedRegion.collectAsStateWithLifecycle()
    val miles by viewModel.sessionMiles.collectAsStateWithLifecycle()
    val earnings by viewModel.sessionEarnings.collectAsStateWithLifecycle()
    val lastSession by viewModel.lastSession.collectAsStateWithLifecycle()
    val net by viewModel.sessionNet.collectAsStateWithLifecycle()
    val countExpenses by viewModel.countExpensesInKept.collectAsStateWithLifecycle()
    val goal by viewModel.dailyGoal.collectAsStateWithLifecycle()
    val goalDay by viewModel.goalDay.collectAsStateWithLifecycle()
    val goalMark by viewModel.goalMark.collectAsStateWithLifecycle()
    val todayProfit = goalDay?.profit
    val gasPrice by viewModel.gasPrice.collectAsStateWithLifecycle()
    val gasAuto by viewModel.isGasPriceAuto.collectAsStateWithLifecycle()
    val gasRefreshing by viewModel.isGasPriceRefreshing.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val config by viewModel.evaluationConfig.collectAsStateWithLifecycle()
    val switchable by viewModel.switchablePlatforms.collectAsStateWithLifecycle()
    val vehicle by viewModel.activeVehicle.collectAsStateWithLifecycle()
    val vehicles by viewModel.vehicles.collectAsStateWithLifecycle()
    val lastReading by viewModel.lastReading.collectAsStateWithLifecycle()
    val readings by viewModel.odometerReadings.collectAsStateWithLifecycle()
    val prompts by viewModel.odometerPrompts.collectAsStateWithLifecycle()
    val gpsTotal by viewModel.gpsTotalMiles.collectAsStateWithLifecycle()
    val saving by viewModel.odometerSaving.collectAsStateWithLifecycle()
    val writeFailed by viewModel.odometerWriteFailed.collectAsStateWithLifecycle()
    val savedNotice by viewModel.odometerNotice.collectAsStateWithLifecycle()
    val now by rememberNow()
    val entry = rememberOdometerEntryState()
    val notice = savedNotice?.takeIf { now - it.savedAt in 0L until 10_000L }
    var acknowledgedNoticeAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var seenVehicleId by rememberSaveable { mutableStateOf(vehicle?.id) }
    LaunchedEffect(savedNotice) {
        savedNotice?.let { saved ->
            if (saved.savedAt != acknowledgedNoticeAt) {
                entry.open = false
                acknowledgedNoticeAt = saved.savedAt
            }
        }
    }
    LaunchedEffect(vehicle?.id) {
        val id = vehicle?.id
        if (id != null && id != seenVehicleId) {
            entry.vehicleChanged()
            seenVehicleId = id
        }
    }
    val reconciliation = remember(readings, vehicle, lastSession) {
        val last = lastSession
        val id = vehicle?.id
        val ended = last?.endedAt
        if (id == null || last == null || ended == null) null
        else MileageReconciliation.between(readings, id, last.startedAt, ended)
    }
    var logOpen by rememberSaveable { mutableStateOf(false) }
    val openOdometer = {
        logOpen = false
        viewModel.refreshOdometer()
        entry.show(vehicle != null)
    }
    val sheetBack = { entry.back(vehicle != null) }
    val noticeContent: (@Composable () -> Unit)? = if (notice == null) null else ({ OdometerNotice(notice) })
    BackHandler(enabled = logOpen) { logOpen = false }
    val context = LocalContext.current
    LaunchedEffect(viewModel, context) {
        viewModel.collapse.collect { context.findActivity()?.finish() }
    }
    var gasError by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.gasPriceRefreshFailed.collect {
            gasError = true
            delay(3_000)
            gasError = false
        }
    }
    val flow = appState.regions.flow.flow
    val liveCardShown by viewModel.liveCardShown.collectAsStateWithLifecycle()
    val model = remember(flow, cards.stack, region, lastSession, liveCardShown) {
        StateCardMapper.map(flow, region?.mode, cards.stack.active, cards.stack, region, lastSession, flowOwned = liveCardShown)
    }
    val pendingOffer = region?.presentedOffer()
    val gas = GasRowModel(gasPrice, gasAuto, gasRefreshing, gasError)
    val state = remember(region, flow, platform, switchable, earnings, miles, lastSession,
        net, countExpenses, goal, goalDay, goalMark, cards.stack.active, logOpen, gas, model) {
        HudStateMapper.map(region, flow, platform, switchable, earnings, miles, lastSession,
            net, countExpenses, goal, todayProfit, cards.stack.active != null,
            pendingOffer != null, logOpen, gas, cashTipCue = model?.cuesCashTip == true,
            goalDay = goalDay?.date, mark = goalMark)
    }
    val ribbon = remember(cards.stack, region?.session?.startedAt) {
        region?.session?.startedAt?.let { RibbonMapper.map(cards.stack, it) }
    }
    CompositionLocalProvider(LocalHudSheetBackHandler provides { enabled, dismiss ->
        BackHandler(enabled = enabled, onBack = dismiss)
    }) {
        HudScreen(
            state = state,
            ribbon = ribbon,
            onGoalCelebrated = viewModel::goalCelebrated,
            sheetOpen = entry.open,
            onSheetDismiss = sheetBack,
            sheet = {
                val check = OdometerReadingCheck.check(OdometerCheckInput(entry.reading, vehicle, lastReading, gpsTotal, now))
                OdometerSheet(OdometerSheetModel(vehicle, vehicles, entry.reading, lastReading, check, entry.page, saving, writeFailed),
                    onDigit = entry::digit, onBackspace = entry::backspace,
                    onSave = { entry.reading?.let(viewModel::saveOdometerReading) },
                    onAddVehicle = viewModel::addVehicle,
                    onSwitchVehicle = { entry.page = OdometerPage.VEHICLES },
                    onSelectVehicle = { id ->
                        if (id == vehicle?.id) entry.page = OdometerPage.READING else viewModel.selectVehicle(id)
                    },
                    onShowAddVehicle = { entry.page = OdometerPage.ADD_VEHICLE }, onBack = sheetBack)
            },
            undo = noticeContent,
            restingFooter = {
                OdometerFooter(vehicle?.name, lastReading, reconciliation, openOdometer)
                OdometerPrompt(prompts, null, lastSession?.endedAt, lastReading, openOdometer)
            },
            // Matches Material 3 ScaffoldDefaults: system bars plus display cutouts.
            modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
            onSelectPlatform = viewModel::selectPlatform,
            onSetGasPrice = viewModel::setGasPrice,
            onRefreshGasPrice = { viewModel.refreshGasPrice() },
            onResumeAutoGasPrice = { viewModel.resumeAutoGasPrice() },
            onDockClick = { kind ->
                when (kind) {
                    DockKind.ODOMETER -> openOdometer()
                    DockKind.LOG -> { entry.open = false; logOpen = true }
                    DockKind.NOW -> logOpen = false
                    DockKind.SETTINGS -> context.startActivity(MainActivity.routeIntent(context, Screen.SettingsHome.route))
                    DockKind.CASH_TIP, DockKind.EXPENSE -> Unit // Cash tip cue is clickable; S3 owns the sheet.
                }
            },
            stateCard = {
                StateCard(model, state.hero.profit, waitingFoot = {
                    OdometerPrompt(prompts, region?.session?.startedAt, null, lastReading, openOdometer)
                })
            },
            offer = {
                pendingOffer?.let { pending ->
                    val offerModel = remember(pending, region?.activeJob, cards.stack.completed, config) {
                        OfferCardMapper.map(pending, region?.activeJob, cards.stack.completed, config)
                    }
                    OfferCard(offerModel, viewModel::acceptOffer, viewModel::declineOffer)
                }
            },
            log = {
                val logData by viewModel.logData.collectAsStateWithLifecycle()
                val messages by viewModel.messages.collectAsStateWithLifecycle()
                val items = remember(cards.stack, logData, messages, model, region?.activeJob) {
                    LogMapper.map(cards.stack, logData, messages, model,
                        liveLinks = region?.activeJob?.let { job ->
                            mapOf(job.jobId to job.acceptedOffers.mapNotNull { it.offerHash }.toSet())
                        }.orEmpty())
                }
                LogView(items, onNow = { logOpen = false }, onUndo = {})
            },
        )
    }
}

/** Unwrap a Compose context to the hosting Activity. */
private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
