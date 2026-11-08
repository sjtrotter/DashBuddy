package cloud.trotter.log.gig.ui

import cloud.trotter.log.gig.domain.analytics.AnalyticsWindow
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindowSelection
import cloud.trotter.log.gig.domain.analytics.checks.ShiftCheck
import cloud.trotter.log.gig.domain.analytics.recap.DayFacts
import cloud.trotter.log.gig.domain.analytics.recap.DayShift
import cloud.trotter.log.gig.domain.analytics.recap.RecapComparisons
import cloud.trotter.log.gig.domain.analytics.recap.RecapDays
import cloud.trotter.log.gig.domain.analytics.recap.RecapMeasure
import cloud.trotter.log.gig.domain.analytics.recap.ShiftWeeks
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindows
import cloud.trotter.log.gig.domain.analytics.DailyEarnings
import cloud.trotter.log.gig.domain.analytics.EarningsHeatmap
import cloud.trotter.log.gig.domain.analytics.EarningsHeatmapCalculator
import cloud.trotter.log.gig.domain.analytics.EarningsHeatmapCell
import cloud.trotter.log.gig.domain.analytics.GradedWindow
import cloud.trotter.log.gig.domain.analytics.GrossSources
import cloud.trotter.log.gig.domain.analytics.PeriodEconomics
import cloud.trotter.log.gig.domain.analytics.PeriodTotals
import cloud.trotter.log.gig.domain.analytics.PlanTarget
import cloud.trotter.log.gig.domain.analytics.SavedPlanWindow
import cloud.trotter.log.gig.domain.analytics.SavedWeeklyPlan
import cloud.trotter.log.gig.domain.analytics.StoreReportCard
import cloud.trotter.log.gig.domain.analytics.WeeklyPlanGrade
import cloud.trotter.log.gig.core.data.vehicle.Garage
import cloud.trotter.log.gig.domain.evaluation.EvaluationConfig
import cloud.trotter.log.gig.domain.evaluation.MetricType
import cloud.trotter.log.gig.domain.evaluation.ScoringRule
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.model.vehicle.GasCar
import cloud.trotter.log.gig.domain.model.vehicle.Vehicle
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.ui.main.analytics.AnalyticsUiState
import cloud.trotter.log.gig.ui.main.analytics.ShiftToCheck
import cloud.trotter.log.gig.ui.main.analytics.day.DayUiState
import cloud.trotter.log.gig.ui.main.analytics.shifts.ShiftsUiState
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
        grossSources = GrossSources(412.50, 4, 0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0, 18.00),
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
        grossSources = GrossSources(452.20, 5, 0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0, 18.00),
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
        grossSources = GrossSources(61.25, 1, 0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0, 3.00),
    )

    /** Monday to Sunday of this week; Thursday on has not happened yet. */
    val weekDailyEarnings: List<DailyEarnings> =
        (listOf(Triple(171.40, 131.20, 16), Triple(194.85, 147.60, 19), Triple(64.25, 49.70, 6)) +
            List(4) { Triple(0.0, 0.0, 0) })
            .mapIndexed { day, (gross, net, deliveries) ->
                DailyEarnings(weekStart.plusDays(day.toLong()), gross, net, deliveries)
            }

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

    // ── Recap (#1515) ───────────────────────────────────────────────────

    private fun shift(id: String, start: String, hours: Double?, earned: Double, profit: Double) = DayShift(
        sessionId = id, platform = Platform.DoorDash, startedAt = at(start),
        endedAt = hours?.let { at(start) + (it * HOUR).toLong() }, earned = earned, profit = profit, paySeen = true,
    )

    /** This week's shifts by day: Monday one, Tuesday two (it opens the day page), today's still running. */
    private val weekFacts = listOf(
        DayFacts(weekStart, listOf(shift("s1", "2026-10-26T13:30:00Z", 9.5, 171.40, 131.20))),
        DayFacts(
            weekStart.plusDays(1),
            listOf(shift("s2", "2026-10-27T09:30:00Z", 4.5, 76.45, 57.00), shift("s3", "2026-10-27T16:00:00Z", 5.5, 118.40, 90.60)),
        ),
        DayFacts(today, listOf(shift("s4", "2026-10-28T14:45:00Z", null, 64.25, 49.70))),
    )

    /** Last week: a shift every day but Thursday; Saturday's had no pay found. */
    private val lastWeekFacts = listOf(88.10, 102.30, 61.75, null, 74.40, -1.0, 143.65).mapIndexedNotNull { day, earned ->
        val date = weekStart.minusWeeks(1).plusDays(day.toLong())
        earned?.let {
            val paid = it >= 0.0
            DayFacts(date, listOf(shift("l$day", "${date}T15:00:00Z", 5.0, if (paid) it else 0.0, if (paid) it * 0.76 else 0.0)
                .copy(paySeen = paid)))
        }
    }

    /** Recap mid-week: today's shift is running, so the comparison waits; Monday's shift has a check. */
    fun recap(): AnalyticsUiState = recapOn(AnalyticsWindowSelection.DEFAULT.resolve(today), weekFacts, lastWeekFacts, running = true)
        .copy(
            shiftsToCheck = listOf(
                ShiftToCheck(weekStart, weekFacts.first().shifts.single(), listOf(ShiftCheck.NoDelivery(offerEventSequenceId = 77))),
            ),
        )

    /** Last week seen from today: every day has happened, so the comparison covers all seven. */
    fun recapPastWeek(): AnalyticsUiState =
        recapOn(AnalyticsWindowSelection(-1).resolve(today), lastWeekFacts, emptyList(), running = false)
            .copy(economics = previousWeek, lastWeekEconomics = PeriodEconomics.EMPTY)

    /** A week with no shift, two weeks back. */
    fun recapWeekOff(): AnalyticsUiState =
        recapOn(AnalyticsWindowSelection(-2).resolve(today), emptyList(), lastWeekFacts, running = false)
            .copy(economics = PeriodEconomics.EMPTY, lastWeekEconomics = previousWeek, spending = 12.50)

    /** No shift has ever been recorded. */
    val recapNoShiftsYet = AnalyticsUiState(anyShift = false, today = today, week = AnalyticsWindowSelection.DEFAULT.resolve(today))

    private fun recapOn(week: AnalyticsWindow, facts: List<DayFacts>, lastFacts: List<DayFacts>, running: Boolean): AnalyticsUiState {
        val start = requireNotNull(week.startDate)
        val days = RecapDays.of(start, today, facts)
        val lastWeek = RecapDays.of(start.minusWeeks(1), today, lastFacts)
        return AnalyticsUiState(
            week = week,
            today = today,
            canStepForward = AnalyticsWindows.canStepForward(week, today),
            shiftRunning = running,
            economics = ScreenshotData.week,
            lastWeekEconomics = previousWeek,
            days = days,
            lastWeekDays = lastWeek,
            comparison = RecapComparisons.of(days, lastWeek, RecapMeasure.EARNED, running),
            spending = 46.30,
        )
    }

    /** Tuesday's day page: two shifts, newest first. */
    val day = DayUiState(
        date = weekStart.plusDays(1),
        economics = PeriodEconomics(
            totals = PeriodTotals(earnings = 188.85, miles = 131.2, deliveries = 19, jobs = 18, onlineDuration = 36_000_000L),
            grossEarnings = 194.85,
            netProfit = 147.60,
            unattributedPay = 0.0,
            netPerHour = 147.60 / 10.0,
            netPerMile = 147.60 / 131.2,
            cash = 6.00,
        ),
        shifts = weekFacts[1].shifts.sortedByDescending { it.startedAt },
    )

    /**
     * The Shifts list's shifts: this week's (Monday also an Uber shift, two apps on one date), last week's (Saturday's
     * had no pay found), and one three weeks back; the week between has none. Monday's DoorDash shift has a check.
     */
    private val listShifts: List<DayShift> = weekFacts.flatMap { it.shifts } +
        shift("u1", "2026-10-26T18:00:00Z", 3.0, 41.20, 30.10).copy(platform = Platform.Uber) +
        lastWeekFacts.flatMap { it.shifts } +
        shift("o1", "2026-10-07T16:00:00Z", 4.0, 92.15, 70.40)

    /** The Shifts list opened at [target]'s week (this week by default). */
    fun shifts(target: LocalDate = weekStart): ShiftsUiState = ShiftsUiState(
        today = today,
        targetWeek = ShiftWeeks.weekStart(target),
        weeks = ShiftWeeks.of(listShifts, target, ZoneOffset.UTC),
        checks = mapOf("s1" to listOf(ShiftCheck.NoDelivery(offerEventSequenceId = 77))),
    )

    /** Last week with 29 shifts (four a day, five on Monday), two apps taking turns: the 200 % text stress case. */
    val busyWeek: ShiftsUiState by lazy {
        val monday = weekStart.minusWeeks(1)
        val shifts = (0 until BUSY_WEEK_SHIFTS).map { i ->
            val date = monday.plusDays((i % 7).toLong())
            val slot = i / 7
            val hour = (7 + slot * 3).toString().padStart(2, '0')
            shift("b$i", "${date}T$hour:00:00Z", 2.5, earned = 18.45 + (i * 7) % 30, profit = 13.90 + (i * 5) % 20)
                .copy(platform = if (slot % 2 == 0) Platform.DoorDash else Platform.Uber)
        }
        ShiftsUiState(today = today, targetWeek = monday, weeks = ShiftWeeks.of(shifts, monday, ZoneOffset.UTC))
    }

    const val BUSY_WEEK_SHIFTS = 29

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

    /** Offer rules (#1447): the app's default rule list (`StrategyRepository`'s), shopping allowed. */
    val evaluationConfig = EvaluationConfig(
        rules = listOf(
            ScoringRule.MetricRule("pay", true, MetricType.PAYOUT, 7.0f),
            ScoringRule.MetricRule("dpm", true, MetricType.DOLLAR_PER_MILE, 1.5f),
            ScoringRule.MetricRule("dist", true, MetricType.MAX_DISTANCE, 10.0f),
            ScoringRule.MetricRule("hr", true, MetricType.ACTIVE_HOURLY, 22.0f),
            ScoringRule.MetricRule("items", false, MetricType.ITEM_COUNT, 50.0f),
        ),
    )

    /** Settings home (#1447): one active car and the default costs. */
    private val car = Vehicle("car", "Sample car", 1L, GasCar(year = "2020", make = "Honda", model = "Civic"))
    val garage = Garage(listOf(car), car.id, readable = true)
    val userEconomy = UserEconomy()
}
