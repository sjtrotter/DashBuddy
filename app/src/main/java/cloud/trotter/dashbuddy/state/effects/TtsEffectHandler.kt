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
import kotlinx.coroutines.CancellationException
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
 * **Liveness (#991).** The engine is built through [TtsEngineFactory] rather than inline, and every
 * way an utterance can be lost escalates through [TtsRecoveryPolicy] — rebuild, backed-off rebuild,
 * then a dasher-visible notice. Before that, the engine was constructed exactly once in `init` with
 * [isReady] latched true forever, so when the engine's service binder dropped (2026-08-09: a Google
 * TTS package update under an 8-day-old process) every utterance was lost silently for three
 * dashes. See [TtsRecoveryPolicy] for the ladder and its reasoning.
 *
 * **Concurrency.** Failures arrive from three threads — the effect drain worker (`speak()`'s
 * return), the engine's init callback, and its utterance callbacks — so all engine state
 * ([tts], [isReady], [engineGeneration], [initFailed], [recovering], [lastFallbackTag]) is guarded
 * by ONE lock, this instance's own monitor. The volatiles are for the cheap unlocked reads on the
 * skip path only; every read that decides something takes the lock.
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

    /**
     * Identity of the engine currently owned by this handler. A `TextToSpeech` reports readiness
     * asynchronously, so a superseded engine's callback can land *after* its replacement has been
     * built; without this check it would mark the wrong instance ready, install the #428-B language
     * on an engine nobody speaks through, and log a false "re-initialized". Guarded by the lock.
     */
    private var engineGeneration = 0L

    /** #991 — when to rebuild the engine, and when to stop pretending it's coming back. */
    private val recoveryPolicy = TtsRecoveryPolicy()

    /**
     * True between requesting a rebuild and the replacement engine reporting ready. Together with
     * [initFailed] it is what lets the "engine never came back" case still escalate: while either
     * holds, a skipped utterance counts as a failure, so an engine that never reports ready still
     * reaches the notice instead of falling into the pre-#991 silent `!isReady` early return
     * forever.
     */
    @Volatile
    private var recovering = false

    /**
     * True when the current engine's `onInit` reported a non-SUCCESS status. Distinct from
     * [recovering] on purpose: an init that is merely *pending* (app startup, engine still binding)
     * is not a failure and must not be counted as one, while an init that FAILED is exactly the
     * fielded trigger class — a process that starts while the TTS package is mid-update — and must
     * arm the ladder rather than latch silent.
     */
    @Volatile
    private var initFailed = false

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
     *  utterance). Guarded by the lock. */
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
            // #991: THIS is the evidence the engine works. `speak()` returning SUCCESS only means
            // the utterance was QUEUED — an engine that accepts everything and speaks nothing
            // would otherwise score as healthy and reset the ladder on every lost offer.
            recoveryPolicy.onUtteranceCompleted()
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            // No-op: errorCode overload handles this
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            Timber.tag("Tts").w("error %d for utterance %s", errorCode, utteranceId)
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
            // #991: an accepted-then-failed utterance is just as lost as a rejected one.
            onSpeechFailed()
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
     * The init callback for ONE engine instance. Carries its own [generation] so a superseded
     * engine's late callback can be recognized and dropped, and its own [engine] reference so the
     * utterance listener is installed on the instance that actually reported — never on whatever
     * happens to be in the field by then.
     */
    private inner class EngineInit(
        val generation: Long,
        val replacing: Boolean,
    ) : TextToSpeech.OnInitListener {

        /** Set by [createEngine] the moment the constructor returns. */
        var engine: TextToSpeech? = null

        /**
         * Set when the callback beat the constructor's return. The real framework engine is
         * asynchronous, but nothing in the contract guarantees it, and a callback that fired with
         * [engine] still null would leave the replacement ready-but-unwired — no utterance listener,
         * so audio focus would never be released and every other app would stay ducked. Deferring
         * it to the end of [createEngine] closes that window without a second lock.
         */
        var deferredStatus: Int? = null

        override fun onInit(status: Int) = onEngineInit(this, status)
    }

    /**
     * Build an engine and wire its init callback (#991). [replacing] only colours the logging —
     * the wiring is identical, which is the point: a rebuilt engine gets the SAME utterance
     * listener and the SAME #428-B language installation as the original, so recovery can never
     * silently drop the settings override or the fallback WARN.
     */
    @Synchronized
    private fun createEngine(replacing: Boolean) {
        val callback = EngineInit(generation = ++engineGeneration, replacing = replacing)
        isReady = false
        initFailed = false
        recovering = replacing
        // A fresh engine has its own installed voices: the edge-gate for the unavailable-language
        // WARN must not stay latched from the dead one, or a replacement that ALSO can't speak the
        // chosen language would fall back to English with no line saying so.
        lastFallbackTag = null
        tts = null

        val engine = engineFactory.create(callback)
        callback.engine = engine
        tts = engine

        callback.deferredStatus?.let { status ->
            callback.deferredStatus = null
            onEngineInit(callback, status)
        }
    }

    /**
     * The engine's readiness callback, keyed to the instance that reported it.
     *
     * A failed init is escalated to the policy (#991 review finding 1) rather than merely logged:
     * leaving it at a WARN was the pre-fix silence in a different costume — `isReady` false with
     * nothing armed to change that, so every later offer took the skip path and the ladder was
     * never consulted at all.
     */
    private fun onEngineInit(callback: EngineInit, status: Int) {
        var failed = false
        synchronized(this) {
            if (callback.engine == null) {
                // Beat the constructor's return — createEngine finishes this call.
                callback.deferredStatus = status
                return
            }
            if (callback.generation != engineGeneration) {
                Timber.tag("Tts").w(
                    "init report from a superseded engine (generation %d, current %d) — ignoring",
                    callback.generation,
                    engineGeneration,
                )
                return
            }
            if (status != TextToSpeech.SUCCESS) {
                Timber.tag("Tts").w("init failed with status %d", status)
                isReady = false
                initFailed = true
                failed = true
            } else {
                // Wire the instance that reported, not the field: identity is the whole point of
                // the generation check above.
                callback.engine?.setOnUtteranceProgressListener(utteranceProgressListener)
                initFailed = false
                recovering = false
                isReady = true
                // #428 Half B: install the effective language now the engine is up (replaces the
                // former unconditional Locale.US).
                applyLanguage()
                if (callback.replacing) {
                    // INFO: a user-meaningful milestone ("the voice came back"), and PII-free by
                    // construction — no offer, store or screen value is in reach (principle 7).
                    Timber.tag("Tts").i("engine re-initialized after failure")
                } else {
                    Timber.tag("Tts").i("engine initialized")
                }
            }
        }
        // Outside the lock: the escalation may schedule a rebuild.
        if (failed) onSpeechFailed()
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
     * Shares the handler's one lock so the init callback and the pref collector can't interleave.
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
        val engine = synchronized(this) { if (isReady) tts else null }
        if (engine == null) {
            Timber.tag("Tts").w("not ready, skipping offer speech")
            // #991: a skip during a recovery — or after an init that FAILED — is a lost utterance,
            // so it escalates. A skip while the first init is merely still pending is not: nothing
            // has failed yet, the engine is simply coming up.
            if (recovering || initFailed) onSpeechFailed()
            return
        }
        val text = formatEvaluation(eval)
        // #551 P7: the spoken text names merchants ("Accept. Target & Maple Street …"),
        // so the shareable INFO stream carries counts only; the raw utterance stays on the
        // DEBUG firehose.
        Timber.tag("Tts").i("speaking (%d chars)", text.length)
        Timber.tag("Tts").d("speaking: %s", text)

        audioManager.requestAudioFocus(audioFocusRequest)
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "offer_${utteranceSeq.incrementAndGet()}")
        if (result != TextToSpeech.SUCCESS) {
            // A failed speak() never fires an utterance callback — release focus here or
            // other apps stay ducked (#341).
            Timber.tag("Tts").w("speak returned %s — abandoning audio focus", result)
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
            onSpeechFailed()
        }
        // A SUCCESS return means QUEUED, not spoken — the policy resets in onDone, not here.
    }

    /**
     * Escalate one lost utterance through [TtsRecoveryPolicy] (#991). Never throws: it runs on the
     * effect drain worker (the #909 boundary) and on the engine's own callback threads.
     */
    private fun onSpeechFailed() {
        val decision = try {
            recoveryPolicy.onSpeechFailure(System.currentTimeMillis())
        } catch (t: Throwable) {
            Timber.tag("Tts").e(t, "recovery policy threw — leaving the engine alone")
            return
        }

        if (decision.notify) {
            // ERROR, not WARN (principle 7): a recovery that demonstrably failed is a crashed
            // subsystem, the same class as superviseDrainWorker's restart line. The rebuild
            // attempts below stay WARN — a defended invariant firing, not a loss.
            Timber.tag("Tts").e(
                "speech engine unrecoverable after %d consecutive losses — notifying the dasher",
                recoveryPolicy.failureStreak,
            )
            healthNotifier.onSpeechEngineUnrecoverable()
        }

        if (decision.rebuild) {
            Timber.tag("Tts").w(
                "speech failed %d time(s) — re-initializing the engine",
                recoveryPolicy.failureStreak,
            )
            scheduleRebuild()
        }

        if (decision.isWait) {
            Timber.tag("Tts").d(
                "speech failed %d time(s) — inside the %d ms rebuild backoff, not rebuilding",
                recoveryPolicy.failureStreak,
                recoveryPolicy.currentBackoffMillis(),
            )
        }
    }

    /**
     * Rebuild off the caller's thread (#991 review finding 7). `speak()`'s failure arrives on the
     * single serialized effect drain worker — the #909 data-integrity boundary that owns the only
     * writer of `app_events` — and both `shutdown()` and the `TextToSpeech` constructor are binder
     * calls into a service that has just proven it is misbehaving. Blocking that worker on a wedged
     * binder would trade a silent voice for silent event loss, which is strictly worse.
     */
    private fun scheduleRebuild() {
        appScope.launch {
            try {
                reinitializeEngine()
            } catch (e: CancellationException) {
                throw e // structured concurrency — never swallowed (#909)
            } catch (t: Throwable) {
                Timber.tag("Tts").e(t, "engine re-initialization failed")
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
