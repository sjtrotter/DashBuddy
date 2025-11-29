package cloud.trotter.dashbuddy.ui.fragments.dashhistory.common

import cloud.trotter.dashbuddy.data.dash.DashDao
import cloud.trotter.dashbuddy.data.offer.OfferStatus
import cloud.trotter.dashbuddy.data.stats.*
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
    // kept for potential future use or dependency injection consistency,
    // though not strictly used in the tuple queries below
) {
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    // ============================================================================================
    // ANNUAL VIEW (Optimized with Tuples)
    // ============================================================================================
    fun getAnnualDisplayFlow(year: Int): Flow<AnnualDisplay> {
        val start = getStartOfYearMillis(year)
        val end = getEndOfYearMillis(year)

        // Combine 5 lightweight queries
        return combine(
            dashDao.getDashStats(start, end),
            dashDao.getOfferStats(start, end),
            dashDao.getOrderStats(start, end),
            dashDao.getAppPayStats(start, end),
            dashDao.getTipStats(start, end)
        ) { dashes, offers, orders, appPays, tips ->

            if (dashes.isEmpty()) return@combine AnnualDisplay.empty(year)

            // 1. Indexing for speed
            val ordersByOffer = orders.groupBy { it.offerId }
            val payByOffer = appPays.groupBy { it.offerId }
            // Map: Offer -> Order -> Tip
            // Tips are linked to OrderId, which is aliased to 'offerId' in our TipStatsTuple query
            val tipsByOrderId = tips.groupBy { it.offerId }

            // 2. Calculate Stats
            val stats =
                calculateStatsFromTuples(dashes, offers, orders, appPays, tips, ordersByOffer)

            // 3. Calculate Monthly Summaries
            val monthlyEarnings = mutableMapOf<Int, Double>()

            for (offer in offers) {
                val cal = Calendar.getInstance().apply { timeInMillis = offer.timestamp }
                val month = cal.get(Calendar.MONTH) + 1

                val pay = payByOffer[offer.id]?.sumOf { it.amount } ?: 0.0
                val offerTips = ordersByOffer[offer.id]?.sumOf { order ->
                    tipsByOrderId[order.id]?.sumOf { it.amount } ?: 0.0
                } ?: 0.0

                monthlyEarnings[month] = (monthlyEarnings[month] ?: 0.0) + pay + offerTips
            }

            val monthSummaries = (1..12).map { month ->
                val earnings = monthlyEarnings[month] ?: 0.0
                AnnualDisplay.MonthInYearSummary(month, earnings, earnings > 0)
            }

            AnnualDisplay(year, stats, monthSummaries)
        }.flowOn(Dispatchers.Default)
    }

    // ============================================================================================
    // MONTHLY VIEW (Optimized with Tuples)
    // ============================================================================================
    fun getMonthlyDisplayFlow(year: Int, month: Int): Flow<MonthlyDisplay> {
        val start = getStartOfMonthMillis(year, month)
        val end = getEndOfMonthMillis(year, month)

        return combine(
            dashDao.getDashStats(start, end),
            dashDao.getOfferStats(start, end),
            dashDao.getOrderStats(start, end),
            dashDao.getAppPayStats(start, end),
            dashDao.getTipStats(start, end)
        ) { dashes, offers, orders, appPays, tips ->

            if (dashes.isEmpty()) return@combine MonthlyDisplay.empty(year, month)

            val ordersByOffer = orders.groupBy { it.offerId }
            val payByOffer = appPays.groupBy { it.offerId }
            val tipsByOrderId = tips.groupBy { it.offerId }

            val stats =
                calculateStatsFromTuples(dashes, offers, orders, appPays, tips, ordersByOffer)

            val dailyEarnings = mutableMapOf<Int, Double>()
            for (offer in offers) {
                val cal = Calendar.getInstance().apply { timeInMillis = offer.timestamp }
                val day = cal.get(Calendar.DAY_OF_MONTH)

                val pay = payByOffer[offer.id]?.sumOf { it.amount } ?: 0.0
                val offerTips = ordersByOffer[offer.id]?.sumOf { order ->
                    tipsByOrderId[order.id]?.sumOf { it.amount } ?: 0.0
                } ?: 0.0

                dailyEarnings[day] = (dailyEarnings[day] ?: 0.0) + pay + offerTips
            }

            val daysInMonth = YearMonth.of(year, month).lengthOfMonth()
            val dailySummaries = (1..daysInMonth).map { day ->
                val earnings = dailyEarnings[day] ?: 0.0
                MonthlyDisplay.DayInMonthSummary(day, earnings, earnings > 0)
            }

            MonthlyDisplay(LocalDate.of(year, month, 1), stats, dailySummaries)
        }.flowOn(Dispatchers.Default)
    }

    // ============================================================================================
    // DAILY VIEW (Keeps using Full Objects for Details)
    // ============================================================================================
    fun getDailyDisplayFlow(date: LocalDate): Flow<DailyDisplay> {
        val start = getStartOfDayMillis(date)
        val end = getEndOfDayMillis(date)

        return combine(
            dashDao.getDashCompositesFlow(start, end),
            zoneDao.getAllZones()
        ) { composites, allZones ->

            if (composites.isEmpty()) return@combine DailyDisplay.empty(date)

            val dashSummaries = mapCompositesToDashSummaries(composites, allZones)
            val stats = calculateStatsFromComposites(composites)

            DailyDisplay(date, stats, dashSummaries)
        }
            .flowOn(Dispatchers.Default)
    }

    // --- TUPLE STATS CALCULATOR (FAST) ---

    private fun calculateStatsFromTuples(
        dashes: List<DashStatsTuple>,
        offers: List<OfferStatsTuple>,
        orders: List<OrderStatsTuple>,
        appPays: List<PayStatsTuple>,
        tips: List<PayStatsTuple>,
        ordersByOffer: Map<Long, List<OrderStatsTuple>>
    ): SummaryStats {
        val totalEarnings = appPays.sumOf { it.amount } + tips.sumOf { it.amount }
        val totalMiles = dashes.sumOf { it.totalDistance ?: 0.0 }
        val totalHours = dashes.sumOf { (it.stopTime ?: it.startTime) - it.startTime } / 3_600_000.0

        val activeMiles = orders.sumOf { it.mileage ?: 0.0 }

        val activeMillis = offers
            .filter { it.status == "ACCEPTED" && it.acceptTime != null }
            .sumOf { offer ->
                val latestCompletion = ordersByOffer[offer.id]
                    ?.mapNotNull { it.completionTimestamp }
                    ?.maxOrNull()
                if (latestCompletion != null) (latestCompletion - offer.acceptTime!!) else 0L
            }
        val activeHours = activeMillis / 3_600_000.0

        return SummaryStats(
            totalDashes = dashes.size,
            uniqueZoneCount = 0, // Optimization: Skipping zone count for Annual/Monthly to avoid joins
            totalHours = totalHours,
            totalMiles = totalMiles,
            activeHours = activeHours,
            activeMiles = activeMiles,
            totalEarnings = totalEarnings
        )
    }

    // --- COMPOSITE STATS CALCULATOR (DETAILED) ---

    private fun calculateStatsFromComposites(composites: List<cloud.trotter.dashbuddy.data.dash.DashComposite>): SummaryStats {
        val totalDashes = composites.size
        val uniqueZoneCount = composites.flatMap { it.zoneLinks }.map { it.zoneId }.distinct().size
        val totalHours = composites.sumOf {
            (it.dash.stopTime ?: it.dash.startTime) - it.dash.startTime
        } / 3_600_000.0
        val totalMiles = composites.sumOf { it.dash.totalDistance ?: 0.0 }

        val activeHours = composites.sumOf { dash ->
            dash.offers.filter { it.offer.status == OfferStatus.ACCEPTED && it.offer.acceptTime != null }
                .sumOf { offerComp ->
                    val latestCompletion =
                        offerComp.orders.mapNotNull { it.order.completionTimestamp }.maxOrNull()
                    if (latestCompletion != null) (latestCompletion - offerComp.offer.acceptTime!!) else 0L
                }
        } / 3_600_000.0

        val activeMiles = composites.sumOf { dash ->
            dash.offers.sumOf { offer -> offer.orders.sumOf { it.order.mileage ?: 0.0 } }
        }

        val totalEarnings = composites.sumOf { dash ->
            dash.offers.sumOf { offer ->
                offer.appPay.sumOf { it.amount } + offer.orders.flatMap { it.tips }
                    .sumOf { it.amount }
            }
        }

        return SummaryStats(
            totalDashes,
            uniqueZoneCount,
            totalHours,
            totalMiles,
            activeHours,
            activeMiles,
            totalEarnings
        )
    }

    private fun mapCompositesToDashSummaries(
        composites: List<cloud.trotter.dashbuddy.data.dash.DashComposite>,
        allZones: List<ZoneEntity>
    ): List<DailyDisplay.DashSummary> {
        val zoneMap = allZones.associateBy { it.id }

        return composites.map { composite ->
            val dash = composite.dash
            val dashEarnings = composite.offers.sumOf { offer ->
                offer.appPay.sumOf { it.amount } + offer.orders.flatMap { it.tips }
                    .sumOf { it.amount }
            }

            val offerSummaries = composite.offers.map { offerComp ->
                val actualPay =
                    offerComp.appPay.sumOf { it.amount } + offerComp.orders.flatMap { it.tips }
                        .sumOf { it.amount }

                val payBreakdown = offerComp.appPay.map {
                    DailyDisplay.ReceiptLine("App Pay", formatMoney(it.amount))
                } + offerComp.orders.flatMap { it.tips }.map {
                    DailyDisplay.ReceiptLine(
                        it.type.name.replaceFirstChar { c -> c.uppercase() },
                        formatMoney(it.amount)
                    )
                }

                DailyDisplay.OfferSummary(
                    offerId = offerComp.offer.id,
                    summaryLine = "${offerComp.orders.firstOrNull()?.order?.storeName ?: "Unknown"}: ${offerComp.offer.distanceMiles ?: 0.0} mi",
                    actualPay = actualPay,
                    status = offerComp.offer.status,
                    payBreakdown = payBreakdown
                )
            }

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

    private fun formatMoney(amount: Double) = String.format(Locale.getDefault(), "$%.2f", amount)

    // --- DATE HELPERS ---

    private fun getStartOfYearMillis(year: Int): Long = Calendar.getInstance().apply {
        set(year, Calendar.JANUARY, 1, 0, 0, 0); set(
        Calendar.MILLISECOND,
        0
    )
    }.timeInMillis

    private fun getEndOfYearMillis(year: Int): Long = Calendar.getInstance().apply {
        set(year, Calendar.DECEMBER, 31, 23, 59, 59); set(
        Calendar.MILLISECOND,
        999
    )
    }.timeInMillis

    private fun getStartOfMonthMillis(year: Int, month: Int): Long = Calendar.getInstance()
        .apply { set(year, month - 1, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private fun getEndOfMonthMillis(year: Int, month: Int): Long {
        val yearMonth = YearMonth.of(year, month); return Calendar.getInstance().apply {
            set(
                year,
                month - 1,
                yearMonth.lengthOfMonth(),
                23,
                59,
                59
            ); set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }

    private fun getStartOfDayMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun getEndOfDayMillis(date: LocalDate): Long =
        date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
}