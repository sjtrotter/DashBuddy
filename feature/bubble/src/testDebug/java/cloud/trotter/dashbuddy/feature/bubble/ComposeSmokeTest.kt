package cloud.trotter.dashbuddy.feature.bubble

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import cloud.trotter.dashbuddy.core.designsystem.component.AppStatTile
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.feature.bubble.hud.StateCard
import cloud.trotter.dashbuddy.feature.bubble.hud.StateCardModel
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
            DashBuddyTheme {
                AppStatTile(label = "Earned", value = "$54.60")
            }
        }

        compose.onNodeWithText("$54.60").assertIsDisplayed()
        compose.onNodeWithText("EARNED").assertIsDisplayed()
    }

    @Test
    fun `state receipt card renders its total`() {
        compose.setContent {
            DashBuddyTheme {
                StateCard(StateCardModel.Receipt("Store", 1_700_000_000_000L,
                    lines = emptyList(), tip = 4.0, total = 11.75), kept = null)
            }
        }
        compose.onNodeWithText("Delivery total").assertIsDisplayed()
        compose.onNodeWithText("$11.75").assertIsDisplayed()
    }
}
