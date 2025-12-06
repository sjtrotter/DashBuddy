package cloud.trotter.dashbuddy.services.accessibility.screen.parsers

import cloud.trotter.dashbuddy.data.pay.ParsedPay
import cloud.trotter.dashbuddy.data.pay.ParsedPayItem
import cloud.trotter.dashbuddy.services.accessibility.UiNode
import cloud.trotter.dashbuddy.log.Logger as Log
import kotlin.math.abs
import java.util.Locale

object DeliverySummaryParser {
    private const val TAG = "DeliverySummaryParser"

    private enum class PayMode {
        NONE, APP_PAY, TIPS
    }

    fun parse(rootNode: UiNode?): ParsedPay {
        if (rootNode == null) {
            Log.w(TAG, "parse: rootNode is null")
            return ParsedPay(emptyList(), emptyList())
        }

        // 1. Flatten ALL text from the rootNode to find ALL candidate "Totals"
        val allScreenTexts = rootNode.findNodes { !it.text.isNullOrEmpty() }
            .map { it.text!!.trim() }

        val candidateTotals = parseCandidateTotals(allScreenTexts)
        Log.d(TAG, "parse: Candidate Totals found: $candidateTotals")

        val appPayComponents = mutableListOf<ParsedPayItem>()
        val customerTips = mutableListOf<ParsedPayItem>()

        // 2. Find the specific breakdown container
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

        val payListContainer =
            candidates.find { it.className == "androidx.recyclerview.widget.RecyclerView" }
                ?: candidates.firstOrNull()

        if (payListContainer == null) {
            Log.w(TAG, "parse: payListContainer not found")
            return ParsedPay(emptyList(), emptyList())
        }

        // 3. Flatten breakdown texts for parsing
        val breakdownTexts = payListContainer.findNodes { !it.text.isNullOrEmpty() }
            .map { it.text!!.trim() }

        Log.d(TAG, "Parsing linear text stream: $breakdownTexts")

        var currentMode = PayMode.NONE
        var i = 0

        while (i < breakdownTexts.size) {
            val text = breakdownTexts[i]

            // A. Check for Mode Switching Headers
            if (text.equals("DoorDash pay", ignoreCase = true)) {
                Log.d(TAG, "Mode switch: APP_PAY")
                currentMode = PayMode.APP_PAY
                i++
                continue
            }
            if (text.equals("Customer tips", ignoreCase = true)) {
                Log.d(TAG, "Mode switch: TIPS")
                currentMode = PayMode.TIPS
                i++
                continue
            }

            // B. Extract Data (Label + Amount/Status)
            if (currentMode != PayMode.NONE) {
                if (i + 1 < breakdownTexts.size) {
                    val nextText = breakdownTexts[i + 1]

                    // Guard clauses
                    val isLabelInvalid = text.startsWith("$") ||
                            text.equals("Total", ignoreCase = true) ||
                            text.equals("This offer", ignoreCase = true) ||
                            text.equals("Ineligible", ignoreCase = true)

                    if (!isLabelInvalid) {
                        // Scenario 1: Standard Price ($5.00)
                        if (nextText.startsWith("$")) {
                            val amount = parseAmount(nextText)
                            if (amount != null) {
                                addPayItem(
                                    currentMode,
                                    text,
                                    amount,
                                    appPayComponents,
                                    customerTips
                                )
                                i += 2
                                continue
                            } else {
                                Log.w(TAG, "Failed to parse amount from nextText: $nextText")
                            }
                        }
                        // Scenario 2: Ineligible Status (Treat as $0.00)
                        else if (nextText.equals("Ineligible", ignoreCase = true)) {
                            // Use addPayItem directly, but pass a flag or suffix for clarity if needed.
                            // Since addPayItem logs, we don't need a second Log statement here.
                            // We just treat it as 0.0.
                            addPayItem(
                                currentMode,
                                text,
                                0.0,
                                appPayComponents,
                                customerTips,
                                "(Ineligible)"
                            )
                            i += 2
                            continue
                        } else {
                            Log.v(
                                TAG,
                                "Skipping candidate pair: ['$text', '$nextText'] (Next text not a price or 'Ineligible')"
                            )
                        }
                    } else {
                        Log.v(TAG, "Skipping invalid label: '$text'")
                    }
                } else {
                    Log.v(TAG, "End of list reached while checking next item for: '$text'")
                }
            }
            i++
        }

        // 4. VALIDATION STAGE
        val totalPayParsed =
            (appPayComponents.sumOf { it.amount } + customerTips.sumOf { it.amount })

        if (candidateTotals.isNotEmpty()) {
            val matchFound = candidateTotals.any { displayedTotal ->
                abs(displayedTotal - totalPayParsed) <= 0.01
            }

            if (matchFound) {
                Log.i(
                    TAG,
                    "Validation Success: Parsed sum $${
                        String.format(
                            Locale.US,
                            "%.2f",
                            totalPayParsed
                        )
                    } matches a displayed total."
                )
            } else {
                Log.w(
                    TAG,
                    "Validation Failed: Parsed Sum ($$totalPayParsed) did not match any displayed totals $candidateTotals. Returning empty result."
                )
                return ParsedPay(emptyList(), emptyList())
            }
        } else {
            Log.w(
                TAG,
                "Validation Warning: Could not find 'This offer' total to validate against. Returning data as-is."
            )
        }

        return ParsedPay(appPayComponents, customerTips)
    }

    private fun parseCandidateTotals(texts: List<String>): List<Double> {
        val candidates = mutableListOf<Double>()
        val iterator = texts.iterator()

        while (iterator.hasNext()) {
            val text = iterator.next()
            if (text.equals("This offer", ignoreCase = true)) {
                while (iterator.hasNext()) {
                    val nextToken = iterator.next()
                    if (nextToken.startsWith("$")) {
                        parseAmount(nextToken)?.let { candidates.add(it) }
                    } else {
                        break
                    }
                }
                return candidates
            }
        }
        return candidates
    }

    private fun addPayItem(
        mode: PayMode,
        label: String,
        amount: Double,
        appPayList: MutableList<ParsedPayItem>,
        tipList: MutableList<ParsedPayItem>,
        suffix: String = ""
    ) {
        val item = ParsedPayItem(label, amount)
        if (mode == PayMode.APP_PAY) {
            appPayList.add(item)
        } else {
            tipList.add(item)
        }
        // Single log statement handles both standard and ineligible cases cleanly
        Log.i(TAG, "Parsed ($mode): $label -> $amount $suffix".trim())
    }

    private fun parseAmount(text: String): Double? {
        return try {
            text.replace("$", "").replace(",", "").trim().toDouble()
        } catch (_: Exception) {
            null
        }
    }
}