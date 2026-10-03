package cloud.trotter.dashbuddy.domain.census

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Server version grammars shared by health recording and reporting. */
object HealthGrammar {
    val platformAppVersion = Regex("^[0-9]{1,5}(\\.[0-9]{1,5}){0,3}$")
    val rulesetVersion = Regex("^(corpus|dev|v?[0-9]{1,5}(\\.[0-9]{1,5}){0,3}(-[a-z0-9]{1,12})?)$")
    val appVersion = Regex("^([0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}(\\+([0-9a-f]{7,40}(\\.dirty)?|nogit))?)$")
}

/** UTC day, platform wire and observed platform app version identify one report. */
@Serializable
data class HealthKey(val day: String, val platform: String, val platformAppVersion: String) {
    override fun toString(): String = "$day|$platform|$platformAppVersion"

    companion object {
        fun parse(value: String): HealthKey {
            val parts = value.split('|')
            require(parts.size == 3)
            return HealthKey(parts[0], parts[1], parts[2])
        }
    }
}

/** Revision receipts belong to the complete row, including its complete rule map. */
@Serializable
data class HealthRow(
    val admitted: Int = 0,
    val unknown: Int = 0,
    val trips: Int = 0,
    val ruleCounts: Map<String, Int> = emptyMap(),
    val revision: Long = 0,
    val postedRevision: Long = -1,
    val refusedRevision: Long = -1,
)

/** Immutable, JSON object keyed ledger scoped to one census identity generation. */
@Serializable
data class HealthLedger(val rows: Map<String, HealthRow> = emptyMap(), val generation: Long = 0) {
    fun record(key: HealthKey, ruleId: String?): HealthLedger = update(key) { row ->
        if (ruleId == null) row.copy(unknown = increment(row.unknown), revision = row.revision + 1)
        else row.copy(
            admitted = increment(row.admitted),
            // Review receipt (#1197): saturated admitted freezes hit totals too; retain every rule key.
            ruleCounts = row.ruleCounts + (ruleId to if (row.admitted >= MAX_COUNT) (row.ruleCounts[ruleId] ?: 0)
                else increment(row.ruleCounts[ruleId] ?: 0)),
            revision = row.revision + 1,
        )
    }

    fun trip(key: HealthKey): HealthLedger = update(key) {
        it.copy(trips = increment(it.trips), revision = it.revision + 1)
    }

    fun pruneBefore(dayExclusive: String): HealthLedger = copy(
        rows = rows.filterKeys { HealthKey.parse(it).day >= dayExclusive },
    )

    fun closedRowsToPost(todayUtc: String, oldestInclusive: String): List<Pair<HealthKey, HealthRow>> = rows
        .map { (key, row) -> HealthKey.parse(key) to row }
        .filter { (key, row) -> key.day >= oldestInclusive && key.day < todayUtc &&
            row.postedRevision < row.revision && row.refusedRevision != row.revision }
        .sortedWith(compareBy<Pair<HealthKey, HealthRow>> { it.first.day }.thenBy { it.first.toString() })

    fun markPosted(key: HealthKey, revision: Long): HealthLedger = acknowledge(key, revision) {
        it.copy(postedRevision = revision)
    }

    fun markRefused(key: HealthKey, revision: Long): HealthLedger = acknowledge(key, revision) {
        it.copy(refusedRevision = revision)
    }

    fun clear(): HealthLedger = HealthLedger(generation = generation + 1)

    private fun update(key: HealthKey, transform: (HealthRow) -> HealthRow): HealthLedger {
        val row = rows[key.toString()]
        if (row == null && rows.size >= MAX_ROWS) return this
        return copy(rows = rows + (key.toString() to transform(row ?: HealthRow())))
    }

    private fun acknowledge(key: HealthKey, revision: Long, transform: (HealthRow) -> HealthRow): HealthLedger {
        val row = rows[key.toString()] ?: return copy()
        return if (row.revision == revision) update(key, transform) else copy()
    }

    companion object {
        const val MAX_ROWS = 256
        private const val MAX_COUNT = 1_000_000
        private fun increment(value: Int): Int = if (value >= MAX_COUNT) MAX_COUNT else value + 1

        fun reportJson(key: HealthKey, row: HealthRow, appVersion: String, rulesetVersion: String): String =
            Json.encodeToString(buildJsonObject {
                put("day", key.day)
                put("platform", key.platform)
                put("platformAppVersion", key.platformAppVersion)
                put("appVersion", appVersion)
                put("rulesetVersion", rulesetVersion)
                put("admitted", row.admitted)
                put("unknown", row.unknown)
                put("trips", row.trips)
                put("ruleCounts", buildJsonObject { row.ruleCounts.forEach { (rule, count) -> put(rule, count) } })
            })

        fun reportFits(json: String): Boolean = json.toByteArray(Charsets.UTF_8).size <= 8192 &&
            Json.parseToJsonElement(json).jsonObject.getValue("ruleCounts").jsonObject.size <= 512
    }
}
