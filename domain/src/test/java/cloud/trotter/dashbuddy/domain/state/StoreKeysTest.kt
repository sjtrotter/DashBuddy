package cloud.trotter.dashbuddy.domain.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #159 — the store-identity SSOT ([StoreKeys]): the chain normalizer, the running-key extraction
 * hardening (D4 shapes), and the deterministic `storeKey` (F7 case/whitespace + parenthetical-wins).
 */
class StoreKeysTest {

    // ── normalizedChain ─────────────────────────────────────────────────

    @Test
    fun `normalizedChain lowercases and collapses whitespace`() {
        assertEquals("target", StoreKeys.normalizedChain("Target"))
        assertEquals("maple street biscuit company", StoreKeys.normalizedChain("  Maple   Street Biscuit Company  "))
    }

    @Test
    fun `normalizedChain strips the trailing parenthetical qualifier`() {
        assertEquals("target", StoreKeys.normalizedChain("Target (02426)"))
        assertEquals("cava", StoreKeys.normalizedChain("CAVA (Sonterra Village)"))
    }

    @Test
    fun `normalizedChain strips the trailing dash-location suffix`() {
        assertEquals("maple street biscuit", StoreKeys.normalizedChain("Maple Street Biscuit - Alamo Ranch"))
    }

    @Test
    fun `normalizedChain strips the trailing franchise number`() {
        assertEquals("sprouts farmers market", StoreKeys.normalizedChain("SPROUTS FARMERS MARKET #161"))
    }

    @Test
    fun `normalizedChain keeps internal hyphens`() {
        assertEquals("h-e-b", StoreKeys.normalizedChain("H-E-B"))
    }

    @Test
    fun `normalizedChain strips only the LAST dash suffix, keeping a brand's internal dash (FIX 5a)`() {
        // A brand whose name contains " - " keeps its brand core when a location is appended — the strip
        // is trailing-only (last occurrence), never a split at the FIRST " - ".
        assertEquals("roli - poli", StoreKeys.normalizedChain("Roli - Poli - Alamo Ranch"))
        // The single-dash location case still reduces to the brand.
        assertEquals("maple street biscuit", StoreKeys.normalizedChain("Maple Street Biscuit - Alamo Ranch"))
    }

    @Test
    fun `normalizedChain strips stacked qualifiers to stability (FIX 5b)`() {
        assertEquals("panda express", StoreKeys.normalizedChain("Panda Express (Loop 410) - San Antonio"))
    }

    @Test
    fun `normalizedChain strips the pipe delimiter so a merchant string cannot forge a key segment (FIX 5c)`() {
        assertEquals("foo bar", StoreKeys.normalizedChain("foo|bar"))
        // Forgery is impossible: a chain carrying the delimiter can't collide with a chain+key split.
        val forged = StoreKeys.storeKey("p", StoreKeys.normalizedChain("foo|bar"), StoreKeys.normalizeRunningKey(null))
        val real = StoreKeys.storeKey("p", StoreKeys.normalizedChain("foo"), StoreKeys.normalizeRunningKey("bar|"))
        assert(forged != real) { "delimiter-stripped chains/keys cannot forge a colliding storeKey" }
    }

    @Test
    fun `normalizedChain falls back to the unstripped form for an all-qualifier input (FIX 5d)`() {
        // An all-qualifier name would strip to empty; the fallback keeps distinct names distinct instead
        // of merging them into an empty `platform||` bucket.
        assertEquals("(0164-0045)", StoreKeys.normalizedChain("(0164-0045)"))
        assertEquals("#161", StoreKeys.normalizedChain("#161"))
        assert(StoreKeys.normalizedChain("(0164-0045)") != StoreKeys.normalizedChain("#161"))
    }

    // ── extractRunningKey (D4 hardening + F7 precedence) ────────────────

    @Test
    fun `extractRunningKey pulls a parenthetical digit code`() {
        assertEquals("02426", StoreKeys.extractRunningKey("Target (02426)"))
        assertEquals("799", StoreKeys.extractRunningKey("H-E-B (799)"))
    }

    @Test
    fun `extractRunningKey pulls a dash-area suffix`() {
        assertEquals("Alamo Ranch", StoreKeys.extractRunningKey("Maple Street Biscuit - Alamo Ranch"))
    }

    @Test
    fun `extractRunningKey pulls a franchise number suffix`() {
        assertEquals("161", StoreKeys.extractRunningKey("SPROUTS FARMERS MARKET #161"))
    }

    @Test
    fun `extractRunningKey pulls a place-name parenthetical`() {
        assertEquals("Sonterra Village", StoreKeys.extractRunningKey("CAVA (Sonterra Village)"))
        assertEquals("Stone Oak", StoreKeys.extractRunningKey("Chipotle (Stone Oak)"))
    }

    @Test
    fun `extractRunningKey pulls a hyphenated store code`() {
        assertEquals("0164-0045", StoreKeys.extractRunningKey("Little Caesars (0164-0045)"))
    }

    @Test
    fun `extractRunningKey precedence — parenthetical wins over dash suffix (F7)`() {
        assertEquals("123", StoreKeys.extractRunningKey("Foo - Bar (123)"))
    }

    @Test
    fun `extractRunningKey is null when no discriminator`() {
        assertNull(StoreKeys.extractRunningKey("Chipotle"))
    }

    // ── runningKey normalization + storeKey (F7) ────────────────────────

    @Test
    fun `normalizeRunningKey lowercases and collapses so casing does not fork a store`() {
        assertEquals("alamo ranch", StoreKeys.normalizeRunningKey("Alamo Ranch"))
        assertEquals("alamo ranch", StoreKeys.normalizeRunningKey("  alamo   ranch "))
        assertNull(StoreKeys.normalizeRunningKey(""))
        assertNull(StoreKeys.normalizeRunningKey(null))
    }

    @Test
    fun `storeKey composes platform, chain, and running key with empty segment while unknown`() {
        assertEquals("doordash|target|02426", StoreKeys.storeKey("doordash", "target", "02426"))
        assertEquals("doordash|heb|", StoreKeys.storeKey("doordash", "heb", null))
    }

    @Test
    fun `storeKey embeds platform so a cross-platform chain does not collide (F5)`() {
        val dd = StoreKeys.storeKey("doordash", "mcdonalds", "123")
        val uber = StoreKeys.storeKey("uber", "mcdonalds", "123")
        assert(dd != uber)
    }
}
