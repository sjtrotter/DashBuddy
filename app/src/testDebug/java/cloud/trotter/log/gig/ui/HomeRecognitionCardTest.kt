package cloud.trotter.log.gig.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.pipeline.ParseRotCause
import cloud.trotter.log.gig.domain.pipeline.RecognitionIncident
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.ui.main.dashboard.DashboardBody
import cloud.trotter.log.gig.ui.main.dashboard.DashboardUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * #1560 — Home shows one card per open recognition incident, in the ready state and on the permission
 * gate (a denied notification permission opens the gate, and the card is then the only place the dasher
 * hears about the incident), and its "For …" line counts up by the minute from the incident's anchor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeRecognitionCardTest {
    @get:Rule val compose = createComposeRule()

    private val clock = MutableClock(T0)

    private val incident = RecognitionIncident(
        platformWire = Platform.DoorDash.wire,
        openedAt = T0 - 60_000,
        parseRot = listOf(ParseRotCause("doordash.screen.offer_card", "offer", listOf("payAmount"), T0 - 60_000)),
    )

    private val title = "DoorDash offers: pay not read"

    private fun show(uiState: DashboardUiState, hasPermissions: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    DashboardBody(
                        uiState = uiState, hasPermissions = hasPermissions, screenReadingOff = false,
                        onContinueGate = {}, onOpenWizard = {}, onCompleteSetup = {}, onShowBubble = {},
                        onOpenRecap = {}, onOpenPlan = {}, onOpenRatings = {}, onOpenSettings = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
    }

    private fun withIncident() = ScreenshotData.dashboard(isFirstRun = false).copy(recognitionIncidents = listOf(incident))

    // 1
    @Test fun `ready Home shows the card`() {
        show(withIncident(), hasPermissions = true)

        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText("For 1m").assertExists()
    }

    // 2
    @Test fun `the card shows on the permission gate`() {
        show(withIncident(), hasPermissions = false)

        compose.onNodeWithText(title).assertExists()
    }

    // 3
    @Test fun `the elapsed line ticks at minute grain`() {
        show(withIncident(), hasPermissions = true)
        compose.onNodeWithText("For 1m").assertExists()

        clock.millis = T0 + 60_000
        compose.mainClock.advanceTimeBy(62_000)

        compose.onNodeWithText("For 2m").assertExists()
        compose.onNodeWithText("For 1m").assertDoesNotExist()
    }

    // 4
    @Test fun `no incident shows no card when ready`() {
        show(ScreenshotData.dashboard(isFirstRun = false), hasPermissions = true)

        compose.onNodeWithText("not read", substring = true).assertDoesNotExist()
    }

    @Test fun `no incident shows no card on the gate`() {
        show(ScreenshotData.dashboard(isFirstRun = false), hasPermissions = false)

        compose.onNodeWithText("not read", substring = true).assertDoesNotExist()
    }

    /** A copy of `ComposeSmokeTest`'s private clock: the test moves it with the Compose main clock. */
    private class MutableClock(var millis: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
        override fun millis(): Long = millis
    }

    private companion object {
        const val T0 = 1_700_000_000_000L
    }
}
