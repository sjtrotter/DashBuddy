package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.domain.analytics.ExpenseCategory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class LogThinRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `every expense category shows its nonblank label after the category`() {
        var row by mutableStateOf(ExpenseRow(1, 1_700_000_000_000, ExpenseCategory.CAR_CARE, "wipers", 18.4))
        compose.setContent { DashBuddyTheme { LogThinRow(row) } }
        val names = mapOf(ExpenseCategory.FOOD to "Food", ExpenseCategory.PARKING to "Parking",
            ExpenseCategory.CAR_CARE to "Car care", ExpenseCategory.SUPPLIES to "Supplies", ExpenseCategory.OTHER to "Other")
        for ((category, name) in names) {
            compose.runOnIdle { row = row.copy(category = category, note = "wipers") }
            compose.onNodeWithText("Expense $name · wipers").assertIsDisplayed()
            for (note in listOf(null, "", " \t", "\u2003\u00a0")) {
                compose.runOnIdle { row = row.copy(note = note) }
                compose.onNodeWithText("Expense $name").assertIsDisplayed()
            }
        }
    }
}
