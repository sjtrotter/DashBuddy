package cloud.trotter.dashbuddy.ui.bubble

import android.app.Notification
import androidx.compose.ui.graphics.toArgb
import cloud.trotter.dashbuddy.core.designsystem.theme.darkAppColors
import cloud.trotter.dashbuddy.domain.evaluation.EvaluationConfig
import cloud.trotter.dashbuddy.domain.state.Job as DeliveryJob
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.feature.bubble.PayBarLabels
import cloud.trotter.dashbuddy.feature.bubble.payBarBitmap
import cloud.trotter.dashbuddy.feature.bubble.hud.OfferCardMapper
import cloud.trotter.dashbuddy.feature.bubble.hud.OfferCardModel
import cloud.trotter.dashbuddy.feature.bubble.hud.PayBarModel
import cloud.trotter.dashbuddy.feature.bubble.notification.*
import cloud.trotter.dashbuddy.feature.bubble.R as BubbleR
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
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.state.effects.OfferActionReceiver
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.activeSessionId
import cloud.trotter.dashbuddy.feature.bubble.scoreGaugeBitmap
import cloud.trotter.dashbuddy.feature.bubble.formatters.getIconResId // <-- Your new UI Formatter!
import cloud.trotter.dashbuddy.feature.bubble.formatters.notificationPersona
import cloud.trotter.dashbuddy.feature.bubble.formatters.toNotificationSummary
import cloud.trotter.dashbuddy.feature.bubble.formatters.offerScoreArgb
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleOdometerReading
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerCue
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Clock
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
    private val appPreferencesRepository: AppPreferencesRepository,
    private val garageRepository: GarageRepository,
    private val odometerReadingRepository: OdometerReadingRepository,
    private val clock: Clock,
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
         * Collision posture (documented, accepted for the single-user alpha): the id is the hash
         * folded into the **disjoint namespace [2^30, 2^31)** — so it can NEVER land on any app
         * fixed id (the bubble's 1, the reserved offer 2, the odometer handler's 101, or any
         * future small constant; an enumerated "nudge" list would silently rot as ids are added —
         * adversarial-review MED-1). Two live offers colliding within the 30-bit space → one
         * banner replaces the other (no data loss; the state layer still resolves each tap by
         * carried (platform, offerHash), B3). Probability ≈ 1/1e9 for two concurrent offers.
         * The per-offer PendingIntents are released in `cancelOfferNotification` (LOW-1); an
         * OS-side 90s [OFFER_HEADS_UP_TIMEOUT_MS] self-dismiss backstops any missed cancel.
         */
        fun offerNotificationId(offerHash: String?): Int {
            if (offerHash == null) return OFFER_NOTIFICATION_ID
            return (offerHash.hashCode() and 0x3FFF_FFFF) or 0x4000_0000
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

    /**
     * Session chat copy only — the dash id derives from state (#437). #867: the STARTING session
     * is known here, so the line is filed to it explicitly (the derived `activeSessionId` may not
     * have caught up to — or may belong to — another platform's dash).
     */
    fun startSession(sessionId: String, platformName: String) {
        Timber.tag("Bubble").i(
            "session start platform=%s sessionId=%s", Platform.fromName(platformName)?.wire ?: platformName, sessionId,
        )
        val verb = sessionVerb(platformName)
        // One Clock rule: cue comparisons share the injected clock used to stamp readings.
        val now = clock.millis()
        val startedLine = context.getString(R.string.bubble_chat_session_started, verb)
        val started = enqueueMessage(
            startedLine,
            ChatPersona.Dispatcher, expand = false, sessionId = sessionId,
        )
        scope.launch {
            try {
                // Keep the cue notification after the persisted session-start line.
                started.join()
                val enabled = appPreferencesRepository.odometerPrompts.first()
                val garage = garageRepository.garage.first()
                // An unreadable garage is not "no vehicle": the sheet says the vehicles could not be read, so no cue.
                if (!garage.readable) return@launch
                val vehicle = garage.active
                val readings = odometerReadingRepository.readings.first()
                val last = vehicle?.let { VehicleOdometerReading.latestFor(readings, it.id) }
                if (OdometerCue.shows(enabled, startedAt = now, endedAt = null, last = last, loaded = true, now = now)) {
                    showNotification(listOf(
                        startedLine to ChatPersona.Dispatcher,
                        context.getString(R.string.bubble_chat_odometer_cue) to ChatPersona.Dispatcher,
                    ), expand = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.tag("Bubble").w("Odometer cue skipped (%s)", error.javaClass.simpleName)
            }
        }
    }

    /**
     * Session chat copy only — the dash id derives from state (#437). #867: [sessionId] is the
     * session that just ENDED, carried by the effect. The active session is NOT a safe stand-in
     * here: a grace-lapsed end commits on a `GRACE_COMMIT` timer or a foreign platform's frame (so
     * `activeSessionId` is the OTHER dash), and the ending region's own session is already null by
     * execute time even single-app (so the line would file session-less).
     */
    fun endSession(platformName: String? = null, sessionId: String? = null) {
        Timber.tag("Bubble").i(
            "session end platform=%s sessionId=%s", Platform.fromName(platformName)?.wire ?: platformName, sessionId,
        )
        val verb = sessionVerb(platformName)
        postMessage(
            context.getString(R.string.bubble_chat_session_done, verb),
            ChatPersona.Dispatcher,
            sessionId = sessionId,
        )
    }

    /**
     * The first-run/ready-to-dash welcome chat line (#428 Half A). Owned here (not
     * [cloud.trotter.dashbuddy.ui.main.dashboard.DashboardViewModel]) because [BubbleManager] already
     * carries a [Context] and a `@HiltViewModel` should not inject one.
     */
    fun postWelcomeMessage() {
        postMessage(
            text = context.getString(R.string.bubble_welcome_message),
            persona = ChatPersona.Dispatcher,
            expand = true,
        )
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
            platform != null -> context.getString(R.string.bubble_session_verb_fallback_named, platform.displayName)
            platformName != null -> context.getString(R.string.bubble_session_verb_fallback_named, platformName)
            else -> context.getString(R.string.bubble_session_verb_fallback_unnamed)
        }
    }

    /**
     * Post a chat line: save it to the dash's history + show the chathead notification.
     *
     * [sessionId] is the message's OWN originating session (#867). Null falls back to
     * [activeSessionId] — the session of whichever platform produced the last recognized frame —
     * which is correct only for a genuinely session-less message (a welcome line, a rule-declared
     * bubble, a tip push). While multi-apping the active session flips on every app switch, so any
     * caller that knows the originating session MUST pass it: without it an Uber offer's outcome
     * line could be filed into the DoorDash session's chat, durably corrupting the per-session
     * history (not just the live view).
     */
    fun postMessage(
        text: CharSequence,
        persona: ChatPersona = ChatPersona.Dispatcher,
        expand: Boolean = false,
        sessionId: String? = null,
    ) {
        enqueueMessage(text, persona, expand, sessionId)
    }

    /** The save job lets session-start copy await its preceding history line. */
    private fun enqueueMessage(
        text: CharSequence, persona: ChatPersona, expand: Boolean, sessionId: String?,
    ): Job {
        // #551 P7: chat text can carry raw merchant/store text ("Pickup: <store>"), so the
        // shareable INFO stream logs a counts-only milestone; the raw body stays on DEBUG.
        // #772: the persona itself logs as its PII-safe logLabel (kind, not displayName) on INFO+.
        Timber.tag("Chat").i("message posted [%s] (%d chars)", persona.logLabel, text.length)
        Timber.tag("Chat").d("[%s]: %s", persona.displayName, text)

        // 2. UPDATED: Launched in a coroutine because the Repository is pure suspend now!
        val saved = scope.launch {
            chatRepository.saveMessage(sessionId ?: activeSessionId.value, text.toString(), persona)
        }

        // Post Notification
        showNotification(text, persona, expand)
        return saved
    }

    /** Built once: a constant, extras-less broadcast for the bubble notification's delete intent (#916). */
    private val dismissIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context, 0, Intent(context, BubbleDismissReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            channelId, context.getString(R.string.bubble_channel_stream_name), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.bubble_channel_stream_description)
            setAllowBubbles(true)
        }
        notificationManager.createNotificationChannel(channel)

        // #457: a SEPARATE, non-bubble channel for the actionable offer heads-up. The bubble channel
        // allows bubbles, so its notifications render as the chathead and suppress the heads-up banner
        // — forcing a shade-pull that breaks the Accept/Decline verified click. This channel never
        // bubbles, so an offer posts as a normal high-importance heads-up over DoorDash.
        val offerChannel = NotificationChannel(
            offerChannelId, context.getString(R.string.bubble_channel_offer_name), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.bubble_channel_offer_description)
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
            .setShortLabel(context.getString(R.string.app_name))
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
    ) = showNotification(listOf(text to persona), expand)

    private fun showNotification(
        messages: List<Pair<CharSequence, ChatPersona>>,
        expand: Boolean,
    ) {
        val notificationMessages = messages.map { (text, persona) ->
            text to Person.Builder()
                .setName(persona.displayName)
                .setKey(persona.id)
                .setIcon(IconCompat.createWithResource(context, persona.getIconResId()))
                .setBot(persona is ChatPersona.Dispatcher)
                .build()
        }
        val persona = messages.last().second
        val senderPerson = notificationMessages.last().second

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
            .setConversationTitle(context.getString(R.string.bubble_notification_conversation_title))
            .setGroupConversation(true)
        notificationMessages.forEach { (text, sender) ->
            style.addMessage(text, System.currentTimeMillis(), sender)
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.bag_red_idle)
            .setStyle(style)
            .setBubbleMetadata(bubbleMetadata)
            .setContentIntent(contentIntent)
            .setDeleteIntent(dismissIntent)
            .setShortcutId(shortcutId)
            .setLocusId(LocusIdCompat(shortcutId))
            .addPerson(senderPerson)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_MAX)

        val notification = builder.build()
        // `canBubble` is the OS gate that actually decides whether a chathead renders (the dasher's
        // bubble preference), not the presence of our own metadata — the most common "bubble vanished"
        // cause is that preference being reverted (fable review of PR #1225).
        Timber.tag("Bubble").i(
            "bubble post requested id=%d canBubble=%s",
            BUBBLE_NOTIFICATION_ID, cloud.trotter.dashbuddy.util.PermissionUtils.hasFullBubblePreference(context),
        )
        try {
            notificationManager.notify(BUBBLE_NOTIFICATION_ID, notification)
            Timber.tag("Bubble").i("bubble post returned id=%d", BUBBLE_NOTIFICATION_ID)
        } catch (t: Throwable) {
            Timber.tag("Bubble").e(
                "bubble post FAILED id=%d cause=%s", BUBBLE_NOTIFICATION_ID, t.javaClass.name,
            )
            throw t
        }
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
    fun postOfferNotification(
        offer: FlowCardSnapshot.Offer,
        evaluation: OfferEvaluation,
        platform: Platform,
        /** The offer's own originating session (#867); null falls back to [activeSessionId]. */
        sessionId: String? = null,
        /**
         * #1104: a countdown-anchor REFRESH of an already-surfaced offer — update the existing
         * notification in place (same per-offer id, `setOnlyAlertOnce`: no re-alert) and write NO
         * chat summary (a second "offer posted" row per moved deadline is not a milestone).
         */
        refreshOnly: Boolean = false,
        pending: PendingOffer,
        config: EvaluationConfig?,
        /** The platform's active job at the offer's step: an add-on maps to the bubble's COMBINED card. */
        job: DeliveryJob? = null,
    ) {
        val summary = evaluation.toNotificationSummary()
        val persona = evaluation.notificationPersona()
        if (refreshOnly) {
            Timber.tag("Chat").d("offer heads-up refreshed [%s] (countdown anchors)", persona.logLabel)
        } else {
            // #551 P7: the offer summary ends with the merchant name (raw third-party UI text), so
            // INFO carries a PII-safe milestone and the raw summary stays on the DEBUG firehose.
            Timber.tag("Chat").i("offer posted [%s] (%d chars)", persona.logLabel, summary.length)
            Timber.tag("Chat").d("[%s]: %s", persona.displayName, summary)
            scope.launch { chatRepository.saveMessage(sessionId ?: activeSessionId.value, summary.toString(), persona) }
        }
        val card = OfferCardMapper.map(pending, job = job, completed = emptyList(), config = config)
        showOfferHeadsUp(card, pending.offerHash, summary, persona, platform)
    }

    /**
     * #457/#578: the actionable offer surface — a separate, **non-bubble** heads-up notification (own
     * channel + id, no `BubbleMetadata`) that floats over DoorDash so Accept/Decline fire while the
     * offer window is still live. #578 makes it a rich "mini offer card" via `DecoratedCustomViewStyle`
     * + custom [RemoteViews] (gross pay, ring, pay bar, rates, live countdown) instead of
     * a plain text line. The custom views use ONLY RemoteViews-legal widgets, so they can't fail
     * inflation and regress the #457 action surface; `setContentText` keeps a text body for surfaces
     * that don't render custom views (Wear/Auto). Dismissed by [cancelOfferNotification].
     */
    private fun showOfferHeadsUp(card: OfferCardModel, offerHash: String, summary: CharSequence, persona: ChatPersona, platform: Platform) {
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
            // #1104: the FIRST post alerts; an in-place update (a moved countdown anchor re-posts
            // under the same per-offer id every few seconds on a re-rendering card) never re-alerts.
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(OFFER_HEADS_UP_TIMEOUT_MS)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(buildOfferCardView(card, offerHash, R.layout.notif_offer_compact, expanded = false, platform))
            .setCustomHeadsUpContentView(buildOfferCardView(card, offerHash, R.layout.notif_offer_compact, expanded = false, platform))
            .setCustomBigContentView(buildOfferCardView(card, offerHash, R.layout.notif_offer_expanded, expanded = true, platform))
        // #583: Accept/Decline are now brand-styled TextViews INSIDE the custom card
        // (wired via setOnClickPendingIntent in buildOfferCardView), not the system action row —
        // the two can't coexist without doubling the buttons. Field-gated: if a dash shows the
        // in-card buttons don't fire from the floating heads-up, revert to addAction(offerAction(…)).
        // #438 B4: per-offer id so two concurrent offers don't clobber each other's banner.
        notificationManager.notify(offerNotificationId(offerHash), builder.build())
    }

    /** Option B: every displayed figure comes from the same model as the HUD. */
    private fun buildOfferCardView(
        card: OfferCardModel,
        offerHash: String,
        layoutRes: Int,
        expanded: Boolean,
        platform: Platform,
    ): RemoteViews {
        val model = OfferNotificationModel.from(card)
        val colors = darkAppColors()
        val metrics = context.resources.displayMetrics
        // Pixels per sp at the user's font scale (DisplayMetrics.scaledDensity is deprecated on API 34+).
        val pxPerSp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, metrics)
        val rv = RemoteViews(context.packageName, layoutRes)
        rv.setTextViewText(R.id.notif_offer_hero, offerNotificationHero(model.pay, expanded, pxPerSp))
        rv.setTextColor(R.id.notif_offer_countdown, colors.good.toArgb())
        val expiresAt = model.expiresAt
        if (expiresAt != null) {
            rv.setChronometerCountDown(R.id.notif_offer_countdown, true)
            rv.setChronometer(R.id.notif_offer_countdown,
                SystemClock.elapsedRealtime() + (expiresAt - System.currentTimeMillis()), null, true)
        }
        rv.setViewVisibility(R.id.notif_offer_countdown, if (expiresAt != null) View.VISIBLE else View.GONE)
        model.ring?.let { ring ->
            rv.setImageViewBitmap(R.id.notif_offer_gauge, scoreGaugeBitmap(ring.score,
                ring.score?.let { offerScoreArgb(it.toDouble()) } ?: colors.neutral.toArgb(),
                ((if (expanded) 64 else 40) * metrics.density).toInt()))
            rv.setContentDescription(R.id.notif_offer_gauge, ring.score?.let {
                context.getString(BubbleR.string.hud_offer_cd_score, it)
            } ?: context.getString(BubbleR.string.hud_offer_cd_no_score))
        }
        rv.setViewVisibility(R.id.notif_offer_gauge, if (model.ring != null) View.VISIBLE else View.GONE)

        // Preserve the per-offer action identity, including the native Accept-left/Decline-right order.
        rv.setOnClickPendingIntent(R.id.notif_btn_decline,
            offerActionPendingIntent(OfferIntent.DECLINE, platform, offerHash))
        rv.setOnClickPendingIntent(R.id.notif_btn_accept,
            offerActionPendingIntent(OfferIntent.ACCEPT, platform, offerHash))
        val labels = PayBarLabels(context.getString(BubbleR.string.hud_offer_profit),
            context.getString(BubbleR.string.hud_offer_loss), context.getString(BubbleR.string.hud_offer_car),
            context.getString(BubbleR.string.hud_offer_distance_unknown))
        if (expanded) {
            rv.setTextViewText(R.id.notif_offer_store,
                model.store ?: context.getString(BubbleR.string.flow_card_offer_fallback_store))
            rv.setTextColor(R.id.notif_offer_store, colors.text.toArgb())
            rv.setTextViewText(R.id.notif_offer_left, context.getString(BubbleR.string.hud_offer_left))
            rv.setTextColor(R.id.notif_offer_left, colors.text2.toArgb())
            rv.setViewVisibility(R.id.notif_offer_left, if (expiresAt != null) View.VISIBLE else View.GONE)
            val bitmap = model.bar?.let {
                payBarBitmap(it, (metrics.widthPixels - (8 + 8 + 16 + 16) * metrics.density).toInt(),
                    (34 * metrics.density).toInt(), metrics.density, labels)
            }
            rv.setImageViewBitmap(R.id.notif_offer_bar, bitmap)
            rv.setViewVisibility(R.id.notif_offer_bar, if (bitmap != null) View.VISIBLE else View.GONE)
            val description = when (val bar = model.bar) {
                is PayBarModel.Split -> context.getString(BubbleR.string.hud_offer_cd_bar,
                    Formats.money(bar.profitCents / 100.0), Formats.money(bar.carCents / 100.0))
                is PayBarModel.Loss -> context.getString(BubbleR.string.hud_offer_cd_bar_loss,
                    Formats.money(bar.lossCents / 100.0), Formats.money(bar.carCents / 100.0))
                PayBarModel.DistanceUnknown -> labels.distanceUnknown
                else -> ""
            }
            rv.setContentDescription(R.id.notif_offer_bar, description)
            listOf(R.id.notif_offer_rate_0, R.id.notif_offer_rate_1, R.id.notif_offer_rate_2)
                .forEachIndexed { index, id ->
                    val rate = model.rates.getOrNull(index)
                    rv.setViewVisibility(id, if (rate != null) View.VISIBLE else View.GONE)
                    rate?.let { rv.setTextViewText(id, offerNotificationRate(it, pxPerSp)) }
                }
            rv.setViewVisibility(R.id.notif_offer_addon, if (model.addOnFigures != null) View.VISIBLE else View.GONE)
            rv.setTextViewText(R.id.notif_offer_addon, model.addOnFigures)
            rv.setTextColor(R.id.notif_offer_addon, colors.text.toArgb())
        } else {
            val profit = when (val bar = model.bar) {
                is PayBarModel.Split -> offerNotificationFigure(Formats.money(bar.profitCents / 100.0),
                    " " + labels.profit, 14, 10, colors.good.toArgb(), scaledDensity = pxPerSp)
                is PayBarModel.Loss -> offerNotificationFigure(Formats.money(bar.lossCents / 100.0),
                    " " + labels.loss, 14, 10, colors.bad.toArgb(), scaledDensity = pxPerSp)
                else -> null
            }
            rv.setViewVisibility(R.id.notif_offer_profit, if (profit != null) View.VISIBLE else View.GONE)
            rv.setTextViewText(R.id.notif_offer_profit, profit)
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
        if (offerHash == null) return
        // Release the offer's Accept/Decline PendingIntent records (adversarial-review LOW-1):
        // cancelling a notification does NOT release its PIs, and per-offer URIs would otherwise
        // accrue two system-server records per offer until process death. The cancel effect
        // carries only the hash, not the platform the URI path needs — probe every registry
        // platform with FLAG_NO_CREATE (a handful of lookups; keyed on the registry, no
        // literals). filterEquals ignores extras, so action + data + component is enough to match.
        for (platform in Platform.entries) {
            for (action in listOf(OfferIntent.ACCEPT, OfferIntent.DECLINE)) {
                val intent = Intent(context, OfferActionReceiver::class.java).apply {
                    this.action = OfferActionReceiver.ACTION
                    data = offerActionUri(platform, offerHash, action)
                }
                PendingIntent.getBroadcast(
                    context, 0, intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )?.cancel()
            }
        }
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
