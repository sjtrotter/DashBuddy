package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1141 — arrival chrome anchors restaurant pickup arrival; survey-only renders stay flowless. */
class PickupWaitArrivalRecognitionTest {

    private val rules = TestRulesetFactory.screenRuleset

    private fun load(path: String): UiNode =
        TestResourceLoader.loadNode(File("src/test/resources/snapshots/$path"))

    private val fixtures = File("src/test/resources/snapshots/pickup_wait_survey")
        .listFiles { file -> file.isFile && file.extension == "json" }
        ?.sortedBy { it.name }
        ?: error("Cannot find pickup_wait_survey fixtures")

    private val arrivalFixtures = fixtures.filter {
        it.readText().contains("Merchant notified of your arrival")
    }
    private val surveyOnlyFixtures = fixtures - arrivalFixtures.toSet()

    @Test
    fun `every wait screen with arrival chrome anchors pickup arrival (#1141)`() {
        assertTrue(
            "the fielded September 27 frame must be in the arrival set",
            arrivalFixtures.any {
                it.name == "2026-09-27_11-50-25-492__doordash__accessibility.window__pickup_wait_survey__70a359.json"
            },
        )
        arrivalFixtures.forEach { fixture ->
            val r = rules.matchFirst(load("pickup_wait_survey/${fixture.name}"))
            assertEquals(fixture.name, "doordash.screen.pickup_wait_survey", r?.ruleId)
            assertEquals(fixture.name, "pickup_wait_survey", r?.intent)
            assertEquals(fixture.name, Flow.TaskPickupArrived, r?.flow)
        }
    }

    @Test
    fun `every wait survey without arrival chrome stays flowless (#1141)`() {
        assertTrue(
            "the GoPuff and June 12 survey-only frames must remain in the flowless set",
            surveyOnlyFixtures.map { it.name }.containsAll(
                listOf(
                    "gopuff_wait_survey.json",
                    "2026-06-12__doordash__pickup_wait_survey__d90d0f.json",
                ),
            ),
        )
        surveyOnlyFixtures.forEach { fixture ->
            val r = rules.matchFirst(load("pickup_wait_survey/${fixture.name}"))
            assertEquals(fixture.name, "doordash.screen.pickup_wait_survey", r?.ruleId)
            assertEquals(fixture.name, "pickup_wait_survey", r?.intent)
            assertNull("${fixture.name}: survey-only renders must not change pickup phase", r?.flow)
        }
    }
}
