package cloud.trotter.dashbuddy.ui

import cloud.trotter.dashbuddy.feature.bubble.hud.DispatcherRow
import cloud.trotter.dashbuddy.feature.bubble.hud.LogView
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
                    LogView(listOf(DispatcherRow("chat:start", T0, "DoorDash shift started")), {}, {})
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("DoorDash shift started").assertIsDisplayed()
    }

    @Test
    fun `garage name selects and separate gear opens details with divider and no trash`() {
        val edited = mutableListOf<String>()
        val selected = mutableListOf<String>()
        compose.setContent {
            DashBuddyTheme {
                GarageList(
                    garage = Garage(listOf(Vehicle("civic", "Civic", 1), Vehicle("truck", "Truck", 2)), "civic", true),
                    busy = false, failure = GarageWriteFailure.NONE,
                    onSelect = { selected += it }, onEdit = { edited += it }, onAdd = {},
                )
            }
        }
        compose.onNodeWithText("Civic").assertIsDisplayed()
        compose.onNodeWithText("Truck").assertIsDisplayed()
        compose.onAllNodes(isSelectable())[0].assertIsSelected()
        compose.onAllNodes(isSelectable())[1].assertIsNotSelected()
        compose.onNodeWithText("Truck").performClick()
        assertEquals(listOf("truck"), selected)
        assertEquals(emptyList<String>(), edited)
        compose.onNodeWithContentDescription("Civic settings").performClick()
        assertEquals(listOf("civic"), edited)
        assertEquals(listOf("truck"), selected)
        for (id in listOf("civic", "truck")) {
            compose.onNodeWithTag("garage.vehicle.divider.$id")
                .assertIsDisplayed().assertWidthIsEqualTo(1.dp).assertHeightIsEqualTo(32.dp)
        }
        compose.onAllNodes(hasContentDescription("Retire", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("Retire").assertDoesNotExist()
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
