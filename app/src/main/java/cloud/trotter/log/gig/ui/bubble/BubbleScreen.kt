package cloud.trotter.log.gig.ui.bubble

import android.widget.Toast
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.domain.model.vehicle.odometerShownFor
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
import cloud.trotter.log.gig.feature.bubble.hud.*
import cloud.trotter.log.gig.feature.bubble.hud.vehicle.VehicleChip
import cloud.trotter.log.gig.feature.bubble.hud.odometer.displayName
import cloud.trotter.log.gig.ui.main.MainActivity
import cloud.trotter.log.gig.ui.main.navigation.Screen
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
    val garage by viewModel.garage.collectAsStateWithLifecycle()
    val odometerShown = odometerShownFor(garage?.takeIf { it.readable }?.active)
    val odometerLoaded by viewModel.odometerLoaded.collectAsStateWithLifecycle()
    val vehicleChip by viewModel.vehicleChip.collectAsStateWithLifecycle()
    val logOpenState = rememberSaveable { mutableStateOf(false) }
    var logOpen by logOpenState
    val closeLog = remember(logOpenState) { { logOpenState.value = false } }
    val context = LocalContext.current
    val openInApp: (String) -> Unit = remember(context) {
        { route ->
            context.startActivity(MainActivity.routeIntent(context, route))
            context.collapseBubble()
        }
    }
    var vehicleMenuOpen by rememberSaveable { mutableStateOf(false) }
    var odometerVehicleMenuOpen by rememberSaveable { mutableStateOf(false) }
    val dismissVehicleMenus = { vehicleMenuOpen = false; odometerVehicleMenuOpen = false }
    val selectVehicle: (String) -> Unit = { id ->
        dismissVehicleMenus()
        // Review r1: the lock is re-checked at the pick (a menu opened at 9:59 must not swap at 10:01),
        // and picking the already-active vehicle is a no-op so an entered odometer reading survives.
        when {
            viewModel.vehicleSwapLocked() -> Toast.makeText(context, R.string.hud_vehicle_locked, Toast.LENGTH_SHORT).show()
            id != vehicleChip?.activeId -> viewModel.odometerActions.select(id)
        }
    }
    val openVehicleAdd = remember(openInApp) { { openInApp(Screen.VehicleAdd.route) } }
    val addVehicle = { dismissVehicleMenus(); openVehicleAdd() }
    val clickVehicleChip: (Boolean) -> Unit = { inSheet ->
        dismissVehicleMenus()
        if (viewModel.vehicleSwapLocked()) {
            Toast.makeText(context, R.string.hud_vehicle_locked, Toast.LENGTH_SHORT).show()
        } else if (inSheet) odometerVehicleMenuOpen = true else vehicleMenuOpen = true
    }
    val odometer = rememberOdometerHud(viewModel, closeLog, openVehicleAdd)
    val cashTip = rememberCashTipHud(viewModel, region?.session?.sessionId ?: lastSession?.sessionId, closeLog)
    val expense = rememberExpenseHud(viewModel, closeLog, openVehicleAdd)
    val goalHud = rememberGoalHud(viewModel, closeLog)
    val sheets = rememberBubbleSheetHost(listOf(
        SheetSlot(odometer) {
            odometer.Sheet {
                vehicleChip?.let {
                    VehicleChip(it.forActive(), odometerVehicleMenuOpen, { clickVehicleChip(true) },
                        dismissVehicleMenus, selectVehicle, addVehicle)
                }
            }
        },
        SheetSlot(cashTip) { cashTip.Sheet() },
        SheetSlot(expense) { expense.Sheet() },
        SheetSlot(goalHud) { goalHud.Sheet() },
    ))
    val openOdometer = remember(sheets) { { sheets.show(EntrySheet.ODOMETER) } }
    val cashTipCue = cashTip.Cue()
    val expenseCue = expense.Cue()
    val odometerCue = odometer.Cue(region?.session?.startedAt, lastSession?.endedAt, odometerShown)
    LaunchedEffect(odometer.open) { dismissVehicleMenus() }
    val odometerFooter: @Composable () -> Unit = remember(odometer, openOdometer, odometerShown) { { odometer.RestingFooter(openOdometer, odometerShown) } }
    BackHandler(enabled = logOpen) { logOpen = false }
    LaunchedEffect(viewModel, context) {
        viewModel.collapse.collect { context.collapseBubble() }
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
    // Editing lasts while the sheet is open OR a goal write is still landing: a preset tapped and
    // then Back before persistence would otherwise publish a key the closed-sheet rule celebrates.
    val goalBusy by viewModel.goalActions.busy.collectAsStateWithLifecycle()
    val goalEditing = goalHud.open || goalBusy
    // Keep the closing frame silent even when the saved preset and close arrive together.
    var goalSheetWasOpen by rememberSaveable { mutableStateOf(false) }
    var editedGoalKey by rememberSaveable { mutableStateOf<String?>(null) }
    val pillGoal = GoalPillMapper.map(goal, todayProfit)
    val metGoalKey = if (pillGoal == GoalPillState.Met)
        goalDay?.date?.let { date -> goal?.let { GoalMetMark.key(date, it) } } else null
    val editKey = if (goalEditing || goalSheetWasOpen) metGoalKey else editedGoalKey
    SideEffect {
        editedGoalKey = if (todayProfit != null && pillGoal != GoalPillState.Met) null else editKey
        goalSheetWasOpen = goalEditing
    }
    val silentGoalMark = GoalCelebrationResolver.markAfterEditing(
        pillGoal, goalDay?.date, goal, goalMark, goalEditing, editKey)
    LaunchedEffect(silentGoalMark) { silentGoalMark?.let(viewModel::goalCelebrated) }
    val state = remember(region, flow, platform, switchable, earnings, miles, lastSession,
        net, goal, goalDay, goalMark, cards.stack.active, logOpen, gas, model, odometerLoaded, odometerShown, odometerCue, cashTipCue, expenseCue, goalEditing, editKey, vehicleChip) {
        HudStateMapper.map(region, flow, platform, switchable, earnings, miles, lastSession,
            net, goal, todayProfit, cards.stack.active != null,
            pendingOffer != null, logOpen, gas, cashTipCue = model?.cuesCashTip == true || cashTipCue,
            goalDay = goalDay?.date, mark = goalMark, odometerLoaded = odometerLoaded, odometerCue = odometerCue, expenseCue = expenseCue,
            goalSheetOpen = goalEditing, editedGoalKey = editKey, vehicleChip = vehicleChip, odometerShown = odometerShown)
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
            onGoalClick = { sheets.show(EntrySheet.GOAL) },
            sheetOpen = sheets.open,
            sheetCompact = sheets.compact,
            onSheetOutsideDismiss = sheets::outside,
            onSheetDismiss = sheets::back,
            sheet = { sheets.Sheet(this) },
            undo = { sheets.Band() },
            undoVisible = { sheets.BandVisible() },
            restingFooter = odometerFooter,
            vehicleMenuOpen = vehicleMenuOpen,
            onVehicleChipClick = { clickVehicleChip(false) },
            onDismissVehicleMenu = dismissVehicleMenus,
            onSelectVehicle = selectVehicle,
            onAddVehicle = addVehicle,
            // Matches Material 3 ScaffoldDefaults: system bars plus display cutouts.
            modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
            onSelectPlatform = viewModel::selectPlatform,
            onSetGasPrice = viewModel::setGasPrice,
            onRefreshGasPrice = { viewModel.refreshGasPrice() },
            onResumeAutoGasPrice = { viewModel.resumeAutoGasPrice() },
            onDockClick = { kind ->
                if (kind == DockKind.LOG || kind == DockKind.NOW || kind == DockKind.SETTINGS) sheets.show(null)
                when (kind) {
                    DockKind.ODOMETER -> openOdometer()
                    DockKind.LOG -> logOpen = true
                    DockKind.NOW -> logOpen = false
                    DockKind.SETTINGS -> openInApp(Screen.SettingsHome.route)
                    DockKind.CASH_TIP -> sheets.show(EntrySheet.CASH_TIP)
                    DockKind.EXPENSE -> sheets.show(EntrySheet.EXPENSE)
                }
            },
            stateCard = {
                val taskId = when (val active = cards.stack.active) {
                    is cloud.trotter.log.gig.domain.model.cards.FlowCardSnapshot.Delivery -> active.taskId
                    is cloud.trotter.log.gig.domain.model.cards.FlowCardSnapshot.PostTask -> active.taskId
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
                val items = remember(cards.stack, logData, messages, model, region?.activeJob, context) {
                    LogMapper.map(cards.stack, logData, messages, model,
                        vehicleDisplayName = { it.displayName(context) },
                        liveLinks = region?.activeJob?.let { job ->
                            mapOf(job.jobId to job.acceptedOffers.mapNotNull { it.offerHash }.toSet())
                        }.orEmpty())
                }
                LogView(items, onNow = { logOpen = false }, onUndo = {})
            },
        )
    }
}

/**
 * Collapse the bubble to its icon: the one way the HUD closes itself (Accept, Decline, and every route into
 * the app). Sending the bubble's task to the back collapses it; finishing the activity would end the bubble's
 * task, and Android then REMOVES the bubble (`BubbleData.doRemove … reason=TASK_FINISHED`, device log
 * 2026-10-20), hidden until the next chat line re-posted it a few seconds later (#1379 follow-up).
 */
private fun Context.collapseBubble() {
    findActivity()?.moveTaskToBack(true)
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
