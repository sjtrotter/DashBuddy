package cloud.trotter.dashbuddy.services.accessibility.screen.parsers

import cloud.trotter.dashbuddy.data.pay.ParsedPay
import cloud.trotter.dashbuddy.data.pay.ParsedPayItem
import cloud.trotter.dashbuddy.services.accessibility.UiNode
import cloud.trotter.dashbuddy.log.Logger as Log

object DeliverySummaryParser {
    private const val TAG = "DeliverySummaryParser"

    private enum class PayMode {
        NONE, APP_PAY, TIPS
    }

    fun parse(rootNode: UiNode?): ParsedPay {
        if (rootNode == null) return ParsedPay(emptyList(), emptyList())

        val appPayComponents = mutableListOf<ParsedPayItem>()
        val customerTips = mutableListOf<ParsedPayItem>()

        // 1. Find the breakdown container (RecyclerView)
        val payListContainer = rootNode.findNode { node ->
            (node.className == "androidx.recyclerview.widget.RecyclerView" || node.className == "android.widget.LinearLayout") &&
                    (node.hasNode {
                        it.text?.equals(
                            "DoorDash pay",
                            ignoreCase = true
                        ) == true
                    } && node.hasNode {
                        it.text?.equals(
                            "Customer tips",
                            ignoreCase = true
                        ) == true
                    })
        }

        if (payListContainer == null) {
            Log.d(TAG, "Breakdown container not found (collapsed or missing).")
            return ParsedPay(emptyList(), emptyList())
        }

        Log.d(TAG, "Parsing breakdown container...")
        var currentMode = PayMode.NONE

        // 2. Iterate through children (rows)
        for (childRow in payListContainer.children) {
            // CRITICAL FIX: Flatten the entire subtree of this row to find ALL text nodes.
            // The previous version only looked at direct children, missing nested TextViews.
            val textsInRow =
                childRow.findNodes { !it.text.isNullOrEmpty() }.map { it.text!!.trim() }

            if (textsInRow.isEmpty()) continue

            // A. Detect Section Headers
            if (textsInRow.any { it.equals("DoorDash pay", ignoreCase = true) }) {
                currentMode = PayMode.APP_PAY
                continue
            }
            if (textsInRow.any { it.equals("Customer tips", ignoreCase = true) }) {
                currentMode = PayMode.TIPS
                continue
            }

            // B. Extract Data (Label + Amount)
            if (currentMode != PayMode.NONE) {
                // Heuristic: Look for a '$' amount and a non-'$' label.
                val amountString = textsInRow.find { it.startsWith("$") }

                // The label is typically the FIRST text that isn't the amount.
                val labelString = textsInRow.find {
                    !it.startsWith("$") &&
                            !it.equals("DoorDash pay", ignoreCase = true) &&
                            !it.equals("Customer tips", ignoreCase = true) &&
                            !it.equals("Total", ignoreCase = true)
                }

                if (amountString != null && labelString != null) {
                    val amount = parseAmount(amountString)
                    if (amount != null) {
                        val item = ParsedPayItem(labelString, amount)
                        if (currentMode == PayMode.APP_PAY) {
                            appPayComponents.add(item)
                        } else {
                            customerTips.add(item)
                        }
                        Log.d(TAG, "Parsed ($currentMode): $labelString -> $amount")
                    }
                }
            }
        }

        return ParsedPay(appPayComponents, customerTips)
    }

    private fun parseAmount(text: String): Double? {
        return try {
            text.replace("$", "").replace(",", "").trim().toDouble()
        } catch (_: Exception) {
            null
        }
    }
}