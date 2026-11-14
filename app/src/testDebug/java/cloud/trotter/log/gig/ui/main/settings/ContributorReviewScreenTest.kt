package cloud.trotter.log.gig.ui.main.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.census.contract.contributor.ContributorRole
import cloud.trotter.census.contract.contributor.SlotClass
import cloud.trotter.log.gig.core.data.census.contributor.ClassesState
import cloud.trotter.log.gig.core.data.census.contributor.ReviewRow
import cloud.trotter.log.gig.core.data.census.contributor.ScreenReview
import cloud.trotter.log.gig.core.data.census.contributor.SlotValue
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.census.contributor.ContributorEntry
import cloud.trotter.log.gig.domain.census.contributor.InvitationAnswer
import cloud.trotter.log.gig.feature.settings.ContributorContent
import cloud.trotter.log.gig.feature.settings.ContributorReviewContent
import cloud.trotter.log.gig.feature.settings.ContributorUiState
import cloud.trotter.log.gig.feature.settings.R as SettingsR
import cloud.trotter.log.gig.feature.settings.ReviewPhase
import cloud.trotter.log.gig.feature.settings.ReviewUiState
import cloud.trotter.log.gig.feature.settings.WaitingReview
import cloud.trotter.log.gig.feature.settings.confirmTag
import cloud.trotter.log.gig.feature.settings.roleTag
import cloud.trotter.log.gig.feature.settings.rowTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * #1626: the review screen renders the outbound skeleton as rows: a constant slot shows its word and may be confirmed;
 * a varying, regional, unknown or covered slot is greyed with no word and no confirm control; any row takes a label;
 * Send stays off until something is chosen. The waiting list on Screen reviews turns a screen ready at the UTC day
 * boundary from the clock alone (Reactive UI rules 2 and 6). Every value is fictional.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp-420dpi")
class ContributorReviewScreenTest {
    @get:Rule val compose = createComposeRule()

    private class MovingClock(var millis: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
    }

    private val context = RuntimeEnvironment.getApplication()
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private val rows = listOf(
        ReviewRow("root/0/text", "android.widget.TextView", "text", SlotValue.Hashed, SlotClass.CONSTANT, "Pick up by"),
        ReviewRow("root/1/text", "android.widget.TextView", "text", SlotValue.Hashed, SlotClass.VARYING, null),
        ReviewRow("root/2/text", "android.widget.TextView", "text", SlotValue.Hashed, SlotClass.REGIONAL, null),
        ReviewRow("root/3/text", "android.widget.TextView", "text", SlotValue.Covered, SlotClass.WITHHELD, null),
        ReviewRow("root/4/text", "android.widget.TextView", "text", SlotValue.Format("\$#.##"), SlotClass.SHAPE, null),
        ReviewRow("root/5/text", "android.widget.Button", "text", SlotValue.Hashed, SlotClass.UNKNOWN, null),
    )

    private fun show(initial: ReviewUiState, sent: MutableList<ReviewUiState> = mutableListOf()) {
        compose.setContent {
            var state by remember { mutableStateOf(initial) }
            GigLogTheme {
                ContributorReviewContent(
                    state = state,
                    onBack = {},
                    onToggle = { slot ->
                        state = state.copy(confirmed = if (slot in state.confirmed) state.confirmed - slot else state.confirmed + slot)
                    },
                    onRole = { slot, role -> state = state.copy(roles = if (role == null) state.roles - slot else state.roles + (slot to role)) },
                    onSend = { sent += state },
                    onDelete = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun ready() = ReviewUiState(
        phase = ReviewPhase.READY,
        review = ScreenReview("r", "2026-11-13", rows, ClassesState.LOADED, wordsKept = true),
    )

    @Test fun `a constant slot shows its word and a confirm control, every other row is greyed with no word`() {
        show(ready())
        compose.onNodeWithText("\"Pick up by\"").assertExists()
        compose.onNodeWithTag(confirmTag("root/0/text")).assertExists()
        listOf("root/1/text", "root/2/text", "root/3/text", "root/4/text", "root/5/text").forEach {
            compose.onNodeWithTag(confirmTag(it)).assertDoesNotExist()
        }
        compose.onNodeWithText(string(SettingsR.string.census_review_screen_varying)).assertExists()
        compose.onNodeWithText(string(SettingsR.string.census_review_screen_regional)).assertExists()
        compose.onNodeWithText(string(SettingsR.string.census_review_screen_covered)).assertExists()
        compose.onNodeWithText(string(SettingsR.string.census_review_screen_format, "\$#.##")).assertExists()
        compose.onNodeWithText(string(SettingsR.string.census_review_screen_unknown)).assertExists()
    }

    @Test fun `Send stays off until a word is confirmed or a field labelled, and sends the choices`() {
        val sent = mutableListOf<ReviewUiState>()
        show(ready(), sent)
        val send = compose.onNodeWithText(string(SettingsR.string.census_review_screen_send))
        send.assertIsNotEnabled()
        compose.onNodeWithTag(confirmTag("root/0/text")).performClick()
        send.assertIsEnabled()
        compose.onNodeWithTag(roleTag("root/4/text")).performClick()
        compose.onNodeWithText(string(SettingsR.string.census_review_role_total_pay)).performClick()
        compose.onNodeWithText(string(SettingsR.string.census_review_screen_role_set, string(SettingsR.string.census_review_role_total_pay)))
            .assertExists()
        send.performClick()
        assertEquals(setOf("root/0/text"), sent.single().confirmed)
        assertEquals(mapOf("root/4/text" to ContributorRole.TOTAL_PAY), sent.single().roles)
    }

    @Test fun `a lost word and a missing release are said, and a shift hides the review`() {
        show(ready().copy(review = ScreenReview("r", "2026-11-13", rows.map { it.copy(word = null) }, ClassesState.RELEASE_UNAVAILABLE, wordsKept = false)))
        compose.onNodeWithText(string(SettingsR.string.census_review_screen_words_lost)).assertExists()
        compose.onNodeWithText(string(SettingsR.string.census_review_screen_release_unavailable)).assertExists()
        compose.onNodeWithTag(confirmTag("root/0/text")).assertDoesNotExist()
        compose.onNodeWithText(string(SettingsR.string.census_review_screen_constant_no_word)).assertExists()
    }

    @Test fun `during a shift the screen shows no row`() {
        show(ready().copy(shiftLive = true))
        compose.onNodeWithText(string(SettingsR.string.census_review_screen_shift)).assertExists()
        compose.onNodeWithTag(rowTag("root/0/text")).assertDoesNotExist()
        compose.onNodeWithText(string(SettingsR.string.census_review_screen_send)).assertDoesNotExist()
    }

    @Test fun `a screen stored today turns ready at the UTC day boundary without a state change`() {
        val clock = MovingClock(Instant.parse("2026-11-13T23:59:00Z").toEpochMilli())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalNowProvider provides clock) {
                GigLogTheme {
                    ContributorContent(
                        state = ContributorUiState(
                            entry = ContributorEntry.ON, canAccept = false, shiftLive = false, answer = InvitationAnswer.NONE,
                            expiresDay = "2026-12-13", ended = null, refusal = null,
                            waiting = listOf(WaitingReview("r", "2026-11-13", "2026-11-13")),
                        ),
                        onBack = {}, onAccept = {}, onNotNow = {}, onNoThanks = {}, onStop = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText(string(SettingsR.string.census_review_waiting_tomorrow)).assertExists()
        clock.millis = Instant.parse("2026-11-14T00:00:30Z").toEpochMilli()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText(string(SettingsR.string.census_review_waiting_ready)).assertExists()
        compose.onNodeWithText(string(SettingsR.string.census_review_waiting_tomorrow)).assertDoesNotExist()
    }
}
