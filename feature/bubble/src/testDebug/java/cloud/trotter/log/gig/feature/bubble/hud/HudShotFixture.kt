package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onRoot
import cloud.trotter.log.gig.core.designsystem.motion.LocalReducedMotion
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.analytics.ExpenseCategory
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.state.PendingOffer
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import java.io.File
import java.util.TimeZone

/**
 * What this module's `*ScreenshotsTest` classes share: the live shift's ribbon and log and an offer presented on
 * [HudScreenFixture]'s clock, all around its T0, and the render procedure that writes `<path>-<theme>.png`.
 */
internal abstract class HudShotFixture(protected val theme: String) : HudScreenFixture() {

    private lateinit var timeZone: TimeZone

    @Before fun fixTimeZone() {
        timeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }
    @After fun restoreTimeZone() { TimeZone.setDefault(timeZone) }

    /** Presented three seconds ago with 27 seconds left on the fixture clock. */
    protected fun presentedNow(pending: PendingOffer) =
        pending.copy(presentedAt = T0 - 3_000, countdownExpiresAt = T0 + 27_000)

    /** Two jobs, an offer, an expense and the dispatcher lines of the live shift, oldest first. */
    protected fun logItems(
        firstStore: String = "Store", offerStore: String = "Store", secondStore: String = "Store",
    ): List<LogItem> = listOf(
        DispatcherRow("chat:1", SESSION_START + 60_000, "Dash Paused!"),
        DispatcherRow("chat:2", SESSION_START + 300_000, "Dash Resumed!"),
        JobCardModel("job1", firstStore, 74, ScoreTone.GOOD, 9.25, 3.8, listOf(
            JobLine.Accepted(T0 - 5_400_000, 18.5, 1.4, false),
            JobLine.PickedUp(T0 - 4_800_000, T0 - 4_500_000, 300_000),
            JobLine.Delivered(T0 - 3_600_000, 9.25),
            JobLine.CashTip(T0 - 3_500_000, 5.0, false, null)), false, T0 - 5_400_000),
        OfferRow("offer:1", T0 - 2_400_000, offerStore, 4.25, 6.8, AppEventType.OFFER_DECLINED),
        ExpenseRow(3, T0 - 2_000_000, ExpenseCategory.FOOD, null, 8.5),
        JobCardModel("job2", secondStore, 81, ScoreTone.GOOD, 10.6, 4.2, listOf(
            JobLine.Accepted(T0 - 1_500_000, 21.0, 1.6, false),
            JobLine.PickedUp(T0 - 900_000, T0 - 600_000, 300_000),
            JobLine.Now(NowPhase.DROPOFF_ARRIVED, T0 - 239_000, T0 + 1_000, 8.1)), true, T0 - 1_500_000),
    )

    /** The live shift's ribbon: two finished jobs, then [tail] running now. */
    protected fun ribbon(tail: RibbonKind): RibbonModel {
        val s = SESSION_START
        val m = 60_000L
        val done = listOf(
            RibbonSegment(RibbonKind.WAIT, s, s + 12 * m), RibbonSegment(RibbonKind.PICKUP, s + 12 * m, s + 30 * m),
            RibbonSegment(RibbonKind.DROPOFF, s + 30 * m, s + 52 * m), RibbonSegment(RibbonKind.WAIT, s + 52 * m, s + 70 * m),
            RibbonSegment(RibbonKind.PICKUP, s + 70 * m, s + 95 * m), RibbonSegment(RibbonKind.DROPOFF, s + 95 * m, s + 120 * m))
        return RibbonModel(s, done + RibbonSegment(tail, s + 120 * m, null),
            listOf(OfferMark(s + 11 * m, true), OfferMark(s + 60 * m, false), OfferMark(s + 69 * m, true)), tail)
    }

    /**
     * strength.log's procedure: a frozen frame clock, two seconds of frames, then the assertion and
     * the capture to `<path>-<theme>.png`. A sheet runs a second round so the capture never lands mid-slide.
     */
    protected fun render(path: String, assertion: () -> Unit, settleTwice: Boolean = false, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme(darkTheme = theme == "dark") {
                CompositionLocalProvider(LocalNowProvider provides clock, LocalReducedMotion provides true, content = content)
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        if (settleTwice) {
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitForIdle()
        }
        assertion()
        if (System.getProperty("roborazzi.test.record") == "true") {
            val file = File(System.getProperty("giglog.screenshots.dir"), "$path-$theme.png")
            file.parentFile?.mkdirs()
            compose.onRoot().captureRoboImage(file)
        }
    }
}
