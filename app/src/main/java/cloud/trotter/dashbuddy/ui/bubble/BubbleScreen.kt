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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import cloud.trotter.dashbuddy.domain.model.chat.ChatMessage
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.ui.formatters.getIconResId
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BubbleScreen(
    viewModel: BubbleViewModel = hiltViewModel()
) {
    // 1. Swap Entity for Pure Domain Model!
    val messages by viewModel.messages.collectAsState()
    var showFullChat by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showFullChat) "Chat History" else "DashBuddy HUD") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(onClick = { viewModel.sendTestMessage() }) {
                        Text("MSG", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { showFullChat = !showFullChat }) {
                        Icon(
                            imageVector = if (showFullChat) Icons.Default.Close else Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "Toggle Chat",
                            tint = MaterialTheme.colorScheme.onSurface
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
                DashboardView(messages, viewModel) { showFullChat = true }
            }
        }
    }
}

@Composable
fun DashboardView(
    messages: List<ChatMessage>, // 2. Pure Domain Model
    viewModel: BubbleViewModel,
    onOpenChat: () -> Unit
) {
    val miles by viewModel.sessionMiles.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        Text("Current Trip", style = MaterialTheme.typography.titleMedium)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .height(100.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "%.2f".format(miles),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "SESSION MILES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }
        }

        Text(
            "Recent Messages",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp)
        )

        val recentMessages = messages.takeLast(3)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clickable { onOpenChat() },
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (recentMessages.isEmpty()) {
                    Text(
                        "No messages yet.",
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                } else {
                    recentMessages.forEach { msg ->
                        val preview = getPreviewText(msg.text)

                        Row(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                // 3. Use displayName from the persona!
                                text = "${msg.persona.displayName}: ",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = preview,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Text(
                    text = "Tap to view all...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 8.dp)
                )
            }
        }
    }
}

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

    // 4. Clean Kotlin type-checking! No messy string ID checks.
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
                    // 5. Use displayName from the sealed class
                    text = message.persona.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = nameColor,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.width(6.dp))

                Icon(
                    // 6. SOLVED: Dynamically grab the correct icon!
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
            val spanned =
                Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
            textView.text = spanned
        }
    )
}

fun getPreviewText(html: String): String {
    if (html.isBlank()) return ""

    val spacedHtml = html
        .replace("<br>", " // ")
        .replace("<br/>", " // ")
        .replace("</p>", " // ")
        .replace("</div>", " // ")

    val spanned =
        Html.fromHtml(spacedHtml, Html.FROM_HTML_MODE_COMPACT)

    return spanned.toString().trim()
}