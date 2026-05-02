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
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.TaskPhase
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
    appState: AppState,
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
private fun StatusBadgeTitle(appState: AppState) {
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
    appState: AppState,
    earnings: Double,
    miles: Double,
    lastSessionSummary: SessionSummary?
) {
    val dd = appState.regions.platforms[Platform.DoorDash]
    val isActive = dd?.mode == Mode.Online || dd?.mode == Mode.Paused

    val displayEarnings: Double?
    val displayMiles: Double?
    val dimmed: Boolean

    when {
        isActive -> {
            displayEarnings = earnings
            displayMiles = miles
            dimmed = false
        }
        dd?.mode == Mode.Offline && lastSessionSummary != null -> {
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
private fun statusBadge(appState: AppState): Pair<String, Color> {
    val green = Color(0xFF4CAF50)
    val amber = Color(0xFFFFC107)
    val blue = Color(0xFF2196F3)
    val grey = MaterialTheme.colorScheme.outline

    val dd = appState.regions.platforms[Platform.DoorDash]
    val flow = appState.regions.flow.flow

    // Mode-driven badges
    if (dd?.mode == Mode.Paused) return "PAUSED" to amber
    if (dd == null || dd.mode == Mode.Offline) {
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

// ---------------------------------------------------------------------------
// State-aware mode card
// ---------------------------------------------------------------------------

@Composable
fun ModeCard(appState: AppState, lastSessionSummary: SessionSummary? = null, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            val dd = appState.regions.platforms[Platform.DoorDash]
            val flow = appState.regions.flow

            when {
                // Paused
                dd?.mode == Mode.Paused -> ModePaused()

                // Offline
                dd == null || dd.mode == Mode.Offline -> {
                    when (flow.flow) {
                        Flow.SessionEnded -> ModePostDash(dd)
                        else -> ModeIdle(lastSessionSummary)
                    }
                }

                // Online — flow-driven
                else -> when (flow.flow) {
                    Flow.Idle -> ModeAwaiting(dd)
                    Flow.OfferPresented -> ModeOffer(flow)
                    Flow.TaskPickupNavigation,
                    Flow.TaskPickupArrived -> ModePickup(dd)
                    Flow.TaskDropoffNavigation,
                    Flow.TaskDropoffArrived -> ModeDelivery(dd)
                    Flow.PostTask -> ModePostDelivery(dd)
                    Flow.SessionEnded -> ModePostDash(dd)
                }
            }
        }
    }
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
private fun ModeAwaiting(dd: cloud.trotter.dashbuddy.domain.state.PlatformRegion) {
    val now by rememberNow()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ModePrimaryText("Waiting for orders")
    }
}

@Composable
private fun ModeOffer(flow: cloud.trotter.dashbuddy.domain.state.FlowRegion) {
    val offer = flow.pendingOffer
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (offer != null) {
            val merchantName = offer.offerFields.parsedOffer.orders.joinToString(", ") { it.storeName }
            if (merchantName.isNotBlank()) ModePrimaryText(merchantName)
            offer.offerFields.parsedOffer.payAmount?.let { amount ->
                Text(
                    text = "$${String.format("%.2f", amount)}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            ModePrimaryText("Offer incoming…")
        }
    }
}

@Composable
private fun ModePickup(dd: cloud.trotter.dashbuddy.domain.state.PlatformRegion) {
    val now by rememberNow()
    val task = dd.activeTask

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ModePrimaryText(task?.storeName ?: "Heading to store")

        val arrivedAt = task?.arrivedAt
        if (arrivedAt != null) {
            // At store — show wait timer
            val waitMillis = now - arrivedAt
            val label = if (task.activity == "shopping") "Shopping" else "Waiting"
            ModeRow(label = label, value = formatDuration(waitMillis))
        } else {
            ModeRow(label = "Status", value = "Heading to store")
        }

        task?.itemCount?.let { ModeRow(label = "Items", value = it.toString()) }
        task?.redCardTotal?.let {
            ModeRow(label = "Red Card", value = "$${String.format("%.2f", it)}")
        }
    }
}

@Composable
private fun ModeDelivery(dd: cloud.trotter.dashbuddy.domain.state.PlatformRegion) {
    val now by rememberNow()
    val task = dd.activeTask

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val storeName = dd.activeJob?.offerStoreHint?.firstOrNull()
        storeName?.let { ModePrimaryText(it) } ?: ModePrimaryText("Delivering…")

        task?.customerNameHash?.take(6)?.let {
            ModeRow(label = "Customer", value = "Cust. $it")
        }

        // Wait timer — only shown once arrived
        task?.arrivedAt?.let { arrivedAt ->
            val waitMillis = now - arrivedAt
            ModeRow(label = "At door", value = formatDuration(waitMillis))
        }
    }
}

@Composable
private fun ModePostDelivery(dd: cloud.trotter.dashbuddy.domain.state.PlatformRegion) {
    val session = dd.session
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (session != null && session.runningEarnings > 0) {
            Text(
                text = "+$${String.format("%.2f", session.runningEarnings)}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4CAF50)
            )
        } else {
            ModePrimaryText("Delivery complete")
        }
    }
}

@Composable
private fun ModePaused() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ModePrimaryText("Dash paused")
    }
}

@Composable
private fun ModePostDash(dd: cloud.trotter.dashbuddy.domain.state.PlatformRegion?) {
    val session = dd?.session
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val earnings = session?.runningEarnings ?: 0.0
        Text(
            text = "$${String.format("%.2f", earnings)}",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        session?.let {
            val durationMillis = System.currentTimeMillis() - it.startedAt
            ModeRow(label = "Duration", value = formatDuration(durationMillis))
        }
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
