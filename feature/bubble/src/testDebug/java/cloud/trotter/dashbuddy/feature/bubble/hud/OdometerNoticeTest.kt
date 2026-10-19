package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.core.designsystem.time.LocalNowProvider
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.feature.bubble.MutableClock
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.*
import org.junit.Assert.*
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OdometerNoticeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val clock = MutableClock(1_700_000_000_000L)
    private lateinit var originalLocale: Locale
    @Before fun setup() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        compose.mainClock.autoAdvance = false
    }
    @After fun cleanup() { Locale.setDefault(originalLocale) }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(100) }
    @Test fun `save notice expires at ten seconds without Undo`() {
        val notice = OdometerNoticeModel(52_388, clock.millis)
        assertTrue(notice.visibleAt(notice.shownAt - 400))
        assertTrue(notice.visibleAt(notice.shownAt))
        assertTrue(notice.visibleAt(notice.shownAt + 9_999))
        assertFalse(notice.visibleAt(notice.shownAt + 10_000))
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    val now by rememberNow()
                    if (notice.visibleAt(now)) OdometerNotice(notice)
                }
            }
        }
        settle()
        compose.onNodeWithText("Odometer saved, 52,388 mi").assertIsDisplayed()
        compose.onNodeWithText("Undo").assertDoesNotExist()
        clock.millis += 9_000
        compose.mainClock.advanceTimeBy(1_000); settle()
        compose.onNodeWithText("Odometer saved, 52,388 mi").assertIsDisplayed()
        clock.millis += 1_000
        compose.mainClock.advanceTimeBy(1_000); settle()
        compose.onNodeWithText("Odometer saved, 52,388 mi").assertDoesNotExist()
    }

}
