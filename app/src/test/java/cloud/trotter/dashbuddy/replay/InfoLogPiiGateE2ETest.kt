package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.test.util.E2ESessionReplay
import cloud.trotter.dashbuddy.test.util.InfoPlusPiiGate
import cloud.trotter.dashbuddy.test.util.RecordingTree
import cloud.trotter.dashbuddy.test.util.ReplayApplication
import cloud.trotter.dashbuddy.test.util.SessionReplay
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import timber.log.Timber

/**
 * #590 / #551 / #1271 — the **INFO+ PII-safe-by-construction regression gate**, at the end-to-end
 * boundary.
 *
 * Development Principle 7 makes INFO+ the *shareable* log stream (a user exports it as a bug
 * report), so a raw merchant/customer/address string in an INFO-or-higher line is a privacy defect
 * of the same class as leaking it to disk. This gate drives a real captured session through
 * [E2ESessionReplay] — recognition, the state machine AND the real `SideEffectEngine` with its
 * handlers, Room and the projector — with a [RecordingTree] planted, then requires (see
 * [InfoPlusPiiGate]):
 *  - INFO+ records exist for the session, and at least one was written by an effect handler after
 *    start-up (the gate is never checking an empty set);
 *  - no INFO+ line contains a raw store/address string harvested from the session's OWN parse
 *    (case-insensitive; the deny-list comes from the data, never hardcoded);
 *  - no INFO+ line trips the hardened `SensitiveTextMarkers` scan (banking markers + PAN/SSN shapes).
 *
 * It replaces the reducer-only `InfoLogPiiGateReplayTest` (#1267 found it vacuous: recognition +
 * `StateMachine` write no INFO+ line at all, so it checked an empty set) with the same two sessions
 * at a strictly stronger boundary — every INFO line the effect layer writes is now in scope.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ReplayApplication::class)
class InfoLogPiiGateE2ETest {

    @Test
    fun `no INFO-plus line from an executed single-delivery session leaks a raw store string or a sensitive marker`() {
        gate(
            "snapshots/sessions/single_delivery_2026_06_16",
            clickFiles = listOf("02_accept_offer_click.json"),
        )
    }

    /**
     * #736 — the unassign-via-help session: the abandon path's shareable stream (the TASK_UNASSIGNED
     * log, the D6 join-miss WARN, the #736 close-out WARNs) plus everything the engine writes for it.
     */
    @Test
    fun `no INFO-plus line from an executed unassign session leaks PII`() {
        gate(
            "snapshots/sessions/unassign_help_2026_07_07",
            clickFiles = listOf("01_arrived_at_store_click.json"),
        )
    }

    /** Plant a [RecordingTree], run [session] end to end, uproot after teardown, gate before it. */
    private fun gate(session: String, clickFiles: List<String>) {
        val app = RuntimeEnvironment.getApplication() as ReplayApplication
        val firstMs = SessionReplay.loadSession(session).minOf { it.capturedAtMs }
        val tree = RecordingTree()
        Timber.plant(tree)
        val replay = E2ESessionReplay(app, firstMs - 10_000L)
        try {
            replay.start()
            val bootstrap = tree.records.size
            // 200 s past the last capture: every grace the session armed is served by the engine's timer.
            replay.feedSession(session, clickFiles, tailMs = 200_000L)
            replay.drain()
            InfoPlusPiiGate.assertClean(session, replay, tree.records, bootstrap)
        } finally {
            replay.close()
            if (tree in Timber.forest()) Timber.uproot(tree)
        }
    }
}
