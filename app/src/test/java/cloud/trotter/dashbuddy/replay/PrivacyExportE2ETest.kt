package cloud.trotter.dashbuddy.replay

import android.content.Context
import android.content.ContextWrapper
import cloud.trotter.census.contract.*
import cloud.trotter.census.contract.auth.Bearer
import cloud.trotter.census.contract.auth.CensusHeaders
import cloud.trotter.census.contract.auth.RequestSigner
import cloud.trotter.dashbuddy.core.data.census.CensusCredentialStore
import cloud.trotter.dashbuddy.core.network.census.CensusApi
import cloud.trotter.dashbuddy.core.pipeline.census.NotificationSkeletonBuilder
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.test.util.PrivacyExportInputs as Inputs
import cloud.trotter.dashbuddy.test.util.PrivacyExportReplay
import cloud.trotter.dashbuddy.test.util.PrivacyExportReplay.Stored
import cloud.trotter.dashbuddy.test.util.ReplayApplication
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.privacyExportDecode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * #1271 scenario 6 — named canaries through the real sensor/export journey, including actual disk
 * bytes and signatures over transmitted bytes. Positive outputs keep privacy checks non-vacuous.
 * UNKNOWN capture protection and log marker coverage remain finite; these cases do not claim that
 * arbitrary customer text is safe, or cover event-receipt consent, screenshots or release DI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ReplayApplication::class)
class PrivacyExportE2ETest {
    @Test fun `default opt out senses and captures but makes no census request`() = runTest(timeout = 20.seconds) {
        replay().use { h ->
            h.start()
            assertFalse(h.preferences.censusUploadEnabled.first())
            assertFalse(h.preferences.censusShareCaptures.first())
            h.manifest()
            h.worker()
            assertEquals(0, h.factoryCalls)
            assertTrue(h.requests.isEmpty())
            assertTrue(h.records(h.skeletonRoot).isEmpty())
            assertTrue(h.records(h.envelopeRoot).isEmpty())
            recognized(h)
            safeSurfaces(h)
        }
    }

    @Test fun `opt in exports scrubbed paired envelopes and both signed skeleton schemas`() = runTest(timeout = 20.seconds) {
        replay().use { h ->
            h.start()
            enroll(h)
            h.preferences.setCensusShareCaptures(true); h.settle()
            h.manifest(repeatBenign = true)
            val skeletons = h.records(h.skeletonRoot)
            val envelopes = h.records(h.envelopeRoot)
            // Vetted acceptance hypotheses: coordinator validation must explain any count change.
            assertEquals(6, h.captures().size)
            assertEquals(4, skeletons.size)
            assertEquals(2, envelopes.size)
            assertEquals(2, h.forwarded.size)
            assertEquals(2L, h.stats.suppressedDuplicateCount)
            assertEquals(1L, h.stats.droppedSensitiveCount)
            assertEquals(2L, h.stats.scrubbedUnknownCaptureCount)
            assertEquals(1L, h.stats.censusRefusedCount(SkeletonBuilder.Refusal.SENSITIVE_FRAME))
            assertEquals(1L, h.stats.censusNotificationRefusedCount(NotificationSkeletonBuilder.Refusal.SENSITIVE_NOTIFICATION))
            assertTrue("first spool append scheduled work", "soon" in h.scheduling.requests)
            recognized(h)
            captureControls(h)
            skeletonControls(skeletons)
            envelopeControls(h, skeletons, envelopes)
            safeSurfaces(h)
            val credential = requireNotNull(h.credentials.current())
            h.worker()
            uploads(h, skeletons, envelopes, credential)
            assertEquals(setOf(SkeletonSchema.SCHEMA_ID, NotificationSkeletonSchema.SCHEMA_ID),
                h.requests.filter { it.path == "/v1/skeletons" }.flatMap { request ->
                    json("request${request.path}", request.body.toByteArray()).getValue("items").jsonArray.map { it.jsonObject.string("schemaId") }
                }.toSet())
            assertTrue(h.records(h.skeletonRoot).isEmpty())
            assertTrue(h.records(h.envelopeRoot).isEmpty())
            assertTrue("real worker INFO reached shareable sink", h.shareable().toString(Charsets.UTF_8).contains("census uploaded="))
            safeSurfaces(h, credential.secret)

            // Separate explicit sink probe, after natural production-log non-vacuity is proved.
            val scrubbed = h.log.autoScrubbedLineCount
            Timber.tag("PrivacyExportProbe").i("Transfer out %s", Inputs.LOG)
            Timber.tag("PrivacyExportProbe").d(Inputs.DEBUG)
            h.settle()
            assertTrue(File(h.localRoot, "app.log").readBytes().toString(Charsets.UTF_8).contains(Inputs.LOG))
            assertTrue(File(h.localRoot, "app.log").readBytes().toString(Charsets.UTF_8).contains(Inputs.DEBUG))
            assertEquals(scrubbed + 1, h.log.autoScrubbedLineCount)
            assertTrue(h.shareable().toString(Charsets.UTF_8).contains("[scrubbed:"))
            val deny = Inputs.forbidden + listOf(Inputs.LOG, Inputs.DEBUG, credential.secret)
            safe("shareable probe", h.shareable(), deny, isJson = false)
            // Keep the leading F JSON-escaped on the wire to prove prefixed log detection.
            val escapedLog = "payload={\"x\":\"\\u0046AKEUNKNOWNNAMECANARY\"}"
            assertThrows(AssertionError::class.java) {
                safe("escaped log detector", escapedLog.toByteArray(Charsets.UTF_8), deny, isJson = false)
            }
        }
    }

    @Test fun `withdrawal clears queued envelopes and retains scrubbed skeletons for later consent`() = runTest(timeout = 20.seconds) {
        replay().use { h ->
            h.start(); enroll(h)
            h.preferences.setCensusShareCaptures(true); h.settle()
            h.screen(Inputs.Screen.CUSTOMER_UNKNOWN)
            val queued = h.records(h.skeletonRoot)
            val envelopes = h.records(h.envelopeRoot)
            assertEquals(1, queued.size)
            assertEquals(1, envelopes.size)
            safeSurfaces(h)
            skeletonControls(queued)
            envelopeControls(h, queued, envelopes)
            val localCaptures = h.captures().map { it.name }.toSet()
            h.preferences.setCensusShareCaptures(false); h.settle()
            assertTrue(h.records(h.envelopeRoot).isEmpty())
            assertEquals(queued, h.records(h.skeletonRoot))
            assertEquals(localCaptures, h.captures().map { it.name }.toSet())
            h.preferences.setCensusUploadEnabled(false); h.settle()
            val requests = h.requests.size
            val factories = h.factoryCalls
            val capturesBefore = h.captures().toSet()
            h.screen(Inputs.Screen.UNKNOWN, Inputs.distinctUnknown())
            val newCaptures = h.captures().toSet() - capturesBefore
            assertEquals("post-consent frame adds one local capture", 1, newCaptures.size)
            assertTrue("post-consent frame was sensed and admitted",
                Inputs.AFTER in strings(json("post-consent capture", newCaptures.single().readBytes())))
            h.worker()
            assertEquals(requests, h.requests.size)
            assertEquals(factories, h.factoryCalls)
            assertEquals(queued, h.records(h.skeletonRoot))
            assertTrue(h.records(h.envelopeRoot).isEmpty())
            h.preferences.setCensusUploadEnabled(true); h.settle()
            assertFalse(h.preferences.censusShareCaptures.first())
            val credential = requireNotNull(h.credentials.current())
            h.worker()
            uploads(h, queued, emptyList(), credential)
            assertTrue(h.requests.none { it.path == "/v1/envelopes" })
            assertTrue(h.records(h.skeletonRoot).isEmpty())
            safeSurfaces(h, credential.secret)
        }
    }

    @Test fun `server revocation wipes identity and allows zero subsequent census requests`() = runTest(timeout = 20.seconds) {
        replay().use { h ->
            h.start(); enroll(h)
            h.preferences.setCensusShareCaptures(true); h.settle()
            h.screen(Inputs.Screen.CUSTOMER_UNKNOWN)
            val queued = h.records(h.skeletonRoot)
            assertEquals(1, queued.size)
            assertEquals(1, h.records(h.envelopeRoot).size)
            skeletonControls(queued)
            envelopeControls(h, queued, h.records(h.envelopeRoot))
            safeSurfaces(h)
            val credential = requireNotNull(h.credentials.current())
            h.revokeNextSkeleton = true
            h.worker()
            uploads(h, queued, emptyList(), credential)
            assertFalse(h.preferences.censusUploadEnabled.first())
            assertFalse(h.preferences.censusShareCaptures.first())
            assertTrue(h.records(h.envelopeRoot).isEmpty())
            assertNull(h.credentials.current())
            assertNull(h.credentials.pending())
            val retainedSkeletons = h.records(h.skeletonRoot)
            val requests = h.requests.size
            val factories = h.factoryCalls
            val capturesBefore = h.captures().toSet()
            h.screen(Inputs.Screen.UNKNOWN, Inputs.distinctUnknown())
            val newCaptures = h.captures().toSet() - capturesBefore
            assertEquals("post-consent frame adds one local capture", 1, newCaptures.size)
            assertTrue("post-consent frame was sensed and admitted",
                Inputs.AFTER in strings(json("post-consent capture", newCaptures.single().readBytes())))
            h.worker()
            assertEquals("revoking request already happened; no subsequent requests", requests, h.requests.size)
            assertEquals(factories, h.factoryCalls)
            assertEquals(retainedSkeletons, h.records(h.skeletonRoot))
            assertTrue(h.records(h.envelopeRoot).isEmpty())
            // Revocation does not promise skeleton deletion; every retained byte must remain safe.
            safeSurfaces(h, credential.secret)
        }
    }

    @Test fun `no op bindings keep sensing and leave files, spools and requests empty`() = runTest(timeout = 20.seconds) {
        // Component behavior in debug tests, not execution of the release Hilt graph/BuildConfig.
        replay(captures = false).use { h ->
            h.start(); enroll(h)
            h.preferences.setCensusShareCaptures(true); h.settle()
            h.screen(Inputs.Screen.UNKNOWN)
            val queued = h.records(h.skeletonRoot)
            assertEquals(1, queued.size)
            assertEquals(JsonNull, queued.single().wrapper["captureId"])
            assertTrue(h.captures().isEmpty())
            assertTrue(h.records(h.envelopeRoot).isEmpty())
            val credential = requireNotNull(h.credentials.current())
            h.worker()
            uploads(h, queued, emptyList(), credential)
            safeSurfaces(h, credential.secret)
        }
        // Combined disabled preferences, capture and no-op sinks; these switches are not isolated.
        // Recognized screens return from SkeletonPublisher before consulting the census sink.
        replay(captures = false, census = false, debug = false).use { h ->
            h.start()
            h.preferences.setCensusUploadEnabled(true)
            h.preferences.setCensusShareCaptures(true); h.settle()
            assertFalse(h.preferences.censusUploadEnabled.first())
            assertFalse(h.preferences.censusShareCaptures.first())
            h.screen(Inputs.Screen.BENIGN)
            assertEquals("waiting_for_offer", (h.forwarded.single() as Observation.Screen).target)
            assertNull(h.forwarded.single().captureId)
            h.worker()
            assertTrue(h.captures().isEmpty())
            assertTrue(h.records(h.skeletonRoot).isEmpty())
            assertTrue(h.records(h.envelopeRoot).isEmpty())
            assertEquals(0, h.factoryCalls)
            assertTrue(h.requests.isEmpty())
            safeSurfaces(h)
        }
    }

    /** Each configuration gets fresh directories beneath Robolectric's actual internal/external roots. */
    private fun TestScope.replay(captures: Boolean = true, census: Boolean = true, debug: Boolean = true): PrivacyExportReplay {
        val app = RuntimeEnvironment.getApplication() as ReplayApplication
        val name = "privacy-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = File(app.filesDir, name).apply { mkdirs() }
            override fun getExternalFilesDir(type: String?): File? = app.getExternalFilesDir(type)?.let { File(it, name).apply { mkdirs() } }
            override fun getApplicationContext(): Context = this
        }
        return PrivacyExportReplay(context, this, captures, census, debug)
    }
    private suspend fun enroll(h: PrivacyExportReplay) {
        h.preferences.setCensusUploadEnabled(true); h.settle()
        h.worker()
        assertNotNull(h.credentials.current())
        assertEquals(CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS.toSet(), h.preferences.acceptedSchemaIds.first())
    }

    /** Hash independently with the JDK, then match actual forwarded parses to actual capture IDs. */
    private fun recognized(h: PrivacyExportReplay) {
        val screens = h.forwarded.filterIsInstance<Observation.Screen>()
        assertEquals(setOf("waiting_for_offer", "dropoff_pre_arrival"), screens.map { it.target }.toSet())
        val customer = screens.single { it.target == "dropoff_pre_arrival" }
        val fields = customer.parsed as ParsedFields.TaskFields
        assertEquals(sha("fakecustomercanary q"), fields.customerNameHash)
        assertEquals(sha("${Inputs.ADDRESS_ONE}, ${Inputs.ADDRESS_TWO}"), fields.customerAddressHash)
        val captures = h.captures().map { json("capture/${it.name}", it.readBytes()) }
        screens.forEach { screen ->
            val capture = captures.single { it.string("captureId") == screen.captureId }
            assertEquals(screen.target, capture.string("classificationName"))
            assertEquals(screen.ruleId, capture.string("ruleId"))
        }
        val root = privacyExportDecode("recognized capture payload") {
            TestResourceLoader.nodeFromElement(captures.single { it.string("captureId") == customer.captureId }.getValue("payload"))
        }
        fun text(id: String) = Inputs.nodes(root).single { it.viewIdResourceName?.endsWith(id) == true }.text
        assertEquals("[redacted:${sha("fakecustomercanary q").take(4)}]", text("user_name"))
        assertEquals("[redacted:${sha(Inputs.ADDRESS_ONE).take(4)}]", text("address_line_1"))
        assertEquals("[redacted]", text("address_line_2"))
    }

    private fun captureControls(h: PrivacyExportReplay) {
        val captures = h.captures().map { json("capture/${it.name}", it.readBytes()) }
        assertEquals(captures.size, captures.map { UUID.fromString(it.string("captureId")) }.toSet().size)
        assertEquals(4, captures.count { it.string("classificationName") == "UNKNOWN" })
        assertTrue("benign raw text proves capture wasn't disabled", captures.any { Inputs.BENIGN in strings(it) })
        assertTrue("sensitive surfaces produce no capture", captures.none { it.string("classificationName").startsWith("sensitive") })
        val unknown = captures.filter { it.string("classificationName") == "UNKNOWN" && it.string("schemaId") == "uinode.v1" }
        val customer = unknown.single { envelopeNodes("unknown capture payload", it).any { n -> n.viewIdResourceName?.endsWith("order_cx_name") == true } }
        assertTrue("customer UNKNOWN has masks", envelopeNodes("customer capture payload", customer).takeLast(4).all { it.text?.contains("[redacted") == true })
    }

    /** Deserialize the actual wrapper's item with the contract; the four appended slots lose all hashes. */
    private fun skeletonControls(records: List<Stored>) {
        val items = records.map { record ->
            privacyExportDecode("skeleton/${record.name}") { CensusSkeletonSchema.deserialize(record.itemJson) }
        }
        assertTrue("benign vocabulary hash is present", items.flatMap(CensusSkeletonSchema::slots).any { it.h != null })
        val customer = items.filterIsInstance<UiSkeletonDto>().single { it.root.children.takeLast(4).any { n -> n.id?.endsWith("order_cx_name") == true } }
        val leaves = customer.root.children.takeLast(4)
        assertEquals(4, leaves.size)
        assertEquals("android.widget.EditText", leaves.last().className)
        leaves.forEach { assertEquals(TextSlot.WITHHELD, it.text["text"]) }
        items.filterIsInstance<NotificationSkeletonDto>().takeIf { it.isNotEmpty() }?.let { notifications ->
            assertEquals(2, notifications.size)
            assertEquals(1, notifications.count { it.slots[NotifTextField.TITLE] == TextSlot.WITHHELD })
            assertEquals(1, notifications.count { it.slots[NotifTextField.TITLE]?.h != null })
        }
        records.forEach { safe("skeleton/${it.name}", it.bytes.toByteArray(), Inputs.forbidden + listOf(Inputs.BENIGN, "Current dash", "Continue")) }
    }

    private fun envelopeNodes(surface: String, envelope: JsonObject) = privacyExportDecode(surface) {
        Inputs.nodes(TestResourceLoader.nodeFromElement(envelope.getValue("payload")))
    }
    private fun envelopeControls(h: PrivacyExportReplay, skeletons: List<Stored>, envelopes: List<Stored>) {
        val captures = h.captures().map { json("capture/${it.name}", it.readBytes()) }.associateBy { it.string("captureId") }
        envelopes.forEach { stored ->
            val item = json("envelope/${stored.name}", stored.itemJson.toByteArray(Charsets.UTF_8))
            assertEquals("uinode.v1", item.string("schemaId"))
            assertEquals("UNKNOWN", item.string("classificationName"))
            val id = item.string("captureId")
            val original = captures.getValue(id)
            val paired = skeletons.single { it.wrapper.string("captureId") == id }
            assertEquals(paired.wrapper.string("fingerprint"), item.string("fingerprint"))
            assertEquals(id, stored.wrapper.string("captureId"))
            assertEquals(0L, item.getValue("timestamp").jsonPrimitive.long % 3_600_000)
            assertEquals(original.getValue("timestamp").jsonPrimitive.long / 3_600_000,
                item.getValue("timestamp").jsonPrimitive.long / 3_600_000)
            assertTrue(original.getValue("metadata").jsonObject.keys.containsAll(listOf("deviceFingerprint", "rulesetSignature")))
            assertFalse(item.getValue("metadata").jsonObject.keys.any { it in setOf("deviceFingerprint", "rulesetSignature") })
            assertTrue("projected envelope retains the scrubbed payload", original.getValue("payload") == item.getValue("payload"))
            if (envelopeNodes("envelope/${stored.name}/payload", item).any { it.viewIdResourceName?.endsWith("order_cx_name") == true }) {
                assertTrue("customer envelope masks all four appended fields", envelopeNodes("envelope/${stored.name}/payload", item).takeLast(4).all { it.text?.contains("[redacted") == true })
                assertEquals("[redacted]", envelopeNodes("envelope/${stored.name}/payload", item).last().text)
            }
        }
        assertTrue("customer envelope carries masks", envelopes.any { "[redacted" in it.itemJson })
        if (envelopes.size == 2) assertTrue("benign envelope positive control", envelopes.any { Inputs.BENIGN in strings(json("envelope/${it.name}", it.itemJson.toByteArray(Charsets.UTF_8))) })
    }

    /** Verify exact pre-upload item bytes and the in-flight marker while those files still existed. */
    private fun uploads(h: PrivacyExportReplay, skeletons: List<Stored>, envelopes: List<Stored>, credential: CensusCredentialStore.Credential) {
        for ((path, expected) in listOf("/v1/skeletons" to skeletons, "/v1/envelopes" to envelopes)) {
            val sent = mutableListOf<String>()
            h.requests.filter { it.path == path }.forEach { request ->
                val body = json("request${request.path}", request.body.toByteArray())
                val marker = json("request$path/inflight", requireNotNull(request.marker).toByteArray())
                val ids = marker.getValue("ids").jsonArray.map { it.jsonPrimitive.content }
                assertTrue("nonempty in-flight batch", ids.isNotEmpty())
                val items = ids.map { id -> expected.single { it.name == id } }
                items.forEach { item -> assertTrue("in-flight $path/${item.name} bytes unchanged", item.bytes == request.queued.single { it.name == item.name }.bytes) }
                val batchId = body.string("batchId")
                assertEquals(batchId, marker.string("batchId"))
                assertEquals(items.size, body.getValue("items").jsonArray.size)
                assertTrue("exact persisted $path item bytes", CensusApi.batchBody(batchId, items.map { it.itemJson }).contentEquals(request.body.toByteArray()))
                assertEquals("POST", request.method)
                assertTrue("credential bearer header", Bearer.format(credential.installId, credential.secret) == request.headers[CensusHeaders.AUTHORIZATION])
                val timestamp = requireNotNull(request.headers[CensusHeaders.TIMESTAMP])
                // Freshness-window behavior is covered by RequestSigner's own deterministic tests.
                assertTrue("signature over captured bytes and actual path", RequestSigner.verify(credential.secret,
                    RequestSigner.canonical(request.method, request.path, timestamp, request.body.toByteArray()), requireNotNull(request.headers[CensusHeaders.SIGNATURE])))
                sent += ids
            }
            assertEquals("every queued $path item transmitted once", expected.map { it.name }.sorted(), sent.sorted())
        }
    }

    /** Captured export surfaces are scanned for the finite deny list; failures name no bodies. */
    private fun safeSurfaces(h: PrivacyExportReplay, secret: String? = null) {
        val deny = Inputs.forbidden + listOfNotNull(secret)
        h.captures().forEach {
            val bytes = it.readBytes()
            safe("capture/${it.name}", bytes, deny)
            val capture = json("capture/${it.name}", bytes)
            UUID.fromString(capture.string("captureId"))
            assertTrue("capture classification", capture.string("classificationName") in setOf("UNKNOWN", "waiting_for_offer", "dropoff_pre_arrival"))
        }
        listOf(h.skeletonRoot, h.envelopeRoot).forEach { root ->
            root.walkTopDown().filter { it.isFile }.forEach {
                safe("${root.name}/${it.name}", it.readBytes(), deny + if (root == h.skeletonRoot) listOf(Inputs.BENIGN) else emptyList())
            }
        }
        File(h.skeletonRoot.parentFile, "inflight.json").takeIf { it.isFile }?.let { safe("skeleton/inflight", it.readBytes(), deny) }
        h.requests.forEachIndexed { i, request ->
            if (request.body.size > 0) safe("request/$i${request.path}", request.body.toByteArray(),
                deny + if (request.path == "/v1/skeletons") listOf(Inputs.BENIGN, "Current dash", "Continue") else emptyList())
            request.marker?.let { safe("request/$i/inflight", it.toByteArray(), deny) }
            request.queued.forEach { safe("request/$i/queued/${it.name}", it.bytes.toByteArray(), deny) }
        }
        safe("shareable.log", h.shareable(), deny, isJson = false)
    }
    private fun safe(surface: String, bytes: ByteArray, deny: List<String>, isJson: Boolean = true) {
        val raw = bytes.toString(Charsets.UTF_8)
        // Decode escapes across the whole text, including JSON split across log lines.
        val unescaped = Regex("""\\(?:u([0-9a-fA-F]{4})|([\\"/]))""").replace(raw) { match ->
            val hex = match.groupValues[1]
            if (hex.isNotEmpty()) hex.toInt(16).toChar().toString() else match.groupValues[2]
        }
        val decoded = if (isJson) {
            strings(privacyExportDecode(surface) { Json.parseToJsonElement(raw) })
        } else raw.lineSequence().flatMap { line ->
            // Logs may contain a complete JSON value or a prefixed object/array.
            val candidates = listOf(line, line.substringAfter(": ", line)) +
                line.indices.filter { line[it] == '{' || line[it] == '[' }.map { line.substring(it) }
            candidates.mapNotNull { candidate ->
                runCatching { privacyExportDecode(surface) { Json.parseToJsonElement(candidate) } }.getOrNull()
            }.flatMap(::strings).asSequence()
        }.toList()
        deny.forEachIndexed { index, token ->
            val id = token.takeIf { it in Inputs.forbidden + listOf(Inputs.BENIGN, Inputs.LOG, Inputs.DEBUG) } ?: "protected-token-$index"
            assertFalse("$surface leaked $id", raw.contains(token) || unescaped.contains(token) || decoded.any { token in it })
        }
    }
    private fun strings(value: JsonElement): List<String> = when (value) {
        is JsonObject -> value.keys.toList() + value.values.flatMap(::strings)
        is JsonArray -> value.flatMap(::strings)
        is JsonPrimitive -> if (value.isString) listOf(value.content) else emptyList()
    }
    private fun json(surface: String, bytes: ByteArray) = privacyExportDecode(surface) {
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
