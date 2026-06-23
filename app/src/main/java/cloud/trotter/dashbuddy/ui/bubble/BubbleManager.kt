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
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.state.effects.OfferActionReceiver
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.activeSessionId
import cloud.trotter.dashbuddy.ui.formatters.getIconResId // <-- Your new UI Formatter!
import cloud.trotter.dashbuddy.ui.formatters.notificationPersona
import cloud.trotter.dashbuddy.ui.formatters.toNotificationSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BubbleManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notificationManager: NotificationManager,
    private val chatRepository: ChatRepository,
    // dagger.Lazy breaks the StateManagerV2 → EffectExecutor → BubbleManager
    // construction cycle (#437); resolved on first activeSessionId access.
    private val stateManager: dagger.Lazy<StateManagerV2>,
) {

    companion object {
        /** The persistent bubble/chat (chathead) notification id. */
        const val BUBBLE_NOTIFICATION_ID = 1

        /**
         * The separate heads-up OFFER notification id (#457). A NORMAL (non-bubble) notification so
         * the dasher can act from the heads-up banner WITHOUT pulling the shade: pulling the shade
         * makes SystemUI foreground, which drops DoorDash's offer window from the accessibility
         * live-window set, failing the fail-closed verified click (the #457 field symptom). A
         * heads-up banner floats over DoorDash without displacing it, so the click lands.
         */
        const val OFFER_NOTIFICATION_ID = 2

        /** Bubble expanded-view height (dp). */
        const val BUBBLE_DESIRED_HEIGHT_DP = 600

        /** PendingIntent request codes for the offer notification actions (#367). */
        const val REQUEST_CODE_ACCEPT = 10
        const val REQUEST_CODE_DECLINE = 11
        const val REQUEST_CODE_OFFER_CONTENT = 12

        /** Backstop auto-dismiss for the offer heads-up if a resolution cancel is somehow missed. */
        const val OFFER_HEADS_UP_TIMEOUT_MS = 90_000L
    }
    // 1. ADDED: CoroutineScope for the suspend database calls
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val channelId = "bubble_channel"
    private val offerChannelId = "offer_channel"
    private val shortcutId = "DashBuddy_Bubble_Shortcut"

    /**
     * The dash id chat/cards attribute to — DERIVED from `AppState` (#437),
     * not a second effect-driven copy. The old `_activeDashId` was set by
     * StartSession/EndSession, which crash recovery suppresses (external
     * effects): a restored mid-dash process had a null id and the bubble
     * showed nothing until the next DASH_START. State restores; this follows.
     * Lazy so the Hilt graph finishes building before StateManagerV2 resolves.
     */
    val activeSessionId: StateFlow<String?> by lazy {
        stateManager.get().state
            .map { it.activeSessionId() }
            .stateIn(scope, SharingStarted.Eagerly, null)
    }

    init {
        createChannel()
        pushDynamicShortcut()
    }

    /** Session chat copy only — the dash id derives from state (#437). */
    fun startSession(sessionId: String, platformName: String) {
        val verb = sessionVerb(platformName)
        postMessage("Started $verb!", ChatPersona.Dispatcher)
    }

    /** Session chat copy only — the dash id derives from state (#437). */
    fun endSession(platformName: String? = null) {
        val verb = sessionVerb(platformName)
        postMessage("Done $verb!", ChatPersona.Dispatcher)
    }

    /**
     * The dispatcher chat verb for a session. Resolves the serialized
     * platform id (the enum constant name [EffectMap] carries, e.g.
     * `"DoorDash"`) to a [Platform] and reads its [Platform.sessionVerb]
     * SSOT (audit #9) — no more wire-name string matching leaking into the
     * UI layer. Platforms without a specific verb fall back to
     * "driving for <displayName>"; an unresolved or null id to "driving".
     */
    private fun sessionVerb(platformName: String?): String {
        val platform = Platform.fromName(platformName)
        return when {
            platform?.sessionVerb != null -> platform.sessionVerb!!
            platform != null -> "driving for ${platform.displayName}"
            platformName != null -> "driving for $platformName"
            else -> "driving"
        }
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
            chatRepository.saveMessage(activeSessionId.value, text.toString(), persona)
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

        // #457: a SEPARATE, non-bubble channel for the actionable offer heads-up. The bubble channel
        // allows bubbles, so its notifications render as the chathead and suppress the heads-up banner
        // — forcing a shade-pull that breaks the Accept/Decline verified click. This channel never
        // bubbles, so an offer posts as a normal high-importance heads-up over DoorDash.
        val offerChannel = NotificationChannel(
            offerChannelId, "Offer Alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Heads-up offer alerts with Accept / Decline"
            setAllowBubbles(false)
        }
        notificationManager.createNotificationChannel(offerChannel)
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

        notificationManager.notify(BUBBLE_NOTIFICATION_ID, builder.build())
    }

    /**
     * Post the offer evaluation. The offer reaches the dasher on three surfaces (#457):
     *  - the in-bubble offer **card** (state-driven `LiveCardBuilder`, shown when the bubble is open) —
     *    not touched here;
     *  - the **chat stream** entry (saved below, the in-bubble history);
     *  - a **separate heads-up notification** with Accept/Decline ([showOfferHeadsUp]) — the
     *    actionable surface that pops over DoorDash.
     *
     * The actions live on the heads-up, NOT the chathead bubble notification: a bubble notification is
     * shown as the floating chathead and suppresses the heads-up, forcing a shade-pull that displaces
     * DoorDash from the accessibility live-window set and fails the verified click (#457).
     * Formatting happens HERE at the UI edge (#436) — the engine hands over the domain evaluation.
     */
    fun postOfferNotification(evaluation: OfferEvaluation) {
        val summary = evaluation.toNotificationSummary()
        val persona = evaluation.notificationPersona()
        Timber.tag("Chat").i("[${persona.displayName}]: $summary")
        scope.launch { chatRepository.saveMessage(activeSessionId.value, summary.toString(), persona) }
        showOfferHeadsUp(summary, persona)
    }

    /**
     * #457: the actionable offer surface — a separate, **non-bubble** heads-up notification (own
     * channel + id, no `BubbleMetadata`). A heads-up banner floats over DoorDash without displacing
     * it, so tapping Accept/Decline fires while the offer window is still live and the verified click
     * lands — unlike the old bubble notification, whose actions were only reachable via the shade
     * (which made SystemUI foreground and emptied DoorDash's live-window set). Backstopped by a
     * timeout; normally dismissed by [cancelOfferNotification] on resolution / when the dasher acts.
     */
    private fun showOfferHeadsUp(text: CharSequence, persona: ChatPersona) {
        val openBubble = Intent(context, BubbleActivity::class.java).apply { action = Intent.ACTION_VIEW }
        val contentIntent = PendingIntent.getActivity(
            context, REQUEST_CODE_OFFER_CONTENT, openBubble,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val builder = NotificationCompat.Builder(context, offerChannelId)
            .setSmallIcon(R.drawable.bag_red_idle)
            .setContentTitle(persona.displayName)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setTimeoutAfter(OFFER_HEADS_UP_TIMEOUT_MS)
            .addAction(offerAction("Decline", OfferIntent.DECLINE, REQUEST_CODE_DECLINE))
            .addAction(offerAction("Accept", OfferIntent.ACCEPT, REQUEST_CODE_ACCEPT))
        notificationManager.notify(OFFER_NOTIFICATION_ID, builder.build())
    }

    /** #457: dismiss the heads-up offer notification — on offer resolution or after the dasher acts. */
    fun cancelOfferNotification() {
        notificationManager.cancel(OFFER_NOTIFICATION_ID)
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