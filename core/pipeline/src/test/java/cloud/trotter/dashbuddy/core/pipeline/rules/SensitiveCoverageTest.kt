package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #432 — every platform that ships screen rules must ship a sensitive rule;
 * the matcher-layer block is only as real as its coverage.
 */
class SensitiveCoverageTest {

    private fun rule(id: String, shape: String? = null) = CompiledRule<UiNode>(
        id = id,
        priority = id.hashCode() and 0xFFFF,
        branches = listOf(CompiledBranch(intent = "x", shape = shape)),
    )

    @Test
    fun `platform without a sensitive-shaped rule is reported`() {
        val screens = listOf(
            rule("doordash.screen.sensitive.known", shape = "sensitive"),
            rule("doordash.screen.idle", shape = "idle"),
            rule("uber.screen.offer", shape = "offer"), // no sensitive rule!
        )
        assertEquals(setOf(Platform.Uber), missingSensitivePlatforms(screens))
    }

    @Test
    fun `all covered - nothing reported`() {
        val screens = listOf(
            rule("doordash.screen.sensitive.known", shape = "sensitive"),
            rule("uber.screen.sensitive.known", shape = "sensitive"),
            rule("uber.screen.offer", shape = "offer"),
        )
        assertTrue(missingSensitivePlatforms(screens).isEmpty())
    }

    @Test
    fun `unknown-platform rules are ignored`() {
        val screens = listOf(rule("mystery.screen.thing", shape = "idle"))
        assertTrue(missingSensitivePlatforms(screens).isEmpty())
    }
}
