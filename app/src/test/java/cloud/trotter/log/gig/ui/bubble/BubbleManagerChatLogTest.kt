package cloud.trotter.log.gig.ui.bubble

import android.app.NotificationManager
import android.util.Log
import cloud.trotter.log.gig.core.data.chat.ChatRepository
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.data.vehicle.Garage
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.log.gig.core.state.StateManagerV2
import cloud.trotter.log.gig.domain.evaluation.OfferAction
import cloud.trotter.log.gig.domain.evaluation.OfferEvaluation
import cloud.trotter.log.gig.domain.evaluation.OfferQuality
import cloud.trotter.log.gig.domain.model.cards.FlowCardSnapshot
import cloud.trotter.log.gig.domain.model.chat.ChatPersona
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.model.order.OrderType
import cloud.trotter.log.gig.domain.model.order.ParsedOrder
import cloud.trotter.log.gig.domain.offer.story.AddOnSpeech
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingOffer
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.test.util.RecordingTree
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import timber.log.Timber
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * #772 / Principle-7: the two "Chat" INFO milestones in [BubbleManager.postMessage] /
 * [BubbleManager.postOfferChat] used to interpolate [ChatPersona.displayName], which
 * carries raw merchant/customer text ("H-E-B", "…'s customer") for name-bearing personas — a
 * Principle-7 leak into the exportable `shareable.log`. They now log [ChatPersona.logLabel]
 * (the persona KIND) instead; the DEBUG line directly below each keeps `displayName` for firehose
 * fidelity. Drives the REAL [BubbleManager] (mocked collaborators only) through [RecordingTree] to
 * prove the fix end-to-end, matching the [BubbleManagerIdentityTest] Robolectric precedent.
 */
@RunWith(RobolectricTestRunner::class)
class BubbleManagerChatLogTest {

    private fun buildManager(): BubbleManager = manager(mock(), mock())

    @Test
    fun `postOfferChat writes the one chat row with a PII-safe INFO milestone`() {
        val chatRepository: ChatRepository = mock()
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            manager(chatRepository, mock()).postOfferChat(pendingOffer(), Platform.DoorDash, job = null, sessionId = "sess-1",
                config = null, addOnSpeech = AddOnSpeech.JOB_TOTALS)
        } finally {
            Timber.uproot(tree)
        }
        verifyBlocking(chatRepository, timeout(5_000).times(1)) { saveMessage(eq("sess-1"), any(), any()) }
        check(tree.records.any { it.priority == Log.INFO && it.tag == "Chat" && it.message.contains("offer posted [Offer]") }) {
            "the chat row must log its 'offer posted' milestone: ${tree.records}"
        }
        // #551 P7: the summary names the store; only the DEBUG firehose may carry it.
        tree.assertNoInfoPlusContains("H-E-B")
        tree.assertLevelContains(Log.DEBUG, "H-E-B")
    }

    @Test
    fun `postOfferNotification writes no chat row and no INFO milestone (fresh, re-quote, refresh)`() {
        for ((refreshOnly, requote) in listOf(false to false, false to true, true to false)) {
            val chatRepository: ChatRepository = mock()
            val notificationManager: NotificationManager = mock()
            val tree = RecordingTree()
            Timber.plant(tree)
            try {
                val pending = pendingOffer()
                val offer = FlowCardSnapshot.Offer.from(
                    parsedOffer = pending.offerFields.parsedOffer, evaluation = evaluation, offerHash = "h1",
                    phaseStartedAt = 1_000L, expiresAt = 41_000L, countdownSeconds = 40,
                )
                manager(chatRepository, notificationManager).postOfferNotification(offer, evaluation, Platform.DoorDash,
                    refreshOnly = refreshOnly, requote = requote, pending = pending, config = null,
                    addOnSpeech = AddOnSpeech.JOB_TOTALS)
            } finally {
                Timber.uproot(tree)
            }
            verify(notificationManager).notify(any(), any())
            verifyNoInteractions(chatRepository)
            check(tree.records.none { it.priority == Log.INFO && it.tag == "Chat" && it.message.contains("offer posted") }) {
                "a heads-up post must not log an 'offer posted' milestone: ${tree.records}"
            }
        }
    }

    private val evaluation = OfferEvaluation(
        action = OfferAction.ACCEPT, score = 74.0, qualityLevel = OfferQuality.GOOD, payAmount = 14.75,
        fuelCostEstimate = 0.5, netPayAmount = 12.0, distanceMiles = 8.5, dollarsPerMile = 1.4,
        dollarsPerHour = 22.0, estimatedTimeMinutes = 33.0, itemCount = 1.0, merchantName = "H-E-B",
    )

    private fun pendingOffer() = PendingOffer(
        "h1",
        ParsedFields.OfferFields(parsedOffer = ParsedOffer("h1", payAmount = 14.75, distanceMiles = 8.5, orders = listOf(
            ParsedOrder(orderIndex = 0, orderType = OrderType.PICKUP, storeName = "H-E-B", itemCount = 1,
                isItemCountEstimated = false, badges = emptySet()),
        ))),
        1_000L, evaluation, returnFlow = Flow.TaskPickupNavigation,
    )

    private fun manager(chatRepository: ChatRepository, notificationManager: NotificationManager): BubbleManager {
        val preferences: AppPreferencesRepository = mock()
        val garage: GarageRepository = mock()
        val readings: OdometerReadingRepository = mock()
        whenever(preferences.odometerPrompts).thenReturn(flowOf(false))
        whenever(garage.garage).thenReturn(flowOf(Garage(emptyList(), null, readable = true)))
        whenever(readings.readings).thenReturn(flowOf(emptyList()))
        val stateManager: StateManagerV2 = mock()
        whenever(stateManager.state).thenReturn(MutableStateFlow(AppState()))
        return BubbleManager(RuntimeEnvironment.getApplication(), notificationManager, chatRepository,
            dagger.Lazy<StateManagerV2> { stateManager }, preferences, garage, readings, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
    }

    @Test
    fun `chat INFO milestones log persona kind, never the raw merchant or customer name`() {
        val manager = buildManager()
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            manager.postMessage("H-E-B · $12.00 net", ChatPersona.Offer("DoorDash offer"))
            manager.postMessage("Pickup: H-E-B ready", ChatPersona.Merchant("H-E-B"))
            manager.postMessage("Heading to H-E-B's customer", ChatPersona.Customer("H-E-B's customer"))
        } finally {
            Timber.uproot(tree)
        }

        // (a) The shareable INFO+ stream never carries the raw merchant/customer text.
        tree.assertNoInfoPlusContains("H-E-B")

        // (b) The INFO milestone lines carry the persona KIND label instead.
        val infoChat = tree.records.filter { it.priority == Log.INFO && it.tag == "Chat" }
        check(infoChat.any { it.message.contains("message posted [Merchant]") }) {
            "No INFO 'Chat' line carried '[Merchant]'. Records: $infoChat"
        }
        check(infoChat.any { it.message.contains("message posted [Customer]") }) {
            "No INFO 'Chat' line carried '[Customer]'. Records: $infoChat"
        }

        check(infoChat.any { it.message.contains("message posted [Offer]") })

        // (c) The DEBUG firehose keeps full fidelity — the raw store/customer text is still there.
        tree.assertLevelContains(Log.DEBUG, "H-E-B")
    }
}
