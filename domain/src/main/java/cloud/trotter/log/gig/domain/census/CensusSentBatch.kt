package cloud.trotter.log.gig.domain.census

import cloud.trotter.census.contract.KindClassifier
import cloud.trotter.census.contract.SkeletonVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** #1622: what one sent census item was, for "What gig.log sent". */
@Serializable
enum class CensusSentKind { SCREEN, NOTIFICATION, DAILY_COUNTS, SCREEN_TEXT }

/**
 * One item of a sent census request, read from the request's own bytes ([CensusSentBatch.read]). It holds counts and
 * the delivery app's wire id only, never a hash, a format template or any other value from the item.
 *
 * Every text value of a screen or notification counts once: [codes] went as a one-way word code, [formats] as a
 * format template such as `$#.##`, [withheld] went as `withheld` because a filter held it back, and [typeOnly] went
 * as its coarse kind alone (`digits`, `mixed`, or more words than are coded). [nodes] is the screen's node count.
 */
@Serializable
data class CensusSentItem(
    val kind: CensusSentKind,
    val platform: String,
    /** The UTC day the item carries (a skeleton's or a daily count's `day`), when it has one. */
    val day: String? = null,
    val nodes: Int = 0,
    val codes: Int = 0,
    val formats: Int = 0,
    val typeOnly: Int = 0,
    val withheld: Int = 0,
)

/**
 * #1622: one census request the server read (it answered 200, 400 or 422), summarized from the exact bytes the
 * transport put on the wire. [batchId] is the request's own batch id (skeletons and envelopes), so a batch sent again
 * after a lost reply is one entry. Kept on the phone only; never uploaded.
 */
@Serializable
data class CensusSentBatch(
    val sentAtMillis: Long,
    val path: String,
    val batchId: String? = null,
    val items: List<CensusSentItem>,
) {
    companion object {
        const val SKELETONS = "/v1/skeletons"
        const val HEALTH = "/v1/health"
        const val ENVELOPES = "/v1/envelopes"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Reads a request body the census transport sent to [path]. Null for a path that carries no census data or a
         * body that is not a JSON object; an item that is not an object is skipped.
         */
        fun read(path: String, body: ByteArray, sentAtMillis: Long): CensusSentBatch? {
            val root = runCatching { json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
                ?: return null
            val batchId = (root["batchId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val items = when (path) {
                SKELETONS -> root.objects("items").map(::skeleton)
                HEALTH -> root.objects("reports").map { CensusSentItem(CensusSentKind.DAILY_COUNTS, it.string("platform"), it.stringOrNull("day")) }
                ENVELOPES -> root.objects("items").map { CensusSentItem(CensusSentKind.SCREEN_TEXT, it.string("platform")) }
                else -> return null
            }
            return CensusSentBatch(sentAtMillis, path, batchId.takeIf { path != HEALTH }, items)
        }

        private fun skeleton(item: JsonObject): CensusSentItem {
            val schemaId = item.stringOrNull("schemaId").orEmpty()
            val counter = SlotCounter()
            val notification = SkeletonVersion.ofNotificationSchema(schemaId) != null
            var nodes = 0
            if (notification) {
                (item["slots"] as? JsonObject)?.values?.forEach(counter::count)
                (item["actions"] as? JsonArray)?.forEach(counter::count)
            } else {
                item["windowTitle"]?.let(counter::count)
                // Iterative walk: a deep tree cannot overflow the stack.
                val pending = ArrayDeque<JsonObject>()
                (item["root"] as? JsonObject)?.let(pending::addLast)
                while (pending.isNotEmpty()) {
                    val node = pending.removeLast()
                    nodes++
                    (node["text"] as? JsonObject)?.values?.forEach(counter::count)
                    (node["children"] as? JsonArray)?.forEach { child -> (child as? JsonObject)?.let(pending::addLast) }
                }
            }
            return CensusSentItem(
                kind = if (notification) CensusSentKind.NOTIFICATION else CensusSentKind.SCREEN,
                platform = item.string("platform"),
                day = item.stringOrNull("day"),
                nodes = nodes,
                codes = counter.codes,
                formats = counter.formats,
                typeOnly = counter.typeOnly,
                withheld = counter.withheld,
            )
        }

        private class SlotCounter {
            var codes = 0
            var formats = 0
            var typeOnly = 0
            var withheld = 0

            fun count(slot: JsonElement) {
                val obj = slot as? JsonObject ?: return
                when {
                    obj["h"] != null -> codes++
                    obj.stringOrNull("kind") == KindClassifier.SHAPE -> formats++
                    obj.stringOrNull("kind") == KindClassifier.WITHHELD -> withheld++
                    else -> typeOnly++
                }
            }
        }

        private fun JsonObject.objects(key: String): List<JsonObject> =
            (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

        private fun JsonObject.stringOrNull(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.string(key: String): String = stringOrNull(key).orEmpty()
    }
}

/** #1622: the sent items of [batches] by kind, for a day's line on "What gig.log sent". */
fun List<CensusSentBatch>.countBy(kind: CensusSentKind): Int = sumOf { batch -> batch.items.count { it.kind == kind } }
