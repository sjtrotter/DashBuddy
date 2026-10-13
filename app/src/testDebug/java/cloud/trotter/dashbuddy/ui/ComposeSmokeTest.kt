package cloud.trotter.dashbuddy.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import cloud.trotter.dashbuddy.core.designsystem.component.AppStatTile
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.time.LocalNowProvider
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.domain.format.formatDuration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposeSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `stat tile renders under the app's resources`() {
        compose.setContent {
            DashBuddyTheme {
                AppStatTile(label = "Kept", value = "$31.10")
            }
        }

        compose.onNodeWithText("$31.10").assertIsDisplayed()
        compose.onNodeWithText("KEPT").assertIsDisplayed()
    }

    @Test
    fun `rememberNow follows the provided clock`() {
        val clock = MutableClock(T0 + 5_000)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    Elapsed(anchor = T0)
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

    private class MutableClock(var millis: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
        override fun millis(): Long = millis
    }

    private companion object {
        const val T0 = 1_700_000_000_000L
    }
}

@Composable
private fun Elapsed(anchor: Long) {
    val now by rememberNow()
    Text(formatDuration(now - anchor))
}
