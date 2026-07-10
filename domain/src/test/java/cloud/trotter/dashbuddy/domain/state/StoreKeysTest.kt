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
