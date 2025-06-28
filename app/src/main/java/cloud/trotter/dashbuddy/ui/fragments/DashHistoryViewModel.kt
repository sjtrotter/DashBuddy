// in DashHistoryViewModel.kt
package cloud.trotter.dashbuddy.ui.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.data.dash.DashRepo
import cloud.trotter.dashbuddy.data.models.DashSummary
import cloud.trotter.dashbuddy.data.models.OfferDisplay
import cloud.trotter.dashbuddy.data.models.OrderDisplay
import cloud.trotter.dashbuddy.data.models.ReceiptLineItem
import cloud.trotter.dashbuddy.data.offer.OfferRepo
import cloud.trotter.dashbuddy.data.order.OrderRepo
import cloud.trotter.dashbuddy.data.pay.AppPayRepo
import cloud.trotter.dashbuddy.data.pay.TipRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

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

                        val payLines = appPays.map { appPay ->
                            val payType = appPayRepo.getPayTypeById(appPay.payTypeId)
                            ReceiptLineItem(
                                label = payType?.name ?: "Unknown Pay",
                                amount = String.format(Locale.US, "$%.2f", appPay.amount)
                            )
                        }

                        val orderDisplays = orders.map { order ->
                            val tips = tipRepo.getTipsForOrderList(order.id)
                            val tipLines = tips.map { tip ->
                                ReceiptLineItem(
                                    label = tip.type.name.replace("_", " ").lowercase(Locale.US)
                                        .replaceFirstChar { it.titlecase(Locale.US) },
                                    amount = String.format(Locale.US, "$%.2f", tip.amount)
                                )
                            }
                            OrderDisplay(
                                storeName = order.storeName,
                                status = order.status.name.lowercase(Locale.US)
                                    .replaceFirstChar { it.titlecase(Locale.US) },
                                tipLines = tipLines
                            )
                        }

                        val totalPay = appPays.sumOf { it.amount } + orders.sumOf { order ->
                            tipRepo.getTipsForOrderList(order.id).sumOf { it.amount }
                        }

                        OfferDisplay(
                            summaryText = "Offered ${
                                String.format(
                                    Locale.US,
                                    "$%.2f",
                                    offer.payAmount
                                )
                            } for ${orders.joinToString(" & ") { it.storeName }}",
                            status = offer.status.name.replace("_", " ").lowercase(Locale.US)
                                .replaceFirstChar { it.titlecase(Locale.US) },
                            payLines = payLines,
                            orders = orderDisplays,
                            total = String.format(Locale.US, "$%.2f", totalPay)
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