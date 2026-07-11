package cloud.trotter.dashbuddy.replay

import android.util.Log
import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.test.util.RecordingTree
import cloud.trotter.dashbuddy.test.util.SessionReplay
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

/**
 * #590 / #551 — the **INFO+ PII-safe-by-construction regression gate**.
 *
 * Development Principle 7 makes INFO+ the *shareable* log stream (a user can export it as a bug
 * report), so a raw merchant/customer/address string in an INFO-or-higher line is a privacy defect
 * of the same class as leaking it to disk. #551 Phase 1 (PR #679) fixed the known INFO leak sites
 * (`TipEffectHandler`/`ShadowStoreChainLogger`/`SideEffectEngine`) and shipped the [RecordingTree]
 * test tree; this test is the **Phase-2 gate** that patrols the property so the future export sink
 * can trust INFO+ *by construction*, not by call-site discipline.
 *
 * It plants a [RecordingTree], drives the real single-delivery capture session end-to-end through
 * the production recognition + the REAL `StateMachine` ([SessionReplay.reduceMixed] — the same
 * fixture + wiring as [SingleDeliveryReplayTest]), then asserts that **no** recorded INFO/WARN/ERROR
 * line either:
 *  (a) contains a raw store/address string harvested from the session's OWN parsed fields
 *      (the deny-list is derived from the fixture data itself — never hardcoded), or
 *  (b) trips the hardened [SensitiveTextMarkers.findMarker] scan (banking markers + SSN/PAN shapes,
 *      post the #590 evasion hardening).
 *
 * Post-#679 the reduceMixed path (classifier + steppers + `EffectMap`) is expected to be clean, so
 * this stays green; it goes red the moment a PII-bearing INFO+ log is (re)introduced into that path.
 * Customer name/address in this fixture are already `sha256`-redacted (a hash in INFO is Principle-7
 * safe), so the meaningful deny-list is the raw merchant `storeName`/`storeAddress` values — exactly
 * the class the #679 sweep demoted to DEBUG.
 */
class InfoLogPiiGateReplayTest {

    /** Raw store/address strings the session's own parse produced — the deny-list, from the data. */
    private fun harvestStoreStrings(steps: List<SessionReplay.ReplayStep>): Set<String> {
        val out = mutableSetOf<String>()
        for (step in steps) {
            val parsed = (step.observation as? Observation.FlowObservation)?.parsed ?: continue
            when (val p = parsed) {
                is ParsedFields.TaskFields -> {
                    p.storeName?.let { out += it }
                    p.storeAddress?.let { out += it }
                }
                is ParsedFields.NotificationFields -> p.storeName?.let { out += it }
                is ParsedFields.OfferFields -> p.parsedOffer.orders.forEach { out += it.storeName }
                else -> {}
            }
        }
        // Only non-trivial tokens can meaningfully leak; drop blanks/very short values that would
        // false-match on unrelated log text (a 1-2 char store token is not a realistic leak anchor).
        return out.map { it.trim() }.filter { it.length >= 3 }.toSet()
    }

    @Test
    fun `no INFO-plus log line in a reduceMixed replay leaks a raw store string or a sensitive marker`() {
        val session = "snapshots/sessions/single_delivery_2026_06_16"
        assertNoPiiLeak(session) {
            val screens = SessionReplay.loadSession(session).map { SessionReplay.ScreenInput(it) }
            val click = SessionReplay.loadClickFrame("$session/02_accept_offer_click.json")
            SessionReplay.reduceMixed(screens + click)
        }
    }

    /**
     * #736 — the same INFO+ PII-safe gate over the unassign-via-help session, so the abandon path's
     * shareable stream (the TASK_UNASSIGNED log, the D6 join-miss WARN, the #736 close-out WARNs) is
     * mechanically proven PII-safe by construction, exactly like the single-delivery path above. Same
     * mixed inputs as [UnassignReplayTest] (screens + the arrival click + a late GRACE_COMMIT timer).
     */
    @Test
    fun `no INFO-plus log line in the unassign reduceMixed replay leaks PII`() {
        val session = "snapshots/sessions/unassign_help_2026_07_07"
        assertNoPiiLeak(session) {
            val screens = SessionReplay.loadSession(session).map { SessionReplay.ScreenInput(it) }
            val click = SessionReplay.loadClickFrame("$session/01_arrived_at_store_click.json")
            val timer = SessionReplay.graceCommit(screens.maxOf { it.atMs } + 200_000L)
            SessionReplay.reduceMixed(screens + click + timer)
        }
    }

    /**
     * Plant a [RecordingTree], drive [reduce] (the session's reduceMixed wiring), then assert no
     * INFO+ line leaks a raw store string harvested from that session's own parse, or trips the
     * hardened sensitive-marker scan.
     */
    private fun assertNoPiiLeak(session: String, reduce: () -> List<SessionReplay.ReplayStep>) {
        val recording = RecordingTree()
        Timber.plant(recording)
        val steps = try {
            reduce()
        } finally {
            Timber.uproot(recording)
        }

        // Sanity: the harness actually ran (drove the machine and produced parsed store data), so a
        // green result is a real patrol, not a vacuous pass on an empty trace.
        assertTrue("[$session] replay produced no steps — fixture/wiring broken", steps.isNotEmpty())
        val denyList = harvestStoreStrings(steps)
        assertTrue("[$session] harvested no store strings from the parse — deny-list would be vacuous", denyList.isNotEmpty())

        val infoPlus = recording.records.filter { it.priority >= Log.INFO }

        // (a) No INFO+ line carries a raw store/address value the parse itself produced.
        for (record in infoPlus) {
            val leak = denyList.firstOrNull { record.message.contains(it, ignoreCase = true) }
            assertNull(
                "[$session] INFO+ leaked raw store string '$leak': [${record.priority}/${record.tag}] ${record.message}",
                leak,
            )
        }

        // (b) No INFO+ line trips the hardened sensitive-marker scan (banking markers + PAN/SSN).
        for (record in infoPlus) {
            assertNull(
                "[$session] INFO+ line hit a sensitive marker: [${record.priority}/${record.tag}] ${record.message}",
                SensitiveTextMarkers.findMarker(record.message),
            )
        }
    }
}
