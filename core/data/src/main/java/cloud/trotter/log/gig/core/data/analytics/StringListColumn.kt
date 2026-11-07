package cloud.trotter.log.gig.core.data.analytics

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * A JSON string-array TEXT column (#1516 `delivery_records` / `pickup_records.jobOfferHashes`), encoded like `payoutStoreForms`.
 * Null and empty both store as null; a malformed value decodes as null with a WARN that carries no value.
 */
internal object StringListColumn {
    private const val TAG = "Analytics"
    private val serializer = ListSerializer(String.serializer())
    private val json = Json

    fun encode(values: List<String>?): String? =
        values?.takeIf { it.isNotEmpty() }?.let { json.encodeToString(serializer, it) }

    fun decode(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return null
        return try {
            json.decodeFromString(serializer, raw)
        } catch (_: Exception) {
            Timber.tag(TAG).w("string-list column decode failed; treating it as absent")
            null
        }
    }
}
