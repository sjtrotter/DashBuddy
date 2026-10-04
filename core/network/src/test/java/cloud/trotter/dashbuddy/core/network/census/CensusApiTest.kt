package cloud.trotter.dashbuddy.core.network.census

import android.util.Log
import cloud.trotter.dashbuddy.core.network.di.ClientProfile
import cloud.trotter.dashbuddy.core.network.di.NetworkClientFactory
import cloud.trotter.census.contract.auth.Bearer
import cloud.trotter.census.contract.auth.CensusHeaders
import cloud.trotter.census.contract.auth.InstallSecret
import cloud.trotter.census.contract.auth.RequestSigner
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import timber.log.Timber

class CensusApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: CensusApi
    private lateinit var client: OkHttpClient
    private val credential = Bearer.Credential("12345678-1234-4123-8123-123456789abc", InstallSecret.encode(ByteArray(32) { it.toByte() }))

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        api = CensusApi(client, server.url("/").toString().removeSuffix("/"))
    }

    @After fun tearDown() {
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test fun `exact transmitted bytes are signed with bearer timestamp and signature`() = runTest {
        respond(200, accepted)
        val items = listOf("{\"fingerprint\":\"${"a".repeat(64)}\", \"root\":{}}", "{\"root\":{}}")
        val batch = CensusApi.batchId(listOf("a".repeat(64), "b".repeat(64)))
        assertTrue(api.uploadSkeletons(credential, batch, items) is UploadResult.Accepted)
        val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        val bytes = requireNotNull(request.body).toByteArray()
        assertArrayEquals(CensusApi.batchBody(batch, items), bytes)
        assertEquals(Bearer.format(credential.installId, credential.secret), request.headers[CensusHeaders.AUTHORIZATION])
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("dashbuddy-census/1", request.headers["User-Agent"])
        val ts = requireNotNull(request.headers[CensusHeaders.TIMESTAMP])
        assertTrue(RequestSigner.timestampInWindow(ts, System.currentTimeMillis() / 1000))
        assertTrue(RequestSigner.verify(credential.secret, RequestSigner.canonical("POST", "/v1/skeletons", ts, bytes),
            requireNotNull(request.headers[CensusHeaders.SIGNATURE])))
    }

    @Test fun `batch id is stable for permutations and distinguishes multiplicity`() {
        assertEquals(CensusApi.batchId(listOf("a", "b")), CensusApi.batchId(listOf("b", "a")))
        assertEquals(32, CensusApi.batchId(listOf("a", "b")).length)
        assertFalse(CensusApi.batchId(listOf("a", "b")) == CensusApi.batchId(listOf("a", "a", "b")))
    }

    @Test fun `enrol is bearer only and policy remains available`() = runTest {
        respond(200, "{\"installIdPrefix\":\"12345678\",\"acceptedSchemaIds\":[\"uinode.skeleton.v1\"]}")
        assertTrue(api.enrol(credential, "test") is EnrolResult.Enrolled)
        val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertNull(request.headers[CensusHeaders.TIMESTAMP])
        assertNull(request.headers[CensusHeaders.SIGNATURE])
        assertEquals(Bearer.format(credential.installId, credential.secret), request.headers[CensusHeaders.AUTHORIZATION])
        assertEquals("dashbuddy-census/1", request.headers["User-Agent"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("{\"installId\":\"${credential.installId}\",\"appVersion\":\"test\",\"schemaIds\":[\"uinode.skeleton.v1\"]}",
            requireNotNull(request.body).utf8())
        respond(200, "{\"acceptedHashDomains\":[1]}")
        assertTrue(api.policy() is PolicyResult.Available)
        val policyRequest = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertNull(policyRequest.headers[CensusHeaders.AUTHORIZATION])
        assertEquals("dashbuddy-census/1", policyRequest.headers["User-Agent"])
        respond(503, "{\"error\":\"db_unavailable\"}")
        assertEquals(PolicyResult.Unavailable(503), api.policy())
    }

    @Test fun `all upload statuses map without internal retry`() = runTest {
        val cases = listOf(
            Triple(200, accepted, UploadResult.Accepted(2, 1, mapOf("bad_hash" to 1), CensusBudget(298, 1000, 39, 100))),
            Triple(200, "{\"status\":\"duplicate\",\"accepted\":0,\"duplicate\":3,\"rejected\":{}}", UploadResult.Duplicate),
            Triple(422, "{\"error\":\"batch_quality\",\"rejected\":{\"bad_hash\":2}}", UploadResult.BatchQuality(mapOf("bad_hash" to 2))),
            Triple(429, "{\"error\":\"budget_exhausted\"}", UploadResult.BudgetExhausted(17)),
            Triple(429, "{\"error\":\"rate_limited\"}", UploadResult.RateLimited(17)),
            Triple(401, "{\"error\":\"unauthorized\"}", UploadResult.Unauthorized(null)),
            Triple(401, "{\"error\":\"revoked\"}", UploadResult.Revoked),
            Triple(400, "{\"error\":\"bad_request\"}", UploadResult.BadRequest),
            Triple(413, "{\"error\":\"batch_too_large\"}", UploadResult.PayloadTooLarge),
            Triple(413, "{\"error\":\"payload_too_large\"}", UploadResult.PayloadTooLarge),
            Triple(408, "{\"error\":\"request_timeout\"}", UploadResult.ServerUnavailable(408)),
            Triple(500, "{\"error\":\"internal_error\"}", UploadResult.ServerUnavailable(500)),
            Triple(503, "{\"error\":\"db_unavailable\"}", UploadResult.ServerUnavailable(503)),
        )
        for ((status, body, expected) in cases) {
            respond(status, body)
            assertEquals(expected, api.uploadSkeletons(credential, "batch", listOf("{}")))
        }
        assertEquals(cases.size, server.requestCount)
    }

    @Test fun `health statuses map without internal retry`() = runTest {
        val cases = listOf(
            Triple(200, """{"status":"accepted","accepted":1,"rejected":{}}""", HealthResult.Accepted(1, emptyMap())),
            Triple(422, """{"error":"batch_quality","rejected":{"bad_count":1}}""", HealthResult.BatchQuality(mapOf("bad_count" to 1))),
            Triple(422, """{"error":"batch_quality","rejected":{"bad_rule_id":1}}""", HealthResult.BatchQuality(mapOf("bad_rule_id" to 1))),
            Triple(429, """{"error":"budget_exhausted"}""", HealthResult.BudgetExhausted(17)),
            Triple(429, """{"error":"rate_limited"}""", HealthResult.RateLimited(17)),
            Triple(401, """{"error":"unauthorized"}""", HealthResult.Unauthorized(null)),
            Triple(401, """{"error":"revoked"}""", HealthResult.Revoked),
            Triple(413, """{"error":"payload_too_large"}""", HealthResult.PayloadTooLarge),
        )
        for ((status, body, expected) in cases) {
            respond(status, body)
            assertEquals(expected, api.postHealth(credential, listOf("{}")))
        }
        assertEquals(cases.size, server.requestCount)
    }

    @Test fun `health exact report envelope is signed with all three auth headers`() = runTest {
        respond(200, """{"status":"accepted","accepted":1,"rejected":{}}""")
        val report = """{"day":"2026-10-01", "ruleCounts":{}}"""
        assertEquals(HealthResult.Accepted(1, emptyMap()), api.postHealth(credential, listOf(report)))
        val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        val bytes = requireNotNull(request.body).toByteArray()
        assertArrayEquals("""{"reports":[$report]}""".toByteArray(Charsets.UTF_8), bytes)
        assertEquals("POST", request.method)
        assertEquals("/v1/health", request.url.encodedPath)
        assertEquals(Bearer.format(credential.installId, credential.secret), request.headers[CensusHeaders.AUTHORIZATION])
        val ts = requireNotNull(request.headers[CensusHeaders.TIMESTAMP])
        assertTrue(RequestSigner.timestampInWindow(ts, System.currentTimeMillis() / 1000))
        assertTrue(RequestSigner.verify(credential.secret, RequestSigner.canonical("POST", "/v1/health", ts, bytes),
            requireNotNull(request.headers[CensusHeaders.SIGNATURE])))
    }

    @Test fun `503 retry after zero never retries inside OkHttp`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0")
            .body("{\"error\":\"db_unavailable\"}").build())
        // A spare response makes an accidental internal retry fail promptly rather than time out.
        respond(200, accepted)
        assertEquals(UploadResult.ServerUnavailable(503), api.uploadSkeletons(credential, "batch", listOf("{}")))
        assertEquals(1, server.requestCount)
    }

    @Test fun `all enrollment statuses map and malformed response is transport failure`() = runTest {
        val cases = listOf(
            Triple(409, "install_exists", EnrolResult.InstallExists),
            Triple(401, "unauthorized", EnrolResult.Unauthorized),
            Triple(401, "revoked", EnrolResult.Revoked),
            Triple(429, "rate_limited", EnrolResult.RateLimited(17)),
            Triple(408, "request_timeout", EnrolResult.ServerUnavailable(408)),
            Triple(500, "internal_error", EnrolResult.ServerUnavailable(500)),
            Triple(503, "db_unavailable", EnrolResult.ServerUnavailable(503)),
            Triple(400, "bad_request", EnrolResult.RequestRejected(400)),
        )
        for ((status, error, expected) in cases) {
            respond(status, "{\"error\":\"$error\"}")
            assertEquals(expected, api.enrol(credential, "test"))
        }
        respond(200, "not json")
        assertTrue(api.uploadSkeletons(credential, "batch", listOf("{}")) is UploadResult.TransportFailure)
    }

    @Test fun `server date is retained and arbitrary rejection text never escapes`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).addHeader("Date", "Fri, 02 Oct 2026 00:00:00 GMT")
            .body("{\"error\":\"unauthorized\"}").build())
        assertEquals(UploadResult.Unauthorized(1790899200000L), api.uploadSkeletons(credential, "batch", listOf("{}")))
        respond(422, "{\"error\":\"batch_quality\",\"rejected\":{\"untrusted response text\":1}}")
        assertEquals(UploadResult.BatchQuality(mapOf("unknown_reason" to 1)), api.uploadSkeletons(credential, "batch", listOf("{}")))
    }

    @Test fun `every endpoint rejects a one MiB response including unknown lengths`() = runTest {
        val oversized = "x".repeat(1024 * 1024)
        for (chunked in listOf(false, true)) {
            fun enqueue() {
                val response = MockResponse.Builder().code(200)
                if (chunked) response.chunkedBody(oversized, 8192) else response.body(oversized)
                server.enqueue(response.build())
            }
            enqueue()
            assertEquals(EnrolResult.TransportFailure("oversized_response"), api.enrol(credential, "test"))
            enqueue()
            assertEquals(PolicyResult.TransportFailure("oversized_response"), api.policy())
            enqueue()
            assertEquals(UploadResult.TransportFailure("oversized_response"), api.uploadSkeletons(credential, "batch", listOf("{}")))
        }
    }

    @Test fun `response bound accepts exactly 64 KiB and rejects one byte more`() = runTest {
        val body = "{}".padEnd(65_536, ' ')
        server.enqueue(MockResponse.Builder().code(200).chunkedBody(body, 8192).build())
        assertTrue(api.policy() is PolicyResult.Available)
        server.enqueue(MockResponse.Builder().code(200).chunkedBody(body + " ", 8192).build())
        assertEquals(PolicyResult.TransportFailure("oversized_response"), api.policy())
    }

    @Test fun `retry after a year is capped to one day`() = runTest {
        for (header in listOf("31536000", Long.MAX_VALUE.toString(), "99999999999999999999999999")) {
            server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", header)
                .body("{\"error\":\"rate_limited\"}").build())
            assertEquals(UploadResult.RateLimited(86_400), api.uploadSkeletons(credential, "batch", listOf("{}")))
        }
    }

    @Test fun `Census profile logs request line at verbose but redacts secrets and bodies`() = runTest {
        val logs = CopyOnWriteArrayList<Pair<Int, String>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (tag == "Census") logs += priority to message
            }
        }
        val censusClient = NetworkClientFactory.okHttpClient("Census", ClientProfile.Census)
        val census = CensusApi(censusClient, server.url("/").toString().removeSuffix("/"))
        Timber.plant(tree)
        try {
            respond(200, accepted)
            val fingerprint = "cafe".repeat(16)
            val item = "{\"fingerprint\":\"$fingerprint\"}"
            assertTrue(census.uploadSkeletons(credential, "private-batch", listOf(item)) is UploadResult.Accepted)
            val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            val signature = requireNotNull(request.headers[CensusHeaders.SIGNATURE])
            assertTrue(logs.any { (priority, line) -> priority == Log.VERBOSE && line.startsWith("--> POST ") && line.contains("/v1/skeletons") })
            for (sensitive in listOf(credential.secret, signature, fingerprint, "private-batch", item, accepted)) {
                assertFalse(logs.any { (_, line) -> line.contains(sensitive) })
            }
            assertEquals(10_000, censusClient.connectTimeoutMillis)
        } finally {
            Timber.uproot(tree)
            censusClient.dispatcher.executorService.shutdown()
            censusClient.connectionPool.evictAll()
        }
    }

    @Test fun `envelopes use the exact item bytes and sign their own path`() = runTest {
        respond(200, accepted)
        val items = listOf("""{"schemaId":"uinode.v1","fingerprint":"${"a".repeat(64)}","timestamp":0}""")
        val batch = CensusApi.envelopeBatchId(listOf("b", "a"))
        assertEquals("env-" + CensusApi.batchId(listOf("a", "b")), batch)
        assertTrue(api.uploadEnvelopes(credential, batch, items) is UploadResult.Accepted)
        val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("/v1/envelopes", request.url.encodedPath)
        val bytes = requireNotNull(request.body).toByteArray()
        assertArrayEquals(CensusApi.batchBody(batch, items), bytes)
        val ts = requireNotNull(request.headers[CensusHeaders.TIMESTAMP])
        assertTrue(RequestSigner.verify(credential.secret, RequestSigner.canonical("POST", "/v1/envelopes", ts, bytes),
            requireNotNull(request.headers[CensusHeaders.SIGNATURE])))
    }

    @Test fun `envelopes decode not trusted batch quality and payload too large`() = runTest {
        for ((code, body, expected) in listOf(
            Triple(403, """{"error":"not_trusted"}""", UploadResult.NotTrusted),
            Triple(422, """{"error":"batch_quality","rejected":{"bad_hash":1}}""", UploadResult.BatchQuality(mapOf("bad_hash" to 1))),
            Triple(413, "{}", UploadResult.PayloadTooLarge),
        )) {
            respond(code, body)
            assertEquals(expected, api.uploadEnvelopes(credential, "env-batch", listOf("{}")))
            server.takeRequest(1, TimeUnit.SECONDS)
        }
    }

    private fun respond(code: Int, body: String) {
        server.enqueue(MockResponse.Builder().code(code).addHeader("Retry-After", "17").body(body).build())
    }

    private val accepted = "{\"status\":\"accepted\",\"accepted\":2,\"duplicate\":1,\"rejected\":{\"bad_hash\":1}," +
        "\"budget\":{\"skeletonsRemainingToday\":298,\"bytesRemainingToday\":1000,\"batchesRemainingToday\":39,\"resetInSeconds\":100}}"
}
