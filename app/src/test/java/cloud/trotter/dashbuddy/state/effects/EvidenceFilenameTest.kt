package cloud.trotter.dashbuddy.state.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * #859 — a rule-declared filename template whose field parsed null must never reach the
 * gallery as a literal `{token}` (331 `Offer - {storeName}.png` files on the dev's device).
 */
class EvidenceFilenameTest {

    @Test
    fun `an unresolvable field degrades to the resolvable half - never a literal brace`() {
        val out = EvidenceFilename.sanitizePrefix("Offer - {storeName}")
        assertEquals("Offer", out)
        assertFalse("no literal template may survive into a filename", out.contains('{'))
        assertFalse(out.contains('}'))
    }

    @Test
    fun `a fully resolved prefix is passed through untouched`() {
        assertEquals("Offer - 12.50", EvidenceFilename.sanitizePrefix("Offer - 12.50"))
        assertEquals(
            "DashSummary - 87.50",
            EvidenceFilename.sanitizePrefix("DashSummary - 87.50"),
        )
    }

    @Test
    fun `every rule-declared prefix shape survives sanitization brace-free`() {
        // The shapes shipped today (both platforms), each with its field unresolved.
        val shipped = listOf(
            "Offer - {storeName}" to "Offer",
            "Offer - {payAmount}" to "Offer",
            "Delivery - {totalPay}" to "Delivery",
            "DeliveryBreakdown - {totalPay}" to "DeliveryBreakdown",
            "DashSummary - {totalEarnings}" to "DashSummary",
        )
        for ((template, expected) in shipped) {
            val out = EvidenceFilename.sanitizePrefix(template)
            assertEquals(template, expected, out)
            assertFalse(template, out.contains('{') || out.contains('}'))
        }
    }

    @Test
    fun `a mid-string token collapses without leaving double separators`() {
        assertEquals("Offer - Target", EvidenceFilename.sanitizePrefix("Offer - {pay} Target"))
        assertEquals("A B", EvidenceFilename.sanitizePrefix("A {x} B"))
    }

    @Test
    fun `a prefix that is nothing but a token falls back rather than saving a nameless file`() {
        assertEquals(EvidenceFilename.FALLBACK_PREFIX, EvidenceFilename.sanitizePrefix("{storeName}"))
        assertEquals(EvidenceFilename.FALLBACK_PREFIX, EvidenceFilename.sanitizePrefix("  -  "))
        assertEquals(EvidenceFilename.FALLBACK_PREFIX, EvidenceFilename.sanitizePrefix(""))
        assertEquals(EvidenceFilename.FALLBACK_PREFIX, EvidenceFilename.sanitizePrefix(null))
    }

    @Test
    fun `a degenerate empty token is stripped too`() {
        assertEquals("Offer", EvidenceFilename.sanitizePrefix("Offer - {}"))
    }

    // =========================================================================
    // #909 — the escaped-brace pattern must be behaviour-identical to the bare one
    // =========================================================================

    @Test
    fun `escaping the closing brace changed nothing about what is stripped (#909)`() {
        // The #909 fix escaped the closing brace (`\{\w*}` -> `\{\w*\}`) because ICU rejects the
        // bare form on-device. `\}` is a literal `}` on both engines, so this pins that the strip
        // semantics are byte-identical: every token shape matches, and every NON-token brace
        // shape is still left alone (an unterminated/space-bearing brace is not a template).
        assertEquals("Offer", EvidenceFilename.sanitizePrefix("Offer - {storeName}"))
        assertEquals("Offer", EvidenceFilename.sanitizePrefix("Offer - {}"))
        assertEquals("Offer", EvidenceFilename.sanitizePrefix("Offer - {total_pay9}"))
        // Not `\w*` between the braces → not an unresolved token → untouched.
        assertEquals("Offer - {a b}", EvidenceFilename.sanitizePrefix("Offer - {a b}"))
        assertEquals("Offer - {", EvidenceFilename.sanitizePrefix("Offer - {"))
        assertEquals("Offer - }", EvidenceFilename.sanitizePrefix("Offer - }"))
    }

    @Test
    fun `the sanitizer's own class initializer is reachable and does not throw (#909)`() {
        // The field failure was NOT in sanitizePrefix's logic — it was the `val UNRESOLVED_TOKEN`
        // initializer throwing PatternSyntaxException, which becomes an ExceptionInInitializerError
        // and (pre-#909 layer 2) killed the whole side-effect engine. Touching the object at all is
        // the thing that used to detonate. This can only ever be green on the host JVM — the
        // divergence guard is IcuRegexGuardTest.
        assertEquals("Rule", EvidenceFilename.FALLBACK_PREFIX)
    }
}
