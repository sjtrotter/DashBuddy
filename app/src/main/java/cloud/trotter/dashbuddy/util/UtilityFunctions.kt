package cloud.trotter.dashbuddy.util

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
                    LocalTime.parse(timeText, formatter24Hour)
                } catch (e2: Exception) {
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
        } catch (e: Exception) {
            null
        }
    }

    fun generateSha256(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            hashBytes.fold("") { str, it -> str + "%02x".format(it) }
        } catch (e: Exception) {
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
}