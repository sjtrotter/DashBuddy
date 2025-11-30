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

        // 1. Find the RecyclerView that contains the breakdown.
        // Strategy: Look for a RecyclerView that contains a child with text "DoorDash pay" or "Customer tips".
        val payListContainer = rootNode.findNode { node ->
            (node.className == "androidx.recyclerview.widget.RecyclerView" || node.className == "android.widget.LinearLayout") &&
                    (node.hasNode {
                        it.text?.contains(
                            "DoorDash pay",
                            ignoreCase = true
                        ) == true
                    } ||
                            node.hasNode {
                                it.text?.contains(
                                    "Customer tips",
                                    ignoreCase = true
                                ) == true
                            })
        }

        if (payListContainer == null) {
            Log.d(
                TAG,
                "Could not find pay breakdown container (RecyclerView). Details might be collapsed."
            )
            return ParsedPay(emptyList(), emptyList())
        }

        Log.d(TAG, "Found Pay Container. Parsing children...")
        var currentMode = PayMode.NONE

        // 2. Iterate through the rows (children of the RecyclerView)
        for (childRow in payListContainer.children) {
            // Flatten the text in this row to see what we are dealing with
            val textsInRow =
                childRow.findNodes { !it.text.isNullOrEmpty() }.map { it.text!!.trim() }

            if (textsInRow.isEmpty()) continue

            // A. Check for Headers to switch modes
            if (textsInRow.any { it.equals("DoorDash pay", ignoreCase = true) }) {
                currentMode = PayMode.APP_PAY
                continue
            }
            if (textsInRow.any { it.equals("Customer tips", ignoreCase = true) }) {
                currentMode = PayMode.TIPS
                continue
            }

            // B. Extract Data if we are in a valid mode
            // We expect pairs like ["Base pay", "$2.00"] or ["PF Chang's", "$7.45"]
            if (currentMode != PayMode.NONE) {
                val amountString = textsInRow.find { it.startsWith("$") }
                val labelString = textsInRow.find { !it.startsWith("$") }

                if (amountString != null && labelString != null) {
                    val amount = parseAmount(amountString)
                    if (amount != null) {
                        val item = ParsedPayItem(labelString, amount)
                        Log.d(TAG, "Parsed Item ($currentMode): $item")

                        if (currentMode == PayMode.APP_PAY) {
                            appPayComponents.add(item)
                        } else {
                            customerTips.add(item)
                        }
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