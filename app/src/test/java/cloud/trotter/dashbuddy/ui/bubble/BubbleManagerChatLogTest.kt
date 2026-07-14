package cloud.trotter.dashbuddy.ui.bubble

import android.app.NotificationManager
import android.util.Log
import cloud.trotter.dashbuddy.core.data.chat.ChatRepository
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.test.util.RecordingTree
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import timber.log.Timber

/**
 * #772 / Principle-7: the two "Chat" INFO milestones in [BubbleManager.postMessage] /
 * [BubbleManager.postOfferNotification] used to interpolate [ChatPersona.displayName], which
 * carries raw merchant/customer text ("H-E-B", "…'s customer") for name-bearing personas — a
 * Principle-7 leak into the exportable `shareable.log`. They now log [ChatPersona.logLabel]
 * (the persona KIND) instead; the DEBUG line directly below each keeps `displayName` for firehose
 * fidelity. Drives the REAL [BubbleManager] (mocked collaborators only) through [RecordingTree] to
 * prove the fix end-to-end, matching the [BubbleManagerIdentityTest] Robolectric precedent.
 */
@RunWith(RobolectricTestRunner::class)
class BubbleManagerChatLogTest {

    private fun buildManager(): BubbleManager {
        val context = RuntimeEnvironment.getApplication()
        val notificationManager: NotificationManager = mock()
        val chatRepository: ChatRepository = mock()
        val stateManager: StateManagerV2 = mock()
        whenever(stateManager.state).thenReturn(MutableStateFlow(AppState()))
        val lazyStateManager = dagger.Lazy<StateManagerV2> { stateManager }
        return BubbleManager(context, notificationManager, chatRepository, lazyStateManager)
    }

    @Test
    fun `chat INFO milestones log persona kind, never the raw merchant or customer name`() {
        val manager = buildManager()
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            manager.postMessage("Pickup: H-E-B ready", ChatPersona.Merchant("H-E-B"))
            manager.postMessage("Heading to H-E-B's customer", ChatPersona.Customer("H-E-B's customer"))
        } finally {
            Timber.uproot(tree)
        }

        // (a) The shareable INFO+ stream never carries the raw merchant/customer text.
        tree.assertNoInfoPlusContains("H-E-B")

        // (b) The INFO milestone lines carry the persona KIND label instead.
        val infoChat = tree.records.filter { it.priority == Log.INFO && it.tag == "Chat" }
        check(infoChat.any { it.message.contains("message posted [Merchant]") }) {
            "No INFO 'Chat' line carried '[Merchant]'. Records: $infoChat"
        }
        check(infoChat.any { it.message.contains("message posted [Customer]") }) {
            "No INFO 'Chat' line carried '[Customer]'. Records: $infoChat"
        }

        // (c) The DEBUG firehose keeps full fidelity — the raw store/customer text is still there.
        tree.assertLevelContains(Log.DEBUG, "H-E-B")
    }
}
