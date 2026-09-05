package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.pipeline.ParseShortfall
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

/** #430 — gate/restart counters and the summary line. */
class PipelineStatsTest {

    /**
     * #1062 — the build id rides the periodic summary, so a field-data pull reads WHICH build
     * produced the frames off any log line instead of inferring it from absent lines.
     */
    @Test
    fun `the summary leads with our own build id when one was injected`() {
        val summary = PipelineStats(appVersionName = "0.230.0+ab12cd34").summary()

        assertTrue("build id present", summary.contains("app=0.230.0+ab12cd34"))
        assertTrue("build id leads the line", summary.startsWith("app=0.230.0+ab12cd34 "))
        assertTrue("the counters still follow", summary.contains("forwarded=0"))
    }

    /** A bare `app=` would be worse than silence — the #937 `platformApps=` shape (review R8). */
    @Test
    fun `an absent build id renders nothing rather than a bare key`() {
        val summary = PipelineStats().summary()

        assertFalse(summary.contains("app="))
        assertTrue(summary.startsWith("forwarded=0"))
    }

    @Test
    fun `content gate drops are split by sensitive vs noise`() {
        val stats = PipelineStats()
        stats.onContentGateDrop(ParsedFields.SensitiveFields())
        stats.onContentGateDrop(ParsedFields.SensitiveFields())
        stats.onContentGateDrop(ParsedFields.NoiseFields())

        assertEquals(2L, stats.droppedSensitiveCount)
        assertEquals(1L, stats.droppedNoiseCount)
    }

    @Test
    fun `counters increment independently and appear in the summary`() {
        val stats = PipelineStats()
        stats.onDisabledPlatformDrop()
        stats.onDuplicateSuppressed()
        stats.onDuplicateSuppressed()
        stats.onUnknownDropped()
        stats.onMappingFailure()
        assertEquals(1L, stats.onPipelineRestart())
        assertEquals(2L, stats.onPipelineRestart())
        stats.onForwarded()

        assertEquals(1L, stats.droppedDisabledPlatformCount)
        assertEquals(2L, stats.suppressedDuplicateCount)
        assertEquals(1L, stats.droppedUnknownCount)
        assertEquals(1L, stats.mappingFailureCount)
        assertEquals(2L, stats.restartCount)
        assertEquals(1L, stats.forwardedCount)

        val summary = stats.summary()
        assertTrue(summary.contains("forwarded=1"))
        assertTrue(summary.contains("dupSuppressed=2"))
        assertTrue(summary.contains("restarts=2"))
        assertTrue(summary.contains("mappingFailures=1"))
    }

    /** #731 — quantify the field-observed notification-listener rebind (129-240x/day). */
    @Test
    fun `notif listener connect and disconnect counters track independently and appear in the summary`() {
        val stats = PipelineStats()

        assertEquals(1L, stats.onNotifListenerConnected())
        assertEquals(2L, stats.onNotifListenerConnected())
        assertEquals(1L, stats.onNotifListenerDisconnected())

        assertEquals(2L, stats.notifListenerConnectCount)
        assertEquals(1L, stats.notifListenerDisconnectCount)

        val summary = stats.summary()
        assertTrue(summary.contains("notifListenerConnects=2"))
        assertTrue(summary.contains("notifListenerDisconnects=1"))
    }

    /** #1036 — per-rule "matched but parsed nothing", rendered on the same summary line. */
    @Test
    fun `parse shortfalls count per rule and ride the summary loudest-first`() {
        val stats = PipelineStats()

        assertEquals(1L, stats.onParseShortfall(allNull("doordash.screen.waiting_for_offer")))
        assertEquals(1L, stats.onParseShortfall(allNull("doordash.screen.delivery_summary_expanded")))
        assertEquals(2L, stats.onParseShortfall(allNull("doordash.screen.delivery_summary_expanded")))

        assertEquals(2L, stats.parseShortfallCount("doordash.screen.delivery_summary_expanded"))
        assertEquals(1L, stats.parseShortfallCount("doordash.screen.waiting_for_offer"))
        assertEquals(
            "a rule that never tripped reads zero",
            0L,
            stats.parseShortfallCount("doordash.screen.offer_popup"),
        )

        assertTrue(
            stats.summary().contains(
                "parseShortfall{doordash.screen.delivery_summary_expanded=2," +
                    "doordash.screen.waiting_for_offer=1}",
            ),
        )
    }

    /** A healthy process says nothing — the suffix is absent, not an empty pair of braces. */
    @Test
    fun `parse shortfall is absent from the summary until something trips`() {
        assertFalse(PipelineStats().summary().contains("parseShortfall"))
    }

    /**
     * #1036 review R4 — this line is written every [PipelineStats.SUMMARY_EVERY] observations for
     * the life of the process and lands in the exported bug report, and benign optional-field
     * rules trip from the first minutes of a dash. An uncapped list would grow on every line.
     */
    @Test
    fun `the summary caps the rendered rules and states how many it omitted`() {
        val stats = PipelineStats()
        // 10 rules, ascending counts, so the cap has something to order by.
        repeat(10) { i ->
            repeat(i + 1) { stats.onParseShortfall(allNull("doordash.screen.rule_$i")) }
        }

        val suffix = stats.summary().substringAfter("parseShortfall{").substringBefore("}")

        assertEquals(
            "the loudest 8 are rendered",
            PipelineStats.PARSE_SHORTFALL_RENDER_LIMIT,
            suffix.split(",").count { it.contains("=") },
        )
        assertTrue("the loudest rule leads", suffix.startsWith("doordash.screen.rule_9=10,"))
        assertTrue("the omission is stated, not silent", suffix.endsWith(",+2 more"))
        assertFalse("the quietest rules are the ones dropped", suffix.contains("rule_0="))
    }

    @Test
    fun `a pathological rule id is clamped rather than owning the line`() {
        val stats = PipelineStats()
        val longId = "doordash.screen." + "x".repeat(400)
        stats.onParseShortfall(allNull(longId))

        val rendered = stats.summary().substringAfter("parseShortfall{").substringBefore("=")
        assertEquals(PipelineStats.MAX_RENDERED_RULE_ID + 1, rendered.length) // + the ellipsis
        assertTrue(rendered.endsWith("…"))
    }

    /** The WARN is once per rule per process; the counter keeps taking every occurrence. */
    @Test
    fun `the shortfall WARN is edge-gated per rule while the count keeps rising`() {
        val recorder = Recorder()
        Timber.plant(recorder)
        try {
            val stats = PipelineStats()
            repeat(3) { stats.onParseShortfall(allNull("doordash.screen.delivery_summary_expanded")) }
            stats.onParseShortfall(
                ParseShortfall("doordash.screen.receipt", nullRequiredFields = listOf("totalPay")),
            )

            val warns = recorder.messages.filter { it.contains("parse shortfall") }
            assertEquals("one line per rule, not per frame", 2, warns.size)
            assertTrue(
                "the total-rot wording says extractable and agrees at n>1",
                warns[0].contains("all 2 extractable fields unresolved"),
            )
            assertTrue(
                "the partial-rot wording names the required field",
                warns[1].contains("required field null: totalPay"),
            )
            assertEquals(3L, stats.parseShortfallCount("doordash.screen.delivery_summary_expanded"))
        } finally {
            Timber.uproot(recorder)
        }
    }

    @Test
    fun `a single unresolved field reads in the singular`() {
        val recorder = Recorder()
        Timber.plant(recorder)
        try {
            PipelineStats().onParseShortfall(
                ParseShortfall("doordash.screen.idle_map", allNullFieldCount = 1),
            )
            assertTrue(
                recorder.messages.single { it.contains("parse shortfall") }
                    .contains("all 1 extractable field unresolved"),
            )
        } finally {
            Timber.uproot(recorder)
        }
    }

    private fun allNull(ruleId: String) = ParseShortfall(ruleId, allNullFieldCount = 2)

    private class Recorder : Timber.Tree() {
        val messages = mutableListOf<String>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages += message
        }
    }
}
