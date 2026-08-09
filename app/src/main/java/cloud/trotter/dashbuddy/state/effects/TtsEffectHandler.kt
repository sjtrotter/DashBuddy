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
import cloud.trotter.dashbuddy.notice.TtsHealthNotifier
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

/**
 * Speaks the offer verdict aloud.
 *
 * **Liveness (#991).** The engine is built through [TtsEngineFactory] rather than inline, and a
 * failed `speak()` escalates through [TtsRecoveryPolicy] — re-init, backed-off re-init, then a
 * dasher-visible notice. Before that, the engine was constructed exactly once in `init` with
 * [isReady] latched true forever, so when the engine's service binder dropped (2026-08-09: a
 * Google TTS package update under an 8-day-old process) every utterance was lost silently for
 * three dashes. See [TtsRecoveryPolicy] for the ladder and its reasoning.
 */
@Singleton
class TtsEffectHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appPreferencesRepository: AppPreferencesRepository,
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val engineFactory: TtsEngineFactory,
    private val healthNotifier: TtsHealthNotifier,
) {
    private var tts: TextToSpeech? = null

    /** #991 — when to rebuild the engine, and when to stop pretending it's coming back. */
    private val recoveryPolicy = TtsRecoveryPolicy()

    /**
     * True between requesting a re-init and the replacement engine reporting ready. It is what
     * lets the "engine never came back" case still escalate: while it holds, a skipped utterance
     * counts as a failure, so an engine whose *construction* also fails (no `onInit` ever lands)
     * still reaches [TtsRecoveryPolicy.Decision.NOTIFY] instead of falling into the pre-#991
     * silent `!isReady` early return forever.
     */
    @Volatile
    private var recovering = false

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

    private val utteranceProgressListener = object : UtteranceProgressListener() {
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
    }

    init {
        createEngine(replacing = false)
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
     * Build an engine and wire its init callback (#991). [replacing] only colours the logging —
     * the wiring is identical, which is the point: a rebuilt engine gets the SAME utterance
     * listener and the SAME #428-B language installation as the original, so recovery can never
     * silently drop the settings override or the fallback WARN.
     *
     * Synchronized against [applyLanguage] so a settings write can't re-language an engine that is
     * being swapped out from under it.
     */
    @Synchronized
    private fun createEngine(replacing: Boolean) {
        isReady = false
        recovering = replacing
        // A fresh engine has its own installed voices: the edge-gate for the unavailable-language
        // WARN must not stay latched from the dead one, or a replacement that ALSO can't speak the
        // chosen language would fall back to English with no line saying so.
        lastFallbackTag = null
        tts = engineFactory.create { status -> onEngineInit(status, replacing) }
    }

    /**
     * The engine's readiness callback. Fires on the framework's own thread, asynchronously — so it
     * is never inside [createEngine]'s frame, and the `tts` field it configures is already the
     * engine that reported.
     */
    private fun onEngineInit(status: Int, replacing: Boolean) {
        if (status != TextToSpeech.SUCCESS) {
            // Not a failure the policy counts: no utterance was lost here. The next skipped
            // utterance does the counting (see [recovering] in speakOffer).
            Timber.tag("Tts").w("init failed with status %d", status)
            return
        }
        tts?.setOnUtteranceProgressListener(utteranceProgressListener)
        isReady = true
        recovering = false
        // #428 Half B: install the effective language now the engine is up (replaces the
        // former unconditional Locale.US).
        applyLanguage()
        if (replacing) {
            // INFO: a user-meaningful milestone ("the voice came back"), and PII-free by
            // construction — no offer, store or screen value is in reach here (principle 7).
            Timber.tag("Tts").i("engine re-initialized after failure")
        } else {
            Timber.tag("Tts").i("engine initialized")
        }
    }

    /**
     * Tear the dead engine down and build a replacement (#991).
     *
     * The in-flight utterance is NOT replayed: the offer that failed to speak is gone, and by the
     * time the new engine reports ready the card may not even be on screen. Losing it honestly and
     * saying so beats speaking a stale verdict at a driving dasher.
     */
    private fun reinitializeEngine() {
        val dead = synchronized(this) {
            val previous = tts
            tts = null
            isReady = false
            previous
        }
        try {
            dead?.shutdown()
        } catch (t: Throwable) {
            // A dead binder can throw on the way out; that must not stop the rebuild.
            Timber.tag("Tts").w(t, "shutdown of the failed engine threw — rebuilding anyway")
        }
        Timber.tag("Tts").d("the failed utterance is dropped — recovery does not replay it")
        createEngine(replacing = true)
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
            // #991: a skip during a recovery IS a lost utterance — count it, so an engine that
            // never reports ready again still escalates to the dasher. A skip before the very
            // first init is not counted (nothing has failed yet; the engine is simply still
            // coming up).
            if (recovering) onSpeakFailed()
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
            onSpeakFailed()
        } else {
            // #991: the engine accepted the utterance — the streak and the backoff reset.
            recoveryPolicy.onSpeakSuccess()
        }
    }

    /**
     * Escalate one lost utterance through [TtsRecoveryPolicy] (#991). Never throws: it runs on the
     * effect drain worker, which the #909 rules keep alive at all costs.
     */
    private fun onSpeakFailed() {
        val decision = try {
            recoveryPolicy.onSpeakFailure(System.currentTimeMillis())
        } catch (t: Throwable) {
            Timber.tag("Tts").e(t, "recovery policy threw — leaving the engine alone")
            return
        }
        when (decision) {
            TtsRecoveryPolicy.Decision.RETRY_REINIT -> {
                Timber.tag("Tts").w(
                    "speech failed %d time(s) — re-initializing the engine",
                    recoveryPolicy.failureStreak,
                )
                try {
                    reinitializeEngine()
                } catch (t: Throwable) {
                    Timber.tag("Tts").e(t, "engine re-initialization failed")
                }
            }

            TtsRecoveryPolicy.Decision.WAIT -> Timber.tag("Tts").d(
                "speech failed %d time(s) — inside the %d ms re-init backoff, not rebuilding",
                recoveryPolicy.failureStreak,
                recoveryPolicy.currentBackoffMillis(),
            )

            TtsRecoveryPolicy.Decision.NOTIFY -> {
                Timber.tag("Tts").w(
                    "speech still failing after %d consecutive losses — notifying the dasher",
                    recoveryPolicy.failureStreak,
                )
                healthNotifier.onSpeechEngineUnrecoverable()
            }
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
        // #936: an offer whose distance never parsed carries 0.0 placeholders in every rate field,
        // so the scored template would speak "zero dollars an hour net … zero miles, score zero"
        // — a fabricated verdict read aloud to a driving dasher. Speak the parsed pay and say
        // there's no verdict instead.
        if (!eval.hasDistanceMetrics) {
            return localized.getString(
                R.string.tts_offer_no_verdict_template,
                verdict,
                eval.merchantName.trim(),
                Formats.decimal(eval.payAmount, 2),
            )
        }
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
