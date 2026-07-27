package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.notification.NotifTextField
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #889 — the engine-level short-token floor on the `[redacted:<4hex>]` distinctness
 * suffix. The suffix addresses only 65 536 buckets, so on a token whose own plaintext
 * space is smaller than that it is an INVERSION ORACLE, not a one-way marker: the
 * fielded `dropoff_pin_entry` envelope masked a single-glyph keypad echo as
 * `[redacted:5fec]`, recoverable in ~100 guesses.
 *
 * The floor is rule-INDEPENDENT (no rule can opt out by forgetting to declare
 * `plainMask`, #795) and scoped to NON-normalized entries — see
 * [CompiledRedact.MIN_HASH_MASK_TOKEN_LENGTH] and the exemption rationale on
 * `hashSuffixIsSafe`.
 */
class ShortTokenMaskFloorTest {

    private fun hashPrefix(token: String) = sha256OrNull(token)!!.take(4)

    private fun notif(title: String) = RawNotificationData(
        title = title, text = null, bigText = null, tickerText = null,
        packageName = "com.doordash.driverapp", postTime = 0L, isClearable = true,
    )

    private val hashShape = Regex("""\[redacted:[0-9a-f]{4}]""")

    // =====================================================================
    // The floor — below it, plain; at/above it, the distinctness suffix.
    // =====================================================================

    @Test
    fun `a single-glyph token masks plain — the fielded keypad-echo defect`() {
        val masked = CompiledRedact.mask("5", emptyList())
        assertEquals(CompiledRedact.REDACTED, masked)
        assertFalse("no invertible suffix", hashShape.containsMatchIn(masked!!))
    }

    @Test
    fun `every token below the floor masks plain (1, 2 and 3 chars, any alphabet)`() {
        listOf("5", "#", "42", "1a", "abc", "12B", "#7C").forEach { token ->
            val masked = CompiledRedact.mask(token, emptyList())
            assertEquals("'$token' is below the floor → plain", CompiledRedact.REDACTED, masked)
        }
    }

    @Test
    fun `a token at the floor keeps the distinctness suffix (4 chars)`() {
        // 4 chars over an alnum alphabet is ~1.5e7 values against 65 536 buckets — no
        // longer an inversion oracle, and every genuine name/address token is ≥ 4.
        assertEquals("[redacted:${hashPrefix("Jane")}]", CompiledRedact.mask("Jane", emptyList()))
        assertEquals(4, CompiledRedact.MIN_HASH_MASK_TOKEN_LENGTH)
    }

    @Test
    fun `long tokens are untouched — per-customer distinctness is preserved`() {
        val a = CompiledRedact.mask("123 Main St", emptyList())
        val b = CompiledRedact.mask("456 Other Ave", emptyList())
        assertEquals("[redacted:${hashPrefix("123 Main St")}]", a)
        assertNotEquals("two addresses must still redact distinctly", a, b)
    }

    @Test
    fun `the floor measures the STRIPPED token, not the whole node text`() {
        // "Apt 5" is 5 chars, but only "5" is hashed — the prefix is kept verbatim, so
        // the floor must be applied to the token that actually reaches sha256.
        assertEquals("Apt ${CompiledRedact.REDACTED}", CompiledRedact.mask("Apt 5", listOf("Apt ")))
        // …and the same prefix over a long enough unit still earns its suffix.
        assertEquals(
            "Apt [redacted:${hashPrefix("512B")}]",
            CompiledRedact.mask("Apt 512B", listOf("Apt ")),
        )
    }

    @Test
    fun `whitespace padding does not lift a token over the floor`() {
        // The token is trimmed before hashing, so padding must not buy a suffix.
        assertEquals(CompiledRedact.REDACTED, CompiledRedact.mask("   5   ", emptyList()))
    }

    // =====================================================================
    // The `normalize: customerName` exemption (#733 invariant preserved).
    // =====================================================================

    @Test
    fun `a normalize customerName entry is EXEMPT — a short name keeps its mask hash`() {
        // "Bo" canonicalizes to the 2-char key `bo`. The floor must NOT fire: the mask
        // hex has to stay equal to the first 4 hex of the `customerNameHash` the parse
        // persists for this customer (#623), and a name-shaped token is not a
        // glyph-shaped secret — the app keeps that hash by design either way.
        val masked = CompiledRedact.mask("Bo", emptyList(), RedactNormalize.CUSTOMER_NAME)
        assertEquals("[redacted:${hashPrefix("bo")}]", masked)
    }

    @Test
    fun `the exemption keeps short-name customers DISTINCT from each other`() {
        val bo = CompiledRedact.mask("Bo", emptyList(), RedactNormalize.CUSTOMER_NAME)
        val al = CompiledRedact.mask("Al", emptyList(), RedactNormalize.CUSTOMER_NAME)
        assertNotEquals("two short-named customers must not collapse", bo, al)
        assertTrue(hashShape.containsMatchIn(bo!!))
    }

    @Test
    fun `the exemption keeps one short-named customer stable across surface forms`() {
        // The #733 contract: every surface form of one customer masks identically.
        val short = CompiledRedact.mask("Bo S", emptyList(), RedactNormalize.CUSTOMER_NAME)
        val full = CompiledRedact.mask("Bo Smith", emptyList(), RedactNormalize.CUSTOMER_NAME)
        val prefixed = CompiledRedact.mask(
            "Deliver to Bo Smith",
            listOf("Deliver to "),
            RedactNormalize.CUSTOMER_NAME,
        )
        assertEquals(short, full)
        assertEquals("Deliver to $full", prefixed)
    }

    @Test
    fun `a blank normalized token still fails closed to the plain constant`() {
        // The exemption skips the LENGTH floor, never the #362 fail-closed arm.
        assertEquals(
            CompiledRedact.REDACTED,
            CompiledRedact.mask("  ", emptyList(), RedactNormalize.CUSTOMER_NAME),
        )
    }

    // =====================================================================
    // Composition with #795 — the floor bounds LENGTH, `plainMask` bounds ALPHABET.
    // =====================================================================

    @Test
    fun `795 stays load-bearing — a 4-digit PIN is ABOVE the floor and still needs plainMask`() {
        // 10^4 < 65 536, so the suffix stays reversible over a bounded-alphabet token
        // that clears the length floor. The engine backstop does not subsume the rule
        // declaration; the rule must still declare it.
        assertEquals("[redacted:${hashPrefix("9315")}]", CompiledRedact.mask("9315", emptyList()))
        assertEquals(
            CompiledRedact.REDACTED,
            CompiledRedact.mask("9315", emptyList(), normalize = null, plainMask = true),
        )
    }

    // =====================================================================
    // The notification path reuses the SAME mint site (SSOT), so it inherits
    // the floor with no second implementation.
    // =====================================================================

    @Test
    fun `the notification field masker inherits the floor`() {
        val redact = CompiledNotifRedact(
            mapOf(NotifTextField.TITLE to NotifFieldMask.Whole()),
        )
        val short = redact.apply(notif("Jo"))
        assertEquals(CompiledRedact.REDACTED, short.title)

        val long = redact.apply(notif("Jo Smith"))
        assertTrue("a full-length title keeps its suffix", hashShape.containsMatchIn(long.title!!))
    }
}
