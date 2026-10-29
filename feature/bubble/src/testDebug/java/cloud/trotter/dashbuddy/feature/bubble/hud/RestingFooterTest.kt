package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.time.LocalNowProvider
import cloud.trotter.dashbuddy.domain.format.formatShortDate
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
class RestingFooterTest {
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
    private val reading = LastOdometerReading(52_388, 157.3, clock.millis)
    private var last by mutableStateOf<LastOdometerReading?>(reading)
    private var reconciliation = ReconciledMileage(59, 57.0, reading.occurredAt - 86_400_000)
    private var opened = false
    private var vehicle = Vehicle("civic", "Civic", 0)
    private fun show() {
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    OdometerFooter(vehicle, last, reconciliation) { opened = true }
                }
            }
        }
        settle()
    }
    @Test fun `shows last reading with vehicle name`() {
        show()
        compose.onNodeWithText("Civic 52,388 mi, ${formatShortDate(reading.occurredAt)}").assertIsDisplayed().performClick()
        compose.onNodeWithText("odometer 59 mi, tracked 57.0 mi since ${formatShortDate(reading.occurredAt - 86_400_000)}").assertIsDisplayed()
        compose.onNodeWithText("other 2.0 mi").assertIsDisplayed()
        assertTrue(opened)
    }
    @Test fun `unnamed vehicle uses the resource type label`() {
        vehicle = Vehicle("bike", null, 0, EBike())
        show()
        compose.onNodeWithText("E-bike 52,388 mi, ${formatShortDate(reading.occurredAt)}").assertIsDisplayed()
    }

    @Test fun `hidden without a reading`() {
        last = null; show()
        compose.onNodeWithText("Civic", substring = true).assertDoesNotExist()
        compose.onNodeWithText("odometer", substring = true).assertDoesNotExist()
    }

    @Test fun `a negative other figure reads as a shortfall`() {
        reconciliation = ReconciledMileage(59, 79.0, reading.occurredAt - 86_400_000)
        show()
        compose.onNodeWithText("odometer 59 mi, tracked 79.0 mi since ${formatShortDate(reconciliation.since)}").assertIsDisplayed()
        compose.onNodeWithText("20.0 mi fewer than tracked").assertIsDisplayed()
        compose.onNodeWithText("other -20.0 mi").assertDoesNotExist()
    }

    @Test fun `a positive other figure stays a figure`() {
        show()
        compose.onNodeWithText("other 2.0 mi").assertIsDisplayed()
        compose.onNodeWithText("fewer than tracked", substring = true).assertDoesNotExist()
    }
}
