package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.domain.analytics.ExpenseCategory
import cloud.trotter.dashbuddy.feature.bubble.hud.expense.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExpenseSubcardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var open by mutableStateOf(true)
    private var selected: ExpensePick? = null
    private fun show() {
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalReducedMotion provides true,
                    LocalHudSheetBackHandler provides { enabled, dismiss ->
                        val latest by rememberUpdatedState(dismiss)
                        DisposableEffect(enabled) {
                            val callback = object : OnBackPressedCallback(enabled) {
                                override fun handleOnBackPressed() = latest()
                            }
                            compose.activity.onBackPressedDispatcher.addCallback(callback)
                            onDispose { callback.remove() }
                        }
                    }) {
                    Box(Modifier.width(352.dp).height(400.dp)) {
                        HudSheetHost(open, { open = false }, compact = true,
                            sheet = { ExpenseSubcard { selected = it } })
                    }
                }
            }
        }
    }
    @Test fun `six tiles appear in approved row order and each returns its pick`() {
        show()
        val names = listOf("food", "gas", "parking", "car_care", "supplies", "other")
        val picks = listOf(ExpensePick.Expense(ExpenseCategory.FOOD), ExpensePick.Fuel,
            ExpensePick.Expense(ExpenseCategory.PARKING), ExpensePick.Expense(ExpenseCategory.CAR_CARE),
            ExpensePick.Expense(ExpenseCategory.SUPPLIES), ExpensePick.Expense(ExpenseCategory.OTHER))
        val bounds = names.map { name -> compose.onNodeWithTag("expense.tile.$name").assertIsDisplayed().getBoundsInRoot() }
        assertEquals(bounds[0].top, bounds[1].top); assertEquals(bounds[1].top, bounds[2].top)
        assertTrue(bounds[0].left < bounds[1].left && bounds[1].left < bounds[2].left)
        assertEquals(bounds[3].top, bounds[4].top); assertEquals(bounds[4].top, bounds[5].top)
        assertTrue(bounds[3].top > bounds[0].bottom)
        assertTrue(bounds[3].left < bounds[4].left && bounds[4].left < bounds[5].left)
        names.forEachIndexed { i, name ->
            assertEquals(72.dp, bounds[i].height)
            compose.onNodeWithTag("expense.tile.$name").performClick()
            assertEquals(picks[i], selected)
        }
    }
    @Test fun `Back dismisses the subcard`() {
        show()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("expense.subcard").assertDoesNotExist()
    }
    @Test fun `outside tap dismisses the subcard`() {
        show()
        compose.onNodeWithTag("hud.sheet.outside").performTouchInput { click(Offset(5f, 5f)) }
        compose.onNodeWithTag("expense.subcard").assertDoesNotExist()
    }
}
