package cloud.trotter.dashbuddy.domain.model.notification

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.reflect.full.memberProperties
import kotlin.reflect.typeOf

/**
 * #666 (F3) — pins the [RawNotificationData.textFields] SSOT anchor:
 *
 * 1. [textFields] must enumerate EVERY nullable `String?` property declared on
 *    [RawNotificationData] (reflection-checked, so a future field added to the
 *    model without a matching [NotifTextField] member fails this test loudly
 *    instead of silently skipping a scrub/serialize site).
 * 2. [RawNotificationData.toFullString] must stay BYTE-IDENTICAL to the
 *    pre-#666 hand-written `listOfNotNull(title, text, bigText, tickerText,
 *    subText).joinToString(" | ")` implementation — this is the CaptureBus
 *    dedup identity ([RawNotificationData.contentHash]); a join-order or
 *    membership change would silently break historical dedup.
 */
class RawNotificationDataTest {

    private fun fullFixture() = RawNotificationData(
        title = "Order ready",
        text = "Deliver to Jane Q Doe",
        tickerText = "New message",
        bigText = "Your order is ready for pickup",
        subText = "DoorDash",
        packageName = "com.dd.doordash",
        postTime = 1_000L,
        isClearable = true,
        isOngoing = false,
        category = "msg",
        channelId = "chat",
        actionLabels = listOf("Reply", "Dismiss"),
    )

    private fun partialFixture() = RawNotificationData(
        title = "Order ready",
        text = null,
        tickerText = null,
        bigText = null,
        subText = null,
        packageName = "com.dd.doordash",
        postTime = 1_000L,
        isClearable = true,
    )

    /**
     * `category`/`channelId` are also nullable `String?` fields but are notification
     * METADATA (a channel/category identifier), not display TEXT that could carry
     * customer PII or belongs in [RawNotificationData.toFullString] — they are
     * deliberately excluded from the [NotifTextField] anchor. This is the ONE place
     * that documents the exclusion; every OTHER nullable `String?` field must appear
     * in [NotifTextField], so a newly added display-text field either gets added here
     * (a conscious exclusion, reviewed) or fails this test until it's added to the anchor.
     */
    private val nonTextNullableStringFields = setOf("category", "channelId")

    @Test
    fun `every nullable String text field is covered by NotifTextField`() {
        val stringOptionalType = typeOf<String?>()
        val nullableStringProps: Set<String> = RawNotificationData::class.memberProperties
            .filter { it.returnType == stringOptionalType }
            .map { it.name }
            .filterNot { it in nonTextNullableStringFields }
            .toSortedSet()

        val enumeratedByAnchor: Set<String> = NotifTextField.entries.map { it.wire }.toSortedSet()

        assertEquals(
            "RawNotificationData gained/lost a String? text field that isn't reflected in NotifTextField " +
                "(the #666 textFields() SSOT anchor) — every String? text field must have a matching member " +
                "(or be added to nonTextNullableStringFields above with a documented reason).",
            nullableStringProps,
            enumeratedByAnchor,
        )
    }

    @Test
    fun `textFields returns every field keyed by its NotifTextField in toFullString order`() {
        val raw = fullFixture()
        assertEquals(
            listOf(
                NotifTextField.TITLE to raw.title,
                NotifTextField.TEXT to raw.text,
                NotifTextField.BIG_TEXT to raw.bigText,
                NotifTextField.TICKER_TEXT to raw.tickerText,
                NotifTextField.SUB_TEXT to raw.subText,
            ),
            raw.textFields(),
        )
    }

    @Test
    fun `withTextFields replaces exactly the supplied fields`() {
        val raw = fullFixture()
        val updated = raw.withTextFields(
            raw.textFields().associate { (field, value) ->
                field to if (field == NotifTextField.TITLE) "[redacted]" else value
            },
        )
        assertEquals("[redacted]", updated.title)
        assertEquals(raw.text, updated.text)
        assertEquals(raw.bigText, updated.bigText)
        assertEquals(raw.tickerText, updated.tickerText)
        assertEquals(raw.subText, updated.subText)
        // Non-text fields untouched.
        assertEquals(raw.actionLabels, updated.actionLabels)
        assertEquals(raw.packageName, updated.packageName)
    }

    @Test
    fun `toFullString is byte-identical to the pre-666 hand-written implementation - full fixture`() {
        val raw = fullFixture()
        val legacy = legacyToFullString(raw)
        assertEquals(legacy, raw.toFullString())
        // Pin the literal too, so a future refactor can't silently drift both sides together.
        assertEquals(
            "Order ready | Deliver to Jane Q Doe | Your order is ready for pickup | New message | DoorDash",
            raw.toFullString(),
        )
    }

    @Test
    fun `toFullString is byte-identical to the pre-666 hand-written implementation - partial fixture`() {
        val raw = partialFixture()
        val legacy = legacyToFullString(raw)
        assertEquals(legacy, raw.toFullString())
        assertEquals("Order ready", raw.toFullString())
    }

    @Test
    fun `contentHash is unaffected by non-text fields`() {
        val raw = fullFixture()
        val sameTextDifferentMeta = raw.copy(
            packageName = "com.uber.driver",
            postTime = 2_000L,
            channelId = "other",
            actionLabels = emptyList(),
        )
        assertEquals(raw.contentHash, sameTextDifferentMeta.contentHash)
    }

    /** The EXACT pre-#666 implementation, kept only as a regression oracle. */
    private fun legacyToFullString(raw: RawNotificationData): String =
        listOfNotNull(raw.title, raw.text, raw.bigText, raw.tickerText, raw.subText).joinToString(" | ")
}
