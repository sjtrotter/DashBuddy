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

    /** Loaded once through the corpus loader (the #941 loud-fail semantics), name → tree. */
    private val loaded: Map<String, UiNode> = TestResourceLoader.loadSnapshots("snapshots/pickup_wait_survey")
        .associate { (name, node, _) -> name to node }
    private val fixtures: List<File> = loaded.keys.sorted().map { File(it) }
    init { require(loaded.isNotEmpty()) { "Cannot find pickup_wait_survey fixtures" } }

    /** Partition by DECODED node text (what the rule matches on), not the serialized file bytes. */
    private fun UiNode.allTexts(): List<String> = buildList {
        fun walk(n: UiNode) { n.text?.let(::add); n.contentDescription?.let(::add); n.children.forEach(::walk) }
        walk(this@allTexts)
    }
    private val arrivalFixtures = fixtures.filter { f ->
        loaded.getValue(f.name).allTexts().any { it.contains("Merchant notified of your arrival") }
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
            val r = rules.matchFirst(loaded.getValue(fixture.name))
            assertEquals(fixture.name, "doordash.screen.pickup_wait_survey", r?.ruleId)
            assertEquals(fixture.name, "pickup_wait_survey", r?.intent)
            assertEquals(fixture.name, Flow.TaskPickupArrived, r?.flow)
            assertEquals(fixture.name, "PICKUP", r?.fields?.get("phase"))
        }
    }

    // Astra review r1 of PR #1220 — the anchor is the curbside card's view id, so quoted chrome in a
    // customer note or a chat line cannot steal a frame into a false arrival, and a combined
    // unassigned-confirmation render keeps its abandonment flow.
    @Test
    fun `quoted arrival chrome on a dropoff sheet does not become a pickup arrival (#1220 r1)`() {
        val sheet = load("dropoff_workflow_sheet/" + File("src/test/resources/snapshots/dropoff_workflow_sheet").list()!!.first { it.endsWith(".json") })
        val quoted = withNote(sheet, "Merchant notified of your arrival. Stay in your car.")
        val r = rules.matchFirst(quoted)
        assertEquals("doordash.screen.dropoff_workflow_sheet", r?.ruleId)
    }

    @Test
    fun `an unassigned confirmation that still shows the curbside labels keeps task-unassigned (#1220 r1)`() {
        val unassigned = load("pickup_unassigned_confirmation/" + File("src/test/resources/snapshots/pickup_unassigned_confirmation").list()!!.first { it.endsWith(".json") })
        val combined = withCurbsideCard(unassigned)
        val r = rules.matchFirst(combined)
        assertEquals("doordash.screen.pickup_unassigned_confirmation", r?.ruleId)
        assertEquals(Flow.TaskUnassigned, r?.flow)
    }

    @Test
    fun `the curbside card id without the pickup host or the text is not enough (#1220 r1)`() {
        val idOnly = UiNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                UiNode(viewIdResourceName = "com.doordash.driverapp:id/curbside_pickup_instructions_component_view"),
                UiNode(text = "Merchant notified of your arrival"),
            ),
        ).restoreParents()
        val r = rules.matchFirst(idOnly)
        assertTrue("no pickup_survey_view host ⇒ not the arrival branch", r?.flow != Flow.TaskPickupArrived)
    }

    private fun withNote(tree: UiNode, note: String): UiNode =
        tree.copy(children = tree.children + UiNode(className = "android.widget.TextView", text = note)).restoreParents()

    private fun withCurbsideCard(tree: UiNode): UiNode = tree.copy(
        children = tree.children + UiNode(
            viewIdResourceName = "com.doordash.driverapp:id/curbside_pickup_instructions_component_view",
            children = listOf(UiNode(text = "Merchant notified of your arrival"), UiNode(text = "Stay in your car")),
        ) + UiNode(viewIdResourceName = "com.doordash.driverapp:id/pickup_survey_view"),
    ).restoreParents()

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
            val r = rules.matchFirst(loaded.getValue(fixture.name))
            assertEquals(fixture.name, "doordash.screen.pickup_wait_survey", r?.ruleId)
            assertEquals(fixture.name, "pickup_wait_survey", r?.intent)
            assertNull("${fixture.name}: survey-only renders must not change pickup phase", r?.flow)
        }
    }
}
