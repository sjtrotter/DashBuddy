package cloud.trotter.dashbuddy.core.pipeline.census

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.NotifTextField
import cloud.trotter.census.contract.NotificationSkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.census.NotificationSkeletonBuilder.Outcome
import cloud.trotter.dashbuddy.core.pipeline.census.NotificationSkeletonBuilder.Refusal
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.state.Platform
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.*
import org.junit.Test

class NotificationSkeletonBuilderTest {
    private val day = LocalDate.of(2026, 9, 30)
    private val meta = ReplayMetadata.EMPTY.copy(engineVersion = 1)
    private val raw = RawNotificationData("Continue", null, null, null,
        packageName = Platform.DoorDash.packageName!!, postTime = 1, isClearable = true, channelId = "synthetic-updates")
    private fun outcome(value: RawNotificationData = raw, metadata: ReplayMetadata = meta, platform: Platform = Platform.DoorDash) =
        NotificationSkeletonBuilder.outcome(value, metadata, platform, day)
    private fun built(value: RawNotificationData = raw) = outcome(value) as Outcome.Built
    private fun refused(reason: Refusal, value: RawNotificationData) = assertEquals(Outcome.Refused(reason), outcome(value))

    @Test fun `every shared field appears once in contract order with absent and blank withheld`() {
        assertEquals(NotifTextField.entries, raw.textFields().map { it.first })
        for (field in NotifTextField.entries) {
            val value = raw.withTextFields(NotifTextField.entries.associateWith { if (it == field) "Continue" else "  " })
            val item = built(value)
            assertEquals(NotifTextField.entries, item.skeleton.slots.keys.toList())
            item.skeleton.slots.forEach { (key, slot) ->
                if (key == field) assertNotNull(slot.h) else assertEquals(TextSlot.WITHHELD, slot)
            }
            assertEquals(item.skeleton, NotificationSkeletonSchema.deserialize(item.json))
            assertEquals(item.json.toByteArray().size, item.itemBytes)
            assertTrue(item.itemBytes <= cloud.trotter.census.contract.SkeletonSchema.MAX_ITEM_BYTES)
        }
        assertEquals(4, built().skeleton.slots.values.count { it == TextSlot.WITHHELD })
    }

    @Test fun `channel grammar validates original bytes without repair`() {
        for (channel in listOf("a", "A_0.-", "a".repeat(64))) assertEquals(channel, built(raw.copy(channelId = channel)).skeleton.channelId)
        for (channel in listOf(null, "", "a".repeat(65), " a", "a ", "a/b", "é", "a\n", "a\u0000")) {
            refused(Refusal.INVALID_CHANNEL, raw.copy(channelId = channel))
        }
    }

    @Test fun `platform must be known and agree with registry`() {
        assertEquals(Outcome.Refused(Refusal.INVALID_ENVELOPE), outcome(platform = Platform.Unknown))
        assertEquals(Outcome.Refused(Refusal.INVALID_ENVELOPE), outcome(platform = Platform.Uber))
        refused(Refusal.INVALID_ENVELOPE, raw.copy(packageName = "example.unregistered"))
        assertEquals(Platform.Uber.wire, (outcome(raw.copy(packageName = Platform.Uber.packageName!!), platform = Platform.Uber) as Outcome.Built).skeleton.platform)
    }

    @Test fun `original sensitive values in every field and action refuse before scrub and channel validation`() {
        for (field in NotifTextField.entries) {
            val value = raw.withTextFields(raw.textFields().toMap() + (field to "Deliver to Transfer out"))
            refused(Refusal.SENSITIVE_NOTIFICATION, value.copy(channelId = null))
        }
        refused(Refusal.SENSITIVE_NOTIFICATION, raw.copy(actionLabels = listOf("Transfer out")))
        refused(Refusal.SENSITIVE_NOTIFICATION, raw.copy(title = "Transfer", text = "out"))
        refused(Refusal.SENSITIVE_NOTIFICATION, raw.copy(title = "Transfer  ", text = "  ", tickerText = " out"))
        refused(Refusal.SENSITIVE_NOTIFICATION, raw.copy(title = "Transfer", text = "", actionLabels = listOf(" ", "out")))
        refused(Refusal.SENSITIVE_NOTIFICATION, raw.copy(title = null, subText = "Transfer", actionLabels = listOf("out")))
    }

    @Test fun `scrub precedes classification and pseudonyms cannot affect fingerprint`() {
        val first = raw.copy(title = "Deliver to Jordan")
        val second = raw.copy(title = "Deliver to Morgan")
        assertNotEquals(first.title, CustomerTextMarkers.scrubNotif(first).title)
        assertEquals(TextSlot.WITHHELD, built(first).skeleton.slots.getValue(NotifTextField.TITLE))
        assertEquals(built(first).skeleton.fingerprint, built(second).skeleton.fingerprint)
        for (text in listOf("Jordan T", "[redacted:abcd]", "sam@example.com", "1425 Sample Ridge Dr", "Call Jordan T")) {
            assertEquals(TextSlot.WITHHELD, built(raw.copy(title = text)).skeleton.slots.getValue(NotifTextField.TITLE))
        }
    }

    @Test fun `length cap and existing coarse kinds are shared`() {
        for ((text, kind) in listOf("a".repeat(40) to "words:1", "a".repeat(41) to "withheld",
            "123" to "digits", "$7.50" to "mixed", "a b c d e f g h i" to "words:8+")) {
            assertEquals(kind, built(raw.copy(title = text)).skeleton.slots.getValue(NotifTextField.TITLE).kind)
        }
    }

    @Test fun `actions and source metadata never travel or change fingerprint`() {
        val original = built()
        val changed = built(raw.copy(actionLabels = listOf("Synthetic action"), postTime = 123,
            category = "synthetic-category", isOngoing = true, isClearable = false))
        assertEquals(original.json, changed.json)
        assertFalse(changed.json.contains("actionLabels"))
        assertFalse(changed.json.contains("packageName"))
        assertFalse(changed.json.contains("postTime"))
        assertFalse(changed.json.contains("Continue"))
        assertEquals(original.skeleton.fingerprint, CensusFingerprint.of(original.skeleton))
        val stamped = outcome(metadata = meta.copy(appVersion = "x".repeat(200), deviceFingerprint = "NEVER_SEND")) as Outcome.Built
        assertEquals(original.skeleton.fingerprint, stamped.skeleton.fingerprint)
        assertFalse(stamped.json.contains("NEVER_SEND"))
        assertEquals(Outcome.Refused(Refusal.INVALID_ENVELOPE), NotificationSkeletonBuilder.outcome(raw, meta, Platform.DoorDash, LocalDate.of(10000, 1, 1)))
    }

    @Test fun `unexpected failures refuse without payload and cancellation escapes`() {
        // Kotlin object methods are instance methods: use an action list seam to exercise unexpected failure.
        val broken = object : AbstractList<String>() {
            override val size: Int get() = throw IllegalStateException("private payload")
            override fun get(index: Int): String = error("unreachable")
        }
        refused(Refusal.BUILD_FAILED, raw.copy(actionLabels = broken))
        val cancelled = object : AbstractList<String>() {
            override val size: Int get() = throw CancellationException("cancelled")
            override fun get(index: Int): String = error("unreachable")
        }
        assertThrows(CancellationException::class.java) { outcome(raw.copy(actionLabels = cancelled)) }
    }
    @Test fun `day and stamps do not change fingerprints but channel and platform do`() {
        val original = built().skeleton
        val later = NotificationSkeletonBuilder.outcome(raw, meta.copy(appVersion = "other"), Platform.DoorDash, day.plusDays(1)) as Outcome.Built
        assertEquals(original.fingerprint, later.skeleton.fingerprint)
        assertNotEquals(original.fingerprint, built(raw.copy(channelId = "Synthetic-updates")).skeleton.fingerprint)
        val uber = outcome(raw.copy(packageName = Platform.Uber.packageName!!), platform = Platform.Uber) as Outcome.Built
        assertNotEquals(original.fingerprint, uber.skeleton.fingerprint)
    }

}
