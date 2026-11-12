package cloud.trotter.log.gig.ui.main.analytics

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.component.CouldNotLoad
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.domain.analytics.SessionDetail
import cloud.trotter.log.gig.domain.analytics.checks.MoneyCheck
import cloud.trotter.log.gig.domain.analytics.receipts.ReceiptMatchPlan
import cloud.trotter.log.gig.domain.analytics.receipts.ReceiptStatus
import cloud.trotter.log.gig.domain.analytics.timeline.JobCard
import cloud.trotter.log.gig.domain.analytics.timeline.JobState
import cloud.trotter.log.gig.domain.analytics.timeline.PassedOffer
import cloud.trotter.log.gig.domain.analytics.timeline.PassedRun
import cloud.trotter.log.gig.domain.analytics.search.SearchRow
import cloud.trotter.log.gig.domain.analytics.search.StoreQuery
import cloud.trotter.log.gig.domain.analytics.search.TimelineSearch
import cloud.trotter.log.gig.domain.format.formatWeekdayMonthDay
import cloud.trotter.log.gig.ui.components.RebuildingRecords
import cloud.trotter.log.gig.ui.components.UpdatingRecordsLine
import cloud.trotter.log.gig.ui.main.analytics.search.SearchButton
import cloud.trotter.log.gig.ui.main.analytics.search.SearchField
import cloud.trotter.log.gig.ui.main.analytics.search.SearchNoMatch
import cloud.trotter.log.gig.ui.main.analytics.search.SearchScopeLabel
import java.time.Instant
import java.time.ZoneId

/**
 * The shift page: one shift's money above a timeline of its offers and deliveries. The title bar names the day;
 * under it the app and times, Earned, three figures, the money check (only with an independent total) and the
 * corrections; then the timeline in one `LazyColumn` keyed by the fold's stable keys. Tapping a job opens its
 * sheet; a job with an accepted offer and no delivery opens "What happened to this offer?" instead. The shift's
 * checks ([SessionDetailUiState.checks]) sit under the money check. A `row` link scrolls to its item, opens the run
 * that holds it, and opens a job's sheet.
 *
 * A review surface (Principle 1, state in, intents out): reactive through the read-model flows; the one ticking
 * value is a running shift's online time ([ShiftFigures]). Store names are merchants and may show; no customer
 * data reaches this page.
 */
@Composable
fun SessionDetailScreen(
    onBack: () -> Unit,
    onSearchAll: (query: String) -> Unit = {},
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ShiftPageContent(
        uiState = uiState,
        landingRow = viewModel.landingRow,
        actions = ShiftPageActions(
            onBack = onBack,
            onSearchAll = onSearchAll,
            onRetry = viewModel::retry,
            onAddManualDelivery = viewModel::addManualDelivery,
            onAdjustDelivery = viewModel::adjustDelivery,
            onUnassignDelivery = viewModel::unassignDelivery,
            onCorrectSessionReport = viewModel::correctSessionReport,
            onCorrectShiftMiles = viewModel::correctShiftMiles,
            onMarkOfferUnassigned = viewModel::markOfferUnassigned,
            onAddDeliveryForOffer = viewModel::addMissedDeliveryForOffer,
            onRemoveDelivery = viewModel::removeManualDelivery,
            onUndoMarkedUnassigned = viewModel::undoOfferUnassigned,
            onConfirmReceiptMatch = viewModel::confirmReceiptMatch,
            onUndoReceiptMatch = viewModel::undoReceiptMatch,
        ),
    )
}

/** The page's intents, hoisted to the ViewModel. */
internal class ShiftPageActions(
    val onBack: () -> Unit,
    /** The search's last row: the same query over every shift. */
    val onSearchAll: (query: String) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onAddManualDelivery: (pay: Double, tip: Double?, cashTip: Double?, storeName: String?, note: String?) -> Unit = { _, _, _, _, _ -> },
    val onAdjustDelivery: (
        targetEventSequenceId: Long,
        newStoreName: String?,
        newPay: Double?,
        newTip: Double?,
        newCashTip: Double?,
        newMiles: Double?,
        note: String?,
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    val onUnassignDelivery: (targetEventSequenceId: Long) -> Unit = {},
    val onCorrectSessionReport: (String, Double?, String?) -> Unit = { _, _, _ -> },
    val onCorrectShiftMiles: (Double?) -> Unit = {},
    /** "It was unassigned" for an accepted offer with no delivery. */
    val onMarkOfferUnassigned: (offerEventSequenceId: Long) -> Unit = {},
    /** "Add a missed delivery" from an accepted offer: at the offer's time, naming it. */
    val onAddDeliveryForOffer: (
        offerEventSequenceId: Long,
        completedAt: Long,
        pay: Double,
        tip: Double?,
        cashTip: Double?,
        storeName: String?,
        note: String?,
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    /** Removes a delivery the dasher added by hand. */
    val onRemoveDelivery: (deliveryEventSequenceId: Long) -> Unit = {},
    /** Undoes "It was unassigned" for one offer. */
    val onUndoMarkedUnassigned: (offerEventSequenceId: Long) -> Unit = {},
    /** #1599: Confirm on the receipt sheet, the plan exactly as it showed. */
    val onConfirmReceiptMatch: (commandId: String, plan: ReceiptMatchPlan) -> Unit = { _, _ -> },
    /** #1599: Undo match on the job sheet. */
    val onUndoReceiptMatch: (matchCommandId: String) -> Unit = {},
)

/**
 * The page's stateless body over [uiState]; tests and screenshots compose this. The store search ([searchOpen] and
 * [query] seed it for tests) is UI state saved across recreation: open, the title is the search field, Back closes
 * it first and restores the full timeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShiftPageContent(
    uiState: SessionDetailUiState,
    landingRow: String?,
    actions: ShiftPageActions,
    searchOpen: Boolean = false,
    query: String = "",
) {
    val detail = uiState.detail
    var searching by rememberSaveable { mutableStateOf(searchOpen) }
    var text by rememberSaveable { mutableStateOf(query) }
    val closeSearch = {
        searching = false
        text = ""
    }
    BackHandler(enabled = searching, onBack = closeSearch)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        SearchField(text, { text = it }, stringResource(R.string.search_this_shift))
                    } else {
                        Text(detail?.let { shiftDay(it) } ?: stringResource(R.string.session_detail_title))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (searching) closeSearch() else actions.onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_content_desc_back))
                    }
                },
                actions = {
                    if (!searching && detail != null && uiState.figuresShown()) {
                        SearchButton(stringResource(R.string.search_this_shift)) { searching = true }
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when {
            uiState.loading -> ShiftPagePlaceholder(modifier)
            uiState.failed -> CouldNotLoad(
                stringResource(R.string.shift_page_could_not_load_what), actions.onRetry, modifier.padding(horizontal = 16.dp),
            )
            // A rebuild wipes the shift's row first, so a missing shift then is not a missing shift.
            detail == null && uiState.records == RecordsStatus.REBUILDING -> RebuildingRecords(modifier)
            detail == null -> Box(modifier, contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.session_detail_not_found), style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.text2)
            }
            else -> ShiftPageBody(uiState, detail, landingRow, actions, modifier, query = if (searching) text else "")
        }
    }
}

/** How long a landed passed offer's highlight takes to fade. */
private const val LANDING_HIGHLIGHT_MS = 2_000

/** "Wed, Oct 28": the shift's start day, in the device zone. */
internal fun shiftDay(detail: SessionDetail): String =
    formatWeekdayMonthDay(Instant.ofEpochMilli(detail.session.startedAt).atZone(ZoneId.systemDefault()).toLocalDate())

/** One `LazyColumn` row of the page; [key] is stable across emissions. */
private sealed interface PageItem {
    val key: String

    data object Summary : PageItem { override val key = "summary" }
    data object Check : PageItem { override val key = "check" }
    data object Checks : PageItem { override val key = "checks" }
    data object Corrections : PageItem { override val key = "corrections" }
    data object Empty : PageItem { override val key = "empty" }
    data object SearchScope : PageItem { override val key = "search-scope" }
    data object NoMatch : PageItem { override val key = "search-none" }
    data object SearchAll : PageItem { override val key = "search-all" }
    data class Card(val card: JobCard) : PageItem { override val key get() = card.key }
    data class Run(val run: PassedRun, val expanded: Boolean) : PageItem { override val key get() = run.key }
    data class Passed(val offer: PassedOffer) : PageItem { override val key get() = passedRowKey(offer.eventSequenceId) }
}

/**
 * The page's rows: the summary, then (unless held back) the check, the corrections and the timeline. While a search
 * has text ([hits] non-null), the timeline is its matching rows under the scope label, or the no-match line, and the
 * last row searches every shift.
 */
private fun pageItems(
    state: SessionDetailUiState,
    check: MoneyCheck?,
    expanded: Set<String>,
    hits: List<SearchRow>?,
): List<PageItem> = buildList {
    add(PageItem.Summary)
    if (!state.figuresShown()) return@buildList
    if (check != null) add(PageItem.Check)
    if (hasCheckLines(state.checks)) add(PageItem.Checks)
    add(PageItem.Corrections)
    if (hits != null) {
        add(PageItem.SearchScope)
        if (hits.isEmpty()) add(PageItem.NoMatch)
        for (hit in hits) {
            when (hit) {
                is SearchRow.Job -> add(PageItem.Card(hit.card))
                is SearchRow.Passed -> add(PageItem.Passed(hit.offer))
            }
        }
        add(PageItem.SearchAll)
        return@buildList
    }
    if (state.timeline.isEmpty()) add(PageItem.Empty)
    for (item in state.timeline) {
        when (item) {
            is JobCard -> add(PageItem.Card(item))
            is PassedRun -> {
                val open = item.key in expanded
                add(PageItem.Run(item, open))
                if (open) item.offers.forEach { add(PageItem.Passed(it)) }
            }
        }
    }
}

/** The open runs' keys, saved across recomposition and process recreation. */
private val expandedSaver = listSaver<MutableState<Set<String>>, String>(
    save = { it.value.toList() },
    restore = { mutableStateOf(it.toSet()) },
)

@Composable
private fun ShiftPageBody(
    state: SessionDetailUiState,
    detail: SessionDetail,
    landingRow: String?,
    actions: ShiftPageActions,
    modifier: Modifier,
    query: String,
) {
    var expanded by rememberSaveable(saver = expandedSaver) { mutableStateOf(emptySet()) }
    var sheetKey by rememberSaveable { mutableStateOf<String?>(null) }
    var receiptSheet by rememberSaveable { mutableStateOf<String?>(null) }
    var landed by rememberSaveable { mutableStateOf(false) }
    var pendingLanding by remember { mutableStateOf<RowLanding?>(null) }
    var highlightKey by remember { mutableStateOf<String?>(null) }
    val highlight = remember { Animatable(0f) }
    val dialogs = remember(detail.session.sessionId) { ShiftPageDialogState() }
    val listState = rememberLazyListState()

    val shown = state.figuresShown()
    val check = remember(detail) { MoneyCheck.of(detail) }
    val money = remember(detail) { ShiftMoney.of(detail) }
    val search = StoreQuery.of(query)
    val hits = remember(state.timeline, state.offers, state.deliveries, search) {
        search?.let { TimelineSearch.filter(state.timeline, state.offers, state.deliveries, it) }
    }
    val rows = pageItems(state, check, expanded, hits)

    // A row link lands once, after the timeline has loaded: open its run and its sheet, then scroll to it. A receipt
    // check's link (#1599) waits for its receipt instead, opens its sheet while the receipt is open, and scrolls to the
    // checks.
    LaunchedEffect(landingRow, state.timeline, shown, state.receipts) {
        if (landed || landingRow == null || !shown) return@LaunchedEffect
        if (landingRow.startsWith(MATCH_KEY_PREFIX)) {
            val id = landingRow.removePrefix(MATCH_KEY_PREFIX)
            val item = state.receipts.items.firstOrNull { it.receipt.evidenceId == id } ?: return@LaunchedEffect
            landed = true
            if (item.status == ReceiptStatus.OPEN) receiptSheet = id
            pendingLanding = RowLanding(PageItem.Checks.key, null, null)
            return@LaunchedEffect
        }
        if (state.timeline.isEmpty()) return@LaunchedEffect
        landed = true
        val landing = RowLanding.of(state.timeline, landingRow) ?: return@LaunchedEffect
        landing.expandRun?.let { expanded = expanded + it }
        landing.openJob?.let { sheetKey = it }
        pendingLanding = landing
    }
    LaunchedEffect(pendingLanding, rows) {
        val landing = pendingLanding ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.key == landing.scrollKey }
        if (index >= 0) {
            listState.scrollToItem(index)
            pendingLanding = null
            // A passed offer's thin row is highlighted once, after the scroll; a job's sheet needs no highlight.
            if (landing.expandRun != null) highlightKey = landing.scrollKey
        }
    }
    LaunchedEffect(highlightKey) {
        if (highlightKey == null) return@LaunchedEffect
        highlight.snapTo(1f)
        highlight.animateTo(0f, tween(LANDING_HIGHLIGHT_MS))
        highlightKey = null
    }
    val highlightTint = MaterialTheme.colorScheme.secondaryContainer

    Column(modifier) {
        UpdatingRecordsLine(updating = state.records == RecordsStatus.UPDATING)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.key }, contentType = { it::class }) { item ->
                when (item) {
                    PageItem.Summary -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ShiftAppTimesLine(detail)
                        if (shown) {
                            EarnedBlock(money)
                            ShiftFigures(detail, money)
                        } else {
                            RebuildingRecords()
                        }
                    }
                    PageItem.Check -> check?.let { MoneyCheckBlock(it, detail.session.platform.displayName) }
                    PageItem.Checks -> ShiftChecksBlock(state.checks, onMatch = { receiptSheet = it })
                    PageItem.Corrections -> CorrectionButtons(
                        ended = detail.session.endedAt != null,
                        onAddMissed = { dialogs.showAdd = true },
                        onSetTotal = { dialogs.openReport(detail) },
                        onCorrectMiles = { dialogs.openMiles(detail) },
                    )
                    PageItem.Empty -> Text(
                        stringResource(R.string.shift_page_timeline_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.text2,
                    )
                    PageItem.SearchScope -> SearchScopeLabel(stringResource(R.string.search_scope_this))
                    PageItem.NoMatch -> SearchNoMatch(query)
                    PageItem.SearchAll -> TextButton(onClick = { actions.onSearchAll(query.trim()) }) {
                        Text(stringResource(R.string.search_all_for_format, query.trim()))
                    }
                    is PageItem.Card -> JobCardRow(
                        item.card,
                        onOpen = { sheetKey = item.card.key },
                        onUndoMarked = { item.card.markedOfferSeqs.forEach(actions.onUndoMarkedUnassigned) },
                    )
                    is PageItem.Run -> PassedRunRow(item.run, item.expanded, onToggle = {
                        expanded = if (item.expanded) expanded - item.run.key else expanded + item.run.key
                    })
                    is PageItem.Passed -> PassedOfferRow(
                        item.offer,
                        if (item.key == highlightKey) {
                            Modifier.drawBehind { drawRect(highlightTint.copy(alpha = highlight.value)) }
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }

    if (!shown) return
    val sheetCard = sheetKey?.let { key -> state.timeline.firstOrNull { it.key == key } as? JobCard }
    val question = sheetCard?.takeIf { it.state == JobState.NO_DELIVERY }?.openOffers?.firstOrNull()
    if (question != null) {
        OfferQuestionSheet(
            offer = question,
            onUnassigned = {
                actions.onMarkOfferUnassigned(question.eventSequenceId)
                sheetKey = null
            },
            onAddMissed = {
                dialogs.addFromOffer = question
                sheetKey = null
            },
            onDismiss = { sheetKey = null },
        )
    } else if (sheetCard != null) {
        ShiftJobSheet(
            card = sheetCard,
            day = shiftDay(detail),
            // The correction opens over the sheet; the sheet re-reads the delivery once the fix folds.
            onFix = { seq -> dialogs.adjustTarget = detail.deliveries.firstOrNull { it.eventSequenceId == seq } },
            onDismiss = { sheetKey = null },
            undoMatchFor = { seq -> state.receipts.undoByDelivery[seq] },
            onUndoMatch = actions.onUndoReceiptMatch,
        )
    }
    // One sheet at a time; a receipt that leaves OPEN (matched, or an undo later) never re-opens its sheet by itself.
    // The effect reads the live selection, so the landing that set it in the same frame is not undone.
    val openReceipt = receiptSheet?.let { id -> state.receipts.open.firstOrNull { it.receipt.evidenceId == id } }
    LaunchedEffect(receiptSheet, state.receipts) {
        val id = receiptSheet ?: return@LaunchedEffect
        if (state.receipts.open.none { it.receipt.evidenceId == id }) receiptSheet = null
    }
    if (openReceipt != null && sheetCard == null) {
        ReceiptMatchSheet(
            receipt = openReceipt.receipt,
            receipts = state.receipts,
            appTotal = check?.appTotal,
            appName = detail.session.platform.displayName,
            onConfirm = { cmd, plan ->
                actions.onConfirmReceiptMatch(cmd, plan)
                receiptSheet = null
            },
            onDismiss = { receiptSheet = null },
        )
    }
    ShiftPageDialogs(detail, dialogs, actions)
}
