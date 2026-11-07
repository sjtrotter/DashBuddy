package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.analytics.PayBasis
import cloud.trotter.log.gig.ui.ScreenshotData
import cloud.trotter.log.gig.ui.main.analytics.ShiftPageFixtures.OrphanAnswer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Fix dialog offers "Remove this delivery" only on a delivery the dasher added by hand, and removes it only after
 * the confirm. The default window, as [SessionMilesDialogTest] drives a dialog with fields: frames advanced, no idle wait.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AdjustDeliveryRemoveTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private fun string(id: Int): String = context.getString(id)
    private val removed = mutableListOf<Long>()

    private fun showFix(manual: Boolean) {
        val detail = ShiftPageFixtures.orphanShift(OrphanAnswer.MISSED_DELIVERY_ADDED).detail!!
        val target = detail.deliveries.single { (it.payBasis == PayBasis.MANUAL) == manual }
        val dialogs = ShiftPageDialogState().apply { adjustTarget = target }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides ScreenshotData.clock) {
                    ShiftPageDialogs(detail, dialogs, ShiftPageActions(onBack = {}, onRemoveDelivery = { removed += it }))
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun click(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        // Publish the click's state write to the composition, then advance frames.
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(2_000)
    }

    @Test fun `a machine delivery has no remove action`() {
        showFix(manual = false)
        assertEquals(0, count(string(R.string.session_detail_remove_delivery)))
    }

    @Test fun `a hand-added delivery is removed only after the confirm`() {
        showFix(manual = true)
        click(string(R.string.session_detail_remove_delivery))
        assertEquals(1, count(string(R.string.session_detail_remove_delivery_confirm)))
        assertEquals(0, removed.size)
        click(string(R.string.session_detail_remove_button))
        assertEquals(listOf(102L), removed)
    }

    @Test fun `cancelling the confirm returns to the edit dialog`() {
        showFix(manual = true)
        click(string(R.string.session_detail_remove_delivery))
        click(string(R.string.session_detail_cancel_button))
        assertEquals(1, count(string(R.string.session_detail_remove_delivery)))
        assertEquals(0, removed.size)
    }
}
