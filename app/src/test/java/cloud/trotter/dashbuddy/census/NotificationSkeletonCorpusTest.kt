package cloud.trotter.dashbuddy.census

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.NotifTextField
import cloud.trotter.census.contract.NotificationSkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.dashbuddy.core.pipeline.census.NotificationSkeletonBuilder
import cloud.trotter.dashbuddy.core.pipeline.census.NotificationSkeletonBuilder.Outcome
import cloud.trotter.dashbuddy.core.pipeline.census.NotificationSkeletonBuilder.Refusal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NotificationSkeletonCorpusTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `synthetic builder coverage is nonempty and field corpus emptiness is explicit`() {
        assertEquals(7, NotificationCorpus.fixtures.count { it.synthetic })
        if (NotificationCorpus.fieldFixtures.isEmpty()) {
            println("Notification field corpus is empty; seven synthetic builder fixtures are not device coverage")
        }
        val outcomes = NotificationCorpus.fixtures.filter { it.synthetic }.associate { fixture ->
            fixture.path.substringAfterLast('/') to NotificationSkeletonBuilder.outcome(
                fixture.raw, SkeletonCorpusTestBase.META, fixture.platform, SkeletonCorpusTestBase.DAY,
            )
        }
        assertEquals(Outcome.Refused(Refusal.SENSITIVE_NOTIFICATION), outcomes["action-only-sensitive.json"])
        assertEquals(Outcome.Refused(Refusal.INVALID_CHANNEL), outcomes["invalid-channel.json"])
        assertEquals(5, outcomes.filterKeys { name -> name in setOf("chrome.json", "uber-chrome.json", "all-fields.json", "long-body.json", "customer-lead-in.json") }.values.count { it is Outcome.Built })
        for (fixture in NotificationCorpus.fixtures) {
            val result = NotificationSkeletonBuilder.outcome(fixture.raw, SkeletonCorpusTestBase.META, fixture.platform, SkeletonCorpusTestBase.DAY)
            if (result !is Outcome.Built) continue
            assertEquals(result.skeleton, NotificationSkeletonSchema.deserialize(result.json))
            val wire = Json.parseToJsonElement(result.json).jsonObject
            assertEquals(setOf("kind", "schemaId", "hashDomain", "filterRev", "fingerprint", "platform",
                "platformAppVersion", "appVersion", "rulesetReleaseTag", "engineVersion", "rulesetFormatVersion",
                "day", "channelId", "slots"), wire.keys)
            assertEquals(NotifTextField.entries.map { it.wire }, wire.getValue("slots").jsonObject.keys.toList())
            wire.getValue("slots").jsonObject.values.forEach { slot -> assertTrue(slot.jsonObject.keys.all { it in setOf("kind", "h") }) }
            // Compare typed wire fields, not raw substring collisions with kind/stamp vocabulary.
            assertFalse(wire.containsKey("actionLabels"))
            assertFalse(wire.containsKey("payload"))
            assertEquals(result.skeleton.fingerprint, CensusFingerprint.of(result.skeleton))
            val changed = fixture.raw.copy(actionLabels = listOf("Synthetic replacement"), postTime = 0)
            val other = NotificationSkeletonBuilder.outcome(changed, SkeletonCorpusTestBase.META, fixture.platform, SkeletonCorpusTestBase.DAY) as Outcome.Built
            assertEquals(result.json, other.json)
        }
    }

    @Test fun `customer pseudonyms and long bodies withhold without changing shape`() {
        val fixture = NotificationCorpus.fixtures.single { it.path.endsWith("/customer-lead-in.json") }
        val variants = listOf("Deliver to Jordan", "Deliver to Morgan", "Deliver to Avery")
            .map { title -> NotificationSkeletonBuilder.outcome(fixture.raw.copy(title = title), SkeletonCorpusTestBase.META, fixture.platform, SkeletonCorpusTestBase.DAY) as Outcome.Built }
        assertEquals(1, variants.map { it.skeleton.fingerprint }.distinct().size)
        variants.forEach { assertEquals(TextSlot.WITHHELD, it.skeleton.slots.getValue(NotifTextField.TITLE)) }
        val long = NotificationCorpus.fixtures.single { it.path.endsWith("/long-body.json") }
        val built = NotificationSkeletonBuilder.outcome(long.raw, SkeletonCorpusTestBase.META, long.platform, SkeletonCorpusTestBase.DAY) as Outcome.Built
        assertEquals(TextSlot.WITHHELD, built.skeleton.slots.getValue(NotifTextField.TEXT))
    }

    @Test fun `discovery requires notification schema rejects bad payloads and skips staging and sensitive`() {
        val root = tmp.newFolder()
        val source = File("src/test/resources/snapshots/notification_census/fixtures/chrome.json").readText()
        for (path in listOf("INBOX/a.json", "SENSITIVE/b.json", "UNKNOWN/c.json")) {
            File(root, path).apply { parentFile?.mkdirs(); writeText("not JSON") }
        }
        File(root, "notifications_view/screen.json").apply { parentFile?.mkdirs(); writeText("""{"schemaId":"uinode.v1","payload":{"text":"Synthetic screen"}}""") }
        assertTrue(NotificationCorpus.discover(root).isEmpty())
        File(root, "UNKNOWN/negative/push.json").apply { parentFile?.mkdirs(); writeText(source) }
        assertEquals(1, NotificationCorpus.discover(root).size)
        File(root, "broken.json").writeText("""{"schemaId":"notification.v1","payload":{"text":"Synthetic"}}""")
        val failure = assertThrows(IllegalStateException::class.java) { NotificationCorpus.discover(root) }
        assertEquals("Invalid notification fixture: broken.json", failure.message)
        assertNull(failure.cause)
    }
}
