package cloud.trotter.dashbuddy.domain.census

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthLedgerTest {
    private val key = HealthKey("2026-10-01", "doordash", "7.1.0")

    @Test fun `HealthGrammar pins server version grammars`() {
        for (version in listOf("8.99.20", "4.599.10004")) {
            assertTrue(HealthGrammar.platformAppVersion.matches(version))
        }
        for (version in listOf("8.97.8|prod", "unknown", "")) {
            assertFalse(HealthGrammar.platformAppVersion.matches(version))
        }
        for (version in listOf("dev", "corpus", "v1.2.3-rc1")) {
            assertTrue(HealthGrammar.rulesetVersion.matches(version))
        }
        for (version in listOf("0.230.0+a41f0965", "1.2.3+nogit")) {
            assertTrue(HealthGrammar.appVersion.matches(version))
        }
        assertFalse(HealthGrammar.appVersion.matches("0.230.0+zz"))
    }

    @Test fun `record unknown and trip are immutable and maintain complete rule counts`() {
        val empty = HealthLedger()
        val ledger = empty.record(key, "doordash.screen.offer").record(key, null)
            .record(key, "doordash.screen.offer").record(key, "doordash.screen.home").trip(key)
        assertTrue(empty.rows.isEmpty())
        assertEquals(HealthRow(3, 1, 1, mapOf("doordash.screen.offer" to 2, "doordash.screen.home" to 1), 5), ledger.rows[key.toString()])
        assertEquals(key, HealthKey.parse(key.toString()))
    }

    @Test fun `all counters saturate and rule totals never exceed saturated admitted`() {
        val row = HealthRow(999_999, 999_999, 999_999, mapOf("doordash.screen.offer" to 999_999))
        var ledger = HealthLedger(mapOf(key.toString() to row))
        repeat(3) { ledger = ledger.record(key, "doordash.screen.offer").record(key, null).trip(key) }
        ledger = ledger.record(key, "doordash.screen.new_rule")
        val saturated = ledger.rows.getValue(key.toString())
        assertEquals(1_000_000, saturated.admitted)
        assertEquals(1_000_000, saturated.unknown)
        assertEquals(1_000_000, saturated.trips)
        assertEquals(mapOf("doordash.screen.offer" to 1_000_000, "doordash.screen.new_rule" to 0), saturated.ruleCounts)
        assertEquals(10L, saturated.revision)
    }

    @Test fun `closed candidates exclude today old and acknowledged revisions and sort deterministically`() {
        val today = key.copy(day = "2026-10-03")
        val old = key.copy(day = "2026-09-25")
        val second = key.copy(day = "2026-10-02")
        val sameDay = key.copy(platform = "uber")
        val ledger = HealthLedger().record(second, null).record(sameDay, null).record(today, null)
            .record(old, null).record(key, null)
        assertEquals(listOf(key, sameDay, second), ledger.closedRowsToPost(today.day, "2026-09-26").map { it.first })
        val posted = ledger.markPosted(key, 1).markRefused(second, 1)
        assertEquals(listOf(sameDay), posted.closedRowsToPost(today.day, "2026-09-26").map { it.first })
        val changed = posted.record(key, null).record(second, null)
        assertEquals(listOf(key, sameDay, second), changed.closedRowsToPost(today.day, "2026-09-26").map { it.first })
    }

    @Test fun `stale acknowledgements never hide new frames`() {
        val ledger = HealthLedger().record(key, null).record(key, null)
        assertEquals(ledger, ledger.markPosted(key, 1))
        assertEquals(ledger, ledger.markRefused(key, 1))
        assertEquals(2L, ledger.markPosted(key, 2).rows.getValue(key.toString()).postedRevision)
    }

    @Test fun `prune keeps its boundary and clear advances generation`() {
        val ledger = HealthLedger(generation = 4).record(key, null).record(key.copy(day = "2026-09-30"), null)
        assertEquals(setOf(key.toString()), ledger.pruneBefore(key.day).rows.keys)
        assertEquals(HealthLedger(generation = 5), ledger.clear())
    }

    @Test fun `257th distinct key is refused while existing keys keep recording`() {
        var ledger = HealthLedger()
        repeat(HealthLedger.MAX_ROWS) { ledger = ledger.record(key.copy(platformAppVersion = "$it"), null) }
        val newKey = key.copy(platformAppVersion = "256")
        assertEquals(256, ledger.rows.size)
        assertEquals(ledger, ledger.record(newKey, "doordash.screen.offer"))
        assertEquals(ledger, ledger.trip(newKey))

        val existing = key.copy(platformAppVersion = "0")
        val updated = ledger.record(existing, null).record(existing, "doordash.screen.offer").trip(existing)
        assertEquals(256, updated.rows.size)
        assertEquals(HealthRow(1, 2, 1, mapOf("doordash.screen.offer" to 1), 4), updated.rows[existing.toString()])
    }

    @Test fun `wire JSON has only nine ordered keys and no receipt fields`() {
        val row = HealthLedger().record(key, "doordash.screen.offer").record(key, null).trip(key).rows.getValue(key.toString())
        val raw = HealthLedger.reportJson(key, row, "1.2.3+abcdef0.dirty", "dev")
        assertEquals("""{"day":"2026-10-01","platform":"doordash","platformAppVersion":"7.1.0","appVersion":"1.2.3+abcdef0.dirty","rulesetVersion":"dev","admitted":1,"unknown":1,"trips":1,"ruleCounts":{"doordash.screen.offer":1}}""", raw)
        val json = Json.parseToJsonElement(raw).jsonObject
        assertEquals(listOf("day", "platform", "platformAppVersion", "appVersion", "rulesetVersion", "admitted", "unknown", "trips", "ruleCounts"), json.keys.toList())
        assertTrue(json.getValue("ruleCounts").jsonObject.values.sumOf { it.jsonPrimitive.int } <= json.getValue("admitted").jsonPrimitive.int)
        assertTrue(HealthLedger.reportFits(raw))
    }

    @Test fun `reports refuse too many rules or too many UTF8 bytes without truncation`() {
        val many = HealthRow(admitted = 600, ruleCounts = (1..600).associate { "a.r$it" to 1 })
        val raw = HealthLedger.reportJson(key, many, "1.2.3", "dev")
        assertTrue(raw.toByteArray().size < 8192)
        assertFalse(HealthLedger.reportFits(raw))
        val big = HealthLedger.reportJson(key, HealthRow(1, ruleCounts = mapOf("a.${"x".repeat(8500)}" to 1)), "1.2.3", "dev")
        assertFalse(HealthLedger.reportFits(big))
        val unicode = HealthLedger.reportJson(key, HealthRow(1, ruleCounts = mapOf("a.${"é".repeat(4100)}" to 1)), "1.2.3", "dev")
        assertTrue(unicode.length < 8192)
        assertFalse(HealthLedger.reportFits(unicode))
    }

    @Test fun `ledger including receipts and generation round trips as a JSON object`() {
        val ledger = HealthLedger(generation = 3).record(key, null).markPosted(key, 1).trip(key).markRefused(key, 2)
        val json = Json.encodeToString(ledger)
        assertTrue(Json.parseToJsonElement(json).jsonObject.getValue("rows").jsonObject.containsKey(key.toString()))
        assertEquals(ledger, Json.decodeFromString<HealthLedger>(json))
    }
}
