package cloud.trotter.dashbuddy.ui.fragments.dashhistory.common

import cloud.trotter.dashbuddy.data.dash.DashComposite
import cloud.trotter.dashbuddy.data.dash.DashDao
import cloud.trotter.dashbuddy.data.offer.OfferStatus
import cloud.trotter.dashbuddy.data.zone.ZoneDao
import cloud.trotter.dashbuddy.data.zone.ZoneEntity
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.annual.AnnualDisplay
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.daily.DailyDisplay
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.monthly.MonthlyDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class DashHistoryRepo(
    private val dashDao: DashDao,
    private val zoneDao: ZoneDao
    // We no longer need OfferDao, OrderDao, PayDao here because DashDao fetches everything!
) {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    /**
     * ANNUAL DISPLAY
     */
    fun getAnnualDisplayFlow(year: Int): Flow<AnnualDisplay> {
        val start = getStartOfYearMillis(year)
        val end = getEndOfYearMillis(year)

        return dashDao.getDashCompositesFlow(start, end)
            .map { composites ->
                if (composites.isEmpty()) {
                    return@map AnnualDisplay.empty(year)
                }

                // 1. Calculate Summaries
                val monthSummaries = mapCompositesToMonthSummaries(composites)

                // 2. Calculate Stats
                val stats = calculateStats(composites)

                AnnualDisplay(
                    year = year,
                    stats = stats,
                    monthSummaries = monthSummaries
                )
            }
            .flowOn(Dispatchers.Default)
    }

    /**
     * MONTHLY DISPLAY
     */
    fun getMonthlyDisplayFlow(year: Int, month: Int): Flow<MonthlyDisplay> {
        val start = getStartOfMonthMillis(year, month)
        val end = getEndOfMonthMillis(year, month)

        return dashDao.getDashCompositesFlow(start, end)
            .map { composites ->
                if (composites.isEmpty()) {
                    return@map MonthlyDisplay.empty(year, month)
                }

                // 1. Calculate Summaries
                val daySummaries = mapCompositesToDailySummaries(composites, year, month)

                // 2. Calculate Stats
                val stats = calculateStats(composites)

                MonthlyDisplay(
                    date = LocalDate.of(year, month, 1),
                    stats = stats,
                    calendarDays = daySummaries
                )
            }
            .flowOn(Dispatchers.Default)
    }

    /**
     * DAILY DISPLAY
     * (Requires Zone Names, so we combine with ZoneDao)
     */
    fun getDailyDisplayFlow(date: LocalDate): Flow<DailyDisplay> {
        val start = getStartOfDayMillis(date)
        val end = getEndOfDayMillis(date)

        return combine(
            dashDao.getDashCompositesFlow(start, end),
            zoneDao.getAllZones() // We need zone names for the "Downtown" label
        ) { composites, allZones ->

            if (composites.isEmpty()) {
                return@combine DailyDisplay.empty(date)
            }

            // 1. Map to Dash Summaries (The detailed cards)
            val dashSummaries = mapCompositesToDashSummaries(composites, allZones)

            // 2. Calculate Stats
            val stats = calculateStats(composites)

            DailyDisplay(
                date = date,
                stats = stats,
                dashSummaries = dashSummaries
            )
        }
            .flowOn(Dispatchers.Default)
    }

    // --- MAIN STATS CALCULATOR ---

    private fun calculateStats(composites: List<DashComposite>): SummaryStats {
        val totalDashes = composites.size

        // Count unique zone IDs across all dashes
        val uniqueZoneCount = composites.flatMap { it.zoneLinks }.map { it.zoneId }.distinct().size

        val totalHours = composites.sumOf {
            (it.dash.stopTime ?: it.dash.startTime) - it.dash.startTime
        } / 3_600_000.0

        val totalMiles = composites.sumOf { it.dash.totalDistance ?: 0.0 }

        // Active Hours: Sum of (CompletionTime - AcceptTime) for all ACCEPTED offers
        val activeHours = composites.sumOf { dash ->
            dash.offers.filter { it.offer.status == OfferStatus.ACCEPTED && it.offer.acceptTime != null }
                .sumOf { offerComp ->
                    val latestCompletion =
                        offerComp.orders.mapNotNull { it.order.completionTimestamp }.maxOrNull()
                    if (latestCompletion != null) (latestCompletion - offerComp.offer.acceptTime!!) else 0L
                }
        } / 3_600_000.0

        val activeMiles = composites.sumOf { dash ->
            dash.offers.sumOf { offer ->
                offer.orders.sumOf { it.order.mileage ?: 0.0 }
            }
        }

        // Total Earnings: Sum of AppPay + Tips across all offers/orders
        val totalEarnings = composites.sumOf { dash ->
            dash.offers.sumOf { offer ->
                val appPay = offer.appPay.sumOf { it.amount }
                val tips = offer.orders.flatMap { it.tips }.sumOf { it.amount }
                appPay + tips
            }
        }

        return SummaryStats(
            totalDashes = totalDashes,
            uniqueZoneCount = uniqueZoneCount,
            totalHours = totalHours,
            totalMiles = totalMiles,
            activeHours = activeHours,
            activeMiles = activeMiles,
            totalEarnings = totalEarnings
        )
    }

    // --- MAPPING HELPERS ---

    private fun mapCompositesToDashSummaries(
        composites: List<DashComposite>,
        allZones: List<ZoneEntity>
    ): List<DailyDisplay.DashSummary> {
        val zoneMap = allZones.associateBy { it.id }

        return composites.map { composite ->
            val dash = composite.dash

            // Calculate earnings for this specific dash
            val dashEarnings = composite.offers.sumOf { offer ->
                val appPay = offer.appPay.sumOf { it.amount }
                val tips = offer.orders.flatMap { it.tips }.sumOf { it.amount }
                appPay + tips
            }

            // Map Offers
            val offerSummaries = composite.offers.map { offerComp ->
                val orders = offerComp.orders
                val appPayList = offerComp.appPay
                val tipsList = orders.flatMap { it.tips }

                val actualPay = appPayList.sumOf { it.amount } + tipsList.sumOf { it.amount }

                // Create Receipt Lines
                val payBreakdown = appPayList.map {
                    DailyDisplay.ReceiptLine("App Pay", formatMoney(it.amount))
                } + tipsList.map {
                    DailyDisplay.ReceiptLine(
                        it.type.name.replaceFirstChar { c -> c.uppercase() },
                        formatMoney(it.amount)
                    )
                }

                DailyDisplay.OfferSummary(
                    offerId = offerComp.offer.id,
                    summaryLine = "${orders.firstOrNull()?.order?.storeName ?: "Unknown"}: ${offerComp.offer.distanceMiles ?: 0.0} mi",
                    actualPay = actualPay,
                    status = offerComp.offer.status,
                    payBreakdown = payBreakdown
                )
            }

            // Resolve Zone Name
            val startZoneId = composite.zoneLinks.find { it.isStartZone }?.zoneId
                ?: composite.zoneLinks.firstOrNull()?.zoneId
            val zoneName = startZoneId?.let { zoneMap[it]?.zoneName } ?: "Unknown Zone"

            DailyDisplay.DashSummary(
                dashId = dash.id,
                startTime = timeFormat.format(Date(dash.startTime)),
                stopTime = dash.stopTime?.let { timeFormat.format(Date(it)) } ?: "Active",
                zoneName = zoneName,
                totalEarnings = dashEarnings,
                offerSummaries = offerSummaries
            )
        }
    }

    private fun mapCompositesToDailySummaries(
        composites: List<DashComposite>,
        year: Int,
        month: Int
    ): List<MonthlyDisplay.DayInMonthSummary> {
        val dailyEarnings = mutableMapOf<Int, Double>()

        for (dash in composites) {
            for (offer in dash.offers) {
                // Attribute earnings to the day of the OFFER, not necessarily the dash start
                val cal = Calendar.getInstance().apply { timeInMillis = offer.offer.timestamp }
                val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)

                val pay = offer.appPay.sumOf { it.amount } +
                        offer.orders.flatMap { it.tips }.sumOf { it.amount }

                dailyEarnings[dayOfMonth] = (dailyEarnings[dayOfMonth] ?: 0.0) + pay
            }
        }

        val daysInMonth = YearMonth.of(year, month).lengthOfMonth()

        return (1..daysInMonth).map { day ->
            val earnings = dailyEarnings[day] ?: 0.0
            MonthlyDisplay.DayInMonthSummary(
                dayOfMonth = day,
                totalEarnings = earnings,
                hasData = earnings > 0.0
            )
        }
    }

    private fun mapCompositesToMonthSummaries(
        composites: List<DashComposite>
    ): List<AnnualDisplay.MonthInYearSummary> {
        val monthlyEarnings = mutableMapOf<Int, Double>()

        for (dash in composites) {
            for (offer in dash.offers) {
                val cal = Calendar.getInstance().apply { timeInMillis = offer.offer.timestamp }
                val month = cal.get(Calendar.MONTH) + 1

                val pay = offer.appPay.sumOf { it.amount } +
                        offer.orders.flatMap { it.tips }.sumOf { it.amount }

                monthlyEarnings[month] = (monthlyEarnings[month] ?: 0.0) + pay
            }
        }

        return (1..12).map { month ->
            val earnings = monthlyEarnings[month] ?: 0.0
            AnnualDisplay.MonthInYearSummary(
                month = month,
                totalEarnings = earnings,
                hasData = earnings > 0.0
            )
        }
    }

    // --- DATE & FORMAT UTILS ---

    private fun formatMoney(amount: Double) = String.format(Locale.getDefault(), "$%.2f", amount)

    private fun getStartOfYearMillis(year: Int): Long = Calendar.getInstance().apply {
        set(year, Calendar.JANUARY, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun getEndOfYearMillis(year: Int): Long = Calendar.getInstance().apply {
        set(year, Calendar.DECEMBER, 31, 23, 59, 59); set(Calendar.MILLISECOND, 999)
    }.timeInMillis

    private fun getStartOfMonthMillis(year: Int, month: Int): Long = Calendar.getInstance()
        .apply { set(year, month - 1, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private fun getEndOfMonthMillis(year: Int, month: Int): Long {
        val yearMonth = YearMonth.of(year, month)
        return Calendar.getInstance().apply {
            set(year, month - 1, yearMonth.lengthOfMonth(), 23, 59, 59); set(
            Calendar.MILLISECOND,
            999
        )
        }.timeInMillis
    }

    private fun getStartOfDayMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun getEndOfDayMillis(date: LocalDate): Long =
        date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
}