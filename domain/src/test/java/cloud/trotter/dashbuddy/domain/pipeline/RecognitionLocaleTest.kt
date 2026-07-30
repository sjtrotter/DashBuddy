package cloud.trotter.dashbuddy.domain.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The pure half of the #938 English-locale boundary detection.
 *
 * Every case constructs its own [Locale] — the JVM-wide default is never read or mutated, so this
 * is order-independent and safe to run in parallel with the locale-pinning format tests.
 */
class RecognitionLocaleTest {

    // =========================================================================
    // isSupported
    // =========================================================================

    @Test
    fun `plain english is supported`() {
        assertTrue(RecognitionLocale.isSupported(Locale.ENGLISH))
    }

    @Test
    fun `english regional variants are supported - the boundary is language-level`() {
        // The anchors are English WORDS; a region only changes formatting, never the vocabulary.
        assertTrue(RecognitionLocale.isSupported(Locale.US))
        assertTrue(RecognitionLocale.isSupported(Locale.UK))
        assertTrue(RecognitionLocale.isSupported(Locale.forLanguageTag("en-CA")))
    }

    @Test
    fun `non-english languages are not supported`() {
        assertFalse(RecognitionLocale.isSupported(Locale.forLanguageTag("es")))
        assertFalse(RecognitionLocale.isSupported(Locale.forLanguageTag("es-MX")))
        assertFalse(RecognitionLocale.isSupported(Locale.FRENCH))
        assertFalse(RecognitionLocale.isSupported(Locale.forLanguageTag("zh-Hans-CN")))
    }

    @Test
    fun `an undetermined language is treated as supported - no false positive`() {
        // Effectively unreachable on Android, but the bias is documented and pinned: a blank tag
        // is not evidence of a non-English device, so it must not fire a user-visible notice.
        assertTrue(RecognitionLocale.isSupported(Locale.ROOT))
    }

    // =========================================================================
    // languageOf — the only device-derived value any log line may carry
    // =========================================================================

    @Test
    fun `languageOf is a lowercase iso-639 code, region and script stripped`() {
        assertEquals("es", RecognitionLocale.languageOf(Locale.forLanguageTag("es-MX")))
        assertEquals("zh", RecognitionLocale.languageOf(Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals("en", RecognitionLocale.languageOf(Locale.US))
        assertEquals("", RecognitionLocale.languageOf(Locale.ROOT))
    }

    // =========================================================================
    // check — warn always, notify once
    // =========================================================================

    @Test
    fun `english yields Supported regardless of the notified flag`() {
        assertEquals(
            LocaleBoundaryCheck.Supported,
            RecognitionLocale.check(Locale.US, alreadyNotified = false),
        )
        assertEquals(
            LocaleBoundaryCheck.Supported,
            RecognitionLocale.check(Locale.US, alreadyNotified = true),
        )
    }

    @Test
    fun `a first non-english start warns and notifies`() {
        val outcome = RecognitionLocale.check(Locale.forLanguageTag("es-MX"), alreadyNotified = false)

        assertEquals(LocaleBoundaryCheck.Unsupported(language = "es", notifyUser = true), outcome)
    }

    @Test
    fun `a repeat non-english start still warns but does not notify again`() {
        val outcome = RecognitionLocale.check(Locale.forLanguageTag("es-MX"), alreadyNotified = true)

        // Still Unsupported — the WARN is a per-connect diagnostic; only the interruption is
        // once-per-install.
        assertEquals(LocaleBoundaryCheck.Unsupported(language = "es", notifyUser = false), outcome)
    }

    @Test
    fun `a second, different non-english language does not re-notify`() {
        // es -> fr on an install that already showed the notice: the copy is language-independent,
        // so there is nothing new to tell the dasher.
        val outcome = RecognitionLocale.check(Locale.FRENCH, alreadyNotified = true)

        assertEquals(LocaleBoundaryCheck.Unsupported(language = "fr", notifyUser = false), outcome)
    }

    @Test
    fun `the supported language constant is the one the markers are written in`() {
        assertEquals("en", RecognitionLocale.SUPPORTED_LANGUAGE)
    }
}
