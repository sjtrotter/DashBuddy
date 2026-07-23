package cloud.trotter.dashbuddy.state.effects

import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Pure, engine-agnostic helpers for #428 Half B (multi-language offer reading). Kept out of
 * [TtsEffectHandler] so the effective-locale resolution and the unavailable-language fallback
 * are unit-testable without a live [TextToSpeech] instance (the engine is faked via a lambda).
 */
object TtsLocale {

    /**
     * Effective spoken-offer locale: the settings [overrideTag] (a BCP-47 tag such as `"es"`) wins
     * when present and parseable, otherwise the [systemLocale]. A blank tag, or one
     * [Locale.forLanguageTag] can't resolve to a real language, degrades to [systemLocale] — so a
     * corrupt stored value never silences speech (fail-safe toward the system default).
     */
    fun effectiveLocale(overrideTag: String?, systemLocale: Locale): Locale {
        val tag = overrideTag?.trim().orEmpty()
        if (tag.isEmpty()) return systemLocale
        val parsed = Locale.forLanguageTag(tag)
        return if (parsed.language.isNullOrEmpty()) systemLocale else parsed
    }

    /** Outcome of applying a language to the engine — the locale actually installed, and whether we
     *  had to fall back to English because the requested one was unavailable / missing voice data. */
    data class ApplyOutcome(val applied: Locale, val fellBack: Boolean)

    /**
     * Applies [target] to the engine via [setLang] — the value of [TextToSpeech.setLanguage] for
     * that locale. A code `>= LANG_AVAILABLE` (0) means the language is usable. A negative code
     * (`LANG_MISSING_DATA` / `LANG_NOT_SUPPORTED`) means it is not, so we fall back to English
     * rather than silently dropping speech. If [target] is already English and even that fails,
     * there is nothing better to try — we report the fallback without a redundant second call.
     *
     * Two edges worth naming:
     * - The English fallback's OWN [setLang] return is not checked: if English is also unavailable
     *   (a doubly-broken engine) the engine keeps whatever language it last had, while the spoken
     *   WORDS still switch to English via [ApplyOutcome.applied]. Rare, and the settings UI only
     *   ever writes en/es/null, so a genuinely unresolvable target is not reachable from the UI.
     * - A parseable-but-unavailable tag (valid BCP-47, no installed voice) falls back to ENGLISH
     *   here — not to the system locale. Only an ill-formed/blank tag degrades to system, and that
     *   happens earlier, in [effectiveLocale], before this function is reached.
     */
    fun applyLanguage(target: Locale, setLang: (Locale) -> Int): ApplyOutcome {
        if (setLang(target) >= TextToSpeech.LANG_AVAILABLE) {
            return ApplyOutcome(target, fellBack = false)
        }
        if (target.language.equals(Locale.ENGLISH.language, ignoreCase = true)) {
            return ApplyOutcome(target, fellBack = true)
        }
        setLang(Locale.ENGLISH)
        return ApplyOutcome(Locale.ENGLISH, fellBack = true)
    }
}
