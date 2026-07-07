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
import cloud.trotter.dashbuddy.ui.formatters.displayLabel
import cloud.trotter.dashbuddy.ui.formatters.offerBadgeIcon
import cloud.trotter.dashbuddy.ui.formatters.offerScoreArgb
import cloud.trotter.dashbuddy.ui.formatters.offerVerdictArgb
import cloud.trotter.dashbuddy.ui.formatters.offerVerdictLabel
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
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
         * The reserved heads-up OFFER notification id (#457). A NORMAL (non-bubble) notification so
         * the dasher can act from the heads-up banner WITHOUT pulling the shade: pulling the shade
         * makes SystemUI foreground, which drops DoorDash's offer window from the accessibility
         * live-window set, failing the fail-closed verified click (the #457 field symptom). A
         * heads-up banner floats over DoorDash without displacing it, so the click lands.
         *
         * #438 B4: this fixed id is now the **null-hash fallback** for [offerNotificationId] —
         * per-offer banners key on their offer hash so two concurrent offers get distinct ids and
         * resolving one cancels only its own. It is also the id any pre-B4 stale banner was posted
         * under, so a hash-less cancel sweeps it (and any stale banner self-dismisses via
         * [OFFER_HEADS_UP_TIMEOUT_MS] regardless).
         */
        const val OFFER_NOTIFICATION_ID = 2

        /** Bubble expanded-view height (dp). */
        const val BUBBLE_DESIRED_HEIGHT_DP = 600

        /** PendingIntent request code for the "open bubble" content intent (#367). */
        const val REQUEST_CODE_OFFER_CONTENT = 12

        /** Backstop auto-dismiss for the offer heads-up if a resolution cancel is somehow missed. */
        const val OFFER_HEADS_UP_TIMEOUT_MS = 90_000L

        /**
         * The stable per-offer heads-up notification id (#438 B4). Derived from the offer's hash so
         * two concurrent offers (multi-platform or fast replacement) render as **distinct** banners
         * and resolving one dismisses only its own. A null hash (legacy / hash-less offer) maps to
         * the reserved [OFFER_NOTIFICATION_ID] — self-consistent post/cancel, and the sweep id for a
         * stale pre-B4 banner.
         *
         * Collision posture (documented, accepted for the single-user alpha): `hashCode()` is a
         * 32-bit space. Two live offers colliding on the same id → one banner replaces the other
         * (no data loss; the state layer still resolves each tap by carried (platform, offerHash),
         * B3). Probability ≈ 1/4e9 for two concurrent offers. Ids that would land on the reserved
         * bubble (1) / offer (2) ids are nudged clear so a hash can never dismiss the wrong surface.
         */
        fun offerNotificationId(offerHash: String?): Int {
            if (offerHash == null) return OFFER_NOTIFICATION_ID
            val h = offerHash.hashCode()
            return if (h == BUBBLE_NOTIFICATION_ID || h == OFFER_NOTIFICATION_ID) h + 2 else h
        }

        /**
         * The per-offer Accept/Decline intent **identity** URI (#438 B4):
         * `dashbuddy://offer/<platform-wire>/<offerHash>?action=<offer-intent>`. The scheme/host are
         * fixed; the platform wire, offer hash, and action make it unique per (offer, button). Unlike
         * extras, a `data` URI participates in [Intent.filterEquals], so two concurrent offers'
         * PendingIntents no longer collide/clobber under `FLAG_UPDATE_CURRENT`. Built via
         * [android.net.Uri.Builder] so the hash/wire are percent-encoded. The extras still carry the
         * payload (B2); this is identity only.
         */
        fun offerActionUri(platform: Platform, offerHash: String, action: String): android.net.Uri =
            android.net.Uri.Builder()
                .scheme("dashbuddy")
                .authority("offer")
                .appendPath(platform.wire)
                .appendPath(offerHash)
                .appendQueryParameter("action", action)
                .build()
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
        // #551 P7: chat text can carry raw merchant/store text ("Pickup: <store>"), so the
        // shareable INFO stream logs a counts-only milestone; the raw body stays on DEBUG.
        Timber.tag("Chat").i("message posted [%s] (%d chars)", persona.displayName, text.length)
        Timber.tag("Chat").d("[%s]: %s", persona.displayName, text)

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
    fun postOfferNotification(offer: FlowCardSnapshot.Offer, evaluation: OfferEvaluation, platform: Platform) {
        val summary = evaluation.toNotificationSummary()
        val persona = evaluation.notificationPersona()
        // #551 P7: the offer summary ends with the merchant name (raw third-party UI text), so
        // INFO carries a PII-safe milestone and the raw summary stays on the DEBUG firehose.
        Timber.tag("Chat").i("offer posted [%s] (%d chars)", persona.displayName, summary.length)
        Timber.tag("Chat").d("[%s]: %s", persona.displayName, summary)
        scope.launch { chatRepository.saveMessage(activeSessionId.value, summary.toString(), persona) }
        showOfferHeadsUp(offer, summary, persona, platform)
    }

    /**
     * #457/#578: the actionable offer surface — a separate, **non-bubble** heads-up notification (own
     * channel + id, no `BubbleMetadata`) that floats over DoorDash so Accept/Decline fire while the
     * offer window is still live. #578 makes it a rich "mini offer card" via `DecoratedCustomViewStyle`
     * + custom [RemoteViews] (verdict banner, $/hr, metrics, score, badges, live countdown) instead of
     * a plain text line. The custom views use ONLY RemoteViews-legal widgets, so they can't fail
     * inflation and regress the #457 action surface; `setContentText` keeps a text body for surfaces
     * that don't render custom views (Wear/Auto). Dismissed by [cancelOfferNotification].
     */
    private fun showOfferHeadsUp(offer: FlowCardSnapshot.Offer, summary: CharSequence, persona: ChatPersona, platform: Platform) {
        val openBubble = Intent(context, BubbleActivity::class.java).apply { action = Intent.ACTION_VIEW }
        val contentIntent = PendingIntent.getActivity(
            context, REQUEST_CODE_OFFER_CONTENT, openBubble,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val builder = NotificationCompat.Builder(context, offerChannelId)
            .setSmallIcon(R.drawable.bag_red_idle)
            .setContentTitle(persona.displayName)
            .setContentText(summary)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setTimeoutAfter(OFFER_HEADS_UP_TIMEOUT_MS)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(buildOfferCardView(offer, R.layout.notif_offer_compact, expanded = false, platform))
            .setCustomHeadsUpContentView(buildOfferCardView(offer, R.layout.notif_offer_compact, expanded = false, platform))
            .setCustomBigContentView(buildOfferCardView(offer, R.layout.notif_offer_expanded, expanded = true, platform))
        // #583: Accept/Decline are now brand-styled TextViews INSIDE the custom card
        // (wired via setOnClickPendingIntent in buildOfferCardView), not the system action row —
        // the two can't coexist without doubling the buttons. Field-gated: if a dash shows the
        // in-card buttons don't fire from the floating heads-up, revert to addAction(offerAction(…)).
        // #438 B4: per-offer id so two concurrent offers don't clobber each other's banner.
        notificationManager.notify(offerNotificationId(offer.offerHash), builder.build())
    }

    /**
     * #578/#583: populate a `notif_offer_*` [RemoteViews] from the offer card snapshot. Colors/
     * countdown/badges/gauge are set at runtime (RemoteViews can't use theme attrs — badges would
     * render black otherwise). Score → a Canvas-drawn ring [scoreGaugeBitmap] pushed via
     * `setImageViewBitmap` (RemoteViews can't draw an arc); countdown → a self-ticking
     * [Chronometer]; Accept/Decline → brand-styled `TextView`s wired with `setOnClickPendingIntent`.
     */
    // Null-safe money/distance for the notification card — "—" when a metric didn't parse.
    private fun money(d: Double?) = d?.let { Formats.money(it) } ?: "—"
    private fun money0(d: Double?) = d?.let { Formats.money0(it) } ?: "—"
    private fun miles(d: Double?) = d?.let { "${Formats.decimal(it)} mi" } ?: "—"

    private fun buildOfferCardView(
        offer: FlowCardSnapshot.Offer,
        layoutRes: Int,
        expanded: Boolean,
        platform: Platform,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, layoutRes)
        val action = offer.evaluationAction?.let { runCatching { OfferAction.valueOf(it) }.getOrNull() }
        val verdictArgb = offerVerdictArgb(action)

        // Verdict banner is expanded-only by design; the collapsed heads-up conveys the verdict
        // through the gauge ring color (the compact layout has no verdict view).
        if (expanded) {
            rv.setTextViewText(
                R.id.notif_offer_verdict,
                offer.qualityLevel?.displayLabel()?.let { "${offerVerdictLabel(action)} · $it" }
                    ?: offerVerdictLabel(action),
            )
            rv.setInt(R.id.notif_offer_verdict, "setBackgroundColor", verdictArgb)
        }
        rv.setTextViewText(R.id.notif_offer_rate, "${money0(offer.dollarsPerHour)}/hr")
        if (!expanded) {
            rv.setTextViewText(
                R.id.notif_offer_sub,
                "Net ${money(offer.netPayAmount)} · ${money(offer.dollarsPerMile)}/mi · ${miles(offer.distanceMiles)}",
            )
        }

        val expiresAt = offer.expiresAt
        if (expiresAt != null) {
            rv.setChronometerCountDown(R.id.notif_offer_countdown, true)
            rv.setChronometer(
                R.id.notif_offer_countdown,
                SystemClock.elapsedRealtime() + (expiresAt - System.currentTimeMillis()),
                null, true,
            )
            rv.setViewVisibility(R.id.notif_offer_countdown, View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.notif_offer_countdown, View.GONE)
        }

        // Score gauge ring — drawn to a bitmap (RemoteViews can't draw an arc). Sized to the slot
        // (larger on the expanded card); hidden when the offer didn't score.
        val scoreValue = offer.evaluationScore
        if (scoreValue != null) {
            val gaugeDp = if (expanded) 56 else 40
            val gaugePx = (gaugeDp * context.resources.displayMetrics.density).toInt()
            rv.setImageViewBitmap(
                R.id.notif_offer_gauge,
                scoreGaugeBitmap(scoreValue.toInt(), offerScoreArgb(scoreValue), gaugePx),
            )
            rv.setViewVisibility(R.id.notif_offer_gauge, View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.notif_offer_gauge, View.GONE)
        }

        // Brand-styled Accept/Decline (both layouts) — fire the same broadcast as the old action row.
        rv.setOnClickPendingIntent(
            R.id.notif_btn_decline,
            offerActionPendingIntent(OfferIntent.DECLINE, platform, offer.offerHash),
        )
        rv.setOnClickPendingIntent(
            R.id.notif_btn_accept,
            offerActionPendingIntent(OfferIntent.ACCEPT, platform, offer.offerHash),
        )

        if (expanded) {
            rv.setTextViewText(
                R.id.notif_offer_metrics,
                "Net ${money(offer.netPayAmount)} · Gross ${money(offer.payAmount)} · " +
                    "${miles(offer.distanceMiles)} · ${money(offer.dollarsPerMile)}/mi",
            )
            // Badges into fixed slots, tinted with the verdict color so they're visible on any
            // notification background (theme-attr fills don't resolve in the remote process).
            val slots = listOf(
                R.id.notif_badge_0, R.id.notif_badge_1, R.id.notif_badge_2,
                R.id.notif_badge_3, R.id.notif_badge_4,
            )
            val icons = offer.badges.mapNotNull { offerBadgeIcon(it) }.distinct().take(slots.size)
            slots.forEachIndexed { i, slot ->
                val icon = icons.getOrNull(i)
                if (icon != null) {
                    rv.setImageViewResource(slot, icon)
                    rv.setInt(slot, "setColorFilter", verdictArgb)
                    rv.setViewVisibility(slot, View.VISIBLE)
                } else {
                    rv.setViewVisibility(slot, View.GONE)
                }
            }
            val stores = offer.storeNames.joinToString(" + ")
            val items = if (offer.itemCount > 1) " · ${offer.itemCount} items" else ""
            rv.setTextViewText(R.id.notif_offer_store, (stores + items).trim())
        }
        return rv
    }

    /**
     * #457: dismiss the heads-up offer notification — on offer resolution or after the dasher acts.
     * #438 B4: keyed per-offer by [offerNotificationId] so resolving offer A does not touch offer
     * B's banner. A null hash maps to the reserved id (also sweeps any stale pre-B4 banner).
     */
    fun cancelOfferNotification(offerHash: String?) {
        notificationManager.cancel(offerNotificationId(offerHash))
    }

    /**
     * #583: the Accept/Decline broadcast, wired onto the in-card buttons via setOnClickPendingIntent.
     * #438 B4: per-offer PendingIntent **identity** via an [offerActionUri] `data` URI — URIs
     * participate in [Intent.filterEquals] (extras don't), so under `FLAG_UPDATE_CURRENT` two
     * concurrent offers no longer collide/clobber each other's Accept/Decline intents. A single
     * fixed request code is enough since the URI already makes each (offer, button) intent distinct.
     * The extras still carry the payload (#438 item 8a: platform wire + offerHash → the dispatched
     * `UiInput`'s target identity; identity-less taps step no region post-#682).
     */
    private fun offerActionPendingIntent(
        action: String,
        platform: Platform,
        offerHash: String,
    ): PendingIntent {
        val intent = Intent(context, OfferActionReceiver::class.java).apply {
            this.action = OfferActionReceiver.ACTION
            data = offerActionUri(platform, offerHash, action)
            putExtra(OfferActionReceiver.EXTRA_ACTION, action)
            putExtra(OfferActionReceiver.EXTRA_PLATFORM, platform.wire)
            putExtra(OfferActionReceiver.EXTRA_OFFER_HASH, offerHash)
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}