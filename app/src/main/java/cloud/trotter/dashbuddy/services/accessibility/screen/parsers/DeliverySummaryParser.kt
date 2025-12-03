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

        // 1. Find candidates
        val candidates = rootNode.findNodes { node ->
            (node.className == "androidx.recyclerview.widget.RecyclerView" || node.className == "android.widget.LinearLayout") &&
                    (node.hasNode { it.text?.equals("DoorDash pay", ignoreCase = true) == true } &&
                            node.hasNode {
                                it.text?.equals(
                                    "Customer tips",
                                    ignoreCase = true
                                ) == true
                            })
        }

        // Prioritize RecyclerView
        val payListContainer =
            candidates.find { it.className == "androidx.recyclerview.widget.RecyclerView" }
                ?: candidates.firstOrNull()

        if (payListContainer == null) {
            return ParsedPay(emptyList(), emptyList())
        }

        // 2. Flatten ALL text from the container into a single list.
        // This solves the issue where multiple items are grouped into a single "Row" view.
        val allTexts = payListContainer.findNodes { !it.text.isNullOrEmpty() }
            .map { it.text!!.trim() }

        Log.d(TAG, "Parsing linear text stream: $allTexts")

        var currentMode = PayMode.NONE
        var i = 0

        // 3. Linear Scan
        while (i < allTexts.size) {
            val text = allTexts[i]

            // A. Check for Mode Switching Headers
            if (text.equals("DoorDash pay", ignoreCase = true)) {
                currentMode = PayMode.APP_PAY
                i++
                continue
            }
            if (text.equals("Customer tips", ignoreCase = true)) {
                currentMode = PayMode.TIPS
                i++
                continue
            }

            // B. Extract Data (Label + Amount)
            // We look for a label (current text) followed immediately by an amount (next text)
            if (currentMode != PayMode.NONE) {
                // Ensure we have a "next" item to check
                if (i + 1 < allTexts.size) {
                    val nextText = allTexts[i + 1]

                    // Guard clauses to ensure 'text' is actually a label
                    val isLabelInvalid = text.startsWith("$") ||
                            text.equals("Total", ignoreCase = true) ||
                            text.equals("This offer", ignoreCase = true)

                    if (!isLabelInvalid && nextText.startsWith("$")) {
                        val amount = parseAmount(nextText)
                        if (amount != null) {
                            val item = ParsedPayItem(text, amount)
                            if (currentMode == PayMode.APP_PAY) {
                                appPayComponents.add(item)
                            } else {
                                customerTips.add(item)
                            }
                            Log.i(TAG, "Parsed ($currentMode): $text -> $amount")

                            // Successfully parsed a pair, skip both
                            i += 2
                            continue
                        }
                    }
                }
            }

            // Move to next token if no match found
            i++
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