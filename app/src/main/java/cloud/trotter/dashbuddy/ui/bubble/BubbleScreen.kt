package cloud.trotter.dashbuddy.ui.bubble

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import cloud.trotter.dashbuddy.feature.bubble.FullChatView
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.feature.bubble.cards.FlowCardItem
import cloud.trotter.dashbuddy.feature.bubble.hud.*
import cloud.trotter.dashbuddy.ui.main.MainActivity
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import kotlinx.coroutines.delay

@Composable
fun BubbleScreen(viewModel: BubbleViewModel = hiltViewModel()) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val appState by viewModel.appState.collectAsStateWithLifecycle()
    val platform by viewModel.focusedPlatform.collectAsStateWithLifecycle()
    val region by viewModel.focusedRegion.collectAsStateWithLifecycle()
    val miles by viewModel.sessionMiles.collectAsStateWithLifecycle()
    val earnings by viewModel.sessionEarnings.collectAsStateWithLifecycle()
    val lastSession by viewModel.lastSession.collectAsStateWithLifecycle()
    val net by viewModel.sessionNet.collectAsStateWithLifecycle()
    val countExpenses by viewModel.countExpensesInKept.collectAsStateWithLifecycle()
    val goal by viewModel.dailyGoal.collectAsStateWithLifecycle()
    val todayProfit by viewModel.todayProfit.collectAsStateWithLifecycle()
    val gasPrice by viewModel.gasPrice.collectAsStateWithLifecycle()
    val gasAuto by viewModel.isGasPriceAuto.collectAsStateWithLifecycle()
    val gasRefreshing by viewModel.isGasPriceRefreshing.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val switchable by viewModel.switchablePlatforms.collectAsStateWithLifecycle()
    var logOpen by rememberSaveable { mutableStateOf(false) }
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
    val model = remember(flow, region?.mode, cards.stack.active, cards.stack, region, lastSession) {
        StateCardMapper.map(flow, region?.mode, cards.stack.active, cards.stack, region, lastSession)
    }
    val gas = GasRowModel(gasPrice, gasAuto, gasRefreshing, gasError)
    val state = remember(region, flow, platform, switchable, earnings, miles, lastSession,
        net, countExpenses, goal, todayProfit, cards.stack.active, logOpen, gas, model) {
        HudStateMapper.map(region, flow, platform, switchable, earnings, miles, lastSession,
            net, countExpenses, goal, todayProfit, cards.stack.active != null,
            cards.stack.active is FlowCardSnapshot.Offer, logOpen, gas, cashTipCue = model?.cuesCashTip == true)
    }
    val ribbon = remember(cards.stack, region?.session?.startedAt) {
        region?.session?.startedAt?.let { RibbonMapper.map(cards.stack, it) }
    }
    HudScreen(
        state = state,
        ribbon = ribbon,
        // Matches Material 3 ScaffoldDefaults: system bars plus display cutouts.
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
        onSelectPlatform = viewModel::selectPlatform,
        onSetGasPrice = viewModel::setGasPrice,
        onRefreshGasPrice = { viewModel.refreshGasPrice() },
        onResumeAutoGasPrice = { viewModel.resumeAutoGasPrice() },
        onDockClick = { kind ->
            when (kind) {
                DockKind.LOG -> logOpen = !logOpen
                DockKind.SETTINGS -> context.startActivity(MainActivity.routeIntent(context, Screen.SettingsHome.route))
                DockKind.CASH_TIP, DockKind.EXPENSE -> Unit // Cash tip cue is clickable; S3 owns the sheet.
            }
        },
        stateCard = {
            StateCard(model, state.hero.profit, region?.session?.startedAt)
        },
        offer = {
            cards.stack.active?.let { active ->
                FlowCardItem(active, isActive = true, expanded = true, onToggleExpand = {},
                    platformLabel = cards.platformLabels[active.id],
                    onAccept = { viewModel.acceptOffer() }, onDecline = { viewModel.declineOffer() })
            }
        },
        log = { FullChatView(messages) },
    )
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
