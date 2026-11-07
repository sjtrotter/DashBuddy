package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.motion.LocalReducedMotion
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.analytics.timeline.JobCard
import cloud.trotter.log.gig.domain.format.formatClockTime
import cloud.trotter.log.gig.ui.ScreenshotData
import cloud.trotter.log.gig.ui.main.analytics.ShiftPageFixtures.OrphanAnswer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

/**
 * The shift page's accepted offer with no delivery: the card and the check line, "What happened to this offer?" and
 * the write each answer makes, and the card's text once each answer has folded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp-420dpi")
class OrphanOfferSheetTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)
    private lateinit var locale: Locale
    private lateinit var zone: TimeZone
    private val marked = mutableListOf<Long>()
    private val added = mutableListOf<List<Any?>>()

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

    private fun show(state: SessionDetailUiState) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides ScreenshotData.clock, LocalReducedMotion provides true) {
                    ShiftPageContent(
                        state, null,
                        ShiftPageActions(
                            onBack = {},
                            onMarkOfferUnassigned = { marked += it },
                            onAddDeliveryForOffer = { seq, at, pay, tip, cash, store, note ->
                                added += listOf(seq, at, pay, tip, cash, store, note)
                            },
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

    private fun SemanticsNodeInteraction.click() = performSemanticsAction(SemanticsActions.OnClick)

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    private val title get() = string(R.string.shift_offer_sheet_title)

    @Test fun `the orphan card reads no delivery recorded and the shift shows its check line`() {
        show(ShiftPageFixtures.orphanShift())
        assertEquals(1, count(string(R.string.shift_page_no_delivery)))
        assertEquals(1, count(context.resources.getQuantityString(R.plurals.shift_check_no_delivery, 1, 1)))
    }

    @Test fun `it was unassigned writes the attestation and closes the sheet`() {
        show(ShiftPageFixtures.orphanShift())
        compose.onNodeWithText("Sample Tacos").click()
        settle()
        assertEquals(1, count(title))
        compose.onNodeWithText(string(R.string.shift_offer_sheet_unassigned)).click()
        settle()
        assertEquals(listOf(ShiftPageFixtures.ORPHAN_OFFER), marked)
        assertEquals(0, count(title))
        assertEquals(0, added.size)
    }

    @Test fun `add a missed delivery closes the sheet for the add dialog`() {
        // The sheet alone: its answer is read off the callback (the page then opens the add dialog, below).
        val offer = ShiftPageFixtures.orphanShift().timeline.filterIsInstance<JobCard>().single { it.key == ShiftPageFixtures.ORPHAN_KEY }
            .openOffers.single()
        var adds = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides ScreenshotData.clock, LocalReducedMotion provides true) {
                    OfferQuestionSheet(offer, onUnassigned = { marked += -1L }, onAddMissed = { adds++ }, onDismiss = {})
                }
            }
        }
        settle()
        compose.onNodeWithTag(OFFER_SHEET_ADD_TAG).click()
        settle()
        assertEquals(1, adds)
        assertEquals(0, marked.size)
    }

    @Test fun `close leaves the offer open`() {
        show(ShiftPageFixtures.orphanShift())
        compose.onNodeWithText("Sample Tacos").click()
        settle()
        compose.onNodeWithText(string(R.string.shift_offer_sheet_close)).click()
        settle()
        assertEquals(0, count(title))
        assertEquals(0, marked.size + added.size)
        assertEquals(1, count(string(R.string.shift_page_no_delivery)))
    }

    @Test fun `once marked the card says so and the check line is gone`() {
        show(ShiftPageFixtures.orphanShift(OrphanAnswer.MARKED_UNASSIGNED))
        assertEquals(1, count(string(R.string.shift_page_marked_unassigned)))
        assertEquals(0, count(string(R.string.shift_page_no_delivery)))
        assertEquals(0, count(context.resources.getQuantityString(R.plurals.shift_check_no_delivery, 1, 1)))
    }

    @Test fun `once the missed delivery is added the card shows it and the check line is gone`() {
        show(ShiftPageFixtures.orphanShift(OrphanAnswer.MISSED_DELIVERY_ADDED))
        assertEquals(0, count(string(R.string.shift_page_no_delivery)))
        assertEquals(1, count(string(R.string.shift_page_source_you_entered)))
        assertEquals(0, count(context.resources.getQuantityString(R.plurals.shift_check_no_delivery, 1, 1)))
    }

    @Test fun `a recorded unassign reads unassigned at its time`() {
        show(ShiftPageFixtures.orphanShift(OrphanAnswer.UNASSIGNED_RECORDED))
        val at = formatClockTime(ShiftPageFixtures.orphanUnassignedAt)
        assertEquals(1, count(string(R.string.shift_page_unassigned_at_format, at)))
        assertEquals(0, count(string(R.string.shift_page_no_delivery)))
    }
}

/**
 * The add dialog opened from an offer, prefilled from it and naming it. Its own class on the default window, as
 * [SessionMilesDialogTest] drives a dialog with fields: frames advanced, no idle wait after a click.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfferAddDialogTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)
    private val added = mutableListOf<List<Any?>>()

    private fun SemanticsNodeInteraction.click() = performSemanticsAction(SemanticsActions.OnClick)

    @Test fun `the add dialog opened from an offer is prefilled from it and names it`() {
        val detail = ShiftPageFixtures.orphanShift().detail!!
        val offer = ShiftPageFixtures.orphanShift().timeline.filterIsInstance<JobCard>().single { it.key == ShiftPageFixtures.ORPHAN_KEY }
            .openOffers.single()
        val dialogs = ShiftPageDialogState().apply { addFromOffer = offer }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides ScreenshotData.clock) {
                    ShiftPageDialogs(
                        detail, dialogs,
                        ShiftPageActions(onBack = {}, onAddDeliveryForOffer = { seq, at, pay, tip, cash, store, note ->
                            added += listOf(seq, at, pay, tip, cash, store, note)
                        }),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(1, compose.onAllNodesWithText("7.5").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("Sample Tacos").fetchSemanticsNodes().size)
        compose.onNodeWithText(string(R.string.session_detail_add_button)).click()
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(
            listOf(listOf(ShiftPageFixtures.ORPHAN_OFFER, ShiftPageFixtures.orphanAcceptedAt, 7.5, null, null, "Sample Tacos", null)),
            added,
        )
    }
}
