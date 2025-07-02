package cloud.trotter.dashbuddy.data.models.dash.history

import cloud.trotter.dashbuddy.data.dash.DashEntity
import cloud.trotter.dashbuddy.data.dash.DashRepo
import cloud.trotter.dashbuddy.data.offer.OfferEntity
import cloud.trotter.dashbuddy.data.offer.OfferRepo
import cloud.trotter.dashbuddy.data.order.OrderEntity
import cloud.trotter.dashbuddy.data.order.OrderRepo
import cloud.trotter.dashbuddy.data.pay.AppPayEntity
import cloud.trotter.dashbuddy.data.pay.AppPayRepo
import cloud.trotter.dashbuddy.data.pay.AppPayType
import cloud.trotter.dashbuddy.data.pay.TipEntity
import cloud.trotter.dashbuddy.data.pay.TipRepo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.*

class DashHistoryRepository(
    private val dashRepo: DashRepo,
    private val offerRepo: OfferRepo,
    private val orderRepo: OrderRepo,
    private val appPayRepo: AppPayRepo,
    private val tipRepo: TipRepo
) {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    fun getDashHistorySummaries(): Flow<List<DaySummary>> {
        // We need 6 flows, so we must use the Array<*> version of combine
        return combine(
            dashRepo.allDashes,
            offerRepo.getAllOffers(),
            orderRepo.getAllOrders(),
            appPayRepo.allAppPays,
            tipRepo.allTips,
            appPayRepo.allAppPayTypes // Assuming this exists per our last step
        ) { flows -> // The lambda now takes a single 'flows' array

            // Manually destructure and cast each list from the array
            @Suppress("UNCHECKED_CAST")
            val dashes = flows[0] as List<DashEntity>

            @Suppress("UNCHECKED_CAST")
            val offers = flows[1] as List<OfferEntity>

            @Suppress("UNCHECKED_CAST")
            val orders = flows[2] as List<OrderEntity>

            @Suppress("UNCHECKED_CAST")
            val appPays = flows[3] as List<AppPayEntity>

            @Suppress("UNCHECKED_CAST")
            val tips = flows[4] as List<TipEntity>

            @Suppress("UNCHECKED_CAST")
            val payTypes = flows[5] as List<AppPayType>

            val offersByDashId = offers.groupBy { it.dashId }
            val ordersByOfferId = orders.groupBy { it.offerId }
            val appPaysByOfferId = appPays.groupBy { it.offerId }
            val tipsByOrderId = tips.groupBy { it.orderId }
            val payTypeMap = payTypes.associateBy { it.id }

            dashes.groupBy { dash ->
                Calendar.getInstance().apply {
                    time = Date(dash.startTime)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.time
            }.map { (date, dashesForDay) ->
                val dashSummaries = dashesForDay.map { dash ->
                    val offersForDash = offersByDashId[dash.id] ?: emptyList()

                    val offerSummaries = offersForDash.map { offer ->
                        val appPaysForOffer = appPaysByOfferId[offer.id] ?: emptyList()
                        val ordersForOffer = ordersByOfferId[offer.id] ?: emptyList()

                        val orderSummaries = ordersForOffer.map { order ->
                            val tipsForOrder = tipsByOrderId[order.id] ?: emptyList()
                            val tipLines = tipsForOrder.map {
                                ReceiptLine(
                                    it.type.name,
                                    String.format(Locale.getDefault(), "$%.2f", it.amount)
                                )
                            }
                            OrderSummary(
                                orderId = order.id,
                                summaryLine = "Order at ${order.storeName}: ${order.status}",
                                tipLines = tipLines
                            )
                        }

                        val payLines = appPaysForOffer.map {
                            ReceiptLine(
                                payTypeMap[it.payTypeId]?.name ?: "Unknown Pay",
                                String.format(Locale.getDefault(), "$%.2f", it.amount)
                            )
                        }

                        // FIX: Explicitly sum Doubles to resolve ambiguity.
                        val totalPay =
                            appPaysForOffer.sumOf { it.amount } + orderSummaries.sumOf { order ->
                                (tipsByOrderId[order.orderId] ?: emptyList()).sumOf { it.amount }
                            }

                        // FIX: Get unique store names from orders and join them.
                        val storeNames =
                            ordersForOffer.map { it.storeName }.distinct().joinToString(" & ")

                        OfferSummary(
                            offerId = offer.id,
                            summaryLine = "Offered $${
                                String.format(
                                    Locale.getDefault(), "%.2f", offer.payAmount
                                )
                            } for $storeNames: ${offer.status}",
                            doordashPayLines = payLines,
                            orders = orderSummaries,
                            totalLine = ReceiptLine(
                                "Actual Total",
                                String.format(Locale.getDefault(), "$%.2f", totalPay)
                            )
                        )
                    }

                    // FIX: Explicitly sum Doubles to resolve ambiguity.
                    val totalEarnings =
                        offerSummaries.sumOf { it.totalLine.amount.removePrefix("$").toDouble() }
                    val totalMilesForDash = offerSummaries.sumOf { o ->
                        (ordersByOfferId[o.offerId]
                            ?: emptyList()).fold(0.0) { acc, order -> acc + (order.mileage ?: 0.0) }
                    }

                    val stopTime = dash.stopTime ?: System.currentTimeMillis()

                    DashSummary(
                        dashId = dash.id,
                        earnings = totalEarnings,
                        time = "${timeFormat.format(Date(dash.startTime))} - ${
                            timeFormat.format(
                                Date(stopTime)
                            )
                        }",
                        stats = "${offerSummaries.sumOf { it.orders.size }} Orders • ${
                            String.format(
                                Locale.getDefault(),
                                "%.2f",
                                totalMilesForDash
                            )
                        } Miles",
                        offers = offerSummaries
                    )
                }

                DaySummary(
                    date = date,
                    totalEarnings = dashSummaries.sumOf { it.earnings },
                    totalTimeInMillis = dashesForDay.sumOf { dash ->
                        (dash.stopTime ?: System.currentTimeMillis()) - dash.startTime
                    },
                    totalMiles = dashSummaries.sumOf { ds ->
                        ds.offers.sumOf { o ->
                            (ordersByOfferId[o.offerId]
                                ?: emptyList()).fold(0.0) { acc, order ->
                                acc + (order.mileage ?: 0.0)
                            }
                        }
                    },
                    dashCount = dashesForDay.size,
                    orderCount = dashSummaries.sumOf { it.offers.sumOf { o -> o.orders.size } },
                    dashes = dashSummaries
                )
            }.sortedByDescending { it.date }
        }
    }
}