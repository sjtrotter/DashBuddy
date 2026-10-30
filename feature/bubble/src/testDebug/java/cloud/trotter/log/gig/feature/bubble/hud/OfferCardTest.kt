package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.model.cards.FlowCardSnapshot
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.offer.OfferBadge
import cloud.trotter.log.gig.feature.bubble.MutableClock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfferCardTest {
    @get:Rule val compose = createComposeRule()
    private val clock = MutableClock(4_000)
    private lateinit var locale: Locale
    private val f = OfferFixtures
    private fun standard() = OfferCardMapper.map(f.pending(), null, emptyList(), f.config)

    @Before fun setup() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        compose.mainClock.autoAdvance = false
    }
    @After fun cleanup() { Locale.setDefault(locale) }
    private fun show(model: OfferCardModel = standard(), accept: () -> Unit = {}, decline: () -> Unit = {}) {
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    Box(Modifier.fillMaxSize()) { OfferCard(model, accept, decline) }
                }
            }
        }
        settle()
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(100) }
    private fun tick(at: Long) {
        compose.runOnIdle { clock.millis = at }
        settle()
        compose.mainClock.advanceTimeBy(2_000)
    }
    private fun value(tag: String, text: String) = compose.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)

    @Test fun `countdown ticks down once a second`() {
        show()
        value("offer.countdown", "0:27").assertExists()
        tick(5_000)
        value("offer.countdown", "0:26").assertExists()
        tick(31_000)
        value("offer.countdown", "0:00").assertExists()
        tick(90_000)
        value("offer.countdown", "0:00").assertExists()
    }
    @Test fun `hero shows gross pay with cents small`() {
        show()
        val whole = value("offer.hero", "11")
        val fraction = value("offer.hero", ".75")
        whole.assertExists()
        fraction.assertExists()
        val wholeLayout = mutableListOf<TextLayoutResult>()
        val fractionLayout = mutableListOf<TextLayoutResult>()
        whole.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(wholeLayout) }
        fraction.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(fractionLayout) }
        assertEquals(56f, wholeLayout.single().layoutInput.style.fontSize.value, 0.0f)
        assertTrue(fractionLayout.single().layoutInput.style.fontSize.value < wholeLayout.single().layoutInput.style.fontSize.value)
    }
    @Test fun `pay bar pills read profit and cost`() {
        show()
        value("offer.bar", "$8.29").assertExists()
        value("offer.bar", "profit").assertExists()
        value("offer.bar", "$3.46").assertExists()
        value("offer.bar", "cost").assertExists()
        compose.onNodeWithTag("offer.bar").assertContentDescriptionEquals("Profit $8.29, cost $3.46")
    }
    @Test fun `loss bar shows the loss pill`() {
        val p = f.pending(f.offer().copy(payAmount = 6.0), f.evaluation().copy(netPayAmount = -1.67))
        show(OfferCardMapper.map(p, null, emptyList(), f.config))
        value("offer.bar", "$1.67").assertExists()
        value("offer.bar", "loss").assertExists()
        compose.onNodeWithTag("offer.bar").assertContentDescriptionEquals("Loss $1.67, cost $7.67")
    }
    @Test fun `distance unknown shows the flat bar and em dash rates`() {
        show(OfferCardMapper.map(f.pending(f.offer().copy(distanceMiles = null)), null, emptyList(), f.config))
        value("offer.bar", "distance unknown").assertExists()
        GaugeKind.entries.forEach { kind ->
            compose.onAllNodes(hasText(EMPTY_VALUE) and hasAnyAncestor(hasTestTag("offer.gauge.$kind")),
                useUnmergedTree = true).assertCountEquals(if (kind == GaugeKind.ITEM) 1 else 2)
        }
        compose.onNodeWithTag("offer.ring").assertContentDescriptionEquals("No score")
    }
    @Test fun `gauges show denominators and rates with units`() {
        show()
        listOf("31", "min", "$16", "/hr").forEach { value("offer.gauge.HOUR", it).assertExists() }
        listOf("6.4", "mi", "$1.30", "/mi").forEach { value("offer.gauge.MILE", it).assertExists() }
        listOf("12", "items", "69¢", "/ea").forEach { value("offer.gauge.ITEM", it).assertExists() }
        compose.onNodeWithTag("offer.gauge.ITEM").assertContentDescriptionEquals("Item target comes from your hourly target")
    }
    @Test fun `gauge state says above or below target`() {
        show()
        compose.onNodeWithTag("offer.gauge.HOUR").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Below target"))
        compose.onNodeWithTag("offer.gauge.ITEM").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Above target"))
    }
    @Test fun `trip line draws one store per pickup and one house per drop`() {
        val o = f.offer().copy(orders = listOf(f.order(), f.order()))
        show(OfferCardMapper.map(f.pending(o), null, emptyList(), f.config))
        compose.onAllNodesWithTag("offer.trip.node").assertCountEquals(4)
        compose.onAllNodesWithContentDescription("Pickup").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Drop-off").assertCountEquals(2)
    }
    @Test fun `accept and decline call their lambdas`() {
        var accepted = 0
        var declined = 0
        show(accept = { accepted++ }, decline = { declined++ })
        compose.onNodeWithTag("offer.accept").performClick()
        compose.onNodeWithTag("offer.decline").performClick()
        compose.runOnIdle { assertEquals(1, accepted); assertEquals(1, declined) }
    }
    @Test fun `combined add on shows the added chip, the was score and the deltas`() {
        val before = FlowCardSnapshot.Offer(0, 1, "before", evaluationScore = 60.0, outcome = AppEventType.OFFER_ACCEPTED)
        show(OfferCardMapper.map(f.pending(f.addon()), f.job(), listOf(before), f.config))
        compose.onNodeWithText("Add-on").assertExists()
        compose.onNodeWithText("+$7.00 added").assertExists()
        compose.onNodeWithText("was 60").assertExists()
        value("offer.gauge.MILE", "+2.1").assertExists()
        value("offer.gauge.ITEM", "+3").assertExists()
        compose.onNodeWithTag("offer.addon.tiles").assertDoesNotExist()
    }
    @Test fun `fallback add on shows four delta tiles and the unscored line`() {
        show(OfferCardMapper.map(f.pending(f.addon()), null, emptyList(), f.config))
        listOf("12", "2.1", "2", "15").forEach { value("offer.addon.tiles", it).assertExists() }
        compose.onNodeWithText("Added to your current job. Unscored, since the card shows only what this stop adds.").assertExists()
        compose.onNodeWithTag("offer.ring").assertDoesNotExist()
        compose.onNodeWithTag("offer.bar").assertDoesNotExist()
        GaugeKind.entries.forEach { compose.onNodeWithTag("offer.gauge.$it").assertDoesNotExist() }
    }
    @Test fun `score ring reads its score`() {
        show()
        compose.onNodeWithTag("offer.ring").assertContentDescriptionEquals("Score 74")
    }
    @Test fun `offer badge copy uses sentence case`() {
        val o = f.offer().copy(badges = setOf(OfferBadge.HIGH_PAYING))
        show(OfferCardMapper.map(f.pending(o), null, emptyList(), f.config))
        compose.onNodeWithText("High paying").assertExists()
        compose.onNodeWithText("Shop and deliver").assertExists()
    }
    @Test fun `unparsed pay shows an unknown hero`() {
        show(OfferCardMapper.map(f.pending(f.offer().copy(payAmount = null)), null, emptyList(), f.config))
        value("offer.hero", EMPTY_VALUE).assertExists()
        compose.onNodeWithText("$0.00").assertDoesNotExist()
    }
    @Test fun `no countdown anchor draws no countdown`() {
        show(OfferCardMapper.map(f.pending().copy(countdownExpiresAt = null), null, emptyList(), f.config))
        compose.onNodeWithTag("offer.countdown").assertDoesNotExist()
    }
    @Test fun `offer state writes settle before the countdown clock advances`() {
        val state = mutableStateOf(standard())
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) { OfferCard(state.value, {}, {}) }
            }
        }
        settle()
        compose.runOnIdle { state.value = OfferCardMapper.map(f.pending().copy(countdownExpiresAt = 41_000), null, emptyList(), f.config) }
        settle()
        tick(5_000)
        value("offer.countdown", "0:36").assertExists()
    }

    @Test fun `card sections run bar then trip then gauges then badges`() {
        show()
        val bar = compose.onNodeWithTag("offer.bar", useUnmergedTree = true).getBoundsInRoot().top
        val trip = compose.onAllNodesWithTag("offer.trip.node", useUnmergedTree = true).onFirst().getBoundsInRoot().top
        val gauge = compose.onNodeWithTag("offer.gauge.HOUR", useUnmergedTree = true).getBoundsInRoot().top
        val badges = compose.onNodeWithTag("offer.badges", useUnmergedTree = true).getBoundsInRoot().top
        org.junit.Assert.assertTrue("$bar < $trip < $gauge < $badges", bar < trip && trip < gauge && gauge < badges)
    }
    @Test fun `badges render without a clock`() {
        compose.setContent {
            GigLogTheme { OfferBadges(listOf(OfferBadgeModel.Platform(OfferBadge.HIGH_PAYING),
                OfferBadgeModel.Shop, OfferBadgeModel.AddOn)) }
        }
        settle()
        compose.onNodeWithTag("offer.badges").assertExists()
        listOf("High paying", "Shop and deliver", "Add-on").forEach { compose.onNodeWithText(it).assertExists() }
    }
    @Test fun `overflow descriptions pluralize more stops`() {
        val overflow = mutableStateOf(1)
        compose.setContent { GigLogTheme { TripLine(TripLineModel(listOf(TripNode(TrackIcon.NAV_PIN, overflow = overflow.value)))) } }
        settle()
        compose.onNodeWithContentDescription("1 more stop").assertExists()
        compose.runOnIdle { overflow.value = 2 }
        settle()
        compose.onNodeWithContentDescription("2 more stops").assertExists()
    }

}
