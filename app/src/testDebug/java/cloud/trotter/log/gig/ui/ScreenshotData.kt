package cloud.trotter.log.gig.ui

import cloud.trotter.log.gig.domain.analytics.AnalyticsWindowSelection
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindows
import cloud.trotter.log.gig.domain.analytics.DailyEarnings
import cloud.trotter.log.gig.domain.analytics.DecisionEconomics
import cloud.trotter.log.gig.domain.analytics.EarningsHeatmap
import cloud.trotter.log.gig.domain.analytics.EarningsHeatmapCalculator
import cloud.trotter.log.gig.domain.analytics.EarningsHeatmapCell
import cloud.trotter.log.gig.domain.analytics.EstimateVsReality
import cloud.trotter.log.gig.domain.analytics.GapStats
import cloud.trotter.log.gig.domain.analytics.GradedWindow
import cloud.trotter.log.gig.domain.analytics.GrossSources
import cloud.trotter.log.gig.domain.analytics.HourComposition
import cloud.trotter.log.gig.domain.analytics.NetPerHourPair
import cloud.trotter.log.gig.domain.analytics.OfferListing
import cloud.trotter.log.gig.domain.analytics.OfferOutcome
import cloud.trotter.log.gig.domain.analytics.PayMix
import cloud.trotter.log.gig.domain.analytics.PayMixParts
import cloud.trotter.log.gig.domain.analytics.PeriodEconomics
import cloud.trotter.log.gig.domain.analytics.PeriodTotals
import cloud.trotter.log.gig.domain.analytics.PlanTarget
import cloud.trotter.log.gig.domain.analytics.PlatformEconomics
import cloud.trotter.log.gig.domain.analytics.SavedPlanWindow
import cloud.trotter.log.gig.domain.analytics.SavedWeeklyPlan
import cloud.trotter.log.gig.domain.analytics.SessionEvent
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.analytics.StoreReportCard
import cloud.trotter.log.gig.domain.analytics.TimeEconomics
import cloud.trotter.log.gig.domain.analytics.WorkGaps
import cloud.trotter.log.gig.domain.analytics.WeeklyPlanGrade
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.ui.main.analytics.AnalyticsTab
import cloud.trotter.log.gig.ui.main.analytics.AnalyticsUiState
import cloud.trotter.log.gig.ui.main.analytics.OffersFeedState
import cloud.trotter.log.gig.ui.main.dashboard.DashboardUiState
import cloud.trotter.log.gig.ui.main.playbook.PlaybookUiState
import cloud.trotter.log.gig.R
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * #1463 — one populated, self-consistent record for the app's screenshot shots: a Wednesday evening,
 * 18:00 UTC on 2026-10-28, after four shifts this week (Mon 26 to Wed 28) and a saved plan for it.
 *
 * The figures agree with each other the way the read model's do: the week's gross is its recorded pay
 * plus its cash tips, its car cost is the gas and wear split, the four shifts sum to the week's totals,
 * the three days of earnings sum to the week, and the plan's projection is its windows' heatmap rates.
 * Store names are made up ("Sample …"); there are no addresses or customers.
 */
internal object ScreenshotData {
    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    val clock: Clock = Clock.fixed(Instant.parse("2026-10-28T18:00:00Z"), ZoneOffset.UTC)
    val today: LocalDate = LocalDate.of(2026, 10, 28)
    private val weekStart: LocalDate = LocalDate.of(2026, 10, 26)
    private const val HOUR = 3_600_000L

    // ── Economics ───────────────────────────────────────────────────────

    /** This week: $412.50 recorded + $18.00 cash = $430.50 earned; $102.00 car cost; 22.5 h online. */
    val week = PeriodEconomics(
        totals = PeriodTotals(earnings = 412.50, miles = 268.4, deliveries = 41, jobs = 38, onlineDuration = 81_000_000L),
        grossEarnings = 430.50,
        netProfit = 328.50,
        unattributedPay = 0.0,
        netPerHour = 328.50 / 22.5,
        netPerMile = 328.50 / 268.4,
        fuelCost = 58.00,
        nonFuelCost = 44.00,
        grossSources = GrossSources(412.50, 4, 0.0, 0, 0.0, 0, 0.0, 0, 0, 18.00),
    )

    /** Last week, for the delta chips. */
    val previousWeek = PeriodEconomics(
        totals = PeriodTotals(earnings = 452.20, miles = 301.0, deliveries = 46, jobs = 43, onlineDuration = 93_600_000L),
        grossEarnings = 470.20,
        netProfit = 356.80,
        unattributedPay = 0.0,
        netPerHour = 356.80 / 26.0,
        netPerMile = 356.80 / 301.0,
        fuelCost = 64.40,
        nonFuelCost = 49.00,
        grossSources = GrossSources(452.20, 5, 0.0, 0, 0.0, 0, 0.0, 0, 0, 18.00),
    )

    /** Today, Wednesday: one three-hour shift that ended at 17:45. */
    val todayEconomics = PeriodEconomics(
        totals = PeriodTotals(earnings = 61.25, miles = 38.2, deliveries = 6, jobs = 6, onlineDuration = 3 * HOUR),
        grossEarnings = 64.25,
        netProfit = 49.70,
        unattributedPay = 0.0,
        netPerHour = 49.70 / 3.0,
        netPerMile = 49.70 / 38.2,
        fuelCost = 8.25,
        nonFuelCost = 6.30,
        grossSources = GrossSources(61.25, 1, 0.0, 0, 0.0, 0, 0.0, 0, 0, 3.00),
    )

    /** Base pay + platform tips = the recorded $412.50; every delivery itemized. */
    private val payMixParts = PayMixParts(
        basePay = 236.00, tips = 176.50, cashTips = 18.00, deliveries = 41, deliveriesWithBreakdown = 41,
        offerEstimatePay = 0.0, offerEstimateDeliveries = 0, paylessDeliveries = 0,
    )

    /** Monday to Sunday of this week; Thursday on has not happened yet. */
    val weekDailyEarnings: List<DailyEarnings> =
        (listOf(Triple(171.40, 131.20, 16), Triple(194.85, 147.60, 19), Triple(64.25, 49.70, 6)) +
            List(4) { Triple(0.0, 0.0, 0) })
            .mapIndexed { day, (gross, net, deliveries) ->
                DailyEarnings(weekStart.plusDays(day.toLong()), gross, net, deliveries)
            }

    /** The week's four shifts, newest first; they sum to [week]. */
    val recentSessions = listOf(
        session("s4", "2026-10-28T14:45:00Z", 3.0, 61.25, 38.2, 6, 6, 10, 6, 3, 1, 3.00),
        session("s3", "2026-10-27T16:00:00Z", 5.5, 112.40, 79.0, 11, 10, 17, 10, 6, 1, 6.00),
        session("s2", "2026-10-27T09:30:00Z", 4.5, 76.45, 52.2, 8, 8, 12, 8, 3, 1, 0.0),
        session("s1", "2026-10-26T13:30:00Z", 9.5, 162.40, 99.0, 16, 14, 25, 14, 10, 1, 9.00),
    )

    private fun session(
        id: String, start: String, hours: Double, reported: Double, miles: Double, deliveries: Int, jobs: Int,
        received: Int, accepted: Int, declined: Int, timedOut: Int, cash: Double,
    ): SessionRecord {
        val startedAt = at(start)
        return SessionRecord(
            sessionId = id, platform = Platform.DoorDash, startedAt = startedAt,
            endedAt = startedAt + (hours * HOUR).toLong(), reportedEarnings = reported,
            reportedDurationMillis = null, miles = miles, deliveries = deliveries, jobsCompleted = jobs,
            offersReceived = received, offersAccepted = accepted, offersDeclined = declined,
            offersTimeout = timedOut, cashTips = cash,
        )
    }

    /**
     * The shifts' accepts and drop-offs, laid out evenly through each shift: a job every
     * `length / (jobs + 1)`, its drop-offs part-way through its slot, the first jobs of a shift carrying
     * the second drop of a stacked order. Every one of the week's 41 drop-offs is here, so the gaps
     * below are what `WorkGaps` measures over the same completions the Money tab counts.
     */
    private class Timeline(val completions: List<SessionEvent>, val accepts: List<SessionEvent>, val deliveryMillis: Long)

    private val timeline: Timeline = run {
        var sequence = 0L
        val completions = mutableListOf<SessionEvent>()
        val accepts = mutableListOf<SessionEvent>()
        var deliveryMillis = 0L
        for (shift in recentSessions.sortedBy { it.startedAt }) {
            val slot = (shift.endedAt!! - shift.startedAt) / (shift.jobsCompleted + 1)
            val stacked = shift.deliveries - shift.jobsCompleted
            for (job in 0 until shift.jobsCompleted) {
                val acceptedAt = shift.startedAt + slot / 2 + job * slot
                accepts += SessionEvent(shift.sessionId, ++sequence, acceptedAt)
                val drops = if (job < stacked) listOf(0.55, 0.70) else listOf(0.45 + 0.05 * (job % 4))
                drops.forEach { completions += SessionEvent(shift.sessionId, ++sequence, acceptedAt + (it * slot).toLong()) }
                deliveryMillis += (drops.last() * slot).toLong()
            }
        }
        Timeline(completions, accepts, deliveryMillis)
    }

    val time = TimeEconomics(
        sessions = 4,
        onlineMillis = 81_000_000L,
        deliveryMinutes = timeline.deliveryMillis / 60_000.0,
        miles = 268.4,
        deliveryMiles = 231.0,
        deliveriesWithDeadline = 41,
        onTimeDeliveries = 38,
        avgDeadlineMarginMillis = 330_000.0,
        deliveries = 41,
        dropoffDwellMillis = 41 * 95_000L,
        dropoffsTimed = 41,
        pickups = 38,
        pickupDwellMillis = 38 * 420_000L,
        pickupsTimed = 38,
    )

    /** Measured, not stated: one gap per drop-off followed by an accept in its shift (37), plus 4 shift tails. */
    private val gaps: GapStats = WorkGaps.compute(timeline.completions, timeline.accepts,
        recentSessions.associate { it.sessionId to it.endedAt!! })

    private val decisions = DecisionEconomics(
        received = 64, accepted = 38, declined = 22, timedOut = 4, acceptanceRate = 38.0 / 64.0,
        declinedEstNet = 71.40, declinedWithEstimate = 22, avgScoreAccepted = 68.0, avgScoreDeclined = 41.0,
        avgEstPerHourAccepted = 19.40, avgEstPerHourDeclined = 11.20,
    )

    private val estimateVsReality = EstimateVsReality(
        acceptedOffers = 38, offers = 31, estPerHour = 19.40, realizedPerHour = 17.85, ratio = 17.85 / 19.40,
    )

    /** The Offers list's first page under All. */
    val offersFeed = OffersFeedState(
        offers = listOf(
            OfferListing(103, Platform.DoorDash, "Sample Pizza", at("2026-10-28T17:20:00Z"), 8.75, 3.9, 21.0, 74.0, OfferOutcome.ACCEPTED),
            OfferListing(102, Platform.DoorDash, "Sample Grocery", at("2026-10-28T16:55:00Z"), 4.25, 7.8, 8.5, 31.0, OfferOutcome.DECLINED),
            OfferListing(101, Platform.DoorDash, "Sample Tacos", at("2026-10-28T16:10:00Z"), 11.50, 5.2, 19.0, 70.0, OfferOutcome.ACCEPTED),
        ),
        total = 3,
    )

    // ── The lifetime record: heatmap, stores ────────────────────────────

    private val hourRates = mapOf(10 to 14.0, 11 to 18.0, 12 to 21.0, 13 to 16.0,
        16 to 15.0, 17 to 19.0, 18 to 24.0, 19 to 26.0, 20 to 22.0, 21 to 17.0)

    private fun dayLift(day: Int) = when (day) {
        1 -> -1.0
        4, 5 -> 3.0
        6 -> 1.0
        else -> 0.0
    }

    private fun rate(day: Int, hour: Int): Double? = hourRates[hour]?.plus(dayLift(day))

    /**
     * Ten worked hours a day (lunch and dinner) at 2.5 h of coverage each, so every weekday clears
     * `DayPlanner.MIN_SAMPLED_HOURS` and each cell rests on several days.
     */
    val heatmap = EarningsHeatmap(
        cells = List(EarningsHeatmap.DAYS * EarningsHeatmap.HOURS) { i ->
            val day = i / EarningsHeatmap.HOURS
            val hour = i % EarningsHeatmap.HOURS
            val r = rate(day, hour)
            val coverage = if (r == null) 0.0 else 2.5
            EarningsHeatmapCell(day, hour, (r ?: 0.0) * coverage, coverage, r)
        },
        minCoverageHours = EarningsHeatmapCalculator.DEFAULT_MIN_COVERAGE_HOURS,
    )

    /** Five stores, made up; "Sample Tacos" waits long enough to be flagged. */
    val storeCards = listOf(
        store("Sample Grocery", "#12", 42, 44, 512.30, 401.10, 480_000L, 1_020_000L),
        store("Sample Pizza", "#3", 31, 31, 287.40, 231.60, 270_000L, 600_000L),
        store("Sample Noodles", "#7", 18, 18, 176.20, 139.80, 390_000L, 840_000L),
        store("Sample Tacos", "#21", 12, 12, 101.50, 80.10, 600_000L, 1_320_000L),
        store("Sample Pharmacy", "#5", 9, 9, 78.30, 61.90, 240_000L, 420_000L),
    )

    private fun store(
        name: String, number: String, pickups: Int, deliveries: Int, gross: Double, net: Double,
        p50: Long, p95: Long,
    ): StoreReportCard {
        val chain = name.lowercase()
        return StoreReportCard(
            storeKey = "${Platform.DoorDash.wire}|$chain|$number",
            platform = Platform.DoorDash.wire,
            normalizedChain = chain,
            chainDisplay = name,
            runningKey = number,
            address = null,
            locationKnown = true,
            pickups = pickups,
            deliveries = deliveries,
            gross = gross,
            net = net,
            avgDwellMillis = p50 * 1.1,
            p50DwellMillis = p50,
            p95DwellMillis = p95,
            firstSeenAt = at("2026-08-03T17:00:00Z"),
            lastSeenAt = at("2026-10-28T17:30:00Z"),
        )
    }

    // ── This week's plan ────────────────────────────────────────────────

    private fun window(day: Int, start: Int, end: Int, samples: Int): SavedPlanWindow =
        SavedPlanWindow(day, start, end, (start until end).map { rate(day, it)!! }.average(), samples)

    /** 15 target hours in four windows; the projection is the windows' own rates ($369.00). */
    val plan: SavedWeeklyPlan = listOf(window(0, 17, 21, 4), window(2, 18, 21, 4), window(4, 17, 21, 5), window(5, 17, 21, 5))
        .let { windows ->
            SavedWeeklyPlan(
                weekStart = weekStart,
                savedAtMillis = at("2026-10-25T20:00:00Z"),
                target = PlanTarget.Hours(15),
                windows = windows,
                projectedKept = windows.sumOf { it.projectedKept },
                randomKept = 301.50,
            )
        }

    /** Monday's window worked in full; tonight's has not started; the rest of the week is ahead. */
    val planGrade: WeeklyPlanGrade = plan.windows.map { w ->
        if (w.dayIndex == 0) GradedWindow(w, actualHours = 4.0, actualKept = 96.40) else GradedWindow(w, 0.0, 0.0)
    }.let { graded ->
        WeeklyPlanGrade(
            weekStart = weekStart,
            plannedHours = plan.totalHours,
            actualHours = graded.sumOf { it.actualHours },
            projectedKept = plan.projectedKept,
            actualKept = graded.sumOf { it.actualKept },
            keptOutsideWindows = week.netProfit - graded.sumOf { it.actualKept },
            windows = graded,
        )
    }

    // ── Screen states ───────────────────────────────────────────────────

    /** Home ready and offline, as the ViewModel projects it ("Ready for a shift"). */
    fun dashboard(isFirstRun: Boolean = false) = DashboardUiState(
        isFirstRun = isFirstRun,
        statusText = R.string.dashboard_status_ready,
        isDashing = false,
        today = today,
        todayEconomics = todayEconomics,
        heatmap = heatmap,
        weekEconomics = week,
        previousWeekEconomics = previousWeek,
        weekDailyEarnings = weekDailyEarnings,
        weeklyPlan = plan,
        sessionStartedAt = null,
    )

    fun analytics(tab: AnalyticsTab, showMore: Boolean): AnalyticsUiState {
        val window = AnalyticsWindowSelection.DEFAULT.resolve(today)
        return AnalyticsUiState(
            showMore = showMore,
            selectedTab = tab,
            window = window,
            today = today,
            canStepBack = AnalyticsWindows.canStepBack(window),
            canStepForward = AnalyticsWindows.canStepForward(window, today),
            economics = week,
            previousEconomics = previousWeek,
            payMix = PayMix.of(week, payMixParts),
            platformSplit = listOf(PlatformEconomics(Platform.DoorDash, week)),
            dailyEarnings = weekDailyEarnings,
            recentSessions = recentSessions,
            decisions = decisions,
            estimateVsReality = estimateVsReality,
            time = time,
            gaps = gaps,
            hourComposition = HourComposition.of(time, gaps),
            netPerHour = NetPerHourPair.of(week.netProfit, time, gaps),
        )
    }

    fun playbook(showMore: Boolean) = PlaybookUiState(
        showMore = showMore,
        loading = false,
        savedPlan = plan,
        planGrade = planGrade,
        heatmap = heatmap,
        storeCards = storeCards,
    )

    /** The one deliberate thin-data shot: nothing on record yet. */
    val playbookEmpty = PlaybookUiState(loading = false)

    /** The Time tab's typical gap, as its tile reports it. */
    val typicalGapMillis: Long get() = gaps.medianMillis!!

    /** The Offers tab's accept rate, as the tile reports it. */
    val acceptanceRate: Double get() = decisions.acceptanceRate!!
}
