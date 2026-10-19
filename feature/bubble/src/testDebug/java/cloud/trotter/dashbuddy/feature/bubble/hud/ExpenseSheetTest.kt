package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.domain.analytics.ExpenseCategory
import cloud.trotter.dashbuddy.feature.bubble.hud.expense.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExpenseSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var locale: Locale
    @Before fun setup() { locale = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun cleanup() { Locale.setDefault(locale) }
    private var model by mutableStateOf(ExpenseSheetModel(ExpensePick.Fuel))
    private var logs = 0
    private var back = 0
    private fun show() {
        compose.setContent {
            DashBuddyTheme {
                Column(Modifier.width(352.dp).height(390.dp)) {
                    ExpenseSheet(model, onBack = { back++ }, onField = { model = model.copy(activeField = it) },
                        onLabel = { model = model.copy(label = it) },
                        onSelectLabel = { model = model.copy(label = it, keyboardOpen = false) },
                        onNewLabel = { model = model.copy(keyboardOpen = true) },
                        onKeyboardDone = { model = model.copy(keyboardOpen = false) },
                        onDigit = {}, onPoint = {}, onBackspace = {}, onLog = { logs++ })
                }
            }
        }
    }
    @Test fun `gas requires positive gallons and the button states amount and category`() {
        model = model.copy(totalCents = 4200)
        show()
        compose.onNodeWithText("Log $42.00 gas").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("expense.total").assertIsSelected()
        compose.onNodeWithTag("expense.gallons").assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithTag("expense.total").assertIsNotSelected()
        compose.onNodeWithTag("keypad.point").assertIsEnabled()
        compose.runOnIdle { model = model.copy(gallonsText = "0.000") }
        compose.onNodeWithTag("expense.log").assertIsNotEnabled()
        compose.runOnIdle { model = model.copy(gallonsText = "11.125") }
        compose.onNodeWithTag("expense.log").assertIsEnabled().performClick()
        assertEquals(1, logs)
        compose.runOnIdle { model = model.copy(busy = true) }
        compose.onNodeWithTag("expense.log").assertIsNotEnabled()
        compose.runOnIdle { model = model.copy(busy = false, totalCents = 0) }
        compose.onNodeWithTag("expense.log").assertIsNotEnabled()
    }
    @Test fun `fields follow category and only three categories offer labels`() {
        show()
        compose.onNodeWithTag("expense.label.new").assertDoesNotExist()
        ExpenseCategory.entries.forEach { category ->
            compose.runOnIdle { model = ExpenseSheetModel(ExpensePick.Expense(category), totalCents = 600) }
            compose.onNodeWithTag("expense.total").assertIsDisplayed()
            compose.onNodeWithTag("expense.gallons").assertDoesNotExist()
            val labeled = category in listOf(ExpenseCategory.CAR_CARE, ExpenseCategory.SUPPLIES, ExpenseCategory.OTHER)
            if (labeled) compose.onNodeWithTag("expense.label.new").assertIsDisplayed()
            else compose.onNodeWithTag("expense.label.new").assertDoesNotExist()
            compose.onNodeWithTag("expense.log").assertIsEnabled()
        }
    }
    @Test fun `recent chips select and New label opens editable single line field`() {
        model = ExpenseSheetModel(ExpensePick.Expense(ExpenseCategory.CAR_CARE), totalCents = 1840,
            recentLabels = listOf("wipers", "wash"))
        show()
        compose.onNodeWithTag("expense.label.0").performClick().assertIsSelected()
        assertEquals("wipers", model.label)
        compose.onNodeWithTag("expense.label.input").assertDoesNotExist()
        compose.onNodeWithTag("expense.label.1").performScrollTo().performClick().assertIsSelected()
        assertEquals("wash", model.label)
        compose.onNodeWithTag("expense.label.new").performScrollTo().performClick()
        compose.onNodeWithTag("expense.label.input").assertIsDisplayed().performTextReplacement("tires")
        assertEquals("tires", model.label)
        compose.onNodeWithTag("expense.label.input").performImeAction()
        compose.onNodeWithTag("expense.label.input").assertDoesNotExist()
        compose.onNodeWithText("Log $18.40 car care").assertIsDisplayed()
    }
    @Test fun `failure and Back copy`() {
        model = ExpenseSheetModel(ExpensePick.Expense(ExpenseCategory.PARKING), totalCents = 600, failure = ExpenseFailure.WRITE)
        show()
        compose.onNodeWithText("Log $6.00 parking").assertIsDisplayed()
        compose.onNodeWithText("Not logged. Try again.").assertIsDisplayed()
        compose.onNodeWithText("Not counted in profit").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, back)
        compose.runOnIdle { model = model.copy(failure = ExpenseFailure.NONE) }
        compose.onNodeWithText("Not logged. Try again.").assertDoesNotExist()
    }
}
