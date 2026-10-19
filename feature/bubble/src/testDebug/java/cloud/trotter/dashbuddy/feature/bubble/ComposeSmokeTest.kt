package cloud.trotter.dashbuddy.feature.bubble

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import cloud.trotter.dashbuddy.core.designsystem.component.AppStatTile
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.time.LocalNowProvider
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.feature.bubble.cards.FlowCardItem
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
    fun `awaiting card ticks with the provided clock`() {
        val clock = MutableClock(T0 + 5_000)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    FlowCardItem(
                        snapshot = FlowCardSnapshot.Awaiting(
                            id = "awaiting:test",
                            phaseStartedAt = T0,
                            phaseEndedAt = null,
                            sessionId = null,
                        ),
                        isActive = true,
                        expanded = true,
                        onToggleExpand = {},
                    )
                }
            }
        }

        compose.onNodeWithText("5s").assertExists()

        clock.millis = T0 + 65_000
        // A 1,000 ms advance rounds to whole frames and can miss the recomposition that shows the new value;
        // 2,000 ms leaves headroom.
        compose.mainClock.advanceTimeBy(2_000)

        compose.onNodeWithText("1m 5s").assertExists()
        compose.onNodeWithText("5s").assertDoesNotExist()
    }
    private companion object {
        const val T0 = 1_700_000_000_000L
    }
}
