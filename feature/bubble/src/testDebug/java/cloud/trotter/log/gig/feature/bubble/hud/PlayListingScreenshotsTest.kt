package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.model.vehicle.Vehicle
import cloud.trotter.log.gig.domain.offer.story.OfferFixtures
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.feature.bubble.R
import cloud.trotter.log.gig.feature.bubble.hud.vehicle.VehicleChipModel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1416 — the bubble for the Play listing's phone screenshots, rendered into `play-listing/<name>-<theme>.png` with
 * the PR CI screenshots (`tools/play/stage_listing_images.sh` stages them). Runs only with `-PscreenShots=true`.
 *
 * The real [HudScreen] at the bubble's commonly visible size (DESIGN.md: about 510 dp of the 600 dp it asks for),
 * the screen's width less the bubble's margins, on a plain surface with one caption line above it, outside the HUD.
 * The phone is 412 x 732 dp at 420 dpi (about 1081 x 1921 px, Play's 1080 x 1920 recommendation). Sample data only:
 * made-up store and car names, on [HudScreenFixture]'s clock. Supported-platform labels (the HUD head's "DoorDash")
 * are allowed; merchant, brand and vehicle names are not, and no text may read "debug".
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h732dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class PlayListingScreenshotsTest(theme: String) : HudShotFixture(theme) {

    private val f = OfferFixtures
    private val car = Vehicle("car", "Sample car", 0)
    private val chip = VehicleChipModel(car, listOf(car), car.id, false)
    private val caption = RuntimeEnvironment.getApplication().getString(R.string.play_shot_bubble_caption)

    /** The standard offer card with Accept and Decline, 27 seconds left. */
    @Test fun offer() {
        val o = f.offer().copy(orders = listOf(f.order("Sample Grocery")))
        val model = mapOffer(presentedNow(f.pending(o)), null, f.config)
        val s = state(vehicleChip = chip, flow = Flow.OfferPresented)
        listing("offer", {
            compose.onNodeWithTag("offer.accept").assertIsDisplayed()
            compose.onAllNodesWithText("Sample Grocery")[0].assertIsDisplayed()
        }) {
            HudScreen(s, ribbon(RibbonKind.WAIT), {}, onGoalClick = {}, offer = { OfferCard(model, {}, {}) })
        }
    }

    /** On the way to a pickup: the shift's earnings, today's goal progress, time online and miles. */
    @Test fun shift() {
        val s = state(vehicleChip = chip, flow = Flow.TaskPickupNavigation, goal = 150.0, today = 71.2)
        val card = StateCardModel.PickupNav(JobFacts("Sample Grocery", T0 + 240_000, 7.9, 5.4), T0 - 120_000)
        listing("shift", {
            compose.onNodeWithContentDescription("Earned $71.20").assertIsDisplayed()
            compose.onNodeWithTag("hud.goal").assertIsDisplayed()
            compose.onNodeWithText("to go").assertIsDisplayed()
            value("hud.top.miles", "42.5").assertIsDisplayed()
        }) {
            HudScreen(s, ribbon(RibbonKind.PICKUP), {}, onGoalClick = {},
                stateCard = { StateCard(card, s.hero.earnings) })
        }
    }

    /** The shift log: two jobs, a declined offer and an expense, without the delivery app's dispatcher lines. */
    @Test fun log() {
        val s = state(vehicleChip = chip, flow = Flow.TaskDropoffArrived, logOpen = true, goal = 150.0, today = 71.2)
        val items = logItems("Sample Grocery", "Sample Pizza", "Noodle House").filterNot { it is DispatcherRow }
        listing("log", { compose.onNodeWithTag("hud.log.now").assertExists() }) {
            HudScreen(s, ribbon(RibbonKind.DROPOFF), {}, onGoalClick = {}, log = { LogView(items, {}, {}) })
        }
    }

    private fun listing(name: String, assertion: () -> Unit, hud: @Composable () -> Unit) =
        render("play-listing/$name", assertion = {
            compose.onNodeWithText(caption).assertIsDisplayed()
            NOT_SAMPLE_NAMES.forEach { compose.onAllNodesWithText(it, substring = true).assertCountEquals(0) }
            compose.onAllNodesWithText("debug", substring = true, ignoreCase = true).assertCountEquals(0)
            compose.onAllNodes(hasContentDescription("debug", substring = true, ignoreCase = true)).assertCountEquals(0)
            assertion()
        }) { Frame(hud) }

    @Composable
    private fun Frame(hud: @Composable () -> Unit) {
        val c = AppTheme.colors
        val shape = RoundedCornerShape(20.dp)
        Column(Modifier.fillMaxSize().background(c.surface2).padding(horizontal = PANEL_MARGIN),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(caption, Modifier.padding(bottom = 16.dp), color = c.text2, style = MaterialTheme.typography.titleMedium)
            Box(Modifier.fillMaxWidth().height(PANEL_HEIGHT).clip(shape).border(1.dp, c.line, shape)) { hud() }
        }
    }

    companion object {
        /** The bubble's commonly visible height (DESIGN.md, Two surfaces). */
        private val PANEL_HEIGHT = 510.dp
        private val PANEL_MARGIN = 8.dp

        /** Names the other HUD fixtures use that are not made up; none may reach a listing picture. */
        private val NOT_SAMPLE_NAMES = listOf("Civic", "Dash Paused", "Dash Resumed")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf<Any>("light"), arrayOf<Any>("dark"))
    }
}
