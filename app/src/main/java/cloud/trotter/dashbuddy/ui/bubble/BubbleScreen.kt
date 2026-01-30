package cloud.trotter.dashbuddy.ui.bubble

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    // Test Button to generate messages
                    IconButton(onClick = { viewModel.sendTestMessage() }) {
                        Text(
                            "MSG",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    // Toggle Full Chat
                    IconButton(onClick = { showFullChat = !showFullChat }) {
                        Icon(
                            imageVector = if (showFullChat) Icons.Default.Close else Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "Toggle Chat"
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
                .height(100.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text("Waiting for Offer...", style = MaterialTheme.typography.headlineSmall)
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
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                if (recentMessages.isEmpty()) {
                    Text(
                        "No messages yet.",
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                } else {
                    recentMessages.forEach { msg ->
                        Text(
                            text = "${msg.senderName}: ${msg.messageText}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = "Tap to view all...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun FullChatView(messages: List<ChatMessageEntity>) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new message arrives
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
    val isSystem = message.senderId == "bot_dispatcher" // Or use your Persona IDs

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isSystem) Arrangement.Start else Arrangement.Start
        // Note: Usually User is Right, Bot is Left. Currently we only have Bots.
    ) {
        Column(
            modifier = Modifier
                .background(
                    color = if (isSystem) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                )
                .padding(8.dp)
                .fillMaxWidth(0.8f) // Limit width
        ) {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = message.messageText,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}