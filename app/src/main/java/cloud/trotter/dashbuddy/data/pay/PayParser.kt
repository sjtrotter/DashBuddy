package cloud.trotter.dashbuddy.data.pay

import cloud.trotter.dashbuddy.log.Logger as Log

object PayParser {

    private const val TAG = "PayParser"

    // Headers to look for to start parsing sections
    private const val APP_PAY_HEADER =
        "DoorDash Pay" // Catches "DoorDash Pay" and "DoorDash pay" with ignoreCase
    private const val CUSTOMER_TIPS_HEADER =
        "Customer Tips" // Catches "Customer Tips" and "Customer tips"

    /**
     * Parses the screen texts from a delivery completion screen using header-based logic.
     *
     * @param screenTexts The list of strings from the screen.
     * @return A ParsedPay object containing separated app pay and customer tips.
     */
    fun parsePay(screenTexts: List<String>): ParsedPay {
        Log.d(TAG, "Parsing pay breakdown using header logic from: $screenTexts")

        val appPayComponents = mutableListOf<ParsedPayItem>()
        val customerTips = mutableListOf<ParsedPayItem>()

        val appPayStartIndex =
            screenTexts.indexOfFirst { it.contains(APP_PAY_HEADER, ignoreCase = true) }
        val tipsStartIndex =
            screenTexts.indexOfFirst { it.contains(CUSTOMER_TIPS_HEADER, ignoreCase = true) }
        val totalIndex = screenTexts.indexOfFirst { it.equals("Total", ignoreCase = true) }

        // --- Parse App Pay Section ---
        if (appPayStartIndex != -1) {
            // The app pay section ends where the tips section starts, or where "Total" starts
            val endOfAppPaySection = if (tipsStartIndex != -1) tipsStartIndex else totalIndex
            if (endOfAppPaySection != -1) {
                val appPayRegion = screenTexts.subList(appPayStartIndex + 1, endOfAppPaySection)
                Log.d(TAG, "Found App Pay Region: $appPayRegion")
                appPayComponents.addAll(parseSection(appPayRegion))
            }
        }

        // --- Parse Customer Tips Section ---
        if (tipsStartIndex != -1) {
            // The tips section ends where "Total" starts
            if (totalIndex != -1) {
                val tipsRegion = screenTexts.subList(tipsStartIndex + 1, totalIndex)
                Log.d(TAG, "Found Customer Tips Region: $tipsRegion")
                customerTips.addAll(parseSection(tipsRegion))
            }
        }

        Log.i(
            TAG,
            "Finished parsing. Found ${appPayComponents.size} app pay components and ${customerTips.size} customer tips."
        )
        return ParsedPay(appPayComponents, customerTips)
    }

    /**
     * Helper function to parse a given sub-list of texts for pay line items.
     * It iterates through the list in pairs, expecting a [label, value] structure.
     * @param regionTexts The list of strings for a specific section
     * (e.g., ["Base Pay", "$2.00", "Peak Pay", "$1.00"]).
     * @return A list of ParsedPayItem found in the region.
     */
    private fun parseSection(regionTexts: List<String>): List<ParsedPayItem> {
        val components = mutableListOf<ParsedPayItem>()
        if (regionTexts.isEmpty()) {
            return components
        }

        // Iterate through the list by pairs (index 0, 2, 4, etc.)
        for (i in regionTexts.indices step 2) {
            val label = regionTexts[i].trim()
            // Safely get the next item, which should be the amount.
            val amountString = regionTexts.getOrNull(i + 1)?.trim()

            // Check if we have a valid pair: a label followed by a string that looks like a dollar amount.
            if (amountString != null && amountString.startsWith("$")) {
                try {
                    // Sanitize and parse the amount
                    val amount = amountString.removePrefix("$").trim().toDouble()

                    if (label.isNotEmpty()) {
                        Log.d(TAG, "Parsed Item Pair: '$label' -> $amount")
                        components.add(ParsedPayItem(type = label, amount = amount))
                    }
                } catch (e: NumberFormatException) {
                    Log.w(TAG, "Could not parse dollar amount from string: '$amountString'", e)
                }
            } else {
                // This can happen if there's a label without a value, or other unexpected formatting.
                Log.w(
                    TAG,
                    "Expected a dollar amount after label '$label', but found '${amountString ?: "nothing"}'. Skipping pair."
                )
            }
        }
        return components
    }
}
