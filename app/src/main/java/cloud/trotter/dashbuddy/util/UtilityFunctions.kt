package cloud.trotter.dashbuddy.util

import cloud.trotter.dashbuddy.log.Logger as Log
import java.security.MessageDigest
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

object UtilityFunctions {
    fun parseTimeTextToMillis(timeText: String): Long? {
        // timeText is now expected to be just "h:mm a" or "HH:mm"
        val formatter12Hour = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        val formatter24Hour = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

        return try {
            val deliveryLocalTime = try {
                LocalTime.parse(timeText, formatter12Hour)
            } catch (e: Exception) {
                try {
                    Log.d("UtilityFunctions.parseTimeTextToMillis", "Trying 24-hour format.", e)
                    LocalTime.parse(timeText, formatter24Hour)
                } catch (e2: Exception) {
                    Log.w(
                        "UtilityFunctions.parseTimeTextToMillis",
                        "Could not parse time text '$timeText' with either 12-hour or 24-hour formats.",
                        e2
                    )
                    return null // Could not parse with known formats
                }
            }

            val nowCal = Calendar.getInstance()
            val offerTimeCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, deliveryLocalTime.hour)
                set(Calendar.MINUTE, deliveryLocalTime.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                // Year, Month, Day are already current from Calendar.getInstance()
            }

            if (offerTimeCal.timeInMillis < nowCal.timeInMillis) {
                offerTimeCal.add(Calendar.DAY_OF_YEAR, 1)
            }
            offerTimeCal.timeInMillis
        } catch (_: Exception) {
            null
        }
    }

    fun generateSha256(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            hashBytes.fold("") { str, it -> str + "%02x".format(it) }
        } catch (_: Exception) {
            input
        }
    }

    /** Match two strings. */
    fun stringsMatch(string1: String, string2: String): Boolean {
        val normalizedString1 = normalize(string1)
        val normalizedString2 = normalize(string2)
        return normalizedString1.contains(normalizedString2)
                || normalizedString2.contains(normalizedString1)
    }

    /** Normalize string by lowering case and removing all non-alphanumeric characters. */
    private fun normalize(input: String): String {
        return input.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    /** Get a text string from a list of texts relative to an anchor. */
    fun getRelativeText(texts: List<String>, anchor: String?, offset: Int): String? {
        if (anchor == null) {
            // This case is expected when an anchor isn't found, so no log is needed here.
            return null
        }
        val anchorIndex = texts.indexOf(anchor)
        if (anchorIndex == -1) {
            // This is also expected if the anchor from a find operation isn't in the list.
            return null
        }

        val targetIndex = anchorIndex + offset
        return if (targetIndex >= 0 && targetIndex < texts.size) {
            texts[targetIndex]
        } else {
            Log.w(
                "UtilityFunctions.getRelativeText",
                "Relative text lookup failed: offset $offset from anchor '$anchor' (index $anchorIndex) is out of bounds."
            )
            null
        }
    }

    /**
     * A more generic address parser that finds the address between two known anchor texts.
     * It intelligently finds the start of the address by looking for a numeric street number.
     */
    fun parseAddressBetweenAnchors(
        texts: List<String>,
        startAnchor: String?,
        endAnchor: String
    ): String? {
        if (startAnchor == null) {
            Log.w(
                "UtilityFunctions.parseBetweenAnchors",
                "Cannot parse address without a start anchor."
            )
            return null
        }
        val startIndex = texts.lastIndexOf(startAnchor)
        val endIndex = texts.indexOf(endAnchor)

        if (startIndex == -1 || endIndex == -1 || startIndex >= endIndex) {
            Log.w(
                "UtilityFunctions.parseBetweenAnchors",
                "Address parsing failed: could not find anchors '$startAnchor' and '$endAnchor' in order."
            )
            return null
        }

        // The address content is the slice of the list between the two anchors.
        val contentSlice = texts.subList(startIndex + 1, endIndex)

        // Find the start of the address within the slice by looking for the first numeric part.
        val addressStartInSlice = contentSlice.indexOfFirst { it.matches(Regex("^\\d+.*")) }

        if (addressStartInSlice == -1) {
            Log.w(
                "UtilityFunctions.parseBetweenAnchors",
                "Could not find a numeric start to the address within the slice."
            )
            return null
        }

        // The actual address is from that numeric start to the end of the slice.
        val addressParts = contentSlice.subList(addressStartInSlice, contentSlice.size)

        return addressParts
            .joinToString(" ")
            .replace(" , ", ", ")
            .replace("Apt/Suite: ", "")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    fun formatCurrency(totalEarnings: Double): String {
        return String.format(Locale.getDefault(), "%.2f", totalEarnings)

    }

    /**
     * Parses currency strings, handling prefixes like "+" and suffixes like "+".
     * Examples: "$10.50", "+$4.00", "$7.75+ Total..."
     */
    fun parseCurrency(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        return try {
            // 1. Clean the string of symbols
            val cleanText = text.replace("$", "")
                .replace("+", "")
                .replace(",", "")
                .trim()

            // 2. Handle "7.75 Total" by taking the first token
            val numberPart = cleanText.split(" ").firstOrNull()

            numberPart?.toDoubleOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses distance strings, handling "Additional" prefix.
     * Examples: "5.5 mi", "Additional 2.6 mi", "500 ft"
     */
    fun parseDistance(text: String?): Double? {
        if (text.isNullOrBlank()) return null

        // Find the first valid number (decimal or integer)
        val numberRegex = Regex("(\\d+(?:\\.\\d+)?)")
        val numberString = numberRegex.find(text)?.value ?: return null
        val number = numberString.toDoubleOrNull() ?: return null

        // Convert feet to miles if needed
        val isFeet = text.contains("ft", true)
        return if (isFeet) number / 5280.0 else number
    }

    /**
     * Parses item counts from various formats.
     * Examples: "(2 items)", "(2 orders)", "(3 items • 4 units)"
     */
    fun parseItemCount(text: String?): Int? {
        if (text.isNullOrBlank()) return null

        // Grab the first number associated with item/order/unit
        val regex = Regex("\\((\\d+)\\s*(?:item|order|unit)", RegexOption.IGNORE_CASE)
        return regex.find(text)?.groupValues?.get(1)?.toIntOrNull()
    }
}