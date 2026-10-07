package cloud.trotter.dashbuddy.domain.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * #159 — the store-identity SSOT ([StoreKeys]): the chain normalizer, the running-key extraction
 * hardening (D4 shapes), and the deterministic `storeKey` (F7 case/whitespace + parenthetical-wins).
 */
class StoreKeysTest {
    @Test fun `normalizedChain cases`() {
        listOf(
            "Target" to "target",
            "  Maple   Street Biscuit Company  " to "maple street biscuit company",
            "Target (02426)" to "target",
            "CAVA (Sonterra Village)" to "cava",
            "Maple Street Biscuit - Alamo Ranch" to "maple street biscuit",
            "SPROUTS FARMERS MARKET #161" to "sprouts farmers market",
            "H-E-B" to "h-e-b",
            "Roli - Poli - Alamo Ranch" to "roli - poli",
            "Maple Street Biscuit - Alamo Ranch" to "maple street biscuit",
            "Panda Express (Loop 410) - San Antonio" to "panda express",
            "foo|bar" to "foo bar",
            "(0164-0045)" to "(0164-0045)",
            "#161" to "#161",
        ).forEachIndexed { index, (input, expected) ->
            assertEquals("row $index input=<$input>", expected, StoreKeys.normalizedChain(input))
        }
    }

    @Test fun `extractRunningKey cases`() {
        listOf(
            "Target (02426)" to "02426",
            "H-E-B (799)" to "799",
            "Maple Street Biscuit - Alamo Ranch" to "Alamo Ranch",
            "SPROUTS FARMERS MARKET #161" to "161",
            "CAVA (Sonterra Village)" to "Sonterra Village",
            "Chipotle (Stone Oak)" to "Stone Oak",
            "Little Caesars (0164-0045)" to "0164-0045",
            "Foo - Bar (123)" to "123",
            "Chipotle" to null,
        ).forEachIndexed { index, (input, expected) ->
            assertEquals("row $index input=<$input>", expected, StoreKeys.extractRunningKey(input))
        }
    }

    @Test fun `normalizeRunningKey cases`() {
        listOf(
            "Alamo Ranch" to "alamo ranch",
            "  alamo   ranch " to "alamo ranch",
            "" to null,
            null to null,
            "@joescafe" to "joescafe",
            "@12125" to "12125",
            "@@x" to "x",
            "@" to null,
        ).forEachIndexed { index, (input, expected) ->
            assertEquals("row $index input=<$input>", expected, StoreKeys.normalizeRunningKey(input))
        }
    }

    @Test fun `addressRunningKey cases`() {
        listOf(
            "12125 Alamo Rnch Pkwy, San Antonio, TX 78240, USA" to "@12125",
            "7330 N Loop 1604 W, San Antonio, TX 78249" to "@7330",
            "1200 Bandera Rd, San Antonio, TX 78240, USA" to "@1200",
            "1200 Bandera Rd, San Antonio, TX 78249-2900, United States" to "@1200",
            "1200 Bandera Rd" to "@1200",
            "12125-A Alamo Ranch Pkwy" to null,
            "00000000000000000000 Main St" to null,
            "1234567 Main St" to null,
            "123456 Main St" to "@123456",
            "2500-2504 Broadway" to null,
            "12125B Alamo Ranch Pkwy" to null,
            "The Rim Shopping Center, San Antonio, TX" to null,
            "Alamo Ranch Pkwy" to null,
            null to null,
            "" to null,
            "   " to null,
        ).forEachIndexed { index, (input, expected) ->
            assertEquals("row $index input=<$input>", expected, StoreKeys.addressRunningKey(input))
        }
    }

    @Test fun `store key composition`() {
        data class Case(val platform: String, val chain: String, val runningKey: String?, val expected: String)
        listOf(
            Case("doordash", "target", "02426", "doordash|target|02426"),
            Case("doordash", "heb", null, "doordash|heb|"),
        ).forEach { (platform, chain, runningKey, expected) ->
            assertEquals(expected, expected, StoreKeys.storeKey(platform, chain, runningKey))
        }
    }

    @Test fun `normalization and platform boundaries cannot collide`() {
        listOf(
            Triple("pipe delimiter forgery (FIX 5c)",
                StoreKeys.storeKey("p", StoreKeys.normalizedChain("foo|bar"), StoreKeys.normalizeRunningKey(null)),
                StoreKeys.storeKey("p", StoreKeys.normalizedChain("foo"), StoreKeys.normalizeRunningKey("bar|"))),
            Triple("all qualifier fallback (FIX 5d)",
                StoreKeys.normalizedChain("(0164-0045)"), StoreKeys.normalizedChain("#161")),
            Triple("platform separation (F5)",
                StoreKeys.storeKey("doordash", "mcdonalds", "123"), StoreKeys.storeKey("uber", "mcdonalds", "123")),
        ).forEach { (name, first, second) -> assertNotEquals(name, first, second) }
    }
}
