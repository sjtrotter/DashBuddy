package cloud.trotter.dashbuddy.data.store

import cloud.trotter.dashbuddy.log.Logger as Log

object StoreParser {

    private val tag = this::class.simpleName ?: "cloud.trotter.dashbuddy.data.store.StoreParser"

    fun parseStoreDetails(screenTexts: List<String>): ParsedStore? {
        try {
            // Find the index of key markers on the screen that frame our data.
            val pickupFromIndex = screenTexts.indexOf("Pickup from")
            val directionsIndex = screenTexts.indexOf("Directions")

            // If these essential markers are missing, we cannot reliably parse the screen.
            if (pickupFromIndex == -1 || directionsIndex == -1) {
                Log.w(tag, "Missing 'Pickup from' or 'Directions' marker. Cannot parse.")
                return null
            }

            // --- 1. Extract Store Name ---
            // The store name is the element immediately following "Pickup from".
            // This remains the most reliable way to get the primary name.
            val storeName = screenTexts.getOrNull(pickupFromIndex + 1)?.trim()
            if (storeName.isNullOrBlank()) {
                Log.w(tag, "Store name appears to be null or blank.")
                return null
            }

            // --- 2. Extract Address ---
            // NEW STRATEGY: Find the start of the address by looking for the first
            // text block after the store name that starts with a number.
            val searchRange = (pickupFromIndex + 1) until directionsIndex
            val addressStartIndex = searchRange.firstOrNull { index ->
                screenTexts.getOrNull(index)?.matches(Regex("^\\d+.*")) == true
            }

            if (addressStartIndex == null) {
                Log.w(tag, "Could not find a numeric start to the address for store: $storeName")
                return null
            }

            // The address is all the text from its detected start up to the "Directions" marker.
            val addressParts = screenTexts.subList(addressStartIndex, directionsIndex)

            // Join the parts, cleaning them up for a consistent format.
            val address = addressParts
                .joinToString(", ") // Join with ", " for better readability
                .replace(" , ", ", ") // Clean up potential spacing artifacts
                .replace("Apt/Suite: ", "") // Remove redundant labels
                .trim()

            if (address.isBlank()) {
                Log.w(tag, "Parsed address is blank for store: $storeName")
                return null
            }

            // Sanitize the store name to remove common suffixes that might vary
//            val sanitizedStoreName = storeName.replace(Regex("\\(.*?\\)"), "").trim()

            return ParsedStore(
                storeName = storeName,
                address = address
            )

        } catch (e: Exception) {
            Log.e(tag, "!!! CRITICAL error during store parsing !!!", e)
            return null
        }
    }
}