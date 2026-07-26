package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.pipeline.EffectVerb
import cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #427 ({parsedHash}) + #859 ({presentationHash}) — the reserved dedupeKey tokens.
 *
 * The pair is what lets a ruleset choose its dedupe granularity: CONTENT identity (one key
 * per distinct parse) or PRESENTATION identity (one key for as long as the same physical
 * showing is on screen, however much it re-quotes itself).
 */
class DedupeTokensTest {

    private fun offer(
        hash: String,
        presentation: String? = null,
    ) = ParsedFields.OfferFields(
        parsedOffer = ParsedOffer(offerHash = hash, presentationKey = presentation),
    )

    private fun effect(key: String) = RequestedEffect(
        verb = EffectVerb.SCREENSHOT,
        dedupeKey = key,
        ruleId = "test.screen.offer",
    )

    private fun resolveKey(key: String, parsed: ParsedFields): String? =
        DedupeTokens.resolve(listOf(effect(key)), parsed).single().dedupeKey

    @Test
    fun `a re-quoted offer keeps ONE presentation key while its content key churns`() {
        // Same physical presentation, three live re-quotes: store/order subset identical,
        // economics (and so offerHash) different on every frame.
        val frames = listOf(
            offer(hash = "h-quote-1", presentation = "pres-A"),
            offer(hash = "h-quote-2", presentation = "pres-A"),
            offer(hash = "h-quote-3", presentation = "pres-A"),
        )

        val presentationKeys = frames.map { resolveKey("offer-ss-{presentationHash}", it) }.toSet()
        val contentKeys = frames.map { resolveKey("offer-ss-{parsedHash}", it) }.toSet()

        assertEquals("one presentation ⇒ one dedupe key", 1, presentationKeys.size)
        assertEquals("the churn the effect used to re-fire on", 3, contentKeys.size)
    }

    @Test
    fun `a different presentation resolves to a different key`() {
        assertNotEquals(
            resolveKey("offer-ss-{presentationHash}", offer("h-1", "pres-A")),
            resolveKey("offer-ss-{presentationHash}", offer("h-2", "pres-B")),
        )
    }

    @Test
    fun `a null presentation key fails closed to the per-quote identity`() {
        // #830's fail-closed rule: no stable subset ⇒ no presentation identity ⇒ behave
        // exactly as {parsedHash} does, never as a constant shared across offers.
        val a = offer(hash = "h-1", presentation = null)
        val b = offer(hash = "h-2", presentation = null)

        assertEquals(resolveKey("offer-ss-{presentationHash}", a), resolveKey("offer-ss-{parsedHash}", a))
        assertNotEquals(
            resolveKey("offer-ss-{presentationHash}", a),
            resolveKey("offer-ss-{presentationHash}", b),
        )
    }

    @Test
    fun `resolution leaves no literal token behind and keeps the surrounding key`() {
        val resolved = resolveKey("offer-ss-{presentationHash}", offer("h", "pres-A"))!!
        assertTrue(resolved.startsWith("offer-ss-"))
        assertTrue("the token must be replaced, not kept", !resolved.contains("{"))
    }

    @Test
    fun `both tokens resolve in one key`() {
        val resolved = resolveKey("k-{parsedHash}-{presentationHash}", offer("h", "pres-A"))!!
        assertTrue(!resolved.contains("{"))
    }

    @Test
    fun `a key with no reserved token is returned untouched, same list instance`() {
        val effects = listOf(effect("offer-ss-static"))
        assertSame(effects, DedupeTokens.resolve(effects, offer("h", "pres-A")))
    }

    @Test
    fun `every reserved name matches its token, so the lint skips exactly what resolves`() {
        assertEquals(setOf("parsedHash", "presentationHash"), DedupeTokens.RESERVED_FIELD_NAMES)
        assertEquals("{parsedHash}", DedupeTokens.PARSED_HASH)
        assertEquals("{presentationHash}", DedupeTokens.PRESENTATION_HASH)
    }

    @Test
    fun `a non-offer parse resolves the presentation token to its content identity`() {
        // The default: shapes with no separate presentation identity behave as before.
        val parsed = ParsedFields.None
        assertEquals(
            resolveKey("k-{parsedHash}", parsed),
            resolveKey("k-{presentationHash}", parsed),
        )
    }
}
