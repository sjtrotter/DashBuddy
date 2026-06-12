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
import cloud.trotter.dashbuddy.core.data.chat.ChatRepository
import cloud.trotter.dashbuddy.state.effects.OfferActionReceiver
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.ui.formatters.getIconResId // <-- Your new UI Formatter!
import cloud.trotter.dashbuddy.ui.formatters.notificationPersona
import cloud.trotter.dashbuddy.ui.formatters.toNotificationSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BubbleManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notificationManager: NotificationManager,
    private val chatRepository: ChatRepository
) {

    companion object {
        /** The single bubble/chat notification id. */
        const val BUBBLE_NOTIFICATION_ID = 1

        /** Bubble expanded-view height (dp). */
        const val BUBBLE_DESIRED_HEIGHT_DP = 600

        /** PendingIntent request codes for the offer notification actions (#367). */
        const val REQUEST_CODE_ACCEPT = 10
        const val REQUEST_CODE_DECLINE = 11
    }
    // 1. ADDED: CoroutineScope for the suspend database calls
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val channelId = "bubble_channel"
    private val shortcutId = "DashBuddy_Bubble_Shortcut"

    private val _activeDashId = MutableStateFlow<String?>(null)
    val activeDashId = _activeDashId.asStateFlow()

    init {
        createChannel()
        pushDynamicShortcut()
    }

    fun startSession(sessionId: String, platformName: String) {
        _activeDashId.value = sessionId
        val verb = sessionVerb(platformName)
        postMessage("Started $verb!", ChatPersona.Dispatcher)
    }

    fun endSession(platformName: String? = null) {
        _activeDashId.value = null
        val verb = sessionVerb(platformName)
        postMessage("Done $verb!", ChatPersona.Dispatcher)
    }

    private fun sessionVerb(platformName: String?): String = when {
        platformName.equals("uber", ignoreCase = true) -> "Ubering"
        platformName.equals("doordash", ignoreCase = true) -> "Dashing"
        platformName != null -> "driving for $platformName"
        else -> "driving"
    }

    fun postMessage(
        text: CharSequence,
        persona: ChatPersona = ChatPersona.Dispatcher,
        expand: Boolean = false
    ) {
        // Updated to use displayName
        Timber.tag("Chat").i("[${persona.displayName}]: $text")

        // 2. UPDATED: Launched in a coroutine because the Repository is pure suspend now!
        scope.launch {
            chatRepository.saveMessage(_activeDashId.value, text.toString(), persona)
        }

        // Post Notification
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
            .setName(ChatPersona.Dispatcher.displayName)
            .setIcon(IconCompat.createWithResource(context, ChatPersona.Dispatcher.getIconResId()))
            .setKey(ChatPersona.Dispatcher.id)
            .build()

        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setLongLived(true)
            .setIntent(activityIntent)
            .setShortLabel("DashBuddy")
            .setIcon(
                IconCompat.createWithResource(
                    context,
                    R.drawable.bag_red_idle
                )
            ) // Main app icon for the shortcut
            .setPerson(person)
            .setCategories(setOf(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION))
            .setLocusId(LocusIdCompat(shortcutId))
            .build()

        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    private fun showNotification(
        text: CharSequence,
        persona: ChatPersona,
        expand: Boolean,
        offerActionable: Boolean = false,
    ) {

        val senderPerson = Person.Builder()
            .setName(persona.displayName)
            .setKey(persona.id)
            .setIcon(IconCompat.createWithResource(context, persona.getIconResId()))
            .setBot(persona is ChatPersona.Dispatcher) // Clean Kotlin type checking!
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
            IconCompat.createWithResource(context, persona.getIconResId())
        )
            .setDesiredHeight(BUBBLE_DESIRED_HEIGHT_DP)
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

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.bag_red_idle)
            .setStyle(style)
            .setBubbleMetadata(bubbleMetadata)
            .setContentIntent(contentIntent)
            .setShortcutId(shortcutId)
            .setLocusId(LocusIdCompat(shortcutId))
            .addPerson(senderPerson)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_MAX)

        if (offerActionable) {
            builder.addAction(offerAction("Decline", OfferIntent.DECLINE, REQUEST_CODE_DECLINE))
            builder.addAction(offerAction("Accept", OfferIntent.ACCEPT, REQUEST_CODE_ACCEPT))
        }

        notificationManager.notify(BUBBLE_NOTIFICATION_ID, builder.build())
    }

    /**
     * Post the offer evaluation as a heads-up notification with Accept /
     * Decline action buttons. Formatting (Spannable summary + persona)
     * happens HERE at the UI edge (#436) — the side-effect engine hands over
     * the domain evaluation and stays free of Android text types.
     */
    fun postOfferNotification(evaluation: OfferEvaluation) {
        val summary = evaluation.toNotificationSummary()
        val persona = evaluation.notificationPersona()
        Timber.tag("Chat").i("[${persona.displayName}]: $summary")
        scope.launch { chatRepository.saveMessage(_activeDashId.value, summary.toString(), persona) }
        showNotification(summary, persona, expand = false, offerActionable = true)
    }

    private fun offerAction(label: String, action: String, requestCode: Int): NotificationCompat.Action {
        val intent = Intent(context, OfferActionReceiver::class.java).apply {
            this.action = OfferActionReceiver.ACTION
            putExtra(OfferActionReceiver.EXTRA_ACTION, action)
        }
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, label, pi).build()
    }
}