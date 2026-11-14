package cloud.trotter.log.gig.core.data.census.contributor

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.census.contract.auth.Bearer
import cloud.trotter.census.contract.auth.InstallSecret
import cloud.trotter.census.contract.contributor.ContributorRole
import cloud.trotter.census.contract.contributor.ContributorVariant
import cloud.trotter.census.contract.contributor.QuarantineNote
import cloud.trotter.census.contract.contributor.SlotClass
import cloud.trotter.census.contract.sha256OrNull
import cloud.trotter.log.gig.core.data.census.CensusUploadLock
import cloud.trotter.log.gig.core.network.census.ContributorCall
import cloud.trotter.log.gig.core.network.census.ContributorRefusal
import cloud.trotter.log.gig.core.network.census.ContributorTransport
import cloud.trotter.log.gig.core.network.census.GateAnswer
import cloud.trotter.log.gig.core.network.census.QuarantineReceipt
import cloud.trotter.log.gig.core.network.census.ReviewCounts
import cloud.trotter.log.gig.core.network.census.SlotClasses
import cloud.trotter.log.gig.core.network.census.WordAnswer
import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.census.contributor.ContributorGate
import cloud.trotter.log.gig.domain.census.contributor.ReviewWords
import cloud.trotter.log.gig.domain.state.Platform
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #1627: "Ask the developer" against a transport that canonicalizes the artifact the way the census server does
 * (census#81 `QuarantineArtifact`: the contract's skeleton serialization, then the three arrays, compact) and answers
 * with the SHA-256 of those bytes. Every value is fictional.
 */
class ContributorQuarantineTest {
    @get:Rule val folder = TemporaryFolder()

    private class FixedClock(var millis: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
    }

    private class Gate(var open: Boolean = true) : ContributorGate {
        override suspend fun mayUpload(): Boolean = open
    }

    @Serializable private data class SlotHash(val slot: String, val hash: String)
    @Serializable private data class Role(val slot: String, val role: String)
    @Serializable private data class Word(val slot: String, val hash: String, val word: String)
    @Serializable private data class Artifact(
        val skeleton: JsonElement,
        val confirmations: List<SlotHash> = emptyList(),
        val roles: List<Role> = emptyList(),
        val words: List<Word> = emptyList(),
    )

    /** The server's encoder for the three arrays (`Contributor.kt` `contributorJson`). */
    private val serverJson = Json { encodeDefaults = true; explicitNulls = false }

    /** The bytes the server seals: what it validated, re-serialized (census#81 `QuarantineArtifact.parse`). */
    private fun serverCanonical(bytes: ByteArray): ByteArray {
        val body = serverJson.decodeFromString(Artifact.serializer(), bytes.decodeToString())
        val skeleton = CensusSkeletonSchema.serialize(CensusSkeletonSchema.deserialize(body.skeleton.toString()))
        return buildString {
            append("{\"skeleton\":").append(skeleton)
            append(",\"confirmations\":").append(serverJson.encodeToString(ListSerializer(SlotHash.serializer()), body.confirmations))
            append(",\"roles\":").append(serverJson.encodeToString(ListSerializer(Role.serializer()), body.roles))
            append(",\"words\":").append(serverJson.encodeToString(ListSerializer(Word.serializer()), body.words))
            append('}')
        }.toByteArray(Charsets.UTF_8)
    }

    private inner class Server : ContributorTransport {
        val calls = mutableListOf<Pair<String, ByteArray>>()
        val artifacts = mutableListOf<ByteArray>()
        val notes = mutableListOf<String?>()
        var classes: Map<String, SlotClass> = emptyMap()
        var gateOpen = false
        var quarantineAnswers = ArrayDeque<ContributorCall<QuarantineReceipt>>()
        var gatesAnswer: ContributorCall<List<GateAnswer>>? = null
        /** Replaces the server's hash, to stand for a server that sealed other bytes. */
        var reportedSha: String? = null
        var beforeQuarantine: () -> Unit = {}

        override suspend fun classes(cred: Bearer.Credential, body: ByteArray): ContributorCall<SlotClasses> {
            calls += ContributorTransport.CLASSES_PATH to body
            return ContributorCall.Ok(SlotClasses("2026-11-14", classes))
        }
        override suspend fun review(cred: Bearer.Credential, body: ByteArray): ContributorCall<ReviewCounts> {
            calls += ContributorTransport.REVIEW_PATH to body
            return ContributorCall.Ok(ReviewCounts(0, 0, 0))
        }
        override suspend fun gates(cred: Bearer.Credential, body: ByteArray): ContributorCall<List<GateAnswer>> {
            calls += ContributorTransport.GATES_PATH to body
            gatesAnswer?.let { return it }
            val asked = Json.parseToJsonElement(body.decodeToString()).jsonObject.getValue("slots").jsonArray
            return ContributorCall.Ok(asked.map {
                val o = it.jsonObject
                GateAnswer(o.getValue("slot").jsonPrimitive.content, o.getValue("hash").jsonPrimitive.content, gateOpen)
            })
        }
        override suspend fun words(cred: Bearer.Credential, body: ByteArray): ContributorCall<List<WordAnswer>> {
            calls += ContributorTransport.WORDS_PATH to body
            return ContributorCall.Ok(emptyList())
        }
        override suspend fun quarantine(cred: Bearer.Credential, body: ByteArray): ContributorCall<QuarantineReceipt> {
            beforeQuarantine()
            calls += ContributorTransport.QUARANTINE_PATH to body
            val request = Json.parseToJsonElement(body.decodeToString()).jsonObject
            val artifact = Base64.getUrlDecoder().decode(request.getValue("artifact").jsonPrimitive.content)
            artifacts += artifact
            notes += request["note"]?.jsonPrimitive?.content
            quarantineAnswers.removeFirstOrNull()?.let { return it }
            return ContributorCall.Ok(QuarantineReceipt("2026-11-17", reportedSha ?: requireNotNull(sha256OrNull(serverCanonical(artifact)))))
        }

        fun paths() = calls.map { it.first }
    }

    private val wording = "Pick up by"
    private val other = "Heads up"
    private val customerText = listOf("Riley", "Back gate", "1234 Sample Ridge Dr", "Gate code 4821")

    private fun hashed(value: String) = TextSlot(h = CensusHash.of(value), kind = "words:${value.split(' ').size}")

    private fun skeleton(extraId: String? = null): UiSkeletonDto {
        val root = UiSkeletonNodeDto(
            className = "android.widget.FrameLayout",
            id = extraId,
            children = listOf(
                UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to hashed(wording))),
                UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to hashed("Riley"))),
                UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to TextSlot.WITHHELD)),
                UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to TextSlot(kind = "shape", shape = "\$#.##"))),
                UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to hashed(other))),
            ),
        )
        return UiSkeletonDto(
            schemaId = SkeletonSchema.SCHEMA_ID_V2, hashDomain = 1, filterRev = 1,
            fingerprint = requireNotNull(CensusFingerprint.of(root)), platform = "doordash", platformAppVersion = "8.0.0",
            engineVersion = 1, day = "2026-11-13", root = root,
        )
    }

    private fun record(item: UiSkeletonDto): CensusRecord {
        val json = SkeletonSchema.measure(item).json
        return CensusRecord(
            Platform.DoorDash, item.fingerprint, json, json.length, null,
            reviewWords = ReviewWords(mapOf("root/0/text" to wording, "root/1/text" to "Riley", "root/4/text" to other)),
        )
    }

    private val clock = FixedClock(Instant.parse("2026-11-13T20:00:00Z").toEpochMilli())
    private val gate = Gate()
    private val server = Server()
    private val credential = Bearer.Credential("12345678-1234-4123-8123-123456789abc", InstallSecret.encode(ByteArray(32) { 7 }))
    private val events = mutableListOf<String>()

    private fun TestScope.queue(name: String = "queue") = ContributorReviewQueue(
        File(folder.root, "census/review/$name.json"), { gate }, flowOf(true), backgroundScope, Dispatchers.Unconfined, clock,
        flowOf(Unit),
    )

    private fun reviewer(queue: ContributorReviewQueue, api: ContributorTransport = server) =
        ContributorReviewer(queue, gate, CensusUploadLock(), { credential }, { api }, { events += "refresh" })

    /** A screen ready for review: stored by the server yesterday, opened today with two constant slots. */
    private suspend fun opened(queue: ContributorReviewQueue, item: UiSkeletonDto = skeleton()): Pair<String, ScreenReview> {
        val r = record(item)
        queue.offer(r)
        queue.uploaded(listOf(r.skeletonJson))
        clock.millis += 86_400_000L
        val id = queue.entries.first().last().id
        server.classes = mapOf("root/0/text" to SlotClass.CONSTANT, "root/1/text" to SlotClass.VARYING, "root/4/text" to SlotClass.CONSTANT)
        val review = (reviewer(queue).open(id) as ReviewLoad.Ready).review
        server.calls.clear()
        return id to review
    }

    private val confirmBoth = setOf("root/0/text", "root/4/text")
    private val tags = mapOf("root/3/text" to ContributorRole.TIP)

    @Test fun `the artifact is the server's canonical form of what the screen described, and its hash matches`() = runTest {
        val queue = queue()
        val (id, review) = opened(queue)
        server.gateOpen = true
        val outcome = reviewer(queue).ask(id, confirmBoth, tags, QuarantineNote.UNSURE_WHAT_SCREEN, review)
        assertEquals(AskOutcome.Sent("2026-11-17"), outcome)
        val sent = server.artifacts.single()
        assertArrayEquals("the phone sends exactly the canonical bytes", serverCanonical(sent), sent)
        val artifact = Json.parseToJsonElement(sent.decodeToString()).jsonObject
        assertEquals(listOf("skeleton", "confirmations", "roles", "words"), artifact.keys.toList())
        // The skeleton is the outbound skeleton the rows came from, byte for byte as the contract writes it.
        val candidateSkeleton = CensusSkeletonSchema.serialize(skeleton())
        assertTrue(sent.decodeToString().startsWith("{\"skeleton\":$candidateSkeleton,"))
        assertEquals(
            listOf("root/0/text" to CensusHash.of(wording), "root/4/text" to CensusHash.of(other)),
            artifact.getValue("confirmations").jsonArray.map { it.jsonObject.let { o -> o.getValue("slot").jsonPrimitive.content to o.getValue("hash").jsonPrimitive.content } },
        )
        assertEquals("[{\"slot\":\"root/3/text\",\"role\":\"tip\"}]", artifact.getValue("roles").toString())
        assertEquals(listOf(wording, other), artifact.getValue("words").jsonArray.map { it.jsonObject.getValue("word").jsonPrimitive.content })
        assertEquals(listOf("unsure_what_screen"), server.notes)
        assertEquals("gates then quarantine; no review", listOf(ContributorTransport.GATES_PATH, ContributorTransport.QUARANTINE_PATH), server.paths())
        assertTrue("the candidate was handled", queue.entries.first().isEmpty())
        assertNull(queue.wordsFor(id))
    }

    @Test fun `a word whose gate is closed reaches the quarantine only as its hash`() = runTest {
        val queue = queue()
        val (id, review) = opened(queue)
        // The gate opens for the second slot only.
        val h4 = requireNotNull(CensusHash.of(other))
        server.gatesAnswer = ContributorCall.Ok(listOf(
            GateAnswer("root/0/text", requireNotNull(CensusHash.of(wording)), false),
            GateAnswer("root/4/text", h4, true),
        ))
        assertTrue(reviewer(queue).ask(id, confirmBoth, emptyMap(), null, review) is AskOutcome.Sent)
        val body = server.calls.single { it.first == ContributorTransport.QUARANTINE_PATH }.second.decodeToString()
        val artifact = server.artifacts.single().decodeToString()
        assertFalse("the closed word is not in the request bytes", body.contains(wording) || artifact.contains(wording))
        assertTrue("its hash is", artifact.contains(requireNotNull(CensusHash.of(wording))))
        val words = Json.parseToJsonElement(artifact).jsonObject.getValue("words").jsonArray
        assertEquals(listOf("root/4/text"), words.map { it.jsonObject.getValue("slot").jsonPrimitive.content })
        assertEquals(listOf<String?>(null), server.notes)
        assertFalse("no note key without a note", body.contains("\"note\""))
    }

    @Test fun `an open answer for another hash opens nothing, and no gate answer sends hashes only`() = runTest {
        val queue = queue()
        val (id, review) = opened(queue)
        server.gatesAnswer = ContributorCall.Ok(listOf(GateAnswer("root/0/text", "0123456789abcdef", true)))
        reviewer(queue).ask(id, setOf("root/0/text"), emptyMap(), null, review)
        assertFalse(server.artifacts.single().decodeToString().contains(wording))

        val second = queue("second")
        val (id2, review2) = opened(second)
        server.artifacts.clear()
        server.gatesAnswer = ContributorCall.Refused(ContributorRefusal.BUDGET_EXHAUSTED)
        assertTrue(reviewer(second).ask(id2, confirmBoth, emptyMap(), null, review2) is AskOutcome.Sent)
        val artifact = server.artifacts.single().decodeToString()
        assertFalse(artifact.contains(wording) || artifact.contains(other))
    }

    @Test fun `a name, an address or a code on the screen is never in the artifact`() = runTest {
        val queue = queue()
        val (id, review) = opened(queue)
        server.gateOpen = true
        // Every slot confirmed and labelled: only the rows shown confirmable count.
        val all = review.rows.map { it.slot }.toSet()
        reviewer(queue).ask(id, all, all.associateWith { ContributorRole.CUSTOMER_INFO }, QuarantineNote.MIGHT_CONTAIN_CUSTOMER, review)
        val sent = server.calls.map { it.second.decodeToString() } + server.artifacts.map { it.decodeToString() }
        customerText.forEach { value -> sent.forEach { assertFalse("'$value' left in $it", it.contains(value)) } }
        val confirmations = Json.parseToJsonElement(server.artifacts.single().decodeToString()).jsonObject.getValue("confirmations").toString()
        assertFalse("a varying slot's hash is never confirmed", confirmations.contains(requireNotNull(CensusHash.of("Riley"))))
    }

    @Test fun `with the gate closed nothing is requested, and a gate that closes mid-ask stops before the quarantine`() = runTest {
        val queue = queue()
        val (id, review) = opened(queue)
        gate.open = false
        assertEquals(AskOutcome.NotSent(AskRefusal.NOT_ALLOWED), reviewer(queue).ask(id, confirmBoth, tags, null, review))
        assertTrue(server.calls.isEmpty())

        gate.open = true
        val closing = object : ContributorTransport by server {
            override suspend fun gates(cred: Bearer.Credential, body: ByteArray): ContributorCall<List<GateAnswer>> =
                server.gates(cred, body).also { gate.open = false }
        }
        assertEquals(AskOutcome.NotSent(AskRefusal.NOT_ALLOWED), reviewer(queue, closing).ask(id, confirmBoth, tags, null, review))
        assertEquals(listOf(ContributorTransport.GATES_PATH), server.paths())
        assertEquals("the candidate stays", 1, queue.entries.first().size)
    }

    @Test fun `a revocation stops the ask, runs the state read, then drops the words`() = runTest {
        for (reason in listOf(ContributorRefusal.GRANT_REQUIRED, ContributorRefusal.INSTALL_REVOKED)) {
            for (stage in listOf(ContributorTransport.GATES_PATH, ContributorTransport.QUARANTINE_PATH)) {
                events.clear()
                server.calls.clear()
                val queue = queue("${reason.name}_${stage.length}")
                val (id, review) = opened(queue)
                queue.hold(ContributorReviewQueue.HeldWord(
                    requireNotNull(ContributorVariant.of(skeleton())), "root/0/text", requireNotNull(CensusHash.of(wording)), wording,
                ), queue.generation)
                events.clear()
                val refusing = object : ContributorTransport by server {
                    override suspend fun gates(cred: Bearer.Credential, body: ByteArray): ContributorCall<List<GateAnswer>> =
                        if (stage == ContributorTransport.GATES_PATH) ContributorCall.Refused(reason) else server.gates(cred, body)
                    override suspend fun quarantine(cred: Bearer.Credential, body: ByteArray): ContributorCall<QuarantineReceipt> =
                        if (stage == ContributorTransport.QUARANTINE_PATH) ContributorCall.Refused(reason) else error("no quarantine after a revocation")
                }
                val outcome = reviewer(queue, refusing).ask(id, confirmBoth, emptyMap(), null, review)
                val expected = if (reason == ContributorRefusal.INSTALL_REVOKED) AskRefusal.INSTALL_REVOKED else AskRefusal.NOT_ALLOWED
                assertEquals("$reason at $stage", AskOutcome.NotSent(expected), outcome)
                assertEquals("$reason at $stage", listOf("refresh"), events)
                assertNull(queue.wordsFor(id))
                assertTrue(queue.takeHeld().isEmpty())
            }
        }
    }

    @Test fun `409 sends the words as hashes once, and 422, 503 and 429 send nothing and keep the candidate`() = runTest {
        val queue = queue()
        val (id, review) = opened(queue)
        server.gateOpen = true
        server.quarantineAnswers.addLast(ContributorCall.Refused(ContributorRefusal.GATE_CLOSED))
        assertTrue(reviewer(queue).ask(id, confirmBoth, emptyMap(), null, review) is AskOutcome.Sent)
        assertEquals(2, server.artifacts.size)
        assertTrue(server.artifacts.first().decodeToString().contains(wording))
        assertFalse("the second has no word", server.artifacts.last().decodeToString().contains(wording))
        assertTrue(server.artifacts.last().decodeToString().contains("\"words\":[]}"))

        val cases = listOf(
            ContributorRefusal.BAD_ARTIFACT to AskRefusal.REFUSED,
            ContributorRefusal.QUARANTINE_UNCONFIGURED to AskRefusal.UNCONFIGURED,
            ContributorRefusal.PAUSED to AskRefusal.LATER,
            ContributorRefusal.BUDGET_EXHAUSTED to AskRefusal.LATER,
            ContributorRefusal.TRANSPORT_FAILURE to AskRefusal.UNREACHABLE,
            ContributorRefusal.GATE_CLOSED to AskRefusal.REFUSED,
        )
        val second = queue("second")
        val (id2, review2) = opened(second)
        for ((refusal, expected) in cases) {
            server.artifacts.clear()
            server.quarantineAnswers.addLast(ContributorCall.Refused(refusal))
            // No confirmation: a 409 with no word in the artifact is not retried.
            assertEquals("$refusal", AskOutcome.NotSent(expected), reviewer(second).ask(id2, emptySet(), tags, null, review2))
            assertEquals("$refusal: one request", 1, server.artifacts.size)
            assertEquals("$refusal: the candidate stays", 1, second.entries.first().size)
        }
    }

    @Test fun `a hash that does not match is said and recorded, never retried, and the candidate is handled`() = runTest {
        val queue = queue()
        val (id, review) = opened(queue)
        server.reportedSha = "0".repeat(64)
        assertEquals(AskOutcome.Unconfirmed("2026-11-17"), reviewer(queue).ask(id, emptySet(), tags, null, review))
        assertEquals(1, server.calls.count { it.first == ContributorTransport.QUARANTINE_PATH })
        assertTrue(queue.entries.first().isEmpty())
    }

    @Test fun `the request carries the artifact and only a note from the fixed list`() {
        val artifact = "{\"skeleton\":{}}".toByteArray()
        QuarantineNote.entries.forEach { note ->
            val body = Json.parseToJsonElement(ContributorRequests.quarantine(artifact, note).decodeToString()).jsonObject
            assertEquals(listOf("artifact", "note"), body.keys.toList())
            assertTrue(body.getValue("note").jsonPrimitive.content in setOf("might_contain_customer", "might_contain_my_info", "unsure_what_screen"))
            assertArrayEquals(artifact, Base64.getUrlDecoder().decode(body.getValue("artifact").jsonPrimitive.content))
        }
        assertEquals(listOf("artifact"), Json.parseToJsonElement(ContributorRequests.quarantine(artifact, null).decodeToString()).jsonObject.keys.toList())
    }

    @Test fun `a word that does not hash to its slot's hash is never in the artifact`() {
        val words = ReviewWords(mapOf("root/0/text" to "Riley"))
        val c = Confirmation("root/0/text", requireNotNull(CensusHash.of(wording)))
        val bytes = ContributorRequests.artifact(skeleton(), listOf(c), emptyList(), words, setOf(c))
        assertFalse(bytes.decodeToString().contains("Riley"))
        assertTrue(bytes.decodeToString().endsWith("\"words\":[]}"))
    }

    @Test fun `an unreadable receipt or an unknown outcome retires the candidate and is never resent`() = runTest {
        for ((refusal, expected) in listOf(
            ContributorRefusal.INVALID_RESPONSE to AskOutcome.ReceiptUnreadable,
            ContributorRefusal.OUTCOME_UNKNOWN to AskOutcome.MaybeSent,
        )) {
            server.artifacts.clear()
            val queue = queue(refusal.name)
            val (id, review) = opened(queue)
            server.gateOpen = true
            server.quarantineAnswers.addLast(ContributorCall.Refused(refusal))
            assertEquals("$refusal", expected, reviewer(queue).ask(id, confirmBoth, tags, null, review))
            assertEquals("$refusal: one request, no retry", 1, server.artifacts.size)
            assertTrue("$refusal: the candidate is retired", queue.entries.first().isEmpty())
            assertNull(queue.wordsFor(id))
        }
    }

    @Test fun `leaving the screen right after the server stored it still retires the candidate`() = runTest {
        val queue = queue()
        val (id, review) = opened(queue)
        server.reportedSha = "0".repeat(64)
        var job: kotlinx.coroutines.Job? = null
        server.beforeQuarantine = { job?.cancel() }
        job = launch { reviewer(queue).ask(id, emptySet(), tags, null, review) }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, server.artifacts.size)
        assertTrue("the stored item's candidate is gone", queue.entries.first().isEmpty())
    }

    @Test fun `a cancellation while the request is out retires the candidate`() = runTest {
        val queue = queue()
        val (id, review) = opened(queue)
        val hanging = object : ContributorTransport by server {
            override suspend fun quarantine(cred: Bearer.Credential, body: ByteArray): ContributorCall<QuarantineReceipt> =
                kotlinx.coroutines.awaitCancellation()
        }
        val job = launch { reviewer(queue, hanging).ask(id, emptySet(), tags, null, review) }
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        assertTrue(queue.entries.first().isEmpty())
    }
}
