package cloud.trotter.log.gig.ui.main.analytics

import androidx.activity.ComponentDialog
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.motion.LocalReducedMotion
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.analytics.receipts.ReceiptMatchPlan
import cloud.trotter.log.gig.domain.format.formatClockTime
import cloud.trotter.log.gig.ui.ScreenshotData
import cloud.trotter.log.gig.ui.main.analytics.ShiftPageFixtures.RECEIPT_EVIDENCE
import cloud.trotter.log.gig.ui.main.analytics.ShiftPageFixtures.RECEIPT_PROVEN
import cloud.trotter.log.gig.ui.main.analytics.ShiftPageFixtures.RECEIPT_Y1
import cloud.trotter.log.gig.ui.main.analytics.ShiftPageFixtures.RECEIPT_Y2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.Locale
import java.util.TimeZone

/**
 * #1599: the receipt check on the shift page and "Which deliveries did this receipt pay for?": the suggestion, the
 * preview, the plan Confirm sends, the exits that save nothing, a locked delivery, the landing link, Undo match on
 * the job sheet, and the sheet's semantics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp-420dpi")
class ReceiptMatchSheetTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)
    private lateinit var locale: Locale
    private lateinit var zone: TimeZone
    private val confirmed = mutableListOf<Pair<String, ReceiptMatchPlan>>()
    private val undone = mutableListOf<String>()

    @Before fun fix() {
        locale = Locale.getDefault()
        zone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun restore() {
        Locale.setDefault(locale)
        TimeZone.setDefault(zone)
    }

    private fun show(state: SessionDetailUiState, row: String? = null) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides ScreenshotData.clock, LocalReducedMotion provides true) {
                    ShiftPageContent(
                        state, row,
                        ShiftPageActions(
                            onBack = {},
                            onConfirmReceiptMatch = { cmd, plan -> confirmed += cmd to plan },
                            onUndoReceiptMatch = { undone += it },
                        ),
                    )
                }
            }
        }
        settle()
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.click() = performSemanticsAction(SemanticsActions.OnClick).also { settle() }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    private val title get() = string(R.string.receipt_match_title)
    private val checkLine get() = string(R.string.shift_check_receipt_format, "$20.50", formatClockTime(ShiftPageFixtures.receiptSeenAt))
    private fun row(seq: Long) = compose.onNodeWithTag("receipt_row_$seq")
    private val confirm get() = compose.onNodeWithTag(RECEIPT_SHEET_CONFIRM_TAG)

    private fun openSheet() {
        show(ShiftPageFixtures.receiptShift())
        compose.onNodeWithTag("receipt_check_match_$RECEIPT_EVIDENCE").click()
    }

    @Test fun `the page shows the receipt check and Match opens the sheet`() { // non-vacuous
        show(ShiftPageFixtures.receiptShift())
        assertEquals(1, count(checkLine))
        assertEquals(0, count(title))
        compose.onNodeWithTag("receipt_check_match_$RECEIPT_EVIDENCE").click()
        assertEquals(1, count(title))
    }

    @Test fun `the stack is suggested and the preview shows the split and the totals`() {
        openSheet()
        row(RECEIPT_Y1).assertIsOn()
        row(RECEIPT_Y2).assertIsOn()
        assertEquals(2, count(string(R.string.receipt_match_suggested)))
        assertEquals(2, count(string(R.string.receipt_match_new_pay_format, "$10.25")))
        assertEquals(1, count(string(R.string.receipt_match_delivery_pay_format, "$51.51", "$63.01")))
        assertEquals(1, count(string(R.string.receipt_match_app_total_format, "DoorDash", "$63.01")))
    }

    @Test fun `Confirm sends the plan as shown and closes the sheet`() { // non-vacuous
        openSheet()
        confirm.click()
        val (_, plan) = confirmed.single()
        assertEquals(mapOf(RECEIPT_Y1 to 1025L, RECEIPT_Y2 to 1025L), plan.covered.associate { it.deliveryEventSequenceId to it.newPayCents })
        assertTrue(plan.cleared.isEmpty())
        assertEquals(RECEIPT_EVIDENCE, plan.evidenceId)
        assertEquals(0, count(title))
    }

    @Test fun `unticking the estimate removes it and the other delivery takes the whole receipt`() {
        openSheet()
        row(RECEIPT_Y2).click()
        row(RECEIPT_Y2).assertIsOff()
        assertEquals(1, count(string(R.string.receipt_match_estimate_removed)))
        assertEquals(1, count(string(R.string.receipt_match_new_pay_format, "$20.50")))
        confirm.click()
        val plan = confirmed.single().second
        assertEquals(mapOf(RECEIPT_Y1 to 2050L), plan.covered.associate { it.deliveryEventSequenceId to it.newPayCents })
        assertEquals(listOf(RECEIPT_Y2), plan.cleared.map { it.deliveryEventSequenceId })
    }

    @Test fun `unticking the delivery with no pay clears nothing`() {
        openSheet()
        row(RECEIPT_Y1).click()
        assertEquals(0, count(string(R.string.receipt_match_estimate_removed)))
        assertEquals(1, count(string(R.string.receipt_match_new_pay_format, "$20.50")))
    }

    @Test fun `Not sure and Back close the sheet, save nothing and keep the check line`() {
        openSheet()
        compose.onNodeWithTag(RECEIPT_SHEET_NOT_SURE_TAG).click()
        assertEquals(0, count(title))
        assertEquals(1, count(checkLine))
        compose.onNodeWithTag("receipt_check_match_$RECEIPT_EVIDENCE").click()
        assertEquals(1, count(title))
        compose.runOnUiThread { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        settle()
        assertEquals(0, count(title))
        assertEquals(1, count(checkLine))
        assertTrue(confirmed.isEmpty())
    }

    @Test fun `a proven delivery is listed, locked, and a tap changes nothing`() {
        openSheet()
        row(RECEIPT_PROVEN).assertIsNotEnabled()
        row(RECEIPT_PROVEN).assertIsOff()
        assertEquals(1, count(string(R.string.receipt_match_paid_by_receipt)))
        row(RECEIPT_PROVEN).performClick()
        settle()
        row(RECEIPT_PROVEN).assertIsOff()
        assertEquals(1, count(string(R.string.receipt_match_delivery_pay_format, "$51.51", "$63.01")))
    }

    @Test fun `with every delivery unticked Confirm is disabled`() {
        openSheet()
        confirm.assertIsEnabled()
        row(RECEIPT_Y1).click()
        row(RECEIPT_Y2).click()
        confirm.assertIsNotEnabled()
    }

    @Test fun `a receipt landing link opens its sheet`() { // non-vacuous
        show(ShiftPageFixtures.receiptShift(), row = receiptRowKey(RECEIPT_EVIDENCE))
        assertEquals(1, count(title))
    }

    @Test fun `a matched receipt has no check line, and the job sheet offers Undo match while it holds`() { // non-vacuous
        show(ShiftPageFixtures.receiptShift(matched = true))
        assertEquals(0, count(checkLine))
        compose.onNodeWithText("Store A").click()
        assertTrue(count(string(R.string.shift_page_source_matched)) >= 2)
        assertEquals(2, count(string(R.string.shift_page_undo_match)))
        assertEquals(2, count(string(R.string.shift_page_fix_delivery)))
        compose.onAllNodesWithText(string(R.string.shift_page_undo_match))[0].click()
        assertEquals(listOf(ShiftPageFixtures.RECEIPT_MATCH_COMMAND), undone)
    }

    @Test fun `a match changed since by the dasher offers no Undo match`() {
        show(ShiftPageFixtures.receiptShift(matched = true, editedAfter = true))
        compose.onNodeWithText("Store A").click()
        assertEquals(0, count(string(R.string.shift_page_undo_match)))
        assertEquals(2, count(string(R.string.shift_page_fix_delivery)))
    }

    @Test fun `the title is a heading and a row is a checkbox with its state`() {
        openSheet()
        compose.onNodeWithText(title).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        row(RECEIPT_Y1).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).assertIsOn()
    }
}
