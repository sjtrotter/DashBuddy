package cloud.trotter.dashbuddy.data.pay

import cloud.trotter.dashbuddy.log.Logger as Log

object PayParser {

    private const val TAG = "PayParser"

    // Regex to capture a line item that ends with a dollar amount.
    // Group 1: The label (e.g., "Base pay", "Richie’s Hot Chicken")
    // Group 2: The dollar amount (e.g., "2.00")
    private val PAY_LINE_ITEM_REGEX = Regex("""^(.*?)\s*\$\s*(\d+\.\d{2})$""")

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
     * @param regionTexts The list of strings for a specific section (e.g., only app pay lines).
     * @return A list of ParsedPayComponent found in the region.
     */
    private fun parseSection(regionTexts: List<String>): List<ParsedPayItem> {
        val components = mutableListOf<ParsedPayItem>()
        regionTexts.forEach { textLine ->
            val cleanedLine = textLine.trim()
            val matchResult = PAY_LINE_ITEM_REGEX.find(cleanedLine)

            if (matchResult != null) {
                try {
                    val payTypeOrStore = matchResult.groupValues[1].trim()
                        .removeSuffix("...") // Clean up trailing characters
                        .trim()
                    val payAmount = matchResult.groupValues[2].toDouble()

                    if (payTypeOrStore.isNotEmpty()) {
                        components.add(ParsedPayItem(type = payTypeOrStore, amount = payAmount))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not fully parse line: '$cleanedLine'", e)
                }
            }
        }
        return components
    }
}
