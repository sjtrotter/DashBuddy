package cloud.trotter.dashbuddy.state.shadow

import android.util.Log
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.test.util.RecordingTree
import org.junit.Test
import org.mockito.kotlin.mock
import timber.log.Timber

/**
 * #551 Phase 1 — Principle 7 fail-closed guard for [ShadowStoreChainLogger]. The store-chain body
 * names raw merchants (the 06-21 receipt: 9 INFO lines naming stores). The shareable INFO milestone
 * now carries only the job id + link count; the merchant-bearing body stays on the DEBUG firehose.
 * The fixture is the real 2026-06-19 Target + Maple Street stack.
 */
class ShadowStoreChainLoggerPiiLogTest {

    private fun pickup(id: String, store: String) = Task(
        taskId = id, jobId = "job-1", phase = TaskPhase.PICKUP,
        storeName = store, startedAt = 100L, completedAt = 200L,
    )

    private fun dropoff(id: String, store: String, cust: String) = Task(
        taskId = id, jobId = "job-1", phase = TaskPhase.DROPOFF, storeName = store,
        customerNameHash = cust, startedAt = 300L, completedAt = 400L,
    )

    @Test
    fun `logChain keeps merchants out of INFO+ but on the DEBUG firehose`() {
        val logger = ShadowStoreChainLogger(mock<StateManagerV2>())
        val job = Job(
            jobId = "job-1",
            offerStoreHint = listOf("Target", "Maple Street Biscuit Company"),
            parentOfferHash = null,
            startedAt = 50L,
            tasks = listOf(
                pickup("p-target", "Target"),
                pickup("p-maple", "Maple Street Biscuit Company"),
                dropoff("d-target", "Target", "cust-A"),
                dropoff("d-maple", "Maple Street Biscuit Company", "cust-B"),
            ),
        )
        val payout = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 6.40)),
            customerTips = listOf(
                ParsedPayItem("Maple Street Biscuit - Alamo Ranch", 6.50),
                ParsedPayItem("Target (02426)", 2.25),
            ),
        )

        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            logger.logChain(job, payout)

            // The shareable INFO milestone carries only the job id + link count.
            tree.assertNoInfoPlusContains("Target")
            tree.assertNoInfoPlusContains("Maple Street")
            tree.assertLevelContains(Log.INFO, "store-chain resolved (2 links)")
            // The DEBUG firehose still names the merchants.
            tree.assertLevelContains(Log.DEBUG, "Target")
            tree.assertLevelContains(Log.DEBUG, "Maple Street")
        } finally {
            Timber.uproot(tree)
        }
    }
}
