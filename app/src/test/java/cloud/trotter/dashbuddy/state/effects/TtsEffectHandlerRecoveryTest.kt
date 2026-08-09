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
import android.speech.tts.UtteranceProgressListener
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #991 — the engine seam, proven at the handler rather than in the policy: every way an utterance
 * can be lost must actually reach [TtsEngineFactory] again, and a sustained outage must actually
 * reach the dasher.
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

    /** Deliver a FAILED init for the most recently built engine. */
    private fun reportInitFailure() = listeners.last().onInit(TextToSpeech.ERROR)

    private fun failSpeech(engine: TextToSpeech) {
        whenever(engine.speak(any(), any(), anyOrNull(), any())).thenReturn(TextToSpeech.ERROR)
    }

    /** The utterance callbacks the handler installed on [engine], for driving async outcomes. */
    private fun progressListener(engine: TextToSpeech) =
        argumentCaptor<UtteranceProgressListener>().apply {
            verify(engine).setOnUtteranceProgressListener(capture())
        }.lastValue

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

    // ---------------------------------------------------------------------------------------
    // The three failure sources
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a rejected utterance tears the engine down and builds a new one`() {
        val tts = handler()
        reportReady()
        failSpeech(engines.first())

        tts.speakOffer(evaluation())

        verify(engines.first()).shutdown()
        assertEquals("the dead engine must be replaced, not reused", 2, engines.size)
    }

    @Test
    fun `an accepted-then-errored utterance also rebuilds`() {
        val tts = handler()
        reportReady()
        // speak() returns SUCCESS (the mock default) — QUEUED, not spoken — and the failure
        // arrives asynchronously. Before #991 review finding 2 this reached nothing at all.
        tts.speakOffer(evaluation())

        progressListener(engines.first()).onError("offer_1", TextToSpeech.ERROR)

        assertEquals("an async error is just as lost as a rejected utterance", 2, engines.size)
    }

    @Test
    fun `a failed init escalates instead of latching silent`() {
        handler()

        reportInitFailure()

        // Finding 1: the fielded trigger class is a process that starts while the TTS package is
        // mid-update. Leaving isReady false with nothing armed was the pre-fix silence.
        assertEquals("a failed init must arm the ladder", 2, engines.size)
    }

    // ---------------------------------------------------------------------------------------
    // What must NOT count
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a completed utterance leaves the engine alone`() {
        val tts = handler()
        reportReady()

        tts.speakOffer(evaluation())
        progressListener(engines.first()).onDone("offer_1")

        verify(engines.first(), never()).shutdown()
        assertEquals("a working engine is never rebuilt", 1, engines.size)
    }

    @Test
    fun `a skipped utterance while the first init is still pending is not counted`() {
        val tts = handler() // no readiness report: the engine is simply still binding

        repeat(5) { tts.speakOffer(evaluation()) }

        verify(notificationManager, never()).notify(any<Int>(), any<Notification>())
        assertEquals("startup is not a failure — nothing to rebuild", 1, engines.size)
    }

    // ---------------------------------------------------------------------------------------
    // Engine identity (finding 3)
    // ---------------------------------------------------------------------------------------

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
    fun `a superseded engine's late init report is ignored`() {
        handler()
        // Engine 1's init fails, which rebuilds: engine 2 is now the live one, still binding.
        reportInitFailure()
        assertEquals(2, engines.size)

        // Engine 1's SUCCESS callback lands late — it was already torn down. Acting on it would
        // ready an instance nobody speaks through, install the #428-B language on it, log a false
        // "re-initialized", and leave the live engine with no progress listener (so audio focus
        // would never be released and every other app would stay ducked).
        listeners.first().onInit(TextToSpeech.SUCCESS)

        verify(engines.first(), never()).setOnUtteranceProgressListener(any())
        verify(engines.last(), never()).setOnUtteranceProgressListener(any())
        assertEquals("a stale report must not trigger anything else either", 2, engines.size)
    }

    // ---------------------------------------------------------------------------------------
    // The dasher-visible end of the ladder (findings 4 + 6)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a burst of failures inside the elapsed floor does not burn the notice`() {
        val tts = handler()
        reportReady()
        failSpeech(engines.first())

        // The replacement engine never reports ready (the TTS package is mid-update), so every
        // later offer is skipped — which, during a recovery, is itself a lost utterance. All of
        // them land milliseconds apart, well inside the policy's 60 s floor.
        repeat(40) { tts.speakOffer(evaluation()) }

        // Finding 4: the notice cannot be un-fired, so a burst must not spend it. A real outage
        // crossing the floor still gets it — that gate is exercised in TtsRecoveryPolicyTest.
        verify(notificationManager, never()).notify(any<Int>(), any<Notification>())
    }

    @Test
    fun `the notice lands on its own id on the shared app-notice channel`() {
        TtsHealthNotifier(
            context = RuntimeEnvironment.getApplication(),
            notificationManager = notificationManager,
        ).onSpeechEngineUnrecoverable()

        verify(notificationManager).createNotificationChannel(any())
        verify(notificationManager, times(1)).notify(
            eq(AppNoticeChannel.Ids.TTS_ENGINE_HEALTH),
            any<Notification>(),
        )
    }
}
