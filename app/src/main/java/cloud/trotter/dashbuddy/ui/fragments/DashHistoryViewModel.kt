package cloud.trotter.dashbuddy.ui.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.data.dash.DashRepo
import cloud.trotter.dashbuddy.data.models.ActualStats
import cloud.trotter.dashbuddy.data.models.DashSummary
import cloud.trotter.dashbuddy.data.models.OfferDisplay
import cloud.trotter.dashbuddy.data.models.OrderDisplay
import cloud.trotter.dashbuddy.data.models.ReceiptLineItem
import cloud.trotter.dashbuddy.data.offer.OfferRepo
import cloud.trotter.dashbuddy.data.order.OrderRepo
import cloud.trotter.dashbuddy.data.pay.AppPayRepo
import cloud.trotter.dashbuddy.data.pay.TipRepo
import cloud.trotter.dashbuddy.data.pay.TipType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

class DashHistoryViewModel(
    private val dashRepo: DashRepo,
    private val offerRepo: OfferRepo,
    private val orderRepo: OrderRepo,
    private val appPayRepo: AppPayRepo,
    private val tipRepo: TipRepo
) : ViewModel() {

    private val _dashSummaries = MutableStateFlow<List<DashSummary>>(emptyList())
    val dashSummaries: StateFlow<List<DashSummary>> = _dashSummaries.asStateFlow()

    init {
        viewModelScope.launch {
            dashRepo.allDashes.collect { dashes ->
                val summaries = dashes.map { dash ->
                    val offers = offerRepo.getOffersForDashList(dash.id)
                    val offerDisplays = offers.map { offer ->
                        val appPays = appPayRepo.getPayComponentsForOfferList(offer.id)
                        val orders = orderRepo.getOrdersForOfferList(offer.id)

                        val totalAppPay = appPays.sumOf { it.amount }
                        var totalTips = 0.0
                        val totalActualMiles = orders.sumOf { it.mileage ?: 0.0 }

                        val orderDisplays = orders.map { order ->
                            val existingTips = tipRepo.getTipsForOrderList(order.id)
                            val orderMiles = order.mileage ?: 0.0

                            // Ensure a receipt line is created for every possible tip type.
                            val allTipTypes = TipType.entries
                            val tipLines = allTipTypes.map { tipType ->
                                val existingTip = existingTips.find { it.type == tipType }
                                ReceiptLineItem(
                                    label = tipType.name.replace("_", " ").lowercase(Locale.US)
                                        .replaceFirstChar {
                                            if (it.isLowerCase()) it.titlecase(
                                                Locale.US
                                            ) else it.toString()
                                        },
                                    amount = String.format(
                                        Locale.US,
                                        "$%.2f",
                                        existingTip?.amount ?: 0.0
                                    )
                                )
                            }

                            val orderTotalTips = existingTips.sumOf { it.amount }
                            totalTips += orderTotalTips

                            val uniqueOrderIcons = order.badges.mapNotNull { it.iconResId }.toSet()

                            OrderDisplay(
                                summaryText = "${order.storeName} - ${
                                    String.format(
                                        Locale.US,
                                        "%.1f mi",
                                        orderMiles
                                    )
                                } (${
                                    order.status.name.lowercase(Locale.US).replaceFirstChar {
                                        if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
                                    }
                                })",
                                tipLines = tipLines,
                                orderBadges = uniqueOrderIcons
                            )
                        }

                        val totalActualPay = totalAppPay + totalTips
                        val offerDurationMillis =
                            (dash.stopTime ?: System.currentTimeMillis()) - dash.startTime
                        val offerDurationHours =
                            if (offerDurationMillis > 0) offerDurationMillis.toDouble() / 3_600_000.0 else 0.0

                        val actualStats = ActualStats(
                            time = formatDuration(offerDurationMillis),
                            distance = "${String.format(Locale.US, "%.1f", totalActualMiles)} mi",
                            dollarsPerMile = if (totalActualMiles > 0) String.format(
                                Locale.US,
                                "$%.2f/mi",
                                totalActualPay / totalActualMiles
                            ) else "$0.00/mi",
                            dollarsPerHour = if (offerDurationHours > 0) String.format(
                                Locale.US,
                                "$%.2f/hr",
                                totalActualPay / offerDurationHours
                            ) else "$0.00/hr"
                        )

                        val uniqueOfferIcons = offer.badges.mapNotNull { it.iconResId }.toSet()

                        OfferDisplay(
                            summaryText = "Offer: ${orders.joinToString(" & ") { it.storeName }}",
                            status = "(${
                                offer.status.name.replace("_", " ").lowercase(Locale.US)
                                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                            })",
                            totalAmount = String.format(Locale.US, "$%.2f", totalActualPay),
                            totalMiles = String.format(Locale.US, "%.1f mi", totalActualMiles),
                            offerBadges = uniqueOfferIcons,
                            payLines = appPays.map { appPay ->
                                val payType = appPayRepo.getPayTypeById(appPay.payTypeId)
                                ReceiptLineItem(
                                    label = payType?.name ?: "Unknown Pay",
                                    amount = String.format(Locale.US, "$%.2f", appPay.amount)
                                )
                            },
                            orders = orderDisplays,
                            actualStats = actualStats
                        )
                    }

                    DashSummary(
                        dashId = dash.id,
                        startTime = dash.startTime,
                        endTime = dash.stopTime,
                        totalEarned = dash.totalEarnings ?: 0.0,
                        deliveryCount = dash.deliveriesCompleted ?: 0,
                        totalMiles = dash.totalDistance ?: 0.0,
                        offerDisplays = offerDisplays
                    )
                }
                _dashSummaries.value = summaries
            }
        }
    }

    private fun formatDuration(millis: Long): String {
        if (millis < 0) return "0 hr 00 min"
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        return String.format(Locale.US, "%d hr %02d min", hours, minutes)
    }

    fun toggleDashExpanded(dashId: Long) {
        _dashSummaries.value = _dashSummaries.value.map {
            if (it.dashId == dashId) it.copy(isExpanded = !it.isExpanded) else it
        }
    }

    fun toggleOfferExpanded(dashId: Long, offerSummary: String) {
        _dashSummaries.value = _dashSummaries.value.map { dash ->
            if (dash.dashId == dashId) {
                dash.copy(offerDisplays = dash.offerDisplays.map { offer ->
                    if (offer.summaryText == offerSummary) offer.copy(isExpanded = !offer.isExpanded) else offer
                })
            } else {
                dash
            }
        }
    }
}