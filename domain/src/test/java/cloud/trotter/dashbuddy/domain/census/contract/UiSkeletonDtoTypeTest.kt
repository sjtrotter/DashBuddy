package cloud.trotter.census.contract

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.reflect.full.memberProperties
import kotlin.reflect.typeOf

/**
 * ADR-0011 §1 / §7(a) — the skeleton TYPE has no plaintext slot. The allowlist is enforced on BOTH
 * the wire (serial descriptors: element names + kinds) and the Kotlin type (reflection), so a new
 * `String` property — or a bounds field — fails here before it can ever carry a value.
 */
@OptIn(ExperimentalSerializationApi::class)
class UiSkeletonDtoTypeTest {

    private val nodeStrings = setOf("class", "id")
    private val slotStrings = setOf("h", "kind")
    private val envelopeStrings = setOf(
        // ReplayMetadata's own names
        "platformAppVersion", "appVersion", "rulesetReleaseTag",
        // census-own
        "schemaId", "fingerprint", "platform", "day",
    )
    private val envelopeInts = setOf(
        "engineVersion", "rulesetFormatVersion", // ReplayMetadata's own names
        "filterRev", "hashDomain", // census-own
    )

    private fun SerialDescriptor.stringElements(): Set<String> =
        elementNames.filterIndexed { i, _ -> getElementDescriptor(i).kind == PrimitiveKind.STRING }.toSet()

    private fun SerialDescriptor.intElements(): Set<String> =
        elementNames.filterIndexed { i, _ -> getElementDescriptor(i).kind == PrimitiveKind.INT }.toSet()

    @Test
    fun `wire - every string element is on the ADR allowlist`() {
        assertEquals(nodeStrings, UiSkeletonNodeDto.serializer().descriptor.stringElements())
        assertEquals(slotStrings, TextSlot.serializer().descriptor.stringElements())
        assertEquals(envelopeStrings, UiSkeletonDto.serializer().descriptor.stringElements())
        assertEquals(envelopeInts, UiSkeletonDto.serializer().descriptor.intElements())
    }

    @Test
    fun `wire - the element sets are exactly the ADR's, nothing else`() {
        assertEquals(
            setOf("class", "id", "isClickable", "isEnabled", "isChecked", "text", "children"),
            UiSkeletonNodeDto.serializer().descriptor.elementNames.toSet(),
        )
        assertEquals(slotStrings, TextSlot.serializer().descriptor.elementNames.toSet())
        assertEquals(
            envelopeStrings + envelopeInts + setOf("windowTitle", "root"),
            UiSkeletonDto.serializer().descriptor.elementNames.toSet(),
        )
    }

    @Test
    fun `wire - the node text map is string-keyed to TextSlot values only`() {
        val node = UiSkeletonNodeDto.serializer().descriptor
        val text = node.getElementDescriptor(node.getElementIndex("text"))
        assertEquals(StructureKind.MAP, text.kind)
        val (key, value) = text.elementDescriptors.toList()
        assertEquals(PrimitiveKind.STRING, key.kind)
        assertEquals(TextSlot.serializer().descriptor.serialName, value.serialName)
    }

    @Test
    fun `wire - no bounds anywhere in the tree`() {
        val seen = mutableSetOf<String>()
        fun walk(d: SerialDescriptor) {
            if (!seen.add(d.serialName)) return
            d.elementNames.forEach { name ->
                assertFalse("bounds must not leave the phone (ADR-0011 §1): ${d.serialName}.$name",
                    name.contains("bound", ignoreCase = true) || name in setOf("left", "top", "right", "bottom", "x", "y", "width", "height"))
            }
            d.elementDescriptors.forEach { walk(it) }
        }
        walk(UiSkeletonDto.serializer().descriptor)
    }

    @Test
    fun `type - the only String-typed properties are the allowlist`() {
        val stringTypes = setOf(typeOf<String>(), typeOf<String?>())
        fun stringProps(k: kotlin.reflect.KClass<*>) =
            k.memberProperties.filter { it.returnType in stringTypes }.map { it.name }.toSet()
        // Kotlin property names (className serializes as "class").
        assertEquals(setOf("className", "id"), stringProps(UiSkeletonNodeDto::class))
        assertEquals(slotStrings, stringProps(TextSlot::class))
        assertEquals(envelopeStrings, stringProps(UiSkeletonDto::class))
    }

    @Test
    fun `a TextSlot enforces h iff words 1 to 8`() {
        val h = CensusHash.of("Accept")!!
        TextSlot(h = h, kind = "words:1")
        TextSlot(kind = "mixed")
        TextSlot(kind = "words:8+")
        listOf(
            { TextSlot(kind = "words:1") },
            { TextSlot(h = h, kind = "mixed") },
            { TextSlot(h = h, kind = "withheld") },
            { TextSlot(h = h, kind = "words:8+") },
            { TextSlot(h = "Accept", kind = "words:1") },
            { TextSlot(kind = "plaintext") },
        ).forEachIndexed { i, make ->
            try {
                make(); fail("case $i must be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `strict decoding rejects an unknown field and a malformed slot`() {
        val item = sampleItem()
        val json = SkeletonSchema.serialize(item)
        assertEquals(item, SkeletonSchema.deserialize(json))
        assertEquals(json, SkeletonSchema.serialize(SkeletonSchema.deserialize(json)))
        val withExtra = json.replaceFirst("{", "{\"installId\":\"abc\",")
        assertThrows { SkeletonSchema.deserialize(withExtra) }
        val withPlain = json.replace("\"kind\":\"mixed\"", "\"kind\":\"mixed\",\"value\":\"Jane\"")
        assertTrue(withPlain != json)
        assertThrows { SkeletonSchema.deserialize(withPlain) }
        val withBadKind = json.replace("\"kind\":\"mixed\"", "\"kind\":\"Jane S\"")
        assertThrows { SkeletonSchema.deserialize(withBadKind) }
    }

    @Test
    fun `envelope validation rejects free text in the constrained metadata slots`() {
        assertThrows { sampleItem().copy(day = "2026-09-30T12:00") }
        assertThrows { sampleItem().copy(platform = "Jane Smith") }
        assertThrows { sampleItem().copy(fingerprint = "abc") }
        assertThrows { sampleItem().copy(hashDomain = 2) }
        assertThrows { sampleItem().copy(schemaId = "uinode.v1") }
        assertThrows { sampleItem().copy(appVersion = "x".repeat(65)) }
        // Review EE3: a stamp must be well-formed, and the day must be a real month/day.
        assertThrows { sampleItem().copy(platformAppVersion = "8.97\uD800") }
        assertThrows { sampleItem().copy(rulesetReleaseTag = "tag\u0000") }
        assertThrows { sampleItem().copy(day = "2026-99-99") }
        assertThrows { sampleItem().copy(day = "2026-13-01") }
        assertThrows { sampleItem().copy(day = "2026-12-32") }
        assertThrows { sampleItem().copy(day = "2026-00-10") }
        assertThrows { sampleItem().copy(day = "2026-02-31") }
        assertThrows { sampleItem().copy(day = "2026-04-31") }
        sampleItem().copy(day = "2028-02-29")
        sampleItem().copy(day = "2026-12-31")
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block(); fail("expected a rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun sampleItem(): UiSkeletonDto {
        val root = UiSkeletonNodeDto(
            className = "android.widget.TextView",
            id = "com.example:id/price",
            text = mapOf("text" to TextSlot(kind = "mixed")),
        )
        return UiSkeletonDto(
            schemaId = SkeletonSchema.SCHEMA_ID,
            hashDomain = CensusHash.HASH_DOMAIN,
            filterRev = 1,
            fingerprint = CensusFingerprint.of(root)!!,
            platform = "doordash",
            appVersion = "1.0+abc",
            engineVersion = 3,
            day = "2026-09-30",
            windowTitle = TextSlot(h = CensusHash.of("Offer")!!, kind = "words:1"),
            root = root,
        )
    }

    @Test
    fun `a text-map key is a wire-key shape, never free text (review UU1)`() {
        cloud.trotter.dashbuddy.domain.model.accessibility.UiNodeTextField.entries.forEach {
            UiSkeletonNodeDto(text = mapOf(it.wire to TextSlot(kind = "mixed")))
        }
        UiSkeletonNodeDto(text = mapOf("futureField" to TextSlot(kind = "mixed"))) // a new enum entry stays accepted
        assertThrows { UiSkeletonNodeDto(text = mapOf("Adam Smith 7610 Fletchers" to TextSlot(kind = "mixed"))) }
        assertThrows { UiSkeletonNodeDto(text = mapOf("" to TextSlot(kind = "mixed"))) }
        assertThrows {
            SkeletonSchema.json.decodeFromString(
                UiSkeletonNodeDto.serializer(),
                "{\"text\":{\"Adam Smith 7610 Fletchers\":{\"kind\":\"mixed\"}}}",
            )
        }
    }
}
