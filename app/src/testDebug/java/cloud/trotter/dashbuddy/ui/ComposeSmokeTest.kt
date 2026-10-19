package cloud.trotter.dashbuddy.ui

import cloud.trotter.dashbuddy.feature.bubble.hud.DispatcherRow
import cloud.trotter.dashbuddy.feature.bubble.hud.LogView
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.ui.main.settings.garage.GarageList
import cloud.trotter.dashbuddy.ui.main.settings.garage.GarageWriteFailure
import org.junit.Assert.assertEquals
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

    @Test
    fun `log renders under the app resources`() {
        val clock = MutableClock(T0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    LogView(listOf(DispatcherRow("chat:start", T0, "Started dashing")), {}, {})
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Started dashing").assertIsDisplayed()
    }

    @Test
    fun `garage shows vehicles active selection and retire confirmation`() {
        var retired: String? = null
        var edited: String? = null
        var selected: String? = null
        compose.setContent {
            DashBuddyTheme {
                GarageList(
                    garage = Garage(listOf(Vehicle("civic", "Civic", 1), Vehicle("truck", "Truck", 2)), "civic", true),
                    busy = false, failure = GarageWriteFailure.NONE,
                    onSelect = { selected = it }, onRetire = { retired = it }, onEdit = { edited = it }, onAdd = {},
                )
            }
        }
        compose.onNodeWithText("Civic").assertIsDisplayed()
        compose.onNodeWithText("Truck").assertIsDisplayed()
        compose.onAllNodes(isSelectable())[0].assertIsSelected()
        compose.onAllNodes(isSelectable())[1].assertIsNotSelected().performClick()
        assertEquals("truck", selected)
        compose.onNodeWithText("Civic").performClick()
        assertEquals("civic", edited)
        assertEquals("truck", selected)
        compose.onNodeWithContentDescription("Retire Civic").performClick()
        compose.onNodeWithText("Retire Civic? Its readings stay.").assertIsDisplayed()
        assertEquals(null, retired)
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Retire Civic? Its readings stay.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Retire Civic").performClick()
        compose.onNodeWithText("Retire").performClick()
        assertEquals("civic", retired)
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
