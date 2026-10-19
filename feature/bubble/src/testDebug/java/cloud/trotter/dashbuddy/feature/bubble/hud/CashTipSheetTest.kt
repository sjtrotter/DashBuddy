package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.feature.bubble.hud.cashtip.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CashTipSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var locale: Locale
    @Before fun setup() { locale = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun cleanup() { Locale.setDefault(locale) }
    private val rows = (1L..5L).map { id -> DeliveryRecord(id, "dash", Platform.DoorDash, "job$id", "task$id",
        "Store $id", 1_700_000_000_000L + id * 60_000, 8.0, "OFFER_PAY", 6.0, null, null, null, null, cashTip = 10.0) }
    private var model by mutableStateOf(CashTipSheetModel(rows, 5))
    private var commits = 0
    private val amounts = mutableListOf<Long>()
    private fun show() {
        compose.setContent {
            DashBuddyTheme {
                Column(Modifier.width(352.dp).height(390.dp)) {
                    CashTipSheet(model, onSelectDelivery = { model = model.copy(selectedDeliveryId = it, page = CashTipPage.COMPACT) },
                        onExpand = { model = model.copy(page = CashTipPage.EXPANDED) },
                        onBack = { model = model.copy(page = CashTipPage.COMPACT) },
                        onSign = { model = model.copy(sign = it) },
                        onAmount = { amount ->
                            amounts += amount
                            model = model.copy(totalCents = (model.totalCents + if (model.sign == CashTipSign.PLUS) amount else -amount).coerceAtLeast(0))
                        }, onOther = { model = model.copy(page = CashTipPage.OTHER_AMOUNT) },
                        onDigit = { model = model.copy(otherAmount = model.otherAmount + it) },
                        onPoint = { model = model.copy(otherAmount = model.otherAmount + ".") },
                        onBackspace = { model = model.copy(otherAmount = model.otherAmount.dropLast(1)) },
                        onDoneOther = { model = model.copy(page = CashTipPage.COMPACT,
                            totalCents = model.totalCents + model.otherAmount.toBigDecimal().movePointRight(2).toLong()) },
                        onCommit = { commits++ })
                }
            }
        }
    }
    @Test fun `compact offers two rows and latest is preselected`() {
        show()
        compose.onNodeWithText("Store 5").assertIsDisplayed()
        compose.onNodeWithText("Store 4").assertIsDisplayed()
        compose.onNodeWithText("Store 3").assertDoesNotExist()
        compose.onNodeWithTag("cashTip.delivery.5").assertIsSelected()
        compose.onNodeWithTag("cashTip.delivery.4").assertIsNotSelected()
        compose.onNodeWithText("Another delivery").assertIsDisplayed()
    }
    @Test fun `pad taps add subtract and never make the total negative`() {
        show()
        compose.onNodeWithText("$5").performClick()
        compose.onNodeWithText("$1").performClick()
        compose.onNodeWithText("$1").performClick()
        compose.onNodeWithText("Add $7.00 cash tip").assertIsDisplayed().performClick()
        assertEquals(1, commits)
        compose.onNodeWithTag("cashTip.sign.MINUS").performClick()
        compose.onNodeWithText("$5").performClick()
        compose.onNodeWithText("Remove $2.00 cash tip").assertIsDisplayed()
        compose.onNodeWithText("$20").performClick()
        compose.onNodeWithTag("cashTip.commit").assertIsNotEnabled()
        assertEquals(0L, model.totalCents)
        assertEquals(listOf(500L, 100L, 100L, 500L, 2000L), amounts)
    }
    @Test fun `Other opens point enabled keypad and Done adds fractional amount`() {
        model = model.copy(totalCents = 100)
        show()
        compose.onNodeWithText("Other").performClick()
        compose.onNodeWithTag("keypad.point").assertIsEnabled()
        compose.onNodeWithTag("keypad.2").performClick()
        compose.onNodeWithTag("keypad.point").performClick()
        compose.onNodeWithTag("keypad.3").performClick()
        compose.onNodeWithTag("keypad.5").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Add $3.35 cash tip").assertIsDisplayed()
        compose.onNodeWithTag("keypad.point").assertDoesNotExist()
    }
    @Test fun `expanded picker lists every delivery hides pad and selecting returns compact`() {
        show()
        compose.onNodeWithText("Another delivery").performClick()
        compose.onNodeWithText("Fewer").assertIsDisplayed()
        compose.onNodeWithText("$5").assertDoesNotExist()
        for (id in 5L downTo 1L) {
            compose.onNodeWithTag("cashTip.deliveries").performScrollToNode(hasTestTag("cashTip.delivery.$id"))
            compose.onNodeWithTag("cashTip.delivery.$id").assertIsDisplayed()
        }
        compose.onNodeWithTag("cashTip.delivery.1").performClick()
        compose.onNodeWithText("Other").assertIsDisplayed()
        compose.onNodeWithText("Fewer").assertDoesNotExist()
        compose.onNodeWithTag("cashTip.delivery.1").assertIsSelected()
    }
    @Test fun `busy and empty amounts disable commit`() {
        model = model.copy(totalCents = 700, busy = true)
        show()
        compose.onNodeWithTag("cashTip.commit").assertIsNotEnabled()
        compose.runOnIdle { model = model.copy(totalCents = 0, busy = false) }
        compose.onNodeWithTag("cashTip.commit").assertIsNotEnabled()
    }
    @Test fun `an empty dash offers no selectable delivery`() {
        model = model.copy(deliveries = emptyList(), selectedDeliveryId = null)
        show()
        compose.onNodeWithText("No completed deliveries in this dash").assertIsDisplayed()
        compose.onNodeWithTag("cashTip.commit").assertIsNotEnabled()
    }
    @Test fun `sheet errors distinguish refused removal from failed save and clear at none`() {
        model = model.copy(failed = CashTipFailure.BELOW_ZERO)
        show()
        compose.onNodeWithText("Less cash than that is logged. Nothing changed.").assertIsDisplayed()
        compose.onNodeWithText("Cash tip not saved. Try again.").assertDoesNotExist()
        compose.runOnIdle { model = model.copy(failed = CashTipFailure.WRITE) }
        compose.onNodeWithText("Cash tip not saved. Try again.").assertIsDisplayed()
        compose.onNodeWithText("Less cash than that is logged. Nothing changed.").assertDoesNotExist()
        compose.runOnIdle { model = model.copy(failed = CashTipFailure.NONE) }
        compose.onNodeWithText("Cash tip not saved. Try again.").assertDoesNotExist()
        compose.onNodeWithText("Less cash than that is logged. Nothing changed.").assertDoesNotExist()
    }
    @Test fun `removing more than the selected delivery cash is disabled`() {
        model = model.copy(totalCents = 1100, sign = CashTipSign.MINUS)
        show()
        compose.onNodeWithText("Remove $11.00 cash tip").assertIsDisplayed()
        compose.onNodeWithTag("cashTip.commit").assertIsNotEnabled()
    }

}
