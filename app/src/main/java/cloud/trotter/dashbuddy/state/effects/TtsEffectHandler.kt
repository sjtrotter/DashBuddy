package cloud.trotter.dashbuddy.state.effects

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

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

    fun speakOffer(offer: ParsedOffer, platformName: String) {
        if (!isReady) {
            Timber.w("TTS not ready, skipping offer speech")
            return
        }
        val text = formatOffer(offer, platformName)
        Timber.i("TTS speaking: %s", text)

        audioManager.requestAudioFocus(audioFocusRequest)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "offer_${offer.offerHash}")
    }

    private fun formatOffer(offer: ParsedOffer, platformName: String): String {
        val parts = mutableListOf<String>()
        parts += "$platformName offer"
        offer.payAmount?.let { parts += "$%.2f".format(it) }
        offer.orders.firstOrNull()?.let { parts += it.storeName.trim() }
        offer.distanceMiles?.let { parts += "%.1f miles".format(it) }
        offer.dueByTimeText?.let { parts += it }
        return parts.joinToString(". ") + "."
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
