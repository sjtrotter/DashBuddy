package cloud.trotter.dashbuddy.ui.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.data.chat.ChatRepository
import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BubbleManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notificationManager: NotificationManager,
    private val chatRepository: ChatRepository
) {
    private val channelId = "bubble_channel"
    private val shortcutId = "DashBuddy_Bubble_Shortcut"

    // We expose the active ID so the UI knows what to observe
    private val _activeDashId = MutableStateFlow<String?>(null)
    val activeDashId = _activeDashId.asStateFlow()

    init {
        createChannel()
        pushDynamicShortcut()
    }

    fun startDash(dashId: String) {
        _activeDashId.value = dashId
        postMessage("Dash Started", ChatPersona.System)
    }

    fun endDash() {
        _activeDashId.value = null
        postMessage("Dash Ended", ChatPersona.System)
    }

    fun postMessage(
        text: CharSequence,
        persona: ChatPersona = ChatPersona.Dispatcher,
        expand: Boolean = false
    ) {
        Timber.tag("Chat").i("[${persona.name}]: $text")

        // 1. Save to DB
        chatRepository.saveMessage(_activeDashId.value, persona, text)

        // 2. Post Notification
        showNotification(text, persona, expand)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            channelId, "DashBuddy Stream", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Live updates from your Dash"
            setAllowBubbles(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun pushDynamicShortcut() {
        val activityIntent = Intent(context, BubbleActivity::class.java).apply {
            action = Intent.ACTION_VIEW
        }
        val person = Person.Builder()
            .setName(ChatPersona.Dispatcher.name)
            .setIcon(IconCompat.createWithResource(context, ChatPersona.Dispatcher.iconResId))
            .setKey(ChatPersona.Dispatcher.id)
            .build()
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setLongLived(true)
            .setIntent(activityIntent)
            .setShortLabel("DashBuddy")
            .setIcon(IconCompat.createWithResource(context, R.drawable.bag_red_idle))
            .setPerson(person)
            .setCategories(setOf(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION))
            .setLocusId(LocusIdCompat(shortcutId))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    private fun showNotification(text: CharSequence, persona: ChatPersona, expand: Boolean) {
        val senderPerson = Person.Builder()
            .setName(persona.name)
            .setKey(persona.id)
            .setIcon(IconCompat.createWithResource(context, persona.iconResId))
//            .setBot(persona is ChatPersona.Dispatcher)
            .build()

        val intentWithAction = Intent(context, BubbleActivity::class.java).apply {
            action = Intent.ACTION_VIEW
        }
        val bubbleIntent = PendingIntent.getActivity(
            context, 0, intentWithAction,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, null
        )

        val bubbleMetadata = NotificationCompat.BubbleMetadata.Builder(
            bubbleIntent,
            IconCompat.createWithResource(context, persona.iconResId)
        )
            .setDesiredHeight(600)
            .setAutoExpandBubble(expand)
            .setSuppressNotification(expand)
            .build()

        val mainAppIntent =
            context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        val contentIntent = PendingIntent.getActivity(
            context, 1, mainAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val style = NotificationCompat.MessagingStyle(senderPerson)
            .setConversationTitle("Current Dash")
            .setGroupConversation(true)
            .addMessage(text, System.currentTimeMillis(), senderPerson)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.bag_red_idle)
            .setStyle(style)
            .setBubbleMetadata(bubbleMetadata)
            .setContentIntent(contentIntent)
            .setShortcutId(shortcutId)
            .setLocusId(LocusIdCompat(shortcutId))
            .addPerson(senderPerson)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()

        notificationManager.notify(1, notification)
    }
}