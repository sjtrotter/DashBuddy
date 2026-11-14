package cloud.trotter.log.gig.census

import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.SkeletonVersion
import cloud.trotter.census.contract.sha256OrNull
import cloud.trotter.census.contract.auth.Bearer
import cloud.trotter.census.contract.auth.InstallSecret
import cloud.trotter.census.contract.contributor.ContributorRole
import cloud.trotter.census.contract.contributor.ContributorSlots
import cloud.trotter.census.contract.contributor.SlotClass
import cloud.trotter.log.gig.core.data.census.CensusCredentialStore
import cloud.trotter.log.gig.core.data.census.CensusUploadLock
import cloud.trotter.log.gig.core.data.census.contributor.AskOutcome
import cloud.trotter.log.gig.core.data.census.contributor.ContributorReviewQueue
import cloud.trotter.log.gig.core.data.census.contributor.ContributorReviewer
import cloud.trotter.log.gig.core.data.census.contributor.ReviewLoad
import cloud.trotter.log.gig.core.data.census.contributor.SendOutcome
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.datastore.census.ContributorDataSource
import cloud.trotter.log.gig.core.network.census.CensusApiFactory
import cloud.trotter.log.gig.core.network.census.CensusSentObserver
import cloud.trotter.log.gig.core.network.census.ContributorCall
import cloud.trotter.log.gig.core.network.census.ContributorTransport
import cloud.trotter.log.gig.core.network.census.GateAnswer
import cloud.trotter.log.gig.core.network.census.QuarantineReceipt
import cloud.trotter.log.gig.core.network.census.ReviewCounts
import cloud.trotter.log.gig.core.network.census.SlotClasses
import cloud.trotter.log.gig.core.network.census.WordAnswer
import cloud.trotter.log.gig.core.network.census.WordStatus
import cloud.trotter.log.gig.core.pipeline.census.SkeletonBuilder
import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.census.contributor.ContributorGate
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * #1626 (ADR-0011 §15): end to end through the real skeleton builder, the review queue and the review session, with a
 * transport that records every body byte. A customer's name, address, code and note on the screen never leave the
 * phone in any field of `/review`, `/gates` or `/words`, whatever the dasher taps; a confirmed word leaves only after
 * its gate opens; and no screen text is ever written under the app's files directory. Every value is fictional.
 */
@RunWith(RobolectricTestRunner::class)
class ContributorReviewPrivacyTest {

    private class FixedClock(var millis: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
    }

    private class Recording(private val classOf: (String) -> SlotClass) : ContributorTransport {
        val calls = mutableListOf<Pair<String, String>>()
        var open = false
        override suspend fun classes(cred: Bearer.Credential, body: ByteArray): ContributorCall<SlotClasses> {
            calls += ContributorTransport.CLASSES_PATH to body.decodeToString()
            val slots = Json.parseToJsonElement(body.decodeToString()).jsonObject.getValue("slots").jsonArray.map { it.jsonPrimitive.content }
            return ContributorCall.Ok(SlotClasses("2026-11-14", slots.associateWith(classOf)))
        }
        override suspend fun review(cred: Bearer.Credential, body: ByteArray): ContributorCall<ReviewCounts> {
            calls += ContributorTransport.REVIEW_PATH to body.decodeToString()
            return ContributorCall.Ok(ReviewCounts(0, 0, 0))
        }
        override suspend fun gates(cred: Bearer.Credential, body: ByteArray): ContributorCall<List<GateAnswer>> {
            calls += ContributorTransport.GATES_PATH to body.decodeToString()
            val asked = Json.parseToJsonElement(body.decodeToString()).jsonObject.getValue("slots").jsonArray.map { it.jsonObject }
            return ContributorCall.Ok(asked.map { GateAnswer(it.getValue("slot").jsonPrimitive.content, it.getValue("hash").jsonPrimitive.content, open) })
        }
        override suspend fun words(cred: Bearer.Credential, body: ByteArray): ContributorCall<List<WordAnswer>> {
            calls += ContributorTransport.WORDS_PATH to body.decodeToString()
            val sent = Json.parseToJsonElement(body.decodeToString()).jsonObject.getValue("words").jsonArray.map { it.jsonObject }
            return ContributorCall.Ok(sent.map { WordAnswer(it.getValue("slot").jsonPrimitive.content, WordStatus.ACCEPTED) })
        }
        /** #1627: the artifact the server would seal, decoded from the request; the receipt echoes its hash. */
        val artifacts = mutableListOf<String>()
        override suspend fun quarantine(cred: Bearer.Credential, body: ByteArray): ContributorCall<QuarantineReceipt> {
            calls += ContributorTransport.QUARANTINE_PATH to body.decodeToString()
            val encoded = Json.parseToJsonElement(body.decodeToString()).jsonObject.getValue("artifact").jsonPrimitive.content
            val artifact = java.util.Base64.getUrlDecoder().decode(encoded)
            artifacts += artifact.decodeToString()
            return ContributorCall.Ok(QuarantineReceipt("2026-11-17", requireNotNull(sha256OrNull(artifact))))
        }
    }

    private val context = RuntimeEnvironment.getApplication()
    private val clock = FixedClock(Instant.parse("2026-11-13T21:00:00Z").toEpochMilli())

    /** Customer data in forms the review must cover, and a bare first name only the server's class can grey. */
    private val customer = listOf(
        "Deliver to Avery Example", "1234 Sample Ridge Dr", "Testville, TX 78000", "Gate code 4821",
        "\"Leave it by the blue door\"", "Customer Notes: ring twice", "Quinn",
    )
    private val wording = "Pick up by"

    private fun frame() = UiNode(
        className = "android.widget.FrameLayout", viewIdResourceName = "com.doordash.driverapp:id/root",
        children = listOf(UiNode(text = wording, className = "android.widget.TextView")) +
            customer.map { UiNode(text = it, className = "android.widget.TextView") } +
            UiNode(text = "$9.75", className = "android.widget.TextView"),
    )

    private val gate = object : ContributorGate {
        var open = true
        override suspend fun mayUpload(): Boolean = open
    }

    @Before fun clean() {
        context.filesDir.deleteRecursively()
        context.filesDir.mkdirs()
    }

    @Test fun `customer data never leaves in any field, and a confirmed word only after its gate opens`() = runTest {
        val built = SkeletonBuilder.outcome(frame(), "Current dash", ReplayMetadata(engineVersion = 1), Platform.DoorDash,
            LocalDate.parse("2026-11-13"), SkeletonVersion.V2, collectWords = true) as SkeletonBuilder.Outcome.Built
        val record = CensusRecord(Platform.DoorDash, built.skeleton.fingerprint, built.json, built.itemBytes, null, reviewWords = built.reviewWords)
        val slotOfWording = ContributorSlots.slots(built.skeleton).single { it.slot.h == CensusHash.of(wording) }.id
        // The server calls every slot it can constant, so only the phone's own covers stand between the screen and Send.
        val transport = Recording { SlotClass.CONSTANT }

        val records = ContributorDataSource(memoryPreferences())
        val queue = ContributorReviewQueue(context, { gate }, records, backgroundScope, Dispatchers.Unconfined, clock)
        val reviewer = reviewerFor(queue, transport)
        queue.offer(record)
        queue.uploaded(listOf(record.skeletonJson))
        clock.millis += 86_400_000L
        val id = queue.entries.first().single().id

        val review = (reviewer.open(id) as ReviewLoad.Ready).review
        val shown = review.rows.mapNotNull { it.word }
        // A bare first name the census filter cannot tell from wording is the one customer value a constant class could
        // show; every other customer value is covered before the screen exists.
        assertTrue(customer.filter { it != "Quinn" }.none { value -> shown.any { it.contains(value.trim('"')) } })

        // The dasher confirms the wording only, and labels every row.
        val all = review.rows.map { it.slot }
        assertEquals(SendOutcome.SENT, reviewer.send(id, setOf(slotOfWording), all.associateWith { ContributorRole.OTHER }, review))
        assertFalse("no word before its gate opens", transport.calls.any { it.second.contains(wording) })
        assertCustomerNeverSent(transport)

        // The gate opens: the next session sends the held word, and only it.
        transport.open = true
        assertEquals(ReviewLoad.NotFound, reviewer.open(id))
        val words = transport.calls.filter { it.first == ContributorTransport.WORDS_PATH }
        assertEquals(1, words.size)
        assertTrue(words.single().second.contains(wording))
        assertCustomerNeverSent(transport)

        // No screen text was ever written under the app's files.
        context.filesDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val text = file.readText()
            (customer + wording).forEach { value -> assertFalse("'$value' in ${file.path}", text.contains(value.trim('"'))) }
        }
    }

    @Test fun `with the gate closed no review request is made`() = runTest {
        val built = SkeletonBuilder.outcome(frame(), null, ReplayMetadata(engineVersion = 1), Platform.DoorDash,
            LocalDate.parse("2026-11-13"), SkeletonVersion.V2, collectWords = true) as SkeletonBuilder.Outcome.Built
        val record = CensusRecord(Platform.DoorDash, built.skeleton.fingerprint, built.json, built.itemBytes, null, reviewWords = built.reviewWords)
        val transport = Recording { SlotClass.CONSTANT }
        val queue = ContributorReviewQueue(context, { gate }, ContributorDataSource(memoryPreferences()), backgroundScope, Dispatchers.Unconfined, clock)
        queue.offer(record)
        queue.uploaded(listOf(record.skeletonJson))
        clock.millis += 86_400_000L
        val id = queue.entries.first().single().id
        val reviewer = reviewerFor(queue, transport)
        val review = (reviewer.open(id) as ReviewLoad.Ready).review
        transport.calls.clear()
        gate.open = false
        assertEquals(ReviewLoad.NotAllowed, reviewer.open(id))
        assertEquals(SendOutcome.NOT_ALLOWED, reviewer.send(id, review.rows.map { it.slot }.toSet(), emptyMap(), review))
        assertTrue(transport.calls.isEmpty())
    }

    @Test fun `with every slot constant and every gate open, confirming every row sends no detectable value`() = runTest {
        // Sol round 1: a return-task line, labels with no space or a full-width colon, a note repeated beside an
        // address block.
        val values = listOf(
            "Return Riley K to Sample Store", "Display name:Sam", "Zone：Springfield", "Customer:Avery",
            "Leave by porch", "Hi, Jordan", "Dash in Testville",
        )
        val tree = UiNode(
            className = "android.widget.FrameLayout", viewIdResourceName = "com.doordash.driverapp:id/root",
            children = listOf(
                UiNode(text = wording, className = "android.widget.TextView"),
                UiNode(
                    className = "android.widget.LinearLayout",
                    children = listOf(
                        UiNode(text = "1234 Sample Ridge Dr", contentDescription = "Leave by porch", className = "android.widget.TextView"),
                        UiNode(text = "Testville, TX 78000", className = "android.widget.TextView"),
                    ),
                ),
            ) + values.map { UiNode(text = it, className = "android.widget.TextView") } +
                values.map { UiNode(contentDescription = it, className = "android.widget.Button") },
        )
        val built = SkeletonBuilder.outcome(tree, "Leave by porch", ReplayMetadata(engineVersion = 1), Platform.DoorDash,
            LocalDate.parse("2026-11-13"), SkeletonVersion.V2, collectWords = true) as SkeletonBuilder.Outcome.Built
        val record = CensusRecord(Platform.DoorDash, built.skeleton.fingerprint, built.json, built.itemBytes, null, reviewWords = built.reviewWords)
        val transport = Recording { SlotClass.CONSTANT }.apply { open = true }
        val queue = ContributorReviewQueue(context, { gate }, ContributorDataSource(memoryPreferences()), backgroundScope, Dispatchers.Unconfined, clock)
        queue.offer(record)
        queue.uploaded(listOf(record.skeletonJson))
        clock.millis += 86_400_000L
        val id = queue.entries.first().single().id
        val reviewer = reviewerFor(queue, transport)
        val review = (reviewer.open(id) as ReviewLoad.Ready).review
        assertEquals(listOf(wording), review.rows.mapNotNull { it.word }.distinct())
        val confirmable = review.rows.filter { it.confirmable }.map { it.slot }.toSet()
        assertEquals(SendOutcome.SENT, reviewer.send(id, confirmable, emptyMap(), review))
        assertTrue(transport.calls.any { it.first == ContributorTransport.WORDS_PATH })
        transport.calls.forEach { (path, body) ->
            values.forEach { value -> assertFalse("'$value' left in $path", body.contains(value)) }
            values.mapNotNull { CensusHash.of(it) }.forEach { assertFalse("a hash of a covered value left in $path", body.contains(it)) }
        }
    }

    @Test fun `Ask the developer sends no customer value in any field, and a word only once its gate opens`() = runTest {
        for (gateOpen in listOf(false, true)) {
            context.filesDir.deleteRecursively()
            context.filesDir.mkdirs()
            val built = SkeletonBuilder.outcome(frame(), "Current dash", ReplayMetadata(engineVersion = 1), Platform.DoorDash,
                LocalDate.parse("2026-11-13"), SkeletonVersion.V2, collectWords = true) as SkeletonBuilder.Outcome.Built
            val record = CensusRecord(Platform.DoorDash, built.skeleton.fingerprint, built.json, built.itemBytes, null, reviewWords = built.reviewWords)
            val transport = Recording { SlotClass.CONSTANT }.apply { open = gateOpen }
            val queue = ContributorReviewQueue(context, { gate }, ContributorDataSource(memoryPreferences()), backgroundScope, Dispatchers.Unconfined, clock)
            queue.offer(record)
            queue.uploaded(listOf(record.skeletonJson))
            val start = clock.millis
            clock.millis += 86_400_000L
            val id = queue.entries.first().single().id
            val reviewer = reviewerFor(queue, transport)
            val review = (reviewer.open(id) as ReviewLoad.Ready).review
            // The dasher confirms every row it can (the bare first name included) and labels every row.
            val confirmable = review.rows.filter { it.confirmable }.map { it.slot }.toSet()
            val all = review.rows.map { it.slot }
            val outcome = reviewer.ask(id, confirmable, all.associateWith { ContributorRole.CUSTOMER_INFO }, null, review)
            assertTrue("gate open=$gateOpen", outcome is AskOutcome.Sent)
            val artifact = Json.parseToJsonElement(transport.artifacts.single()).jsonObject
            assertEquals(listOf("skeleton", "confirmations", "roles", "words"), artifact.keys.toList())
            // No plaintext customer value anywhere in the artifact, the skeleton included.
            customer.filter { it != "Quinn" }.forEach { value ->
                assertFalse("'$value' in the artifact", transport.artifacts.single().contains(value.trim('"')))
            }
            val words = artifact.getValue("words").jsonArray.map { it.jsonObject.getValue("word").jsonPrimitive.content }
            if (gateOpen) {
                // Every gate answers open in this fake; only words the phone's covers let through are held at all.
                assertTrue(wording in words)
                customer.filter { it != "Quinn" }.forEach { value -> assertTrue(words.none { it.contains(value.trim('"')) }) }
            } else {
                assertTrue("a closed gate sends no word", words.isEmpty())
                assertFalse(transport.artifacts.single().contains(wording))
            }
            // Customer values' hashes are never confirmed: their slots were covered, so they are not confirmable.
            val confirmed = artifact.getValue("confirmations").toString()
            customer.filter { it != "Quinn" }.mapNotNull { CensusHash.of(it) }.forEach { assertFalse(confirmed.contains(it)) }
            assertEquals("no review is sent by Ask the developer", 0, transport.calls.count { it.first == ContributorTransport.REVIEW_PATH })
            assertTrue("the candidate was handled", queue.entries.first().isEmpty())
            clock.millis = start
        }
    }

    private fun assertCustomerNeverSent(transport: Recording) {
        transport.calls.forEach { (path, body) ->
            customer.forEach { value -> assertFalse("'$value' left in $path", body.contains(value.trim('"'))) }
        }
        // A covered value's hash does not travel either: its slot was withheld, so it has none.
        // Nor does the hash of any customer value: only a confirmed slot's hash is ever named.
        customer.mapNotNull { CensusHash.of(it) }.forEach { h ->
            transport.calls.forEach { (path, body) -> assertFalse("a customer hash left in $path", body.contains(h)) }
        }
    }

    private suspend fun reviewerFor(queue: ContributorReviewQueue, transport: Recording): ContributorReviewer {
        val credentials: CensusCredentialStore = mock()
        whenever(credentials.current()).thenReturn(
            CensusCredentialStore.Credential("12345678-1234-4123-8123-123456789abc", InstallSecret.encode(ByteArray(32) { 3 }), enrolled = true),
        )
        val settings: DevSettingsRepository = mock()
        whenever(settings.censusBaseUrl).thenReturn(flowOf("https://census.example"))
        val apis = object : CensusApiFactory(CensusSentObserver.NONE) {
            override fun contributor(baseUrl: String): ContributorTransport = transport
        }
        // The real contributor's gate and state read, standing in as this test's gate.
        val contributor: cloud.trotter.log.gig.core.data.census.contributor.CensusContributor = mock()
        whenever(contributor.mayUpload()).thenAnswer { gate.open }
        whenever(contributor.refresh()).thenAnswer { gate.open }
        return ContributorReviewer(queue, contributor, CensusUploadLock(), credentials, settings, apis)
    }

    private fun memoryPreferences() = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        override val data = MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
        override suspend fun updateData(
            transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences,
        ) = transform(data.value).also { data.value = it }
    }
}
