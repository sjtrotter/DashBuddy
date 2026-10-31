package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.feature.bubble.MutableClock
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.model.cards.FlowCardSnapshot
import cloud.trotter.log.gig.domain.state.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import org.junit.Before
import org.junit.After

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal abstract class HudScreenFixture {
    @get:Rule val compose = createComposeRule()
    protected val clock = MutableClock(T0)
    protected lateinit var locale: Locale

    @Before fun setup() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        compose.mainClock.autoAdvance = false
    }
    @After fun cleanup() { Locale.setDefault(locale) }

    protected fun last() = SessionRecord(
        "last", Platform.DoorDash, T0 - 158 * 60_000, T0 - 38 * 60_000,
        reportedEarnings = 100.0, reportedDurationMillis = null, miles = 42.5,
        deliveries = 5, jobsCompleted = 5, offersReceived = 8, offersAccepted = 5,
        offersDeclined = 2, offersTimeout = 1,
    )

    protected fun state(
        vehicleChip: cloud.trotter.log.gig.feature.bubble.hud.vehicle.VehicleChipModel? = null,
        flow: Flow = Flow.Idle, resting: Boolean = false, first: Boolean = false, paused: Boolean = false,
        earnings: Double? = 71.2, cash: Double? = null, goal: Double? = null, today: Double? = null,
        logOpen: Boolean = false, cashTipCue: Boolean = false,
    ) = HudStateMapper.map(
        region = if (resting || first) null else PlatformRegion(
            Platform.DoorDash, if (paused) Mode.Paused else Mode.Online, Session("live", T0 - 160 * 60_000 - 30_000)),
        flow = flow, platform = Platform.DoorDash, switchable = emptyList(), vehicleChip = vehicleChip,
        shift = when {
            first -> null
            resting -> DisplayedShift(Platform.DoorDash, "last", live = false, earnings = earnings, cashTips = cash, record = last())
            else -> DisplayedShift(Platform.DoorDash, "live", live = true, earnings = earnings, cashTips = cash, record = null)
        },
        miles = 42.5, lastSession = if (first) null else last(),
        goal = goal, todayEarned = today,
        hasActiveCard = !resting && !first, hasOfferCard = flow == Flow.OfferPresented, logOpen = logOpen, gas = GasRowModel(3.5f, true), cashTipCue = cashTipCue, odometerLoaded = true,
    )

    protected fun show(model: @Composable () -> HudUiState = { state() }, onDock: (DockKind) -> Unit = {}) {
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    HudScreen(model(), RibbonModel(T0 - 180 * 60_000,
                        listOf(RibbonSegment(RibbonKind.WAIT, T0 - 180 * 60_000, null)), emptyList(), RibbonKind.WAIT),
                        onDockClick = onDock, stateCard = { Text("State slot") },
                        offer = { Text("Offer slot") }, log = { Text("Log slot") })
                }
            }
        }
    }

    protected fun value(tag: String, text: String) = compose.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)

    // A state write from the test (a click callback, a runOnIdle assignment) lands as a pending snapshot
    // notification on the paused main looper; waitForIdle delivers it, and only THEN does a clock
    // advance run the frame that recomposes. Advancing first runs a frame the write has not reached.
    protected fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(100) }

    protected fun tick(delta: Long) {
        clock.millis += delta
        compose.mainClock.advanceTimeBy(2_000)
    }

    protected companion object { const val T0 = 1_700_000_000_000L }
}
