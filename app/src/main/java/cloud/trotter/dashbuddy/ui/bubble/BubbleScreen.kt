package cloud.trotter.dashbuddy.ui.bubble

import android.text.Html
import android.text.format.DateFormat
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.Chat
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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
import cloud.trotter.dashbuddy.domain.model.chat.ChatMessage
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.model.order.PickupStatus
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.ui.formatters.getIconResId
import java.util.Date
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BubbleScreen(
    viewModel: BubbleViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val appState by viewModel.appState.collectAsState()
    val sessionMiles by viewModel.sessionMiles.collectAsState()
    val sessionEarnings by viewModel.sessionEarnings.collectAsState()
    val lastSessionSummary by viewModel.lastSessionSummary.collectAsState()
    var showFullChat by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (showFullChat) {
                        Text("Chat History")
                    } else {
                        StatusBadgeTitle(appState = appState)
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
                            appState = appState,
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
                    appState = appState,
                    messages = messages,
                    lastSessionSummary = lastSessionSummary,
                    onOpenChat = { showFullChat = true }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Dashboard layout
// ---------------------------------------------------------------------------

@Composable
fun DashboardView(
    appState: AppStateV2,
    messages: List<ChatMessage>,
    lastSessionSummary: SessionSummary?,
    onOpenChat: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ModeCard(appState = appState, lastSessionSummary = lastSessionSummary, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.weight(1f))
        LatestMessageTicker(messages = messages, onClick = onOpenChat)
    }
}

// ---------------------------------------------------------------------------
// TopAppBar title — status badge only (left side)
// ---------------------------------------------------------------------------

@Composable
private fun StatusBadgeTitle(appState: AppStateV2) {
    val (badgeText, badgeColor) = statusBadge(appState)
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

// ---------------------------------------------------------------------------
// TopAppBar actions — session earnings + miles (right side, active dashes only)
// ---------------------------------------------------------------------------

@Composable
private fun SessionMetricsActions(
    appState: AppStateV2,
    earnings: Double,
    miles: Double,
    lastSessionSummary: SessionSummary?
) {
    val isActive = appState !is AppStateV2.IdleOffline &&
            appState !is AppStateV2.Initializing &&
            appState !is AppStateV2.PostDash

    val displayEarnings: Double?
    val displayMiles: Double?
    val dimmed: Boolean

    when {
        isActive -> {
            displayEarnings = earnings
            displayMiles = miles
            dimmed = false
        }
        appState is AppStateV2.IdleOffline && lastSessionSummary != null -> {
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
            text = "$${String.format("%.2f", displayEarnings)}",
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
            text = "${"%.1f".format(displayMiles)} mi",
            style = MaterialTheme.typography.titleSmall,
            color = textColor
        )
    }
}

@Composable
private fun statusBadge(appState: AppStateV2): Pair<String, Color> {
    val green = Color(0xFF4CAF50)
    val amber = Color(0xFFFFC107)
    val blue = Color(0xFF2196F3)
    val grey = MaterialTheme.colorScheme.outline

    return when (appState) {
        is AppStateV2.Initializing -> "STARTING" to grey
        is AppStateV2.IdleOffline -> "OFFLINE" to grey
        is AppStateV2.AwaitingOffer -> "WAITING" to green
        is AppStateV2.OfferPresented -> "OFFER" to blue
        is AppStateV2.OnPickup -> "PICKUP" to green
        is AppStateV2.OnDelivery -> "DELIVERING" to green
        is AppStateV2.PostDelivery -> "DELIVERED" to green
        is AppStateV2.DashPaused -> "PAUSED" to amber
        is AppStateV2.PausedOrInterrupted -> "PAUSED" to amber
        is AppStateV2.PostDash -> "DONE" to grey
    }
}

// ---------------------------------------------------------------------------
// State-aware mode card
// ---------------------------------------------------------------------------

@Composable
fun ModeCard(appState: AppStateV2, lastSessionSummary: SessionSummary? = null, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            when (appState) {
                is AppStateV2.Initializing -> ModeInitializing()
                is AppStateV2.IdleOffline -> ModeIdle(lastSessionSummary)
                is AppStateV2.AwaitingOffer -> ModeAwaiting(appState)
                is AppStateV2.OfferPresented -> ModeOffer(appState)
                is AppStateV2.OnPickup -> ModePickup(appState)
                is AppStateV2.OnDelivery -> ModeDelivery(appState)
                is AppStateV2.PostDelivery -> ModePostDelivery(appState)
                is AppStateV2.DashPaused -> ModePaused(appState)
                is AppStateV2.PausedOrInterrupted -> ModePausedOrInterrupted(appState)
                is AppStateV2.PostDash -> ModePostDash(appState)
            }
        }
    }
}

@Composable
private fun ModeInitializing() {
    ModeRow(label = "Status", value = "Starting up…")
}

@Composable
private fun ModeIdle(lastSessionSummary: SessionSummary?) {
    if (lastSessionSummary != null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Last dash",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = "$${String.format("%.2f", lastSessionSummary.earnings)}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            ModeRow(label = "Miles", value = "${"%.1f".format(lastSessionSummary.miles)} mi")
            ModeRow(label = "Duration", value = formatDuration(lastSessionSummary.durationMillis))
            ModeRow(label = "Acceptance", value = lastSessionSummary.acceptanceRate)
        }
    } else {
        ModeRow(label = "Status", value = "Offline — not dashing")
    }
}

@Composable
private fun ModeAwaiting(state: AppStateV2.AwaitingOffer) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ModePrimaryText("Waiting for orders")
        if (state.isHeadingBackToZone) {
            ModeRow(label = "Heads up", value = "Heading back to zone")
        }
        state.waitTimeEstimate?.let {
            ModeRow(label = "Est. wait", value = it)
        }
    }
}

@Composable
private fun ModeOffer(state: AppStateV2.OfferPresented) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.merchantName?.let { ModePrimaryText(it) }
        state.amount?.let { amount ->
            Text(
                text = "$${String.format("%.2f", amount)}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (state.merchantName == null && state.amount == null) {
            ModePrimaryText("Offer incoming…")
        }
    }
}

@Composable
private fun ModePickup(state: AppStateV2.OnPickup) {
    val now by rememberNow()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ModePrimaryText(state.storeName)

        when (state.status) {
            PickupStatus.NAVIGATING, PickupStatus.UNKNOWN -> {
                ModeRow(label = "Status", value = "Heading to store")
                state.pickupDeadline?.text?.let { ModeRow(label = "Pick up by", value = it) }
                state.itemCount?.let { ModeRow(label = "Items", value = it.toString()) }
            }

            PickupStatus.ARRIVED, PickupStatus.SHOPPING -> {
                val waitMillis = state.arrivedAt?.let { now - it } ?: 0L
                ModeRow(
                    label = if (state.status == PickupStatus.SHOPPING) "Shopping" else "Waiting",
                    value = formatDuration(waitMillis)
                )
                state.pickupDeadline?.text?.let { ModeRow(label = "Pick up by", value = it) }
                state.itemCount?.let { ModeRow(label = "Items", value = it.toString()) }
                state.redCardTotal?.let {
                    ModeRow(label = "Red Card", value = "$${String.format("%.2f", it)}")
                }
            }

            PickupStatus.CONFIRMED -> {
                val elapsed = state.arrivedAt?.let { now - it }
                ModeRow(label = "Status", value = "Order confirmed")
                elapsed?.let { ModeRow(label = "Pickup took", value = formatDuration(it)) }
                state.itemCount?.let { ModeRow(label = "Items", value = it.toString()) }
            }
        }
    }
}

@Composable
private fun ModeDelivery(state: AppStateV2.OnDelivery) {
    val now by rememberNow()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.storeName?.let { ModePrimaryText(it) } ?: ModePrimaryText("Delivering…")

        state.customerNameHash?.take(6)?.let {
            ModeRow(label = "Customer", value = "Cust. $it")
        }

        state.deliveryDeadline?.text?.let {
            ModeRow(label = "Deliver by", value = it)
        }

        // Wait timer — only shown once GPS-confirmed arrival detected
        state.arrivedAt?.let { arrivedAt ->
            val waitMillis = now - arrivedAt
            ModeRow(label = "At door", value = formatDuration(waitMillis))
        }
    }
}

@Composable
private fun ModePostDelivery(state: AppStateV2.PostDelivery) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.parsedPay?.let { pay ->
            if (pay.total > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "+$${String.format("%.2f", pay.total)}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    )
                }
            }
        }
        if (state.merchantNames.isNotBlank() && state.merchantNames != "Delivery") {
            ModeRow(label = "From", value = state.merchantNames)
        }
        if (state.summaryText.isNotBlank() && state.summaryText != "Processing…") {
            Text(
                text = state.summaryText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ModePaused(state: AppStateV2.DashPaused) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ModePrimaryText("Dash paused")
        ModeRow(label = "Paused for", value = formatDuration(state.durationMs))
    }
}

@Composable
private fun ModePausedOrInterrupted(state: AppStateV2.PausedOrInterrupted) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ModePrimaryText("Interrupted")
        ModeRow(
            label = "Was",
            value = state.previousState::class.simpleName?.replace(
                Regex("([A-Z])"), " $1"
            )?.trim() ?: "Unknown"
        )
    }
}

@Composable
private fun ModePostDash(state: AppStateV2.PostDash) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "$${String.format("%.2f", state.totalEarnings)}",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        ModeRow(label = "Duration", value = formatDuration(state.durationMillis))
        ModeRow(label = "Acceptance", value = state.acceptanceRateForSession)
    }
}

// ---------------------------------------------------------------------------
// Latest message ticker — replaces the old Recent Messages card
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
        items(messages) { msg ->
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontSize = 10.sp
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
private fun rememberNow(tickMs: Long = 1000L): State<Long> =
    produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(tickMs)
            value = System.currentTimeMillis()
        }
    }

@Composable
private fun ModePrimaryText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun ModeRow(label: String, value: String) {
    Row {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0s"
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
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
