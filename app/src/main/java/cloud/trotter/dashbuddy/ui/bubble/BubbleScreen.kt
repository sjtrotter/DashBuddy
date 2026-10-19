package cloud.trotter.dashbuddy.ui.bubble

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
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
import cloud.trotter.dashbuddy.core.designsystem.time.rememberDeadlinePassed
import cloud.trotter.dashbuddy.feature.bubble.hud.undo.cashTipUndoWins
import cloud.trotter.dashbuddy.ui.main.MainActivity
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import kotlinx.coroutines.delay

private enum class EntrySheet { ODOMETER, CASH_TIP }

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
    val odometerLoaded by viewModel.odometerLoaded.collectAsStateWithLifecycle()
    val logOpenState = rememberSaveable { mutableStateOf(false) }
    var logOpen by logOpenState
    val closeLog = remember(logOpenState) { { logOpenState.value = false } }
    val odometer = rememberOdometerHud(viewModel, closeLog)
    val cashTip = rememberCashTipHud(viewModel, region?.session?.sessionId ?: lastSession?.sessionId, closeLog)
    // One exclusive rule for every open route, including the resting odometer footer.
    val openOdometer = remember(odometer, cashTip) { { cashTip.dismiss(); odometer.openSheet() } }
    val openCashTip = remember(odometer, cashTip) { { odometer.dismiss(); cashTip.openSheet() } }
    val cashTipCue = cashTip.Cue()
    val odometerCue = odometer.Cue(region?.session?.startedAt, lastSession?.endedAt)
    var lastSheet by remember { mutableStateOf(EntrySheet.ODOMETER) }
    LaunchedEffect(cashTip.open) { if (cashTip.open) lastSheet = EntrySheet.CASH_TIP }
    LaunchedEffect(odometer.open) { if (odometer.open) lastSheet = EntrySheet.ODOMETER }
    val entrySheet: @Composable ColumnScope.() -> Unit = remember(odometer, cashTip, lastSheet) {
        { when (lastSheet) {
            EntrySheet.CASH_TIP -> cashTip.Sheet()
            EntrySheet.ODOMETER -> odometer.Sheet()
        } }
    }
    val entryUndo: @Composable () -> Unit = remember(odometer, cashTip, viewModel) {
        {
            val strip by viewModel.cashTipActions.undo.collectAsStateWithLifecycle()
            val notice by viewModel.odometerNotice.collectAsStateWithLifecycle()
            val now = strip?.strip?.let { model ->
                val passed by rememberDeadlinePassed(model.deadline)
                if (passed) model.deadline else model.deadline - 1
            } ?: 0L
            if (cashTipUndoWins(strip?.strip, notice?.token, now)) cashTip.Undo()
            else odometer.Undo()
        }
    }
    val entryUndoVisible: @Composable () -> Boolean = remember(odometer, cashTip) {
        { val tipVisible = cashTip.UndoVisible(); val odometerVisible = odometer.UndoVisible(); tipVisible || odometerVisible }
    }
    val odometerFooter: @Composable () -> Unit = remember(odometer, openOdometer) { { odometer.RestingFooter(openOdometer) } }
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
        net, countExpenses, goal, goalDay, goalMark, cards.stack.active, logOpen, gas, model, odometerLoaded, odometerCue, cashTipCue) {
        HudStateMapper.map(region, flow, platform, switchable, earnings, miles, lastSession,
            net, countExpenses, goal, todayProfit, cards.stack.active != null,
            pendingOffer != null, logOpen, gas, cashTipCue = model?.cuesCashTip == true || cashTipCue,
            goalDay = goalDay?.date, mark = goalMark, odometerLoaded = odometerLoaded, odometerCue = odometerCue)
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
            sheetOpen = odometer.open || cashTip.open,
            onSheetDismiss = { if (cashTip.open) cashTip.back() else odometer.back() },
            sheet = entrySheet,
            undo = entryUndo,
            undoVisible = entryUndoVisible,
            restingFooter = odometerFooter,
            // Matches Material 3 ScaffoldDefaults: system bars plus display cutouts.
            modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
            onSelectPlatform = viewModel::selectPlatform,
            onSetGasPrice = viewModel::setGasPrice,
            onRefreshGasPrice = { viewModel.refreshGasPrice() },
            onResumeAutoGasPrice = { viewModel.resumeAutoGasPrice() },
            onDockClick = { kind ->
                when (kind) {
                    DockKind.ODOMETER -> openOdometer()
                    DockKind.LOG -> logOpen = true
                    DockKind.NOW -> logOpen = false
                    DockKind.SETTINGS -> context.startActivity(MainActivity.routeIntent(context, Screen.SettingsHome.route))
                    DockKind.CASH_TIP -> openCashTip()
                    DockKind.EXPENSE -> Unit // S3b owns expense entry.
                }
            },
            stateCard = {
                val taskId = when (val active = cards.stack.active) {
                    is cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot.Delivery -> active.taskId
                    is cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot.PostTask -> active.taskId
                    else -> null
                }
                StateCard(model, state.hero.profit, cashTip.Pending(taskId))
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
