package cloud.trotter.dashbuddy.state.effects

import android.content.Context
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.dashbuddy.domain.format.Formats

@Singleton
class TtsEffectHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appPreferencesRepository: AppPreferencesRepository,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private var tts: TextToSpeech? = null

    /** Monotonic utterance ids (#551 P7): the id is logged by the WARN error callback, so it
     *  must never embed the merchant name — a counter correlates callbacks just as well. */
    private val utteranceSeq = AtomicLong(0)

    @Volatile
    private var isReady = false

    /**
     * #428 Half B — the settings language override (BCP-47 tag; null ⇒ follow system locale),
     * kept current by the reactive [AppPreferencesRepository.ttsLanguageTag] collector below.
     */
    @Volatile
    private var overrideTag: String? = null

    /**
     * The locale actually installed on the engine (== the effective locale, or English if that was
     * unavailable). The SPOKEN COPY is resolved through this exact locale so the words and the voice
     * always match — a Spanish override changes both, and a fallback to an English voice also falls
     * the words back to English.
     */
    @Volatile
    private var spokenLocale: Locale = Locale.getDefault()

    /** Edge-gate for the fallback WARN: only log when the unavailable target changes (never per
     *  utterance). Guarded by [applyLanguage]'s lock. */
    private var lastFallbackTag: String? = null

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
                        Timber.tag("Tts").w("error %d for utterance %s", errorCode, utteranceId)
                        audioManager.abandonAudioFocusRequest(audioFocusRequest)
                    }
                })
                isReady = true
                // #428 Half B: install the effective language now the engine is up (replaces the
                // former unconditional Locale.US).
                applyLanguage()
                Timber.tag("Tts").i("engine initialized")
            } else {
                Timber.tag("Tts").w("init failed with status %d", status)
            }
        }
        // Observe the override reactively so a settings change re-languages the live engine AND the
        // spoken copy with no restart (Reactive UI / UDF: the pref is the single source of truth).
        // distinctUntilChanged() is load-bearing: the app-prefs DataStore emits on EVERY write to the
        // store (theme, glance, economy edits, the EIA gas-price writer), so without it an unrelated
        // write would re-run applyLanguage() and issue a redundant engine setLanguage() — possibly
        // mid-utterance (same-value re-set is engine-dependent, not guaranteed inert).
        appScope.launch {
            appPreferencesRepository.ttsLanguageTag.distinctUntilChanged().collect { tag ->
                overrideTag = tag
                applyLanguage()
            }
        }
    }

    /**
     * Resolves the effective locale (override > system) and installs it on the engine, falling back
     * to English (with ONE edge-gated WARN) if the requested language is unavailable / missing voice
     * data — never a silent drop. No-ops until the engine is ready; the init callback re-runs it.
     * Synchronized so the init callback and the pref collector can't interleave.
     */
    @Synchronized
    private fun applyLanguage() {
        val engine = tts ?: return
        if (!isReady) return
        val target = TtsLocale.effectiveLocale(overrideTag, Locale.getDefault())
        val outcome = TtsLocale.applyLanguage(target) { engine.setLanguage(it) }
        spokenLocale = outcome.applied
        if (outcome.fellBack) {
            val targetTag = target.toLanguageTag()
            if (lastFallbackTag != targetTag) {
                lastFallbackTag = targetTag
                Timber.tag("Tts").w(
                    "language %s unavailable — speaking English instead", targetTag
                )
            }
        } else {
            lastFallbackTag = null
        }
    }

    /** Speak the offer's evaluation aloud — the verdict, then the card's headline economics. */
    fun speakOffer(eval: OfferEvaluation) {
        if (!isReady) {
            Timber.tag("Tts").w("not ready, skipping offer speech")
            return
        }
        val text = formatEvaluation(eval)
        // #551 P7: the spoken text names merchants ("Accept. Target & Maple Street …"),
        // so the shareable INFO stream carries counts only; the raw utterance stays on the
        // DEBUG firehose.
        Timber.tag("Tts").i("speaking (%d chars)", text.length)
        Timber.tag("Tts").d("speaking: %s", text)

        audioManager.requestAudioFocus(audioFocusRequest)
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "offer_${utteranceSeq.incrementAndGet()}")
        if (result != TextToSpeech.SUCCESS) {
            // A failed speak() never fires an utterance callback — release focus here or
            // other apps stay ducked (#341).
            Timber.tag("Tts").w("speak returned %s — abandoning audio focus", result)
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
        }
    }

    private fun formatEvaluation(eval: OfferEvaluation): String {
        // #428 Half B: the verdict word + template connectives are resolved through the EFFECTIVE
        // locale's resources (via a localized Context) so the settings override changes the words as
        // well as the voice. Formats.decimal(...) numeric formatting is unchanged — the SSOT locale
        // policy in :domain (#358/#456/#467) still owns number/money formatting; the es voice reads
        // those digits.
        val localized = localizedContext(spokenLocale)
        val verdict = localized.getString(
            when (eval.action) {
                OfferAction.ACCEPT -> R.string.tts_verdict_accept
                OfferAction.DECLINE -> R.string.tts_verdict_decline
                OfferAction.MANUAL_REVIEW -> R.string.tts_verdict_review
                else -> R.string.tts_verdict_offer
            }
        )
        return localized.getString(
            R.string.tts_offer_evaluation_template,
            verdict,
            eval.merchantName.trim(),
            Formats.decimal(eval.dollarsPerHour, 0),
            Formats.decimal(eval.netPayAmount, 2),
            Formats.decimal(eval.distanceMiles),
            eval.score.toInt().toString(),
        )
    }

    /** A [Context] whose resources resolve against [locale] — so the spoken strings match the
     *  engine voice regardless of the device default (#428 Half B). */
    private fun localizedContext(locale: Locale): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
