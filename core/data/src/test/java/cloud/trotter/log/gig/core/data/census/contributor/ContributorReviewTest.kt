package cloud.trotter.log.gig.core.data.census.contributor

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.census.contract.auth.Bearer
import cloud.trotter.census.contract.auth.InstallSecret
import cloud.trotter.census.contract.contributor.ContributorRole
import cloud.trotter.census.contract.contributor.SlotClass
import cloud.trotter.log.gig.core.data.census.CensusUploadLock
import cloud.trotter.log.gig.core.network.census.ContributorCall
import cloud.trotter.log.gig.core.network.census.ContributorRefusal
import cloud.trotter.log.gig.core.network.census.ContributorTransport
import cloud.trotter.log.gig.core.network.census.GateAnswer
import cloud.trotter.log.gig.core.network.census.ReviewCounts
import cloud.trotter.log.gig.core.network.census.SlotClasses
import cloud.trotter.log.gig.core.network.census.WordAnswer
import cloud.trotter.log.gig.core.network.census.WordStatus
import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.census.contributor.ContributorGate
import cloud.trotter.log.gig.domain.census.contributor.ReviewWords
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * #1626: the review queue and session against a recording transport. The skeleton is synthetic and every value is
 * fictional: a constant slot holding app wording, a varying slot whose word is a first name the census filter cannot
 * tell from wording, a withheld address, a money shape and a slot the server has no class for.
 */
class ContributorReviewTest {
    @get:Rule val folder = TemporaryFolder()

    private class MutableClock(var millis: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
    }

    private class Gate(var open: Boolean = true) : ContributorGate {
        var checks = 0
        override suspend fun mayUpload(): Boolean = open.also { checks++ }
    }

    /** Records every call's path and exact body bytes. */
    private class Recording : ContributorTransport {
        val calls = mutableListOf<Pair<String, ByteArray>>()
        var classes: Map<String, SlotClass> = emptyMap()
        var gateOpen = false
        override suspend fun classes(cred: Bearer.Credential, body: ByteArray): ContributorCall<SlotClasses> {
            calls += ContributorTransport.CLASSES_PATH to body
            return ContributorCall.Ok(SlotClasses("2026-11-14", classes))
        }
        override suspend fun review(cred: Bearer.Credential, body: ByteArray): ContributorCall<ReviewCounts> {
            calls += ContributorTransport.REVIEW_PATH to body
            return ContributorCall.Ok(ReviewCounts(1, 1, 0))
        }
        override suspend fun gates(cred: Bearer.Credential, body: ByteArray): ContributorCall<List<GateAnswer>> {
            calls += ContributorTransport.GATES_PATH to body
            val asked = Json.parseToJsonElement(body.decodeToString()).jsonObject.getValue("slots").jsonArray
            return ContributorCall.Ok(asked.map {
                val o = it.jsonObject
                GateAnswer(o.getValue("slot").toString().trim('"'), o.getValue("hash").toString().trim('"'), gateOpen)
            })
        }
        override suspend fun words(cred: Bearer.Credential, body: ByteArray): ContributorCall<List<WordAnswer>> {
            calls += ContributorTransport.WORDS_PATH to body
            val sent = Json.parseToJsonElement(body.decodeToString()).jsonObject.getValue("words").jsonArray
            return ContributorCall.Ok(sent.map { WordAnswer(it.jsonObject.getValue("slot").toString().trim('"'), WordStatus.ACCEPTED) })
        }
        override suspend fun quarantine(cred: Bearer.Credential, body: ByteArray): ContributorCall<cloud.trotter.log.gig.core.network.census.QuarantineReceipt> {
            calls += ContributorTransport.QUARANTINE_PATH to body
            return ContributorCall.Refused(ContributorRefusal.QUARANTINE_UNCONFIGURED)
        }
        fun bodies(path: String? = null): List<String> = calls.filter { path == null || it.first == path }.map { it.second.decodeToString() }
    }

    private val wording = "Pick up by"
    private val firstName = "Riley"
    private val note = "Back gate"
    private val customerText = listOf(firstName, note, "1234 Sample Ridge Dr", "Gate code 4821", "Leave it by the door")

    private fun hashed(value: String) = TextSlot(h = CensusHash.of(value), kind = "words:${value.split(' ').size}")

    private fun skeleton(extraId: String? = null): UiSkeletonDto {
        val root = UiSkeletonNodeDto(
            className = "android.widget.FrameLayout",
            id = extraId,
            children = listOf(
                UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to hashed(wording))),
                UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to hashed(firstName))),
                UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to TextSlot.WITHHELD)),
                UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to TextSlot(kind = "shape", shape = "\$#.##"))),
                UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to hashed(note))),
            ),
        )
        return UiSkeletonDto(
            schemaId = SkeletonSchema.SCHEMA_ID_V2, hashDomain = 1, filterRev = 1,
            fingerprint = requireNotNull(CensusFingerprint.of(root)), platform = "doordash", platformAppVersion = "8.0.0",
            engineVersion = 1, day = "2026-11-13", root = root,
        )
    }

    private fun record(item: UiSkeletonDto = skeleton()): CensusRecord {
        val json = SkeletonSchema.measure(item).json
        return CensusRecord(
            Platform.DoorDash, item.fingerprint, json, json.length, null,
            reviewWords = ReviewWords(mapOf("root/0/text" to wording, "root/1/text" to firstName, "root/4/text" to note)),
        )
    }

    private val day = 86_400_000L
    private val clock = MutableClock(Instant.parse("2026-11-13T20:00:00Z").toEpochMilli())
    private val gate = Gate()
    private val transport = Recording()
    private val credential = Bearer.Credential("12345678-1234-4123-8123-123456789abc", InstallSecret.encode(ByteArray(32) { 7 }))

    private fun TestScope.queue() = ContributorReviewQueue(
        File(folder.root, "census/review/queue.json"), { gate }, flowOf(true), backgroundScope, Dispatchers.Unconfined, clock,
        ticks,
    )

    private val tick = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val ticks = kotlinx.coroutines.flow.flow { tick.collect { emit(Unit) } }

    /** What happened, in order: `refresh` from the reviewer, the transport's calls are in [Recording.calls]. */
    private val events = mutableListOf<String>()

    private fun reviewer(
        queue: ContributorReviewQueue,
        api: ContributorTransport = transport,
        cred: suspend () -> Bearer.Credential? = { credential },
    ) = ContributorReviewer(queue, gate, CensusUploadLock(), cred, { api }, { events += "refresh" })

    /** A candidate the server stored yesterday, so today's release covers it. */
    private suspend fun ready(queue: ContributorReviewQueue, item: UiSkeletonDto = skeleton()): String {
        val r = record(item)
        queue.offer(r)
        queue.uploaded(listOf(r.skeletonJson))
        clock.millis += day
        return queue.entries.first().last().id
    }

    @Test fun `a candidate is kept only while the gate stands, once per layout, at most 3 a day`() = runTest {
        val queue = queue()
        gate.open = false
        queue.offer(record())
        assertTrue(queue.entries.first().isEmpty())
        gate.open = true
        queue.offer(record())
        queue.offer(record())
        assertEquals("one entry per layout", 1, queue.entries.first().size)
        (1..5).forEach { queue.offer(record(skeleton("com.doordash.driverapp:id/screen_$it"))) }
        assertEquals(ContributorReviewQueue.MAX_PER_DAY, queue.entries.first().size)
        assertFalse(queue.wantsCandidates)
        assertEquals(0, transport.calls.size)
    }

    @Test fun `classes are asked only for a layout this phone uploaded on an earlier UTC day`() = runTest {
        val queue = queue()
        val reviewer = reviewer(queue)
        val r = record()
        queue.offer(r)
        val id = queue.entries.first().single().id
        assertEquals(ReviewLoad.NotReady, reviewer.open(id))
        queue.uploaded(listOf(r.skeletonJson))
        assertEquals("uploaded today: tomorrow's release", ReviewLoad.NotReady, reviewer.open(id))
        assertTrue(transport.calls.isEmpty())

        clock.millis += day
        transport.classes = mapOf("root/0/text" to SlotClass.CONSTANT, "root/1/text" to SlotClass.VARYING)
        val review = (reviewer.open(id) as ReviewLoad.Ready).review
        val body = Json.parseToJsonElement(transport.bodies(ContributorTransport.CLASSES_PATH).single()).jsonObject
        assertEquals(setOf("variant", "slots"), body.keys)
        assertEquals(
            listOf("root/0/text", "root/1/text", "root/2/text", "root/3/text", "root/4/text"),
            body.getValue("slots").jsonArray.map { it.toString().trim('"') },
        )
        assertEquals(wording, review.rows[0].word)
        assertNull("a varying slot shows no word", review.rows[1].word)
        assertNull("an unknown slot shows no word", review.rows[4].word)
        assertEquals(listOf(true, false, false, false, false), review.rows.map { it.confirmable })
        customerText.forEach { value -> assertTrue(transport.bodies().none { it.contains(value) }) }
    }

    @Test fun `with the gate closed nothing is requested or sent`() = runTest {
        val queue = queue()
        val reviewer = reviewer(queue)
        val id = ready(queue)
        transport.classes = mapOf("root/0/text" to SlotClass.CONSTANT)
        val review = (reviewer.open(id) as ReviewLoad.Ready).review
        transport.calls.clear()
        gate.open = false
        assertEquals(ReviewLoad.NotAllowed, reviewer.open(id))
        assertEquals(SendOutcome.NOT_ALLOWED, reviewer.send(id, setOf("root/0/text"), mapOf("root/3/text" to ContributorRole.TIP), review))
        assertTrue(transport.calls.isEmpty())
    }

    @Test fun `the gate is checked again before each request`() = runTest {
        val queue = queue()
        val reviewer = reviewer(queue)
        val id = ready(queue)
        transport.classes = mapOf("root/0/text" to SlotClass.CONSTANT)
        val review = (reviewer.open(id) as ReviewLoad.Ready).review
        transport.calls.clear()
        // The grant ends between the review and the gate check: no gate check, no word.
        val realGate = gate
        var reviews = 0
        val closing = object : ContributorTransport by transport {
            override suspend fun review(cred: Bearer.Credential, body: ByteArray): ContributorCall<ReviewCounts> =
                transport.review(cred, body).also { reviews++; realGate.open = false }
        }
        val gated = reviewer(queue, closing)
        assertEquals(SendOutcome.SENT, gated.send(id, setOf("root/0/text"), emptyMap(), review))
        assertEquals(1, reviews)
        assertEquals(listOf(ContributorTransport.REVIEW_PATH), transport.calls.map { it.first })
    }

    @Test fun `a name, an address, a code and a note in a varying or covered slot never leave the phone`() = runTest {
        val queue = queue()
        val reviewer = reviewer(queue)
        val id = ready(queue)
        transport.classes = mapOf("root/0/text" to SlotClass.CONSTANT, "root/1/text" to SlotClass.VARYING, "root/4/text" to SlotClass.REGIONAL)
        transport.gateOpen = true
        val review = (reviewer.open(id) as ReviewLoad.Ready).review
        // The dasher tries to confirm every slot and labels them all.
        val all = review.rows.map { it.slot }.toSet()
        val outcome = reviewer.send(id, all, all.associateWith { ContributorRole.CUSTOMER_INFO }, review)
        assertEquals(SendOutcome.SENT, outcome)
        assertEquals(
            listOf(ContributorTransport.CLASSES_PATH, ContributorTransport.REVIEW_PATH, ContributorTransport.GATES_PATH, ContributorTransport.WORDS_PATH),
            transport.calls.map { it.first },
        )
        val bodies = transport.bodies()
        customerText.forEach { value -> bodies.forEach { assertFalse("'$value' left in $it", it.contains(value)) } }
        // Not even the varying or regional slot's hash is confirmed or gated.
        listOf(firstName, note).forEach { value ->
            val h = requireNotNull(CensusHash.of(value))
            bodies.forEach { assertFalse("the hash of '$value' left in $it", it.contains(h)) }
        }
        val review0 = Json.parseToJsonElement(transport.bodies(ContributorTransport.REVIEW_PATH).single()).jsonObject
        assertEquals(1, review0.getValue("confirmations").jsonArray.size)
        // Only the constant slot's word, under its own hash, went to /words.
        val words = Json.parseToJsonElement(transport.bodies(ContributorTransport.WORDS_PATH).single()).jsonObject.getValue("words").jsonArray
        assertEquals(1, words.size)
        assertEquals(setOf("slot", "hash", "word"), words.single().jsonObject.keys)
        assertTrue(words.single().toString().contains(wording))
        assertTrue(queue.entries.first().isEmpty())
    }

    @Test fun `a confirmed word is in no upload until its gate opens`() = runTest {
        val queue = queue()
        val reviewer = reviewer(queue)
        val id = ready(queue)
        transport.classes = mapOf("root/0/text" to SlotClass.CONSTANT)
        val review = (reviewer.open(id) as ReviewLoad.Ready).review
        assertEquals(SendOutcome.SENT, reviewer.send(id, setOf("root/0/text"), emptyMap(), review))
        assertEquals(
            listOf(ContributorTransport.CLASSES_PATH, ContributorTransport.REVIEW_PATH, ContributorTransport.GATES_PATH),
            transport.calls.map { it.first },
        )
        assertTrue(transport.bodies().none { it.contains(wording) })
        val confirmation = Json.parseToJsonElement(transport.bodies(ContributorTransport.REVIEW_PATH).single()).jsonObject
            .getValue("confirmations").jsonArray.single().jsonObject
        assertEquals(setOf("slot", "hash"), confirmation.keys)
        assertTrue(confirmation.toString().contains(requireNotNull(CensusHash.of(wording))))

        // The next session asks the gate again; still closed, still nothing.
        val second = ready(queue, skeleton("com.doordash.driverapp:id/other"))
        reviewer.open(second)
        assertTrue(transport.bodies().none { it.contains(wording) })
        // Open now: the held word goes, once, to /words only.
        transport.gateOpen = true
        reviewer.open(second)
        val withWord = transport.calls.filter { it.second.decodeToString().contains(wording) }.map { it.first }
        assertEquals(listOf(ContributorTransport.WORDS_PATH), withWord)
        reviewer.open(second)
        assertEquals(1, transport.calls.count { it.first == ContributorTransport.WORDS_PATH })
    }

    @Test fun `role tags travel with the review and carry the slot and the role only`() = runTest {
        val queue = queue()
        val reviewer = reviewer(queue)
        val id = ready(queue)
        val review = (reviewer.open(id) as ReviewLoad.Ready).review
        reviewer.send(id, emptySet(), mapOf("root/3/text" to ContributorRole.TIP, "root/1/text" to ContributorRole.CUSTOMER_INFO), review)
        val body = Json.parseToJsonElement(transport.bodies(ContributorTransport.REVIEW_PATH).single()).jsonObject
        assertEquals(listOf("variant", "confirmations", "roles"), body.keys.toList())
        val roles = body.getValue("roles").jsonArray.map { it.jsonObject }
        roles.forEach { assertEquals(setOf("slot", "role"), it.keys) }
        assertEquals(listOf("customer_info", "tip"), roles.map { it.getValue("role").toString().trim('"') })
        assertTrue(body.getValue("confirmations").jsonArray.isEmpty())
        assertEquals("no gate without a confirmation", 0, transport.calls.count { it.first == ContributorTransport.GATES_PATH })
    }

    @Test fun `nothing chosen sends nothing, and Delete drops the candidate unsent`() = runTest {
        val queue = queue()
        val reviewer = reviewer(queue)
        val id = ready(queue)
        val review = (reviewer.open(id) as ReviewLoad.Ready).review
        transport.calls.clear()
        assertEquals(SendOutcome.NOTHING_TO_SEND, reviewer.send(id, emptySet(), emptyMap(), review))
        reviewer.delete(id)
        assertTrue(queue.entries.first().isEmpty())
        assertNull(queue.wordsFor(id))
        assertTrue(transport.calls.isEmpty())
        // A deleted layout is not taken again the same day.
        queue.offer(record())
        assertTrue(queue.entries.first().isEmpty())
    }

    @Test fun `a refused review keeps the candidate`() = runTest {
        val queue = queue()
        val id = ready(queue)
        val paused = object : ContributorTransport by transport {
            override suspend fun review(cred: Bearer.Credential, body: ByteArray): ContributorCall<ReviewCounts> =
                ContributorCall.Refused(ContributorRefusal.PAUSED, 3600)
        }
        val reviewer = reviewer(queue, paused)
        val review = (reviewer.open(id) as ReviewLoad.Ready).review
        assertEquals(SendOutcome.LATER, reviewer.send(id, emptySet(), mapOf("root/3/text" to ContributorRole.TIP), review))
        assertEquals(1, queue.entries.first().size)
    }

    @Test fun `the screen text is never written to disk, and clear removes the queue`() = runTest {
        val queue = queue()
        val reviewer = reviewer(queue)
        val id = ready(queue)
        transport.classes = mapOf("root/0/text" to SlotClass.CONSTANT)
        val review = (reviewer.open(id) as ReviewLoad.Ready).review
        ready(queue, skeleton("com.doordash.driverapp:id/kept"))
        reviewer.send(id, setOf("root/0/text"), mapOf("root/3/text" to ContributorRole.TIP), review)
        val files = folder.root.walkTopDown().filter { it.isFile }.toList()
        assertTrue("the sanitized queue is stored", files.isNotEmpty())
        files.forEach { file ->
            val text = file.readText()
            (customerText + wording).forEach { value -> assertFalse("'$value' in ${file.name}", text.contains(value)) }
        }
        queue.clear()
        assertTrue(folder.root.walkTopDown().none { it.isFile })
        assertTrue(queue.entries.first().isEmpty())
        assertTrue(queue.takeHeld().isEmpty())
    }

    @Test fun `a process death loses the words and keeps the sanitized candidate`() = runTest {
        val queue = queue()
        val id = ready(queue)
        // A new queue over the same file: the words were memory only.
        val restarted = queue()
        transport.classes = mapOf("root/0/text" to SlotClass.CONSTANT)
        val review = (reviewer(restarted).open(id) as ReviewLoad.Ready).review
        assertFalse(review.wordsKept)
        assertTrue(review.rows.none { it.word != null })
    }

    @Test fun `the gate closing while the credential is read sends no review`() = runTest {
        val queue = queue()
        val id = ready(queue)
        val review = (reviewer(queue).open(id) as ReviewLoad.Ready).review
        transport.calls.clear()
        val closing = reviewer(queue, cred = { gate.open = false; credential })
        assertEquals(SendOutcome.NOT_ALLOWED, closing.send(id, emptySet(), mapOf("root/3/text" to ContributorRole.TIP), review))
        assertTrue(transport.calls.isEmpty())
        assertEquals(1, queue.entries.first().size)
    }

    @Test fun `a revocation mid-session stops it, runs the state read, then drops the words`() = runTest {
        for (reason in listOf(ContributorRefusal.GRANT_REQUIRED, ContributorRefusal.INSTALL_REVOKED)) {
            for (stage in listOf(ContributorTransport.REVIEW_PATH, ContributorTransport.GATES_PATH)) {
                events.clear()
                transport.calls.clear()
                val queue = queue()
                val id = ready(queue, skeleton("com.doordash.driverapp:id/${reason.name.lowercase()}_${stage.length}"))
                transport.classes = mapOf("root/0/text" to SlotClass.CONSTANT)
                val review = (reviewer(queue).open(id) as ReviewLoad.Ready).review
                // A word held from an earlier session must go too.
                queue.hold(ContributorReviewQueue.HeldWord(
                    requireNotNull(cloud.trotter.census.contract.contributor.ContributorVariant.of(skeleton())), "root/0/text",
                    requireNotNull(CensusHash.of(wording)), wording,
                ), queue.generation)
                val refusing = object : ContributorTransport by transport {
                    override suspend fun review(cred: Bearer.Credential, body: ByteArray): ContributorCall<ReviewCounts> =
                        if (stage == ContributorTransport.REVIEW_PATH) ContributorCall.Refused(reason).also { events += "review" }
                        else transport.review(cred, body)
                    override suspend fun gates(cred: Bearer.Credential, body: ByteArray): ContributorCall<List<GateAnswer>> =
                        ContributorCall.Refused(reason).also { events += "gates" }
                    override suspend fun words(cred: Bearer.Credential, body: ByteArray): ContributorCall<List<WordAnswer>> =
                        error("no word after a revocation")
                }
                val outcome = reviewer(queue, refusing).send(id, setOf("root/0/text"), emptyMap(), review)
                val expected = if (reason == ContributorRefusal.INSTALL_REVOKED) SendOutcome.INSTALL_REVOKED else SendOutcome.NOT_ALLOWED
                assertEquals("$reason at $stage", expected, outcome)
                assertEquals("$reason at $stage", "refresh", events.last())
                assertNull(queue.wordsFor(id))
                assertTrue(queue.takeHeld().isEmpty())
                assertEquals(0, transport.calls.count { it.first == ContributorTransport.WORDS_PATH })
            }
        }
    }

    @Test fun `a revocation on the classes read stops the session`() = runTest {
        val queue = queue()
        val id = ready(queue)
        val refusing = object : ContributorTransport by transport {
            override suspend fun classes(cred: Bearer.Credential, body: ByteArray): ContributorCall<SlotClasses> =
                ContributorCall.Refused(ContributorRefusal.INSTALL_REVOKED)
        }
        assertEquals(ReviewLoad.NotAllowed, reviewer(queue, refusing).open(id))
        assertEquals(listOf("refresh"), events)
        assertNull(queue.wordsFor(id))
    }

    @Test fun `a candidate past 7 days never opens, is never listed, and leaves the store`() = runTest {
        val queue = queue()
        val id = ready(queue)
        assertEquals(1, queue.entries.first().size)
        clock.millis += 8 * day // the skeleton's day is now 9 days back
        assertNull(queue.candidate(id))
        assertEquals(ReviewLoad.NotFound, reviewer(queue).open(id))
        assertTrue(queue.entries.first().isEmpty())
        assertNull(queue.wordsFor(id))
        assertTrue(folder.root.walkTopDown().none { it.isFile && it.readText().contains(id) })
        // Stored before a restart: a new queue over the same file drops it at load.
        val stale = queue()
        assertTrue(stale.entries.first().isEmpty())
    }

    @Test fun `the clock alone drops an expired candidate from a collected list`() = runTest {
        val queue = queue()
        ready(queue)
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch { queue.entries.collect { seen += it.size } }
        testScheduler.runCurrent()
        clock.millis += 8 * day
        tick.value++
        testScheduler.runCurrent()
        assertEquals(listOf(1, 0), seen)
        job.cancel()
    }

    @Test fun `a gate answer that arrives after Stop holds nothing`() = runTest {
        val queue = queue()
        val id = ready(queue)
        transport.classes = mapOf("root/0/text" to SlotClass.CONSTANT)
        val review = (reviewer(queue).open(id) as ReviewLoad.Ready).review
        // Stop lands while the gate question is in flight: the contributor clears the queue, then the answer arrives.
        val late = object : ContributorTransport by transport {
            override suspend fun gates(cred: Bearer.Credential, body: ByteArray): ContributorCall<List<GateAnswer>> {
                queue.clear()
                return transport.gates(cred, body)
            }
        }
        assertEquals(SendOutcome.SENT, reviewer(queue, late).send(id, setOf("root/0/text"), emptyMap(), review))
        assertTrue("a closed answer after the clear holds nothing", queue.takeHeld().isEmpty())
    }

    @Test fun `a candidate whose gate check raced a clear is not kept`() = runTest {
        lateinit var raced: ContributorReviewQueue
        val clearing = object : ContributorGate {
            override suspend fun mayUpload(): Boolean { raced.clear(); return true }
        }
        raced = ContributorReviewQueue(
            File(folder.root, "census/review/raced.json"), { clearing }, flowOf(true), backgroundScope, Dispatchers.Unconfined, clock, ticks,
        )
        raced.offer(record())
        assertTrue(raced.entries.first().isEmpty())
        assertNull(raced.takeHeld().firstOrNull())
    }

    @Test fun `a grant check that fails before the words discards them`() = runTest {
        val queue = queue()
        val id = ready(queue)
        transport.classes = mapOf("root/0/text" to SlotClass.CONSTANT)
        transport.gateOpen = true
        val review = (reviewer(queue).open(id) as ReviewLoad.Ready).review
        val closing = object : ContributorTransport by transport {
            override suspend fun gates(cred: Bearer.Credential, body: ByteArray): ContributorCall<List<GateAnswer>> =
                transport.gates(cred, body).also { gate.open = false }
        }
        assertEquals(SendOutcome.SENT, reviewer(queue, closing).send(id, setOf("root/0/text"), emptyMap(), review))
        assertEquals(0, transport.calls.count { it.first == ContributorTransport.WORDS_PATH })
        assertTrue("an open word is discarded, never held again", queue.takeHeld().isEmpty())
    }
}
