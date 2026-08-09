package cloud.trotter.dashbuddy.state.effects

import android.app.Notification
import android.app.NotificationManager
import android.speech.tts.TextToSpeech
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality
import cloud.trotter.dashbuddy.notice.AppNoticeChannel
import cloud.trotter.dashbuddy.notice.TtsHealthNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #991 — the engine seam, proven at the handler rather than in the policy: a failed utterance must
 * actually reach [TtsEngineFactory] again, and a run of them must actually reach the dasher.
 *
 * Robolectric supplies the real `Context` (the spoken copy is resolved through app resources) and
 * a working `AudioManager`; the engine itself is a stub handed out by a fake factory, which also
 * keeps each `onInit` listener so the test can deliver readiness the way the framework does —
 * asynchronously, after `create` returns.
 */
@RunWith(RobolectricTestRunner::class)
class TtsEffectHandlerRecoveryTest {

    private lateinit var engines: MutableList<TextToSpeech>
    private lateinit var listeners: MutableList<TextToSpeech.OnInitListener>
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        engines = mutableListOf()
        listeners = mutableListOf()
        notificationManager = mock()
    }

    private val factory = TtsEngineFactory { onInit ->
        val engine = mock<TextToSpeech>()
        engines += engine
        listeners += onInit
        engine
    }

    private fun handler(): TtsEffectHandler {
        val prefs = mock<AppPreferencesRepository>()
        whenever(prefs.ttsLanguageTag).thenReturn(flowOf(null))
        return TtsEffectHandler(
            context = RuntimeEnvironment.getApplication(),
            appPreferencesRepository = prefs,
            appScope = CoroutineScope(Dispatchers.Unconfined),
            engineFactory = factory,
            healthNotifier = TtsHealthNotifier(
                context = RuntimeEnvironment.getApplication(),
                notificationManager = notificationManager,
            ),
        )
    }

    /** Deliver the asynchronous readiness callback for the most recently built engine. */
    private fun reportReady() = listeners.last().onInit(TextToSpeech.SUCCESS)

    private fun failSpeech(engine: TextToSpeech) {
        whenever(engine.speak(any(), any(), anyOrNull(), any())).thenReturn(TextToSpeech.ERROR)
    }

    private fun evaluation() = OfferEvaluation(
        action = OfferAction.ACCEPT,
        score = 74.0,
        qualityLevel = OfferQuality.GOOD,
        payAmount = 7.50,
        fuelCostEstimate = 0.50,
        netPayAmount = 7.00,
        distanceMiles = 3.2,
        dollarsPerMile = 2.19,
        dollarsPerHour = 22.0,
        estimatedTimeMinutes = 19.0,
        itemCount = 1.0,
        merchantName = "Chipotle",
    )

    @Test
    fun `a failed utterance tears the engine down and builds a new one`() {
        val tts = handler()
        reportReady()
        failSpeech(engines.first())

        tts.speakOffer(evaluation())

        verify(engines.first()).shutdown()
        assertEquals("the dead engine must be replaced, not reused", 2, engines.size)
    }

    @Test
    fun `a successful utterance leaves the engine alone`() {
        val tts = handler()
        reportReady()
        // A mock returns 0 == TextToSpeech.SUCCESS for speak() by default.

        tts.speakOffer(evaluation())

        verify(engines.first(), never()).shutdown()
        assertEquals("a working engine is never rebuilt", 1, engines.size)
    }

    @Test
    fun `the replacement engine is wired for speech exactly like the original`() {
        val tts = handler()
        reportReady()
        failSpeech(engines.first())
        tts.speakOffer(evaluation())

        reportReady() // the rebuilt engine reports in

        // #428-B language installation and the utterance callbacks must both survive recovery —
        // otherwise the voice comes back mute, or comes back speaking the wrong language.
        verify(engines.last()).setOnUtteranceProgressListener(any())
        verify(engines.last()).setLanguage(any())
    }

    @Test
    fun `a recovery that never comes back notifies the dasher once`() {
        val tts = handler()
        reportReady()
        failSpeech(engines.first())

        // 1st: the engine fails and is rebuilt. The replacement never reports ready (the engine's
        // package is mid-update), so the next two offers are skipped — which, during a recovery,
        // is itself a lost utterance.
        repeat(4) { tts.speakOffer(evaluation()) }

        verify(notificationManager).notify(
            eq(AppNoticeChannel.Ids.TTS_ENGINE_HEALTH),
            any<Notification>(),
        )
    }

    @Test
    fun `a skipped utterance before the first init is not counted as a failure`() {
        val tts = handler() // no reportReady(): the engine is still coming up

        repeat(5) { tts.speakOffer(evaluation()) }

        verify(notificationManager, never()).notify(any<Int>(), any<Notification>())
        assertEquals("startup is not a failure — nothing to rebuild", 1, engines.size)
    }
}
