package cloud.trotter.dashbuddy.ui.fragments.dashhistory.common

import cloud.trotter.dashbuddy.data.dash.DashDao
import cloud.trotter.dashbuddy.data.dash.DashEntity
import cloud.trotter.dashbuddy.data.links.dashZone.DashZoneDao
import cloud.trotter.dashbuddy.data.links.dashZone.DashZoneEntity
import cloud.trotter.dashbuddy.data.offer.OfferDao
import cloud.trotter.dashbuddy.data.offer.OfferEntity
import cloud.trotter.dashbuddy.data.offer.OfferStatus
import cloud.trotter.dashbuddy.data.order.OrderDao
import cloud.trotter.dashbuddy.data.order.OrderEntity
import cloud.trotter.dashbuddy.data.pay.AppPayDao
import cloud.trotter.dashbuddy.data.pay.AppPayEntity
import cloud.trotter.dashbuddy.data.pay.TipDao
import cloud.trotter.dashbuddy.data.pay.TipEntity
import cloud.trotter.dashbuddy.data.zone.ZoneDao
import cloud.trotter.dashbuddy.data.zone.ZoneEntity
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.annual.AnnualDisplay
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.daily.DailyDisplay
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.monthly.MonthlyDisplay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * A repository responsible for querying and combining raw data from various DAOs
 * into ready-to-display summary objects for the history screens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashHistoryRepo(
    private val dashDao: DashDao,
    private val dashZoneDao: DashZoneDao,
    private val offerDao: OfferDao,
    private val orderDao: OrderDao,
    private val tipDao: TipDao,
    private val appPayDao: AppPayDao,
    private val zoneDao: ZoneDao
) {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    /**
     * Creates a reactive flow for the complete annual display model for a given year.
     */
    fun getAnnualDisplayFlow(year: Int): Flow<AnnualDisplay> {
        val startOfYearMillis = getStartOfYearMillis(year)
        val endOfYearMillis = getEndOfYearMillis(year)

        return dashDao.getDashesByDateRangeFlow(startOfYearMillis, endOfYearMillis)
            .flatMapLatest { dashesInYear ->
                if (dashesInYear.isEmpty()) {
                    return@flatMapLatest flowOf(AnnualDisplay.Companion.empty(year))
                }

                val dashIds = dashesInYear.map { it.id }
                val offersInYearFlow = offerDao.getOffersForDashesFlow(dashIds)

                offersInYearFlow.flatMapLatest { offersInYear ->
                    if (offersInYear.isEmpty()) {
                        val stats = SummaryStats(
                            totalDashes = dashesInYear.size,
                            totalHours = calculateTotalHours(dashesInYear),
                            totalMiles = dashesInYear.sumOf { it.totalDistance ?: 0.0 }
                        )
                        return@flatMapLatest flowOf(
                            AnnualDisplay.Companion.empty(year).copy(stats = stats)
                        )
                    }

                    val offerIds = offersInYear.map { it.id }
                    val uniqueZoneCountFlow = dashZoneDao.getUniqueZoneCountForDashesFlow(dashIds)
                    val ordersInYearFlow = orderDao.getOrdersForOffersFlow(offerIds)
                    val appPaysInYearFlow = appPayDao.getPayComponentsForOffersFlow(offerIds)

                    ordersInYearFlow.flatMapLatest { ordersInYear ->
                        val orderIds = ordersInYear.map { it.id }
                        val tipsInYearFlow = tipDao.getTipsForOrdersFlow(orderIds)

                        combine(
                            uniqueZoneCountFlow,
                            appPaysInYearFlow,
                            tipsInYearFlow
                        ) { uniqueZoneCount, appPaysInYear, tipsInYear ->
                            val totalEarnings =
                                appPaysInYear.sumOf { it.amount } + tipsInYear.sumOf { it.amount }
                            val monthSummaries = calculateMonthlySummaries(
                                offersInYear,
                                ordersInYear,
                                appPaysInYear,
                                tipsInYear
                            )
                            val currentStats = SummaryStats(
                                totalDashes = dashesInYear.size,
                                uniqueZoneCount = uniqueZoneCount,
                                totalHours = calculateTotalHours(dashesInYear),
                                totalMiles = dashesInYear.sumOf { it.totalDistance ?: 0.0 },
                                activeHours = calculateActiveHours(offersInYear, ordersInYear),
                                activeMiles = calculateActiveMiles(ordersInYear),
                                totalEarnings = totalEarnings
                            )
                            AnnualDisplay(
                                year = year,
                                stats = currentStats,
                                monthSummaries = monthSummaries
                            )
                        }
                    }
                }
            }
    }

    /**
     * Creates a reactive flow for the complete monthly display model for a given month and year.
     */
    fun getMonthlyDisplayFlow(year: Int, month: Int): Flow<MonthlyDisplay> {
        val startOfMonthMillis = getStartOfMonthMillis(year, month)
        val endOfMonthMillis = getEndOfMonthMillis(year, month)

        return dashDao.getDashesByDateRangeFlow(startOfMonthMillis, endOfMonthMillis)
            .flatMapLatest { dashesInMonth ->
                if (dashesInMonth.isEmpty()) {
                    return@flatMapLatest flowOf(MonthlyDisplay.Companion.empty(year, month))
                }

                val dashIds = dashesInMonth.map { it.id }
                val offersInMonthFlow = offerDao.getOffersForDashesFlow(dashIds)

                offersInMonthFlow.flatMapLatest { offersInMonth ->
                    if (offersInMonth.isEmpty()) {
                        val stats = SummaryStats(
                            totalDashes = dashesInMonth.size,
                            totalHours = calculateTotalHours(dashesInMonth),
                            totalMiles = dashesInMonth.sumOf { it.totalDistance ?: 0.0 }
                        )
                        return@flatMapLatest flowOf(
                            MonthlyDisplay.Companion.empty(year, month).copy(stats = stats)
                        )
                    }

                    val offerIds = offersInMonth.map { it.id }
                    val uniqueZoneCountFlow = dashZoneDao.getUniqueZoneCountForDashesFlow(dashIds)
                    val ordersInMonthFlow = orderDao.getOrdersForOffersFlow(offerIds)
                    val appPaysInMonthFlow = appPayDao.getPayComponentsForOffersFlow(offerIds)

                    ordersInMonthFlow.flatMapLatest { ordersInMonth ->
                        val orderIds = ordersInMonth.map { it.id }
                        val tipsInMonthFlow = tipDao.getTipsForOrdersFlow(orderIds)

                        combine(
                            uniqueZoneCountFlow,
                            appPaysInMonthFlow,
                            tipsInMonthFlow
                        ) { uniqueZoneCount, appPaysInMonth, tipsInMonth ->
                            val totalEarnings =
                                appPaysInMonth.sumOf { it.amount } + tipsInMonth.sumOf { it.amount }
                            val dailySummaries = calculateDailySummaries(
                                offersInMonth,
                                ordersInMonth,
                                appPaysInMonth,
                                tipsInMonth
                            )

                            val currentStats = SummaryStats(
                                totalDashes = dashesInMonth.size,
                                uniqueZoneCount = uniqueZoneCount,
                                totalHours = calculateTotalHours(dashesInMonth),
                                totalMiles = dashesInMonth.sumOf { it.totalDistance ?: 0.0 },
                                activeHours = calculateActiveHours(offersInMonth, ordersInMonth),
                                activeMiles = calculateActiveMiles(ordersInMonth),
                                totalEarnings = totalEarnings
                            )

                            MonthlyDisplay(
                                date = LocalDate.of(year, month, 1),
                                stats = currentStats,
                                calendarDays = dailySummaries
                            )
                        }
                    }
                }
            }
    }

    /**
     * Creates a reactive flow for the complete daily display model for a given date.
     */
    fun getDailyDisplayFlow(date: LocalDate): Flow<DailyDisplay> {
        val startOfDayMillis = getStartOfDayMillis(date)
        val endOfDayMillis = getEndOfDayMillis(date)

        return dashDao.getDashesByDateRangeFlow(startOfDayMillis, endOfDayMillis)
            .flatMapLatest { dashesInDay ->
                if (dashesInDay.isEmpty()) {
                    return@flatMapLatest flowOf(DailyDisplay.Companion.empty(date))
                }

                val dashIds = dashesInDay.map { it.id }
                val offersInDayFlow = offerDao.getOffersForDashesFlow(dashIds)

                offersInDayFlow.flatMapLatest { offersInDay ->
                    if (offersInDay.isEmpty()) {
                        val stats = SummaryStats(
                            totalDashes = dashesInDay.size,
                            totalHours = calculateTotalHours(dashesInDay),
                            totalMiles = dashesInDay.sumOf { it.totalDistance ?: 0.0 }
                        )
                        return@flatMapLatest flowOf(
                            DailyDisplay.Companion.empty(date).copy(stats = stats)
                        )
                    }

                    val offerIds = offersInDay.map { it.id }
                    val uniqueZoneCountFlow = dashZoneDao.getUniqueZoneCountForDashesFlow(dashIds)
                    val ordersInDayFlow = orderDao.getOrdersForOffersFlow(offerIds)
                    val appPaysInDayFlow = appPayDao.getPayComponentsForOffersFlow(offerIds)

                    // Fetch the zone links for the dashes
                    val zoneLinksInDayFlow = dashZoneDao.getLinksForDashesFlow(dashIds)

                    zoneLinksInDayFlow.flatMapLatest { zoneLinksInDay ->
                        val zoneIds = zoneLinksInDay.map { it.zoneId }.distinct()
                        val zonesInDayFlow = zoneDao.getZonesByIdsFlow(zoneIds)

                        ordersInDayFlow.flatMapLatest { ordersInDay ->
                            val orderIds = ordersInDay.map { it.id }
                            val tipsInDayFlow = tipDao.getTipsForOrdersFlow(orderIds)

                            combine(
                                uniqueZoneCountFlow,
                                appPaysInDayFlow,
                                tipsInDayFlow,
                                zonesInDayFlow // Add zones to the combine block
                            ) { uniqueZoneCount, appPaysInDay, tipsInDay, zonesInDay ->
                                val totalEarnings =
                                    appPaysInDay.sumOf { it.amount } + tipsInDay.sumOf { it.amount }
                                val dashSummaries = aggregateToDashSummaries(
                                    dashesInDay,
                                    offersInDay,
                                    ordersInDay,
                                    appPaysInDay,
                                    tipsInDay,
                                    zoneLinksInDay, // Pass the new data down
                                    zonesInDay      // Pass the new data down
                                )

                                val currentStats = SummaryStats(
                                    totalDashes = dashesInDay.size,
                                    uniqueZoneCount = uniqueZoneCount,
                                    totalHours = calculateTotalHours(dashesInDay),
                                    totalMiles = dashesInDay.sumOf { it.totalDistance ?: 0.0 },
                                    activeHours = calculateActiveHours(offersInDay, ordersInDay),
                                    activeMiles = calculateActiveMiles(ordersInDay),
                                    totalEarnings = totalEarnings
                                )

                                DailyDisplay(
                                    date = date,
                                    stats = currentStats,
                                    dashSummaries = dashSummaries
                                )
                            }
                        }
                    }
                }
            }
    }

    // --- PRIVATE AGGREGATION & CALCULATION HELPERS ---

    private fun aggregateToDashSummaries(
        dashes: List<DashEntity>,
        offers: List<OfferEntity>,
        orders: List<OrderEntity>,
        appPays: List<AppPayEntity>,
        tips: List<TipEntity>,
        zoneLinks: List<DashZoneEntity>, // Accept the new data
        zones: List<ZoneEntity>          // Accept the new data
    ): List<DailyDisplay.DashSummary> {
        val offersByDashId = offers.groupBy { it.dashId }
        val ordersByOfferId = orders.groupBy { it.offerId }
        val appPaysByOfferId = appPays.groupBy { it.offerId }
        val tipsByOrderId = tips.groupBy { it.orderId }

        // Create lookup maps for zone information
        val zoneLinksByDashId = zoneLinks.groupBy { it.dashId }
        val zoneNamesById = zones.associateBy({ it.id }, { it.zoneName })

        return dashes.map { dash ->
            val offersForDash = offersByDashId[dash.id] ?: emptyList()
            val dashEarnings = offersForDash.sumOf { offer ->
                (appPaysByOfferId[offer.id]?.sumOf { it.amount } ?: 0.0) +
                        (ordersByOfferId[offer.id]?.sumOf { order ->
                            tipsByOrderId[order.id]?.sumOf { it.amount } ?: 0.0
                        } ?: 0.0)
            }

            val offerSummaries = offersForDash.map { offer ->
                val ordersForOffer = ordersByOfferId[offer.id] ?: emptyList()
                val appPayForOffer = appPaysByOfferId[offer.id] ?: emptyList()
                val tipsForOffer = ordersForOffer.flatMap { tipsByOrderId[it.id] ?: emptyList() }
                val actualPay =
                    appPayForOffer.sumOf { it.amount } + tipsForOffer.sumOf { it.amount }

                val payBreakdown = appPayForOffer.map {
                    DailyDisplay.ReceiptLine(
                        "App Pay",
                        String.Companion.format(Locale.getDefault(), "$%.2f", it.amount)
                    )
                } +
                        tipsForOffer.map {
                            DailyDisplay.ReceiptLine(
                                it.type.name.replaceFirstChar { c -> c.uppercase() },
                                String.Companion.format(Locale.getDefault(), "$%.2f", it.amount)
                            )
                        }

                DailyDisplay.OfferSummary(
                    offerId = offer.id,
                    summaryLine = "${ordersForOffer.firstOrNull()?.storeName ?: "Unknown Store"}: ${offer.distanceMiles ?: 0.0} mi",
                    actualPay = actualPay,
                    status = offer.status,
                    payBreakdown = payBreakdown
                )
            }

            // Find the starting zone for this dash
            val startZoneLink = zoneLinksByDashId[dash.id]?.find { it.isStartZone }
                ?: zoneLinksByDashId[dash.id]?.firstOrNull()
            val zoneName = startZoneLink?.let { zoneNamesById[it.zoneId] } ?: "Unknown Zone"

            DailyDisplay.DashSummary(
                dashId = dash.id,
                startTime = timeFormat.format(Date(dash.startTime)),
                stopTime = dash.stopTime?.let { timeFormat.format(Date(it)) } ?: "Active",
                zoneName = zoneName, // Use the looked-up zone name
                totalEarnings = dashEarnings,
                offerSummaries = offerSummaries
            )
        }
    }

    private fun calculateMonthlySummaries(
        offers: List<OfferEntity>,
        orders: List<OrderEntity>,
        appPays: List<AppPayEntity>,
        tips: List<TipEntity>
    ): List<AnnualDisplay.MonthInYearSummary> {
        val ordersByOfferId = orders.groupBy { it.offerId }
        val appPaysByOfferId = appPays.groupBy { it.offerId }
        val tipsByOrderId = tips.groupBy { it.orderId }
        val monthlyEarnings = mutableMapOf<Int, Double>()

        for (offer in offers) {
            val calendar = Calendar.getInstance().apply { timeInMillis = offer.timestamp }
            val month = calendar.get(Calendar.MONTH) + 1
            val offerAppPay = appPaysByOfferId[offer.id]?.sumOf { it.amount } ?: 0.0
            val offerTips = ordersByOfferId[offer.id]?.sumOf { order ->
                tipsByOrderId[order.id]?.sumOf { it.amount } ?: 0.0
            } ?: 0.0
            monthlyEarnings[month] = (monthlyEarnings[month] ?: 0.0) + offerAppPay + offerTips
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

    private fun calculateDailySummaries(
        offers: List<OfferEntity>,
        orders: List<OrderEntity>,
        appPays: List<AppPayEntity>,
        tips: List<TipEntity>
    ): List<MonthlyDisplay.DayInMonthSummary> {
        val ordersByOfferId = orders.groupBy { it.offerId }
        val appPaysByOfferId = appPays.groupBy { it.offerId }
        val tipsByOrderId = tips.groupBy { it.orderId }
        val dailyEarnings = mutableMapOf<Int, Double>()

        for (offer in offers) {
            val calendar = Calendar.getInstance().apply { timeInMillis = offer.timestamp }
            val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
            val offerAppPay = appPaysByOfferId[offer.id]?.sumOf { it.amount } ?: 0.0
            val offerTips = ordersByOfferId[offer.id]?.sumOf { order ->
                tipsByOrderId[order.id]?.sumOf { it.amount } ?: 0.0
            } ?: 0.0
            dailyEarnings[dayOfMonth] = (dailyEarnings[dayOfMonth] ?: 0.0) + offerAppPay + offerTips
        }

        val year = if (offers.isNotEmpty()) Calendar.getInstance()
            .apply { timeInMillis = offers.first().timestamp }.get(Calendar.YEAR) else 0
        val month = if (offers.isNotEmpty()) Calendar.getInstance()
            .apply { timeInMillis = offers.first().timestamp }.get(Calendar.MONTH) + 1 else 0
        val daysInMonth = if (year > 0) YearMonth.of(year, month).lengthOfMonth() else 31

        return (1..daysInMonth).map { day ->
            val earnings = dailyEarnings[day] ?: 0.0
            MonthlyDisplay.DayInMonthSummary(
                dayOfMonth = day,
                totalEarnings = earnings,
                hasData = earnings > 0.0
            )
        }
    }

    private fun calculateTotalHours(dashes: List<DashEntity>): Double =
        dashes.sumOf { (it.stopTime ?: it.startTime) - it.startTime } / 3_600_000.0

    private fun calculateActiveMiles(orders: List<OrderEntity>): Double =
        orders.sumOf { it.mileage ?: 0.0 }

    private fun calculateActiveHours(offers: List<OfferEntity>, orders: List<OrderEntity>): Double {
        val ordersByOfferId = orders.groupBy { it.offerId }
        val totalActiveMillis = offers
            .filter { it.status == OfferStatus.ACCEPTED && it.acceptTime != null }
            .sumOf { offer ->
                val ordersForThisOffer = ordersByOfferId[offer.id] ?: emptyList()
                val latestCompletionTime =
                    ordersForThisOffer.mapNotNull { it.completionTimestamp }.maxOrNull()
                if (latestCompletionTime != null) (latestCompletionTime - offer.acceptTime!!) else 0L
            }
        return totalActiveMillis / 3_600_000.0
    }

    // --- PRIVATE DATE HELPERS ---
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
        val yearMonth = YearMonth.of(year, month)
        return Calendar.getInstance().apply {
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