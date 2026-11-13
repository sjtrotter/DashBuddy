package cloud.trotter.log.gig.domain.census

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.KindClassifier
import cloud.trotter.census.contract.NotifTextField
import cloud.trotter.census.contract.NotificationSkeletonDto
import cloud.trotter.census.contract.SkeletonKind
import cloud.trotter.census.contract.ShapeTemplate
import cloud.trotter.census.contract.SkeletonVersion
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * #1620 (ADR-0011 §8, §21): the per-day sample key is the layout and the value formats, never the words. Keying on a
 * word hash would keep one sample per customer.
 */
class CensusSampleKeyTest {

    private fun words(text: String) = TextSlot(h = CensusHash.of(text), kind = KindClassifier.shapeKind(text).wire)

    /** An offer card: a heading, a name line, a pay figure. Only [name], [pay] and [checked] vary. */
    private fun card(
        name: TextSlot,
        pay: TextSlot = TextSlot.ofShape(ShapeTemplate.MONEY),
        checked: Int = 0,
        platformAppVersion: String = "15.1",
    ): UiSkeletonDto {
        val root = UiSkeletonNodeDto(
            className = "android.widget.LinearLayout", id = "card",
            children = listOf(
                UiSkeletonNodeDto(className = "android.widget.TextView", id = "title", text = mapOf("text" to words("New order"))),
                UiSkeletonNodeDto(className = "android.widget.TextView", id = "customer", text = mapOf("text" to name)),
                UiSkeletonNodeDto(className = "android.widget.TextView", id = "pay", text = mapOf("text" to pay), isChecked = checked),
            ),
        )
        return UiSkeletonDto(
            schemaId = SkeletonVersion.V2.screenSchemaId, hashDomain = CensusHash.HASH_DOMAIN, filterRev = 1,
            fingerprint = requireNotNull(CensusFingerprint.of(root)), platform = "doordash",
            platformAppVersion = platformAppVersion, engineVersion = 1, day = "2026-11-13", root = root,
        )
    }

    @Test fun `two frames that differ only in a customer's name are one variant`() {
        val ann = card(words("Ann"))
        val maria = card(words("Maria Elena Ruiz"))
        val withheld = card(TextSlot.WITHHELD)
        assertNotEquals("the hashes differ, so the test is not vacuous", ann.root.children[1].text, maria.root.children[1].text)
        assertEquals(CensusSampleKey.of(ann), CensusSampleKey.of(maria))
        assertEquals("a withheld name is the same variant too", CensusSampleKey.of(ann), CensusSampleKey.of(withheld))
    }

    @Test fun `a value's format is a variant`() {
        assertNotEquals(
            "a whole-dollar figure is another format",
            CensusSampleKey.of(card(words("Ann"))),
            CensusSampleKey.of(card(words("Ann"), pay = TextSlot.ofShape(ShapeTemplate.MONEY_WHOLE))),
        )
    }

    @Test fun `layout, flags and the platform app version are variants`() {
        val base = CensusSampleKey.of(card(words("Ann")))
        assertNotEquals(base, CensusSampleKey.of(card(words("Ann"), checked = 1)))
        assertNotEquals(base, CensusSampleKey.of(card(words("Ann"), platformAppVersion = "15.2")))
        assertNotEquals(base, CensusSampleKey.of(card(words("Ann")).toV1()))
    }

    @Test fun `the key's input holds no word hash`() {
        val item = card(words("Ann"))
        val canonical = CensusSampleKey.canonical(item)
        val hashes = CensusSkeletonSchema.slots(item).mapNotNull { it.h }
        assertEquals(2, hashes.size)
        hashes.forEach { assertFalse("no hash in the key input", it in canonical) }
    }

    /** A push whose title is [title] and whose text carries a pay figure; the fingerprint covers the slots' words. */
    private fun push(title: TextSlot, channel: String = "offers"): NotificationSkeletonDto {
        val slots = NotifTextField.entries.associateWith { TextSlot.WITHHELD } +
            mapOf(NotifTextField.entries[0] to title, NotifTextField.entries[1] to TextSlot.ofShape(ShapeTemplate.MONEY))
        return NotificationSkeletonDto(
            kind = SkeletonKind.NOTIFICATION, schemaId = SkeletonVersion.V2.notificationSchemaId,
            hashDomain = CensusHash.HASH_DOMAIN, filterRev = 1,
            fingerprint = requireNotNull(CensusFingerprint.of("doordash", channel, slots)), platform = "doordash",
            platformAppVersion = "15.1", engineVersion = 1, day = "2026-11-13", channelId = channel, slots = slots,
        )
    }

    @Test fun `two notifications that differ only in a customer's name are one variant`() {
        val ann = push(words("Ann"))
        val maria = push(words("Maria Elena Ruiz"))
        assertNotEquals("the fingerprints differ, so the test is not vacuous", ann.fingerprint, maria.fingerprint)
        assertEquals(CensusSampleKey.of(ann), CensusSampleKey.of(maria))
        assertEquals(CensusSampleKey.of(ann), CensusSampleKey.of(push(TextSlot.WITHHELD)))
        assertFalse("no fingerprint in a notification's key input", ann.fingerprint in CensusSampleKey.canonical(ann))
    }

    @Test fun `which notification fields held a value is a variant, separate from withholding`() {
        val item = push(TextSlot.WITHHELD)
        val inText = setOf(NotifTextField.TITLE, NotifTextField.TEXT)
        val inBigText = setOf(NotifTextField.TITLE, NotifTextField.BIG_TEXT)
        assertNotEquals("same wire slots, body in another field", CensusSampleKey.of(item, inText), CensusSampleKey.of(item, inBigText))
        assertEquals(
            "a name change keeps the layout",
            CensusSampleKey.of(push(words("Ann")), inText),
            CensusSampleKey.of(push(words("Maria Elena Ruiz")), inText),
        )
    }

    @Test fun `a notification's channel and formats are variants`() {
        val base = CensusSampleKey.of(push(words("Ann")))
        assertNotEquals(base, CensusSampleKey.of(push(words("Ann"), channel = "chat")))
        assertNotEquals(base, CensusSampleKey.of(push(TextSlot.ofShape(ShapeTemplate.CLOCK_12H))))
    }
}
