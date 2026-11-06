package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.analytics.TimeEconomics
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.ui.ScreenshotData
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #1535: the shift miles dialog's prefill, bounds, below-deliveries warning and restore caption. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionMilesDialogTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val saves = mutableListOf<Double>()
    private var restores = 0

    private fun shift(miles: Double?, machineMiles: Double?, milesOverride: Double?) = SessionRecord(
        sessionId = "1152", platform = Platform.DoorDash, startedAt = 1_000L, endedAt = 2_000L,
        reportedEarnings = null, reportedDurationMillis = null, miles = miles, machineMiles = machineMiles,
        milesOverride = milesOverride, deliveries = 1, jobsCompleted = 1, offersReceived = 1, offersAccepted = 1,
        offersDeclined = 0, offersTimeout = 0,
    )

    private fun show(session: SessionRecord, deliveryMiles: Double = 0.0) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            var text by remember { mutableStateOf(milesFieldText(session.miles)) }
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides ScreenshotData.clock) {
                    SessionMilesDialog(
                        session = session,
                        deliveryMiles = deliveryMiles,
                        miles = text,
                        onMilesChange = { text = it },
                        onSave = { saves += it },
                        onRestore = { restores++ },
                        onDismiss = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
    }

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun field() = compose.onNodeWithText(string(R.string.session_miles_dialog_field))
    private fun save() = compose.onNodeWithText(string(R.string.session_miles_dialog_save))
    private fun type(text: String) {
        field().performTextReplacement(text)
        compose.mainClock.advanceTimeBy(2_000)
    }

    @Test
    fun `the field is prefilled with the current miles and an unchanged value cannot be saved`() {
        assertEquals("59.4", milesFieldText(59.400000000000006))
        assertEquals("2.25", milesFieldText(2.25))
        assertEquals("", milesFieldText(null))
        show(shift(miles = 59.400000000000006, machineMiles = 59.400000000000006, milesOverride = null))
        compose.onNodeWithText("59.4").assertExists()
        save().assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.session_miles_dialog_restore), substring = true).assertDoesNotExist()

        type("2.5")
        save().assertIsEnabled()
        save().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(2.5), saves)
    }

    @Test
    fun `an invalid value disables Save with one error line`() {
        show(shift(miles = 59.4, machineMiles = 59.4, milesOverride = null))
        for (bad in listOf("-1", "2000.5", "abc")) {
            type(bad)
            save().assertIsNotEnabled()
            compose.onNodeWithText(string(R.string.session_miles_dialog_invalid)).assertExists()
        }
        type("0")
        save().assertIsEnabled()
        compose.onNodeWithText(string(R.string.session_miles_dialog_invalid)).assertDoesNotExist()
    }

    @Test
    fun `a value below the deliveries' miles warns and still saves`() {
        show(shift(miles = 59.4, machineMiles = 59.4, milesOverride = null), deliveryMiles = 8.0)
        val warning = string(R.string.session_miles_dialog_below_deliveries)
        compose.onNodeWithText(warning).assertDoesNotExist()
        type("2.5")
        compose.onNodeWithText(warning).assertExists()
        save().assertIsEnabled()
        type("8")
        compose.onNodeWithText(warning).assertDoesNotExist()
    }

    @Test
    fun `a corrected shift offers the tracked miles with the number when it is known`() {
        show(shift(miles = 2.5, machineMiles = 59.4, milesOverride = 2.5))
        val restore = compose.onNodeWithText(string(R.string.session_miles_dialog_restore_format, "59.4"))
        restore.assertExists()
        restore.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, restores)
    }

    @Test
    fun `a corrected shift with no tracked miles offers the restore without a number`() {
        show(shift(miles = 0.0, machineMiles = null, milesOverride = 0.0))
        compose.onNodeWithText(string(R.string.session_miles_dialog_restore)).assertExists()
    }
}

/** #1535 D3: the empty miles card withholds its split, with the reason, when delivery miles exceed the shift miles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimeTabMilesSplitTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()

    private fun show(time: TimeEconomics) {
        compose.mainClock.autoAdvance = false
        compose.setContent { GigLogTheme { DeadheadCard(time) } }
        compose.mainClock.advanceTimeBy(2_000)
    }

    @Test
    fun `delivery miles above the shift miles withhold the split and say why`() {
        show(TimeEconomics.EMPTY.copy(miles = 2.5, deliveryMiles = 8.0))
        compose.onNodeWithText(context.getString(R.string.time_tab_delivery_miles_exceed_total)).assertExists()
        compose.onNodeWithText(context.getString(R.string.time_tab_deadhead_caption)).assertDoesNotExist()
    }

    @Test
    fun `delivery miles within the shift miles keep the split`() {
        show(TimeEconomics.EMPTY.copy(miles = 59.4, deliveryMiles = 8.0))
        compose.onNodeWithText(context.getString(R.string.time_tab_deadhead_caption)).assertExists()
        compose.onNodeWithText(context.getString(R.string.time_tab_delivery_miles_exceed_total)).assertDoesNotExist()
    }
}
