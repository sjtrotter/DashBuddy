package cloud.trotter.dashbuddy.state.effects

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.dashbuddy.core.designsystem.format.DashFormats

@Singleton
class TtsEffectHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private var tts: TextToSpeech? = null

    @Volatile
    private var isReady = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        audioManager.abandonAudioFocusRequest(audioFocusRequest)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        // No-op: errorCode overload handles this
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Timber.w("TTS error %d for utterance %s", errorCode, utteranceId)
                        audioManager.abandonAudioFocusRequest(audioFocusRequest)
                    }
                })
                isReady = true
                Timber.i("TTS engine initialized")
            } else {
                Timber.w("TTS init failed with status %d", status)
            }
        }
    }

    /** Speak the offer's evaluation aloud — the verdict, then the card's headline economics. */
    fun speakOffer(eval: OfferEvaluation) {
        if (!isReady) {
            Timber.w("TTS not ready, skipping offer speech")
            return
        }
        val text = formatEvaluation(eval)
        Timber.i("TTS speaking: %s", text)

        audioManager.requestAudioFocus(audioFocusRequest)
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "offer_${eval.merchantName}")
        if (result != TextToSpeech.SUCCESS) {
            // A failed speak() never fires an utterance callback — release focus here or
            // other apps stay ducked (#341).
            Timber.w("TTS speak returned %s — abandoning audio focus", result)
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
        }
    }

    private fun formatEvaluation(eval: OfferEvaluation): String {
        val verdict = when (eval.action) {
            OfferAction.ACCEPT -> "Accept"
            OfferAction.DECLINE -> "Decline"
            OfferAction.MANUAL_REVIEW -> "Review"
            else -> "Offer"
        }
        return "$verdict. ${eval.merchantName.trim()}. " +
            "${DashFormats.decimal(eval.dollarsPerHour, 0)} dollars an hour net. " +
            "Net ${DashFormats.decimal(eval.netPayAmount, 2)}, " +
            "${DashFormats.decimal(eval.distanceMiles)} miles, " +
            "score ${eval.score.toInt()}."
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
