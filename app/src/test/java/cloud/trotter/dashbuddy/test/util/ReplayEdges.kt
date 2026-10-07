package cloud.trotter.dashbuddy.test.util

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cloud.trotter.dashbuddy.core.location.LocationDataSource
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.location.Coordinates
import cloud.trotter.dashbuddy.domain.model.location.UserLocation
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.state.effects.OfferActionReceiver
import cloud.trotter.dashbuddy.state.effects.TtsEngineFactory
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * #1271 — the EXTERNAL edges of the end-to-end harness ([E2ESessionReplay]), and nothing else.
 *
 * Everything between the classifier and Room is production code; only what sits outside the
 * process boundary is replaced here: the wall clock, preference storage, GPS, the speech engine,
 * the third-party accessibility tree and the system notification shade (through a recording
 * `BubbleManager`, whose real implementation is UI rendering).
 */
object ReplayEdges {

    /**
     * The one clock: `fixtureEpochMs + scheduler.currentTime`. Every production seam that reads an
     * instant (classifier, engine, event repo, snapshot store, manager, receiver) reads this, and
     * every `delay` runs on the same scheduler — so a timer's fire time and the deadline it was
     * computed against cannot disagree.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    class SchedulerClock(
        private val scheduler: TestCoroutineScheduler,
        private val fixtureEpochMs: Long,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun millis(): Long = fixtureEpochMs + scheduler.currentTime
        override fun instant(): Instant = Instant.ofEpochMilli(millis())
    }

    /**
     * An in-memory `DataStore<Preferences>`, serialized like the real one (one writer at a time) —
     * the stores' own data sources and repositories run unchanged on top of it.
     */
    class MemoryPreferences : DataStore<Preferences> {
        private val mutex = Mutex()
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    /**
     * What a process death leaves behind (#1271 scenario 4): the Room database FILE and the
     * preference stores. A memory store that outlives the harness is an exact model of a DataStore
     * file here — DataStore commits each edit atomically before `updateData` returns, so a store
     * holds exactly the edits that completed, which is what a relaunched process reads back.
     */
    class DurableStores(val dbFile: java.io.File) {
        val app = MemoryPreferences()
        val strategy = MemoryPreferences()
        val odometer = MemoryPreferences()
        val grants = MemoryPreferences()
    }

    /**
     * GPS. While the odometer collects (it collects only between Start/Resume and Stop/Pause, exactly
     * as the fused provider is only active while collected), a fix is emitted every [intervalMs] of
     * virtual time along a straight line at [speedMps] — synthetic, timestamped, well inside every
     * [cloud.trotter.dashbuddy.domain.location.OdometerFixPolicy] bound, so the REAL policy accrues
     * the distance.
     */
    class FakeLocation(
        private val clock: Clock,
        private val intervalMs: Long = 15_000L,
        private val speedMps: Double = 10.0,
    ) : LocationDataSource {
        /** Collectors currently attached — 0 means GPS is off. */
        var activeCollectors = 0
            private set
        var fixesEmitted = 0
            private set

        private fun fixAt(nowMs: Long): Coordinates {
            // ~111 320 m per degree of latitude.
            val metres = speedMps * (nowMs / 1000.0)
            return Coordinates(
                latitude = 29.0 + (metres % 100_000.0) / 111_320.0,
                longitude = -98.5,
                accuracyMeters = 5.0,
                timestampMs = nowMs,
                monotonicMs = nowMs,
            )
        }

        override val locationUpdates: Flow<Coordinates> = flow {
            while (true) {
                fixesEmitted++
                emit(fixAt(clock.millis()))
                delay(intervalMs)
            }
        }.onStart { activeCollectors++ }.onCompletion { activeCollectors-- }

        override suspend fun getLastKnownLocation(): Coordinates? = null
        override suspend fun getUserLocation(): UserLocation? = null
    }

    /**
     * The speech engine. Hands out a stub engine that reports a successful init when [reportReady] is
     * called (the real callback is asynchronous), records every `speak`, and delivers the utterance's
     * `onDone` on [deliverDone] — a SUCCESS return only means queued (#991).
     */
    class FakeTts {
        val spoken = mutableListOf<String>()
        private val listeners = mutableListOf<TextToSpeech.OnInitListener>()
        private val engines = mutableListOf<TextToSpeech>()
        private val pendingDone = mutableListOf<String>()
        private var progress: UtteranceProgressListener? = null

        val factory = TtsEngineFactory { onInit ->
            val engine = mock<TextToSpeech>()
            whenever(engine.setOnUtteranceProgressListener(any())).thenAnswer {
                progress = it.getArgument(0)
                TextToSpeech.SUCCESS
            }
            whenever(engine.speak(any(), any(), org.mockito.kotlin.anyOrNull(), any())).thenAnswer {
                spoken += it.getArgument<CharSequence>(0).toString()
                pendingDone += it.getArgument<String>(3)
                TextToSpeech.SUCCESS
            }
            engines += engine
            listeners += onInit
            engine
        }

        val enginesBuilt: Int get() = engines.size

        fun reportReady() = listeners.last().onInit(TextToSpeech.SUCCESS)

        fun deliverDone() {
            val ids = pendingDone.toList()
            pendingDone.clear()
            ids.forEach { progress?.onDone(it) }
        }
    }

    /**
     * The third-party app's live accessibility tree. [show] mirrors a captured `UiNode` frame into
     * mocked `AccessibilityNodeInfo`s — the frame's own package, labels, view ids, classes, click
     * flags and bounds; `refresh()` succeeds; `ACTION_CLICK` is RECORDED and reported dispatched.
     * The production [cloud.trotter.dashbuddy.state.effects.UiInteractionHandler] resolves, verifies
     * and clicks against it exactly as on a device (the #1149 tap-kit pattern).
     */
    class FakeAccessibility {
        data class Click(val viewId: String?, val text: String?, val desc: String?, val bounds: Rect)

        val clicks = mutableListOf<Click>()
        private var root: AccessibilityNodeInfo? = null
        private val service = mock<AccessibilityService>()

        val source: AccessibilitySource = mock {
            on { getService() } doReturn service
        }

        init {
            whenever(source.getLiveWindowRoots()).thenAnswer {
                val r = root
                if (r == null) AccessibilitySource.LiveRoots(null, emptyList())
                else AccessibilitySource.LiveRoots(r, listOf(r))
            }
        }

        /** The foreground frame changed: [node] is now the live tree of [packageName]. */
        fun show(node: UiNode, packageName: String?) {
            root = packageName?.let { mirror(node, it).first }
        }

        /** Mirror [n] and its subtree; returns the node and its whole subtree (itself included). */
        @Suppress("DEPRECATION") // getActions(): the legacy bitmask takesClick() reads
        private fun mirror(n: UiNode, pkg: String): Pair<AccessibilityNodeInfo, List<Pair<UiNode, AccessibilityNodeInfo>>> {
            val node = mock<AccessibilityNodeInfo>()
            val built = n.children.map { mirror(it, pkg) }
            val children = built.map { it.first }
            val subtree = listOf(n to node) + built.flatMap { it.second }
            val rect = n.boundsInScreen.let { Rect(it.left, it.top, it.right, it.bottom) }
            whenever(node.packageName).thenReturn(pkg)
            whenever(node.className).thenReturn(n.className)
            whenever(node.text).thenReturn(n.text)
            whenever(node.contentDescription).thenReturn(n.contentDescription)
            whenever(node.viewIdResourceName).thenReturn(n.viewIdResourceName)
            whenever(node.isClickable).thenReturn(n.isClickable)
            whenever(node.isEnabled).thenReturn(n.isEnabled)
            whenever(node.isVisibleToUser).thenReturn(n.isVisibleToUser)
            val advertises = n.isClickable || n.hasClickAction
            whenever(node.actions).thenReturn(if (advertises) AccessibilityNodeInfo.ACTION_CLICK else 0)
            whenever(node.actionList).thenReturn(
                if (advertises) listOf(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK) else emptyList(),
            )
            whenever(node.childCount).thenReturn(children.size)
            whenever(node.getChild(any())).thenAnswer { children.getOrNull(it.getArgument(0)) }
            for (c in children) whenever(c.parent).thenReturn(node)
            whenever(node.getBoundsInScreen(any())).thenAnswer { (it.arguments[0] as Rect).set(rect) }
            whenever(node.refresh()).thenReturn(true)
            whenever(node.performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))).thenAnswer {
                clicks += Click(n.viewIdResourceName, n.text, n.contentDescription, Rect(rect))
                true
            }
            whenever(node.findAccessibilityNodeInfosByViewId(any())).thenAnswer { inv ->
                val id = inv.getArgument<String>(0)
                subtree.filter { it.first.viewIdResourceName == id }.map { it.second }
            }
            whenever(node.findAccessibilityNodeInfosByText(any())).thenAnswer { inv ->
                val needle = inv.getArgument<String>(0).lowercase()
                subtree.filter { (u, _) ->
                    u.text?.lowercase()?.contains(needle) == true ||
                        u.contentDescription?.lowercase()?.contains(needle) == true
                }.map { it.second }
            }
            return node to subtree
        }
    }

    /**
     * The bubble/notification UI. `BubbleManager` is pure rendering (RemoteViews, chat rows, the
     * chathead), so it is the edge: a Mockito stand-in RECORDS every call, and the offer heads-up is
     * posted to / cancelled from the real (shadow) [NotificationManager] under the production id
     * ([BubbleManager.offerNotificationId]) with Accept/Decline actions built from the production
     * receiver constants and [BubbleManager.offerActionUri] — so a test taps it exactly as SystemUI
     * would deliver the broadcast.
     */
    class RecordingBubble(private val context: Context, private val notifications: NotificationManager) {
        data class Call(val method: String, val args: List<Any?>)

        val calls = mutableListOf<Call>()

        fun calls(method: String) = calls.filter { it.method == method }

        val manager: BubbleManager = mock(defaultAnswer = { inv ->
            val args = inv.arguments.toList()
            calls += Call(inv.method.name, args)
            when (inv.method.name) {
                "postOfferNotification" -> {
                    val offer = args[0] as FlowCardSnapshot.Offer
                    val platform = args[2] as Platform
                    postOffer(offer.offerHash, platform)
                }
                "cancelOfferNotification" -> notifications.cancel(BubbleManager.offerNotificationId(args[0] as String?))
            }
            null
        })

        private fun actionIntent(action: String, platform: Platform, offerHash: String): PendingIntent {
            val intent = Intent(context, OfferActionReceiver::class.java).apply {
                this.action = OfferActionReceiver.ACTION
                data = BubbleManager.offerActionUri(platform, offerHash, action)
                putExtra(OfferActionReceiver.EXTRA_ACTION, action)
                putExtra(OfferActionReceiver.EXTRA_PLATFORM, platform.wire)
                putExtra(OfferActionReceiver.EXTRA_OFFER_HASH, offerHash)
            }
            return PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun postOffer(offerHash: String, platform: Platform) {
            val n: Notification = NotificationCompat.Builder(context, "replay_offer")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("offer")
                .addAction(0, OfferIntent.DECLINE, actionIntent(OfferIntent.DECLINE, platform, offerHash))
                .addAction(0, OfferIntent.ACCEPT, actionIntent(OfferIntent.ACCEPT, platform, offerHash))
                .build()
            notifications.notify(BubbleManager.offerNotificationId(offerHash), n)
        }
    }
}
