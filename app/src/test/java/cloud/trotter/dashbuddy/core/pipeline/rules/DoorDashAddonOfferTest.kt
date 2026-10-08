package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.offer.OfferQuoteBasis
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.CountUnit
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Programmatic structure only; the coordinator owns real-capture intake and golden regeneration. */
class DoorDashAddonOfferTest {
    @Test
    fun `production branch parses the paired delta card and both same-store shop rows`() {
        val offer = AddonCardTree.parse()
        assertEquals(10.50, offer.payAmount!!, 0.0)
        assertEquals(1.5, offer.distanceMiles!!, 0.0)
        assertEquals(1L, offer.timeToCompleteMinutes)
        assertEquals(45, offer.initialCountdownSeconds)
        assertNull(offer.dueByTimeText)
        assertNull(offer.dueByTimeMillis)
        assertNull(offer.offerKind)
        assertNull(offer.presentationKey)
        assertEquals(OfferQuoteBasis.INCREMENTAL, offer.quoteBasis)
        assertEquals(listOf(95, 32), offer.orders.map { it.itemCount })
        assertEquals(listOf(OrderType.SHOP_FOR_ITEMS, OrderType.SHOP_FOR_ITEMS), offer.orders.map { it.orderType })
        assertEquals(listOf(CountUnit.ITEMS, CountUnit.ITEMS), offer.orders.map { it.countUnit })
        assertEquals(127, offer.itemCount)
        assertEquals(listOf("H-E-B"), offer.displayStores)
        assertEquals(offer.offerHash, AddonCardTree.parse(AddonCardTree.card(countdown = "0:44")).offerHash)
    }

    @Test
    fun `partial cards and mismatched signs never parse offers`() {
        val negatives = listOf(
            AddonCardTree.card(accept = false),
            AddonCardTree.card(disclaimer = false),
            AddonCardTree.card(merchants = false),
            AddonCardTree.card(merchant = "   "),
            AddonCardTree.card(route = "1 stop (1.5 mi) • 1 min"),
            AddonCardTree.card(pay = "$10.50"),
            AddonCardTree.card(payOutside = true),
            AddonCardTree.card(routeOutside = true),
        )
        negatives.forEachIndexed { i, tree ->
            val match = TestRulesetFactory.screenRuleset.matchFirst(tree)
            assertNotEquals("negative $i must not parse an offer", "offer", match?.shape)
            if (i !in 4..5) assertNull("partial delta $i stays UNKNOWN over drawer chrome", match)
        }
    }

    @Test
    fun `both delta strings alone cannot forge an offer`() {
        val tree = UiNode(children = listOf(UiNode(text = "+$10.50"), UiNode(text = AddonCardTree.ROUTE))).restoreParents()
        assertNull(TestRulesetFactory.screenRuleset.matchFirst(tree))
    }
}

internal object AddonCardTree {
    const val ROUTE = "+1 stop (1.5 mi) • +1 min"
    private const val COMPOSE = "androidx.compose.ui.platform.ComposeView"
    private const val HOLDER = "androidx.compose.ui.viewinterop.ViewFactoryHolder"
    private fun view(vararg children: UiNode) = UiNode(className = "android.view.View", children = children.toList())
    private fun text(value: String) = UiNode(className = "android.widget.TextView", text = value)
    private fun holder(value: String) = UiNode(className = HOLDER, children = listOf(text(value)))
    private fun shop(merchant: String, count: String) = view(
        view(), holder(merchant), text("Test street, Test city, 78230 TX"),
        view(text(count)).copy(isClickable = true), view(),
    )

    fun card(
        pay: String = "+$10.50",
        route: String = ROUTE,
        countdown: String = "0:45",
        accept: Boolean = true,
        disclaimer: Boolean = true,
        merchants: Boolean = true,
        merchant: String = "H-E-B",
        payOutside: Boolean = false,
        routeOutside: Boolean = false,
    ): UiNode {
        val body = UiNode(className = "android.widget.ScrollView", children = buildList {
            if (!payOutside) add(text(pay))
            if (!routeOutside) add(holder(route))
            add(view(view(), text("Shop & deliver")))
            add(view())
            if (merchants) {
                add(shop(merchant, "Shop for 95 items (143 units, ~33 min)"))
                add(shop(merchant, "Shop for 32 items (33 units)"))
            }
            repeat(2) { add(view(view(), holder("Customer dropoff"))) }
            add(view())
            if (disclaimer) add(holder("Guaranteed earnings for completing the offer. A black marker or Sharpie is recommended. Items may be added before checkout."))
        })
        val footer = view(view(view(view(), view(), view(text(if (accept) "Accept" else ""), text(countdown))).copy(isClickable = true)))
        val map = UiNode(className = HOLDER, children = listOf(
            UiNode(className = "android.view.TextureView"),
            UiNode(className = COMPOSE, isClickable = true),
            UiNode(className = COMPOSE, isClickable = true),
            UiNode(className = COMPOSE, isClickable = true),
        ))
        val card = UiNode(className = COMPOSE, children = listOf(view(view(
            map, view(), view(text("Decline"), UiNode(className = "android.widget.Button")).copy(isClickable = true),
            view(view(view(), body, footer)),
        ))))
        val drawer = UiNode(className = COMPOSE, viewIdResourceName = "com.doordash.driverapp:id/side_nav_content_container",
            children = listOf(text("Test D."), text("Ratings"), text("Promos"), text("Preferences")))
        return view(card, drawer, text(if (payOutside) pay else ""), text(if (routeOutside) route else "")).restoreParents()
    }

    fun parse(tree: UiNode = card()): ParsedOffer {
        val match = requireNotNull(TestRulesetFactory.screenRuleset.matchFirst(tree))
        assertEquals("doordash.screen.offer_popup", match.ruleId)
        return (ParsedFieldsFactory.create(match.shape, match.fields) as ParsedFields.OfferFields).parsedOffer
    }
}
