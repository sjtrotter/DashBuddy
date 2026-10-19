package cloud.trotter.dashbuddy.ui.main.settings.garage

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.ui.components.vehicle.displayName
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GarageDisplayNameTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `display name prefers custom name then identity then type`() {
        val profile = GasCar(year = "2020", make = "Honda", model = "Civic")
        compose.setContent {
            DashBuddyTheme {
                Column {
                    Text(Vehicle("named", "Commute", 1, profile).displayName())
                    Text(Vehicle("identity", null, 2, profile).displayName())
                    Text(Vehicle("type", null, 3, Bicycle()).displayName())
                }
            }
        }
        compose.onNodeWithText("Commute").assertIsDisplayed()
        compose.onNodeWithText("2020 Honda Civic").assertIsDisplayed()
        compose.onNodeWithText("Bicycle").assertIsDisplayed()
    }

    @Test fun `garage repeats identity only below a custom name`() {
        val vehicles = listOf(
            Vehicle("identity", null, 1, GasCar(year = "2020", make = "Honda", model = "Civic")),
            Vehicle("named", "Commute", 2, GasCar(year = "2021", make = "Toyota", model = "Corolla")),
        )
        compose.setContent {
            DashBuddyTheme {
                GarageList(Garage(vehicles, "identity", true), false, GarageWriteFailure.NONE, {}, {}, {}, {})
            }
        }
        compose.onAllNodesWithText("2020 Honda Civic", substring = true).assertCountEquals(1)
        compose.onNodeWithText("Car").assertIsDisplayed()
        compose.onNodeWithText("Commute").assertIsDisplayed()
        compose.onNodeWithText("Car · 2021 Toyota Corolla").assertIsDisplayed()
    }
}
