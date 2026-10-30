package cloud.trotter.log.gig.ui.bubble

import android.app.Notification
import android.app.NotificationManager
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.data.chat.ChatRepository
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.data.vehicle.Garage
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.log.gig.core.state.StateManagerV2
import cloud.trotter.log.gig.domain.evaluation.EvaluationConfig
import cloud.trotter.log.gig.domain.evaluation.OfferEvaluator
import cloud.trotter.log.gig.domain.model.cards.FlowCardSnapshot
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.model.order.OrderType
import cloud.trotter.log.gig.domain.model.order.ParsedOrder
import cloud.trotter.log.gig.domain.state.*
import cloud.trotter.log.gig.feature.bubble.hud.OfferCardMapper
import cloud.trotter.log.gig.feature.bubble.notification.OfferNotificationModel
import cloud.trotter.log.gig.feature.bubble.notification.offerNotificationHero
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Clock

// The Notification RemoteViews fields are deprecated, and they are the test's only window onto what SystemUI inflates.
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
class OfferHeadsUpViewsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val config = EvaluationConfig()
    private val notifications: NotificationManager = mock()
    private val offer = ParsedOffer("s2g", payAmount = 11.75, distanceMiles = 6.4, itemCount = 12,
        orders = listOf(ParsedOrder(0, OrderType.SHOP_FOR_ITEMS, "Store", 12, false, emptySet())))

    private fun pending(parsed: ParsedOffer = offer) = PendingOffer(parsed.offerHash,
        ParsedFields.OfferFields(parsedOffer = parsed), presentedAt = 1_000L,
        evaluation = OfferEvaluator().evaluate(parsed, config), returnFlow = Flow.TaskPickupNavigation,
        sourceRuleId = "doordash.screen.offer", countdownExpiresAt = System.currentTimeMillis() + 30_000)

    private fun manager(): BubbleManager {
        val preferences: AppPreferencesRepository = mock()
        val garage: GarageRepository = mock()
        val readings: OdometerReadingRepository = mock()
        val state: StateManagerV2 = mock()
        whenever(preferences.odometerPrompts).thenReturn(flowOf(false))
        whenever(garage.garage).thenReturn(flowOf(Garage(emptyList(), null, readable = true)))
        whenever(readings.readings).thenReturn(flowOf(emptyList()))
        whenever(state.state).thenReturn(MutableStateFlow(AppState()))
        return BubbleManager(context, notifications, mock<ChatRepository>(), dagger.Lazy { state },
            preferences, garage, readings, Clock.systemUTC())
    }

    private fun post(pending: PendingOffer, job: Job? = null): Notification {
        // Deliberately stale snapshot: custom views must render the PendingOffer instead.
        val snapshot = FlowCardSnapshot.Offer(phaseStartedAt = 0L, offerHash = pending.offerHash, payAmount = 999.0)
        manager().postOfferNotification(snapshot, pending.evaluation!!, pending.platform,
            refreshOnly = true, pending = pending, config = config, job = job)
        val captor = argumentCaptor<Notification>()
        verify(notifications).notify(eq(BubbleManager.offerNotificationId(pending.offerHash)), captor.capture())
        return captor.firstValue
    }

    @Test fun `both custom surfaces render the HUD figures and remove the old fields`() {
        val pending = pending()
        val model = OfferNotificationModel.from(OfferCardMapper.map(pending, null, emptyList(), config))
        val notification = post(pending)
        val expanded = notification.bigContentView.apply(context, FrameLayout(context))
        assertEquals(model.store, expanded.findViewById<TextView>(R.id.notif_offer_store).text.toString())
        assertEquals(offerNotificationHero(model.pay, true).toString(), expanded.findViewById<TextView>(R.id.notif_offer_hero).text.toString())
        assertEquals(3, model.rates.size)
        listOf(R.id.notif_offer_rate_0, R.id.notif_offer_rate_1, R.id.notif_offer_rate_2).forEachIndexed { index, id ->
            val rate = model.rates[index]
            assertEquals(rate.value + rate.unit, expanded.findViewById<TextView>(id).text.toString())
        }
        assertNotNull(expanded.findViewById<ImageView>(R.id.notif_offer_bar).drawable)
        assertNotNull(expanded.findViewById<ImageView>(R.id.notif_offer_gauge).drawable)
        assertRemoved(expanded)
        for (remote in listOf(notification.contentView, notification.headsUpContentView)) {
            val compact = remote.apply(context, FrameLayout(context))
            assertEquals(offerNotificationHero(model.pay, false).toString(), compact.findViewById<TextView>(R.id.notif_offer_hero).text.toString())
            assertNotNull(compact.findViewById<ImageView>(R.id.notif_offer_gauge).drawable)
            assertEquals(View.VISIBLE, compact.findViewById<TextView>(R.id.notif_offer_profit).visibility)
            assertRemoved(compact)
        }
    }

    /** Review r1 (Sol): an add-on over an open job is the bubble's COMBINED card, not the raw delta. */
    @Test fun `an add on over the open job shows the same combined card as the bubble`() {
        val accepted = AcceptedOfferEconomics("before", payAmount = 11.75, netPay = 8.29,
            estMinutes = 31.0, distanceMiles = 6.4, acceptedAt = 0,
            storeHints = listOf("Store"), isShop = true, quotedItemCount = 12)
        val job = Job("job", listOf("Store"), "before", listOf(accepted), listOf(
            Task("pickup", "job", TaskPhase.PICKUP, expectedStoreHint = "Store", startedAt = 0),
            Task("drop", "job", TaskPhase.DROPOFF, startedAt = 0)), startedAt = 0)
        val addOn = offer.copy(offerHash = "addon", quoteBasis = OfferQuoteBasis.INCREMENTAL,
            offerScope = cloud.trotter.log.gig.domain.model.offer.OfferScope.ADD_ON, payAmount = 7.0,
            distanceMiles = 2.1, timeToCompleteMinutes = 12, stopCount = 2, itemCount = 15,
            orders = listOf(ParsedOrder(0, OrderType.SHOP_FOR_ITEMS, "Store", 12, false, emptySet()),
                ParsedOrder(1, OrderType.SHOP_FOR_ITEMS, "New", 3, false, emptySet())))
        val pending = pending(addOn)
        val card = OfferCardMapper.map(pending, job, emptyList(), config)
        assertTrue("fixture must map to the combined card", card is cloud.trotter.log.gig.feature.bubble.hud.OfferCardModel.Combined)
        val model = OfferNotificationModel.from(card)
        val expanded = post(pending, job).bigContentView.apply(context, FrameLayout(context))
        assertEquals("the combined gross, not the \$7.00 delta", offerNotificationHero(model.pay, true).toString(),
            expanded.findViewById<TextView>(R.id.notif_offer_hero).text.toString())
        assertEquals(View.VISIBLE, expanded.findViewById<View>(R.id.notif_offer_bar).visibility)
        assertEquals(View.VISIBLE, expanded.findViewById<View>(R.id.notif_offer_gauge).visibility)
        assertEquals(View.GONE, expanded.findViewById<View>(R.id.notif_offer_addon).visibility)
    }

    @Test fun `raw add on hides scored imagery and shows only delta figures`() {
        val pending = pending(offer.copy(quoteBasis = OfferQuoteBasis.INCREMENTAL, timeToCompleteMinutes = 12))
        val model = OfferNotificationModel.from(OfferCardMapper.map(pending, null, emptyList(), config))
        val notification = post(pending)
        val expanded = notification.bigContentView.apply(context, FrameLayout(context))
        assertEquals(model.addOnFigures, expanded.findViewById<TextView>(R.id.notif_offer_addon).text.toString())
        for (id in listOf(R.id.notif_offer_bar, R.id.notif_offer_gauge, R.id.notif_offer_rate_0,
            R.id.notif_offer_rate_1, R.id.notif_offer_rate_2)) {
            assertEquals(View.GONE, expanded.findViewById<View>(id).visibility)
        }
        val compact = notification.headsUpContentView.apply(context, FrameLayout(context))
        assertEquals(View.GONE, compact.findViewById<View>(R.id.notif_offer_profit).visibility)
        assertEquals(View.GONE, compact.findViewById<View>(R.id.notif_offer_gauge).visibility)
    }

    @Test fun `unknown distance keeps the ring placeholder and neutral bar without compact profit`() {
        val pending = pending(offer.copy(distanceMiles = null))
        val model = OfferNotificationModel.from(OfferCardMapper.map(pending, null, emptyList(), config))
        val notification = post(pending)
        val expanded = notification.bigContentView.apply(context, FrameLayout(context))
        assertNotNull(expanded.findViewById<ImageView>(R.id.notif_offer_bar).drawable)
        assertNotNull(expanded.findViewById<ImageView>(R.id.notif_offer_gauge).drawable)
        assertEquals(model.rates[0].value + model.rates[0].unit,
            expanded.findViewById<TextView>(R.id.notif_offer_rate_0).text.toString())
        val compact = notification.contentView.apply(context, FrameLayout(context))
        assertEquals(View.GONE, compact.findViewById<View>(R.id.notif_offer_profit).visibility)
    }

    private fun assertRemoved(view: View) {
        val removed = listOf("notif_offer_verdict", "notif_offer_metrics", "notif_offer_badges",
            "notif_offer_rate", "notif_offer_sub") + (0..4).map { "notif_badge_$it" }
        for (name in removed) {
            val id = context.resources.getIdentifier(name, "id", context.packageName)
            assertNull("$name must not be in the custom view", view.findViewById<View>(id))
        }
        assertNotNull(view.findViewById<TextView>(R.id.notif_btn_accept))
        assertNotNull(view.findViewById<TextView>(R.id.notif_btn_decline))
    }
}
