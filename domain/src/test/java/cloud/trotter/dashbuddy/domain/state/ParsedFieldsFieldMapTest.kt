package cloud.trotter.dashbuddy.domain.state

import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.reflect.full.primaryConstructor

/**
 * #434 — toFieldMap() replaced EffectMap's Java-reflection gate extraction.
 * The hand-written maps must never drift from the constructors: a field
 * added to a subtype but not its map would silently vanish from `onlyIf`
 * gate evaluation. Reflection is fine HERE (test-only, never minified).
 */
class ParsedFieldsFieldMapTest {

    private val samples: List<ParsedFields> = listOf(
        ParsedFields.IdleFields(),
        ParsedFields.OfferFields(parsedOffer = ParsedOffer(offerHash = "h", payAmount = 1.0)),
        ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION),
        ParsedFields.PostTaskFields(totalPay = 1.0),
        ParsedFields.SessionEndedFields(totalEarnings = 1.0),
        ParsedFields.PausedFields(),
        ParsedFields.TimelineFields(),
        ParsedFields.RatingsFields(),
        ParsedFields.SensitiveFields(),
        ParsedFields.NoiseFields(),
        ParsedFields.ClickFields(intent = "x"),
        ParsedFields.NotificationFields(intent = "x"),
    )

    private fun expectedKeys(instance: ParsedFields): Set<String> =
        instance::class.primaryConstructor!!.parameters
            .mapNotNull { it.name }
            .filter { it != "activity" }
            .toSet()

    @Test
    fun `every subtype's field map covers exactly its constructor properties minus activity`() {
        for (sample in samples) {
            assertEquals(
                "toFieldMap drifted from the constructor for ${sample::class.simpleName}",
                expectedKeys(sample),
                sample.toFieldMap().keys,
            )
        }
    }

    @Test
    fun `None maps to empty`() {
        assertEquals(emptyMap<String, Any?>(), ParsedFields.None.toFieldMap())
    }

    @Test
    fun `field map values are the instance's values`() {
        val parsed = ParsedFields.PostTaskFields(totalPay = 12.5, isExpanded = false)
        val map = parsed.toFieldMap()
        assertEquals(12.5, map["totalPay"])
        assertEquals(false, map["isExpanded"])
        assertEquals(null, map["sessionEarnings"])
    }

    @Test
    fun `sealed hierarchy is fully sampled - update samples when adding a subtype`() {
        assertEquals(
            "new ParsedFields subtype? add it to [samples] above",
            samples.size + 1, // +1 = the None object, asserted separately
            ParsedFields::class.sealedSubclasses.size,
        )
    }
}
