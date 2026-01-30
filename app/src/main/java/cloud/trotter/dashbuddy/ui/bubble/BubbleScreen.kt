package cloud.trotter.dashbuddy.ui.bubble

import android.text.Html
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import cloud.trotter.dashbuddy.data.chat.ChatMessageEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BubbleScreen(
    viewModel: BubbleViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    var showFullChat by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showFullChat) "Chat History" else "DashBuddy HUD") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface, // Cleaner look
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
                DashboardView(messages) { showFullChat = true }
            }
        }
    }
}

@Composable
fun DashboardView(messages: List<ChatMessageEntity>, onOpenChat: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        Text("Current Status", style = MaterialTheme.typography.titleMedium)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .height(80.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    "Waiting for Offer...",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Text(
            "Recent Messages",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp)
        )

        // Show last 3 messages
        val recentMessages = messages.takeLast(3)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clickable { onOpenChat() },
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface) // Standard surface
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (recentMessages.isEmpty()) {
                    Text(
                        "No messages yet.",
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                } else {
                    recentMessages.forEach { msg ->
                        // HELPER: Convert HTML -> "Good Offer // McDonalds"
                        val preview = getPreviewText(msg.messageText)

                        Row(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = "${msg.senderName}: ",
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
fun FullChatView(messages: List<ChatMessageEntity>) {
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
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        items(messages) { msg ->
            ChatBubble(msg)
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessageEntity) {
    // 1. Determine Sender Type
    val isSystem = message.senderId == "bot_dispatcher"

    // 2. Choose Colors (Dark Mode Friendly)
    // System: Secondary Container (Muted Teal/Blue)
    // User/Other: Surface Variant (Dark Gray)
    val bubbleColor = if (isSystem) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = if (isSystem) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .background(
                    color = bubbleColor,
                    shape = MaterialTheme.shapes.medium
                )
                .padding(12.dp)
                .fillMaxWidth(0.85f) // Limit width slightly
        ) {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.7f) // Dim sender name
            )
            // Use the rich HTML renderer we built
            HtmlText(
                html = message.messageText,
                color = textColor
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
                // Important: Enable link clicking if your HTML has links
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

/**
 * Helper to flatten HTML into a single line preview.
 * Replaces <br> and <p> with " // ".
 */
fun getPreviewText(html: String): String {
    if (html.isBlank()) return ""

    // 1. Quick replace of common block tags with a separator
    val spacedHtml = html
        .replace("<br>", " // ")
        .replace("<br/>", " // ")
        .replace("</p>", " // ")
        .replace("</div>", " // ")

    // 2. Strip all other tags
    val spanned =
        Html.fromHtml(spacedHtml, Html.FROM_HTML_MODE_COMPACT)

    // 3. Clean up double separators or trailing whitespace
    return spanned.toString().trim()
}