package cloud.trotter.log.gig.feature.bubble

import androidx.compose.runtime.CompositionLocalProvider
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.feature.bubble.hud.DispatcherRow
import cloud.trotter.log.gig.feature.bubble.hud.LogView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import cloud.trotter.log.gig.core.designsystem.component.AppStatTile
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.feature.bubble.hud.StateCard
import cloud.trotter.log.gig.feature.bubble.hud.StateCardModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposeSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `stat tile renders its label and value`() {
        compose.setContent {
            GigLogTheme {
                AppStatTile(label = "Earned", value = "$54.60")
            }
        }

        compose.onNodeWithText("$54.60").assertIsDisplayed()
        compose.onNodeWithText("EARNED").assertIsDisplayed()
    }

    @Test
    fun `state receipt card renders its total`() {
        compose.setContent {
            GigLogTheme {
                StateCard(StateCardModel.Receipt("Store", 1_700_000_000_000L,
                    lines = emptyList(), tip = 4.0, total = 11.75), kept = null)
            }
        }
        compose.onNodeWithText("Delivery total").assertIsDisplayed()
        compose.onNodeWithText("$11.75").assertIsDisplayed()
    }
    @Test
    fun `log renders with the feature resources`() {
        compose.mainClock.autoAdvance = false
        val clock = MutableClock(1_700_000_000_000L)
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    LogView(listOf(DispatcherRow("chat:start", clock.millis, "DoorDash shift started")), {}, {})
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("DoorDash shift started").assertIsDisplayed()
    }

}
