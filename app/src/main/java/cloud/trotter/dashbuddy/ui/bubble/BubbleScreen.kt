package cloud.trotter.dashbuddy.ui.bubble

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.Html
import android.text.format.DateFormat
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatDuration
import cloud.trotter.dashbuddy.core.designsystem.theme.DashTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import cloud.trotter.dashbuddy.domain.model.cards.CardStack
import cloud.trotter.dashbuddy.domain.model.chat.ChatMessage
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.ui.bubble.cards.FlowCardItem
import cloud.trotter.dashbuddy.ui.formatters.getIconResId
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import java.util.Date
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BubbleScreen(
    viewModel: BubbleViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val appState by viewModel.appState.collectAsStateWithLifecycle()
    val focusedPlatform by viewModel.focusedPlatform.collectAsStateWithLifecycle()
    val focusedRegion by viewModel.focusedRegion.collectAsStateWithLifecycle()
    val sessionMiles by viewModel.sessionMiles.collectAsStateWithLifecycle()
    val sessionEarnings by viewModel.sessionEarnings.collectAsStateWithLifecycle()
    val lastSessionSummary by viewModel.lastSessionSummary.collectAsStateWithLifecycle()
    val cardStack by viewModel.cardStack.collectAsStateWithLifecycle()
    var showFullChat by remember { mutableStateOf(false) }

    // Collapse the bubble to its head after the user acts on an offer.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.collapse.collect { context.findActivity()?.finish() }
    }

    val flow = appState.regions.flow

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (showFullChat) {
                        Text("Chat History")
                    } else {
                        StatusBadgeTitle(
                            region = focusedRegion,
                            flow = flow,
                            platform = focusedPlatform,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    if (showFullChat) {
                        IconButton(onClick = { showFullChat = false }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close chat",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        SessionMetricsActions(
                            region = focusedRegion,
                            earnings = sessionEarnings,
                            miles = sessionMiles,
                            lastSessionSummary = lastSessionSummary
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (showFullChat) {
                FullChatView(messages)
            } else {
                DashboardView(
                    cardStack = cardStack,
                    region = focusedRegion,
                    messages = messages,
                    lastSessionSummary = lastSessionSummary,
                    onOpenChat = { showFullChat = true },
                    onAccept = { viewModel.acceptOffer() },
                    onDecline = { viewModel.declineOffer() },
                )
            }
        }
    }
}

/** Unwrap a Compose [LocalContext] (often a ContextThemeWrapper) to the hosting Activity. */
private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

// ---------------------------------------------------------------------------
// Dashboard layout
// ---------------------------------------------------------------------------

@Composable
fun DashboardView(
    cardStack: CardStack,
    region: PlatformRegion?,
    messages: List<ChatMessage>,
    lastSessionSummary: SessionSummary?,
    onOpenChat: () -> Unit,
    onAccept: () -> Unit = {},
    onDecline: () -> Unit = {},
) {
    // rememberSaveable (#367): expansion state survives rotation/process-restore.
    val expandedIds = rememberSaveable(
        saver = listSaver(
            save = { map -> map.filterValues { it }.keys.toList() },
            restore = { saved -> mutableStateMapOf<String, Boolean>().apply { saved.forEach { put(it, true) } } },
        ),
    ) { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            cardStack.isEmpty && (region == null || region.mode == Mode.Offline) -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        ModeIdle(lastSessionSummary)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
            }
            cardStack.isEmpty -> {
                Text(
                    text = "Waiting for activity…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
            }
            else -> {
                // reverseLayout = true pins the active card to the bottom of
                // the viewport and grows the history upward. Items are
                // declared in reverse chronological order (active first,
                // then completed newest→oldest) so that visually:
                //   top    = oldest completed card
                //   middle = newer completed cards
                //   bottom = active (live) card
                // No autoscroll required — Compose naturally keeps the
                // first-declared item (the active card) at the bottom.
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listState,
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    cardStack.active?.let { live ->
                        item(key = "live:${live.id}") {
                            FlowCardItem(
                                snapshot = live,
                                isActive = true,
                                expanded = true,
                                onToggleExpand = { /* active always expanded */ },
                                onAccept = onAccept,
                                onDecline = onDecline,
                            )
                        }
                    }
                    items(
                        // distinctBy is a last-line crash guard: a duplicate
                        // key here is a fatal Compose exception, so never trust
                        // the upstream list to be unique (FlowCardMapper already
                        // dedups, but the HUD must not crash if a new source of
                        // collisions ever slips through).
                        items = cardStack.completed.distinctBy { it.id }.asReversed(),
                        key = { it.id },
                    ) { snap ->
                        val expanded = expandedIds[snap.id] == true
                        FlowCardItem(
                            snapshot = snap,
                            isActive = false,
                            expanded = expanded,
                            onToggleExpand = { expandedIds[snap.id] = !expanded },
                        )
                    }
                }
            }
        }
        LatestMessageTicker(messages = messages, onClick = onOpenChat)
    }
}

// ---------------------------------------------------------------------------
// TopAppBar title — status badge (left side)
// ---------------------------------------------------------------------------

@Composable
private fun StatusBadgeTitle(region: PlatformRegion?, flow: FlowRegion, platform: Platform?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Platform label — only shown when a platform is active
        platform?.let {
            Text(
                text = platformShortName(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontWeight = FontWeight.Medium,
            )
        }
        val (badgeText, badgeColor) = statusBadge(region, flow.flow)
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = badgeColor.copy(alpha = 0.15f)
        ) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// TopAppBar actions — session earnings + miles (right side, active dashes only)
// ---------------------------------------------------------------------------

@Composable
private fun SessionMetricsActions(
    region: PlatformRegion?,
    earnings: Double,
    miles: Double,
    lastSessionSummary: SessionSummary?
) {
    val isActive = region?.mode == Mode.Online || region?.mode == Mode.Paused

    val displayEarnings: Double?
    val displayMiles: Double?
    val dimmed: Boolean

    when {
        isActive -> {
            displayEarnings = earnings
            displayMiles = miles
            dimmed = false
        }
        region?.mode == Mode.Offline && lastSessionSummary != null -> {
            displayEarnings = lastSessionSummary.earnings
            displayMiles = lastSessionSummary.miles
            dimmed = true
        }
        else -> return
    }

    Row(
        modifier = Modifier.padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val textColor = if (dimmed)
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        else
            MaterialTheme.colorScheme.onSurface

        Text(
            text = Formats.money(displayEarnings),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
        Text(
            text = "  ·  ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
        )
        Text(
            text = "${Formats.decimal(displayMiles)} mi",
            style = MaterialTheme.typography.titleSmall,
            color = textColor
        )
    }
}

@Composable
private fun statusBadge(region: PlatformRegion?, flow: Flow): Pair<String, Color> {
    val c = DashTheme.colors
    val green = c.good
    val amber = c.warn
    val blue = c.stOffer
    val grey = c.neutral

    // Mode-driven badges
    if (region?.mode == Mode.Paused) return "PAUSED" to amber
    if (region == null || region.mode == Mode.Offline) {
        return when (flow) {
            Flow.SessionEnded -> "DONE" to grey
            else -> "OFFLINE" to grey
        }
    }

    // Flow-driven badges (online)
    return when (flow) {
        Flow.Idle -> "WAITING" to green
        Flow.OfferPresented -> "OFFER" to blue
        Flow.TaskPickupNavigation -> "PICKUP" to green
        Flow.TaskPickupArrived -> "AT STORE" to green
        Flow.TaskDropoffNavigation -> "DELIVERING" to green
        Flow.TaskDropoffArrived -> "AT DOOR" to green
        Flow.PostTask -> "DELIVERED" to green
        Flow.SessionEnded -> "DONE" to grey
    }
}

@Composable
private fun ModeIdle(lastSessionSummary: SessionSummary?) {
    if (lastSessionSummary != null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Last session",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = Formats.money(lastSessionSummary.earnings),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            ModeRow(label = "Miles", value = "${Formats.decimal(lastSessionSummary.miles)} mi")
            ModeRow(
                label = "Duration",
                value = formatDuration(lastSessionSummary.endedAt - lastSessionSummary.startedAt),
            )
        }
    } else {
        ModeRow(label = "Status", value = "Offline")
    }
}

// ---------------------------------------------------------------------------
// Latest message ticker
// ---------------------------------------------------------------------------

@Composable
fun LatestMessageTicker(messages: List<ChatMessage>, onClick: () -> Unit) {
    val latest = messages.lastOrNull()

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (latest != null) {
            Icon(
                painter = painterResource(id = latest.persona.getIconResId()),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Color.Unspecified
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${latest.persona.displayName}: ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = getPreviewText(latest.text),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = "No messages yet",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f),
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Open chat",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

// ---------------------------------------------------------------------------
// Full chat view (unchanged)
// ---------------------------------------------------------------------------

@Composable
fun FullChatView(messages: List<ChatMessage>) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Bottom,
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        items(messages, key = { it.id }) { msg ->
            ChatBubble(msg)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val context = LocalContext.current

    val isSystem =
        message.persona is ChatPersona.Dispatcher || message.persona is ChatPersona.System

    val nameColor = if (isSystem) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.primary
    }

    val timeString = remember(message.timestamp) {
        DateFormat.getTimeFormat(context).format(Date(message.timestamp))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Deliberate 12dp chat-bubble radius — between the small/medium
                // shape tokens; revisit if DashShapes grows a chat variant (#406).
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = message.persona.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = nameColor,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.width(6.dp))

                Icon(
                    painter = painterResource(id = message.persona.getIconResId()),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Color.Unspecified
                )

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = timeString,
                    // labelSmall already maps to the micro (10sp) token —
                    // the explicit override duplicated it invisibly (#406).
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            HtmlText(
                html = message.text,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun HtmlText(
    html: String,
    modifier: Modifier = Modifier,
    color: Color
) {
    val androidTextColor = color.toArgb()

    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                setTextColor(androidTextColor)
                textSize = 16f
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
            }
        },
        update = { textView ->
            textView.setTextColor(androidTextColor)
            textView.text = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        }
    )
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

@Composable
private fun ModeRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor
        )
    }
}



private fun platformShortName(platform: Platform): String = when (platform) {
    Platform.DoorDash -> "DD"
    Platform.Uber -> "Uber"
    Platform.Instacart -> "IC"
    Platform.WalmartSpark -> "Spark"
    Platform.Unknown -> ""
}

fun getPreviewText(html: String): String {
    if (html.isBlank()) return ""

    val spacedHtml = html
        .replace("<br>", " // ")
        .replace("<br/>", " // ")
        .replace("</p>", " // ")
        .replace("</div>", " // ")

    val spanned = Html.fromHtml(spacedHtml, Html.FROM_HTML_MODE_COMPACT)
    return spanned.toString().trim()
}
