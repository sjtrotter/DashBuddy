package cloud.trotter.dashbuddy.domain.pipeline

import java.util.Locale

/**
 * The recognition layer's **English-locale boundary** (#938).
 *
 * Rule anchors (the `matchers/rules` JSON5 source) and BOTH text-marker privacy SSOTs
 * (`SensitiveTextMarkers.KEYWORDS`, `CustomerTextMarkers.MARKERS`) are literal
 * English strings. On a device whose platform apps render in another language:
 *
 * - **recognition** drops toward zero — every frame falls to UNKNOWN (survivable:
 *   release builds bind `NoOpCaptureBus`, so nothing is written), and
 * - the **Pledge layers thin** — the sensitive-screen text backstop, the UNKNOWN-capture
 *   customer-PII scrub, and the shareable-log scrub all weaken **silently**, which is the
 *   part that is not survivable without a signal.
 *
 * The id-based layer (`CustomerTextMarkers.ID_MARKERS`, #910) is the one **locale-immune**
 * defence — Android view ids do not localize — and is the pattern a future translation
 * effort should prefer.
 *
 * This object is the pure decision half of the detection: no Android, no I/O, no clock.
 * The side-effecting half (WARN + the one-time dasher-visible notice) lives behind
 * [LocaleBoundaryReporter].
 *
 * **Scope note:** this is *detection and documentation only*. Translating anchors/markers
 * needs a non-English corpus and is deliberately out of scope (#938). Separately, #428
 * bounds the app's OWN copy/TTS to en/es/fr — a different assumption from this one.
 */
object RecognitionLocale {

    /** The one language the anchors and marker SSOTs are written in. */
    const val SUPPORTED_LANGUAGE: String = "en"

    /**
     * The device language as a lowercase ISO-639 code, or `""` when the platform reports
     * no language at all. `Locale.ROOT` keeps the case fold machine-stable (principle 4).
     */
    fun languageOf(locale: Locale): String = locale.language.lowercase(Locale.ROOT)

    /**
     * Whether recognition's English assumption holds for [locale].
     *
     * A **blank** language (undetermined — effectively unreachable on Android, which always
     * reports at least `en`) counts as supported: a blank tag is not evidence of a
     * non-English device, and a false "your language isn't supported" notice costs the
     * dasher's trust while changing no behaviour. The bias is deliberate — this signal is
     * informational, so the safe side is *no false positive*, not *warn on doubt*.
     */
    fun isSupported(locale: Locale): Boolean {
        val language = languageOf(locale)
        return language.isBlank() || language == SUPPORTED_LANGUAGE
    }

    /**
     * The full decision for one sensor-service start: whether to warn at all, and — when the
     * dasher has already been told once ([alreadyNotified]) — whether to repeat the
     * user-visible notice. The WARN is not suppressed by [alreadyNotified]: it is a per-connect
     * diagnostic in the log (rare — a service connects at boot / on enable), whereas the notice
     * is a once-per-install interruption.
     */
    fun check(locale: Locale, alreadyNotified: Boolean): LocaleBoundaryCheck =
        if (isSupported(locale)) {
            LocaleBoundaryCheck.Supported
        } else {
            LocaleBoundaryCheck.Unsupported(
                language = languageOf(locale),
                notifyUser = !alreadyNotified,
            )
        }
}

/** Outcome of [RecognitionLocale.check]. */
sealed interface LocaleBoundaryCheck {

    /** The device language is (or is indistinguishable from) English — nothing to report. */
    data object Supported : LocaleBoundaryCheck

    /**
     * The device language is outside the recognition/marker vocabulary.
     *
     * @param language the lowercase ISO-639 code — PII-safe by construction (principle 7),
     *   the only device-derived value any INFO+ line here may carry.
     * @param notifyUser true only for the first such start on this install.
     */
    data class Unsupported(val language: String, val notifyUser: Boolean) : LocaleBoundaryCheck
}
