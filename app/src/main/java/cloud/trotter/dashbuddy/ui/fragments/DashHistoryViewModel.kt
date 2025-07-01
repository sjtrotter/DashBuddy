package cloud.trotter.dashbuddy.ui.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.data.dash.DashEntity
import cloud.trotter.dashbuddy.data.dash.DashRepo
import cloud.trotter.dashbuddy.data.models.*
import cloud.trotter.dashbuddy.data.offer.OfferEntity
import cloud.trotter.dashbuddy.data.offer.OfferRepo
import cloud.trotter.dashbuddy.data.offer.OfferStatus
import cloud.trotter.dashbuddy.data.order.OrderEntity
import cloud.trotter.dashbuddy.data.order.OrderRepo
import cloud.trotter.dashbuddy.data.pay.AppPayEntity
import cloud.trotter.dashbuddy.data.pay.AppPayRepo
import cloud.trotter.dashbuddy.data.pay.TipEntity
import cloud.trotter.dashbuddy.data.pay.TipRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class DashHistoryViewModel(
    private val dashRepository: DashRepo,
    private val offerRepository: OfferRepo,
    private val orderRepository: OrderRepo,
    private val tipRepository: TipRepo,
    private val appPayRepository: AppPayRepo
) : ViewModel() {

    private val _historyListItems = MutableStateFlow<List<HistoryListItem>>(emptyList())
    val historyListItems = _historyListItems.asStateFlow()

    private val _expandedDayIds = MutableStateFlow<Set<Long>>(setOf())
    private val _expandedDashIds = MutableStateFlow<Set<Long>>(setOf())

    private data class AllData(
        val dashes: List<DashEntity>,
        val offers: List<OfferEntity>,
        val orders: List<OrderEntity>,
        val tips: List<TipEntity>,
        val appPays: List<AppPayEntity>
    )

    init {
        viewModelScope.launch {
            val allDataFlow = combine(
                dashRepository.allDashes,
                offerRepository.getAllOffers(),
                orderRepository.getAllOrders(),
                tipRepository.allTips,
                appPayRepository.allAppPays,

                ) { dashes, offers, orders, tips, appPays ->
                AllData(dashes, offers, orders, tips, appPays)
            }

            combine(
                allDataFlow,
                _expandedDayIds,
                _expandedDashIds
            ) { allData, expandedDays, expandedDashes ->
                buildHistoryList(allData, expandedDays, expandedDashes)
            }.collect {
                _historyListItems.value = it
            }
        }
    }

    private fun buildHistoryList(
        allData: AllData,
        expandedDayIds: Set<Long>,
        expandedDashIds: Set<Long>
    ): List<HistoryListItem> {

        if (allData.dashes.isEmpty()) return emptyList()

        val items = mutableListOf<HistoryListItem>()
        val calendar = Calendar.getInstance()

        val dashesByMonth = allData.dashes.sortedByDescending { it.startTime }
            .groupBy {
                calendar.timeInMillis = it.startTime
                calendar.get(Calendar.YEAR) * 100 + calendar.get(Calendar.MONTH)
            }

        dashesByMonth.forEach { (_, monthDashes) ->
            items.add(createMonthHeader(monthDashes.first().startTime))

            val dashesByDay = monthDashes.groupBy {
                calendar.timeInMillis = it.startTime
                calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(
                Calendar.MINUTE,
                0
            ); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis
            }

            dashesByDay.forEach { (dayTimestamp, dayDashes) ->
                val dayIsExpanded = expandedDayIds.contains(dayTimestamp)

                val dashItemsForDay = dayDashes.map { dash ->
                    val dashIsExpanded = expandedDashIds.contains(dash.id)
                    val dashOffers = allData.offers.filter { it.dashId == dash.id }
                    val offerItemsForDash = dashOffers.map { offer ->
                        val offerOrders = allData.orders.filter { it.offerId == offer.id }
                        val offerAppPays = allData.appPays.filter { it.offerId == offer.id }
                        val offerTips =
                            allData.tips.filter { t -> offerOrders.any { o -> o.id == t.orderId } }
                        createOfferItem(offer, offerOrders, offerAppPays, offerTips)
                    }
                    createDashItem(dash, dayTimestamp, dashIsExpanded, offerItemsForDash)
                }

                items.add(
                    createDaySummary(
                        dayTimestamp,
                        dayDashes,
                        allData,
                        dayIsExpanded,
                        dashItemsForDay
                    )
                )
            }
        }
        return items
    }

    private fun createMonthHeader(timestamp: Long) = MonthHeaderItem(
        monthYear = SimpleDateFormat("MMMM yyyy", Locale.US).format(Date(timestamp))
            .uppercase(Locale.US),
        timestamp = timestamp
    )

    private fun createDaySummary(
        dayTimestamp: Long,
        dayDashes: List<DashEntity>,
        allData: AllData,
        isExpanded: Boolean,
        dashItems: List<DashItem>
    ): DaySummaryItem {
        val calendar = Calendar.getInstance().apply { timeInMillis = dayTimestamp }
        var dayEarnings = 0.0
        var dayMiles = 0.0
        val dayDuration = dayDashes.sumOf { (it.stopTime ?: it.startTime) - it.startTime }

        dayDashes.forEach { dash ->
            allData.offers.filter { it.dashId == dash.id && it.status != OfferStatus.DECLINED_USER }
                .forEach { offer ->
                    val offerOrders = allData.orders.filter { it.offerId == offer.id }
                    dayMiles += offerOrders.sumOf { it.mileage ?: 0.0 }
                    dayEarnings += allData.appPays.filter { it.offerId == offer.id }
                        .sumOf { it.amount }
                    dayEarnings += allData.tips.filter { t -> offerOrders.any { o -> o.id == t.orderId } }
                        .sumOf { it.amount }
                }
        }

        val dayHours = if (dayDuration > 0) dayDuration.toDouble() / 3_600_000.0 else 0.0
        val dollarsPerHour = if (dayHours > 0) dayEarnings / dayHours else 0.0
        val dollarsPerMile = if (dayMiles > 0) dayEarnings / dayMiles else 0.0

        // FIX: Create two separate stat lines
        val statsLine1 =
            "${formatDurationHours(dayDuration)} | ${String.format(Locale.US, "%.1f mi", dayMiles)}"
        val statsLine2 = "${String.format(Locale.US, "$%.2f/hr", dollarsPerHour)} | ${
            String.format(
                Locale.US,
                "$%.2f/mi",
                dollarsPerMile
            )
        }"

        return DaySummaryItem(
            dateTimestamp = dayTimestamp,
            dayOfMonth = SimpleDateFormat("d", Locale.US).format(calendar.time),
            dayOfWeek = SimpleDateFormat("EEE", Locale.US).format(calendar.time).uppercase(),
            totalEarnings = String.format(Locale.US, "$%.2f", dayEarnings),
            statsLine1 = statsLine1, // Pass line 1
            statsLine2 = statsLine2, // Pass line 2
            isExpanded = isExpanded,
            dashes = dashItems
        )
    }

    private fun createDashItem(
        dash: DashEntity,
        dayId: Long,
        isExpanded: Boolean,
        offers: List<OfferItem>
    ): DashItem {
        val sdf = SimpleDateFormat("h:mm a", Locale.US)
        val duration = (dash.stopTime ?: dash.startTime) - dash.startTime
        return DashItem(
            dashId = dash.id, dayId = dayId,
            timeRange = "${sdf.format(Date(dash.startTime))} - ${
                dash.stopTime?.let {
                    sdf.format(
                        Date(it)
                    )
                } ?: "..."
            }",
            duration = "• ${formatDurationMinutes(duration)}",
            isExpanded = isExpanded, offers = offers
        )
    }

    private fun createOfferItem(
        offer: OfferEntity,
        orders: List<OrderEntity>,
        appPays: List<AppPayEntity>,
        tips: List<TipEntity>
    ): OfferItem {
        val aggregatedBadges =
            (offer.badges.mapNotNull { it.iconResId } + orders.flatMap { it.badges.mapNotNull { b -> b.iconResId } }).toSet()
        val totalMiles = orders.sumOf { it.mileage ?: 0.0 }
        val totalPay = appPays.sumOf { it.amount } + tips.sumOf { it.amount }
        return OfferItem(
            offerId = offer.id, dashId = offer.dashId, status = offer.status,
            summaryText = "Offer: ${orders.joinToString(" & ") { it.storeName }}",
            payAndMiles = "${String.format(Locale.US, "%.1f mi", totalMiles)} • ${
                String.format(
                    Locale.US,
                    "$%.2f",
                    totalPay
                )
            }",
            aggregatedBadges = aggregatedBadges
        )
    }

    fun toggleDayExpansion(dayId: Long) {
        val current = _expandedDayIds.value
        _expandedDayIds.value = if (current.contains(dayId)) current - dayId else current + dayId
    }

    fun toggleDashExpansion(dashId: Long) {
        val current = _expandedDashIds.value
        _expandedDashIds.value =
            if (current.contains(dashId)) current - dashId else current + dashId
    }

    private fun formatDurationMinutes(millis: Long) =
        if (millis < 0) "0 min" else "${TimeUnit.MILLISECONDS.toMinutes(millis)} min"

    private fun formatDurationHours(millis: Long) = if (millis < 0) "0.0 hrs" else String.format(
        Locale.US,
        "%.1f hrs",
        millis.toDouble() / 3_600_000.0
    )
}