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

        // 1. Find all candidates that look like the pay container
        val candidates = rootNode.findNodes { node ->
            (node.className == "androidx.recyclerview.widget.RecyclerView" || node.className == "android.widget.LinearLayout") &&
                    (node.hasNode {
                        it.text?.equals("DoorDash pay", ignoreCase = true) == true
                    } && node.hasNode {
                        it.text?.equals("Customer tips", ignoreCase = true) == true
                    })
        }

        // FIX: Prioritize RecyclerView over LinearLayout to avoid selecting the wrapper
        val payListContainer =
            candidates.find { it.className == "androidx.recyclerview.widget.RecyclerView" }
                ?: candidates.firstOrNull()

        if (payListContainer == null) {
            // Only log this if we actually expected to be on the screen (reduced noise)
            Log.d(TAG, "Breakdown container not found (collapsed or missing).")
            return ParsedPay(emptyList(), emptyList())
        }

        Log.d(
            TAG,
            "Parsing breakdown container: ${payListContainer.className} with ${payListContainer.children.size} children"
        )
        var currentMode = PayMode.NONE

        // 2. Iterate through children (rows)
        for ((index, childRow) in payListContainer.children.withIndex()) {
            val textsInRow =
                childRow.findNodes { !it.text.isNullOrEmpty() }.map { it.text!!.trim() }

            if (textsInRow.isEmpty()) continue

            // LOGGING: See exactly what the parser sees for this row
            Log.d(TAG, "Row $index texts: $textsInRow")

            // A. Detect Section Headers
            if (textsInRow.any { it.equals("DoorDash pay", ignoreCase = true) }) {
                Log.d(TAG, "-> Switching mode to APP_PAY")
                currentMode = PayMode.APP_PAY
                continue
            }
            if (textsInRow.any { it.equals("Customer tips", ignoreCase = true) }) {
                Log.d(TAG, "-> Switching mode to TIPS")
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
                            !it.equals("Total", ignoreCase = true) &&
                            !it.equals(
                                "Base pay",
                                ignoreCase = true
                            ) // Optional: prevent label matching itself if logic changes
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
                        Log.i(TAG, "Parsed ($currentMode): $labelString -> $amount")
                    } else {
                        Log.w(TAG, "Failed to parse amount from: $amountString")
                    }
                } else {
                    Log.d(TAG, "Skipping row (incomplete data): $textsInRow")
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