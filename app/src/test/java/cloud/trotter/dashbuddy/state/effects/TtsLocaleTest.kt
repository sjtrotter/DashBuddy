package cloud.trotter.dashbuddy.state.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * #428 Half B — the pure locale-resolution + unavailable-language fallback that drives the TTS
 * engine. The engine is faked via a `(Locale) -> Int` lambda (the [android.speech.tts.TextToSpeech]
 * setLanguage return code), so these run as plain JVM tests with no device.
 */
class TtsLocaleTest {

    // --- effectiveLocale: override > system ---

    @Test
    fun `null override follows the system locale`() {
        assertEquals(Locale.US, TtsLocale.effectiveLocale(overrideTag = null, systemLocale = Locale.US))
    }

    @Test
    fun `blank override follows the system locale`() {
        assertEquals(Locale.US, TtsLocale.effectiveLocale("   ", Locale.US))
    }

    @Test
    fun `override wins over the system locale`() {
        val eff = TtsLocale.effectiveLocale(overrideTag = "es", systemLocale = Locale.US)
        assertEquals("es", eff.language)
    }

    @Test
    fun `english override wins over a spanish system locale`() {
        val eff = TtsLocale.effectiveLocale(overrideTag = "en", systemLocale = Locale.forLanguageTag("es"))
        assertEquals("en", eff.language)
    }

    @Test
    fun `ill-formed override degrades to the system locale`() {
        // A tag Locale.forLanguageTag can't resolve to a real language must not silence speech.
        assertEquals(Locale.US, TtsLocale.effectiveLocale("not a tag", Locale.US))
    }

    // --- applyLanguage: fallback to English on an unavailable language ---

    @Test
    fun `available language applies with no fallback`() {
        val calls = mutableListOf<Locale>()
        val target = Locale.forLanguageTag("es")
        val outcome = TtsLocale.applyLanguage(target) { calls.add(it); 0 /* LANG_AVAILABLE */ }
        assertEquals(target, outcome.applied)
        assertFalse(outcome.fellBack)
        assertEquals(listOf(target), calls)
    }

    @Test
    fun `country-available code counts as usable`() {
        val target = Locale.forLanguageTag("es")
        val outcome = TtsLocale.applyLanguage(target) { 1 /* LANG_COUNTRY_AVAILABLE */ }
        assertEquals(target, outcome.applied)
        assertFalse(outcome.fellBack)
    }

    @Test
    fun `missing voice data falls back to english`() {
        val calls = mutableListOf<Locale>()
        val target = Locale.forLanguageTag("es")
        val outcome = TtsLocale.applyLanguage(target) { loc ->
            calls.add(loc)
            if (loc == target) -1 /* LANG_MISSING_DATA */ else 0
        }
        assertTrue(outcome.fellBack)
        assertEquals(Locale.ENGLISH.language, outcome.applied.language)
        // Tried the target first, then installed English.
        assertEquals(target, calls.first())
        assertTrue(calls.any { it == Locale.ENGLISH })
    }

    @Test
    fun `unsupported language falls back to english`() {
        val target = Locale.forLanguageTag("es")
        val outcome = TtsLocale.applyLanguage(target) { loc ->
            if (loc == target) -2 /* LANG_NOT_SUPPORTED */ else 0
        }
        assertTrue(outcome.fellBack)
        assertEquals(Locale.ENGLISH.language, outcome.applied.language)
    }

    @Test
    fun `english target that itself fails does not re-call the engine`() {
        val calls = mutableListOf<Locale>()
        val outcome = TtsLocale.applyLanguage(Locale.ENGLISH) { calls.add(it); -2 }
        // Nothing better to fall back to — report the fallback, but only one attempt.
        assertTrue(outcome.fellBack)
        assertEquals(1, calls.size)
    }
}
