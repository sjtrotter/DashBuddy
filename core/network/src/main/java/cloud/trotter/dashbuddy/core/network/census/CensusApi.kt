package cloud.trotter.dashbuddy.core.network.census

import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.auth.Bearer
import cloud.trotter.census.contract.auth.CensusHeaders
import cloud.trotter.census.contract.auth.RequestSigner
import cloud.trotter.dashbuddy.core.network.di.ClientProfile
import cloud.trotter.dashbuddy.core.network.di.NetworkClientFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface CensusTransport {
    suspend fun enrol(cred: Bearer.Credential, appVersion: String): EnrolResult
    suspend fun policy(): PolicyResult
    suspend fun uploadSkeletons(cred: Bearer.Credential, batchId: String, itemsJson: List<String>): UploadResult
}

sealed interface EnrolResult {
    data class Enrolled(val policy: JsonObject) : EnrolResult
    data object InstallExists : EnrolResult
    data object Unauthorized : EnrolResult
    data object Revoked : EnrolResult
    data class RateLimited(val retryAfter: Long) : EnrolResult
    data class ServerUnavailable(val status: Int) : EnrolResult
    data class RequestRejected(val status: Int) : EnrolResult
    data class TransportFailure(val failureClass: String) : EnrolResult
}

sealed interface PolicyResult {
    data class Available(val policy: JsonObject) : PolicyResult
    data class Unavailable(val status: Int) : PolicyResult
    data class TransportFailure(val failureClass: String) : PolicyResult
}

data class CensusBudget(
    val skeletonsRemainingToday: Int,
    val bytesRemainingToday: Int,
    val batchesRemainingToday: Int,
    val resetInSeconds: Int,
)

sealed interface UploadResult {
    data class Accepted(val accepted: Int, val duplicate: Int, val rejected: Map<String, Int>, val budget: CensusBudget) : UploadResult
    data object Duplicate : UploadResult
    data object PayloadTooLarge : UploadResult
    data object BadRequest : UploadResult
    data class BatchQuality(val rejected: Map<String, Int>) : UploadResult
    data class BudgetExhausted(val retryAfterSeconds: Long) : UploadResult
    data class Unauthorized(val serverDateMillis: Long?) : UploadResult
    data object Revoked : UploadResult
    data class RateLimited(val retryAfter: Long) : UploadResult
    data class ServerUnavailable(val status: Int) : UploadResult
    data class TransportFailure(val failureClass: String) : UploadResult
}

/** Factory is the injectable seam for workers; callers never configure their own HTTP logging. */
@Singleton
open class CensusApiFactory @Inject constructor() {
    private val client by lazy { NetworkClientFactory.okHttpClient("Census", ClientProfile.Census) }
    open fun create(baseUrl: String): CensusTransport = CensusApi(client, baseUrl)
}

/**
 * Plain OkHttp; no transport retry, redirect or body re-serialization. The worker owns retries.
 * Application request data is limited to the bearer, timestamp and signature (upload only),
 * constant User-Agent `dashbuddy-census/1`, Content-Type `application/json`, and the body:
 * protocol-required installId/appVersion/schemaIds on enrol, batchId/items on upload.
 * Enrol uses the bearer without timestamp/signature; policy is a bodyless, unauthenticated GET
 * with the same User-Agent. No other application metadata is sent.
 */
class CensusApi(client: OkHttpClient, private val baseUrl: String) : CensusTransport {
    private val client = client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // OkHttp replays GET /policy on 503 + Retry-After: 0 even with connection retries off.
            // This status is returned to the caller; it has no delay field in our result contract.
            if (response.code == 503 && response.header("Retry-After") == "0") {
                response.newBuilder().removeHeader("Retry-After").build()
            } else response
        }
        .build()

    override suspend fun enrol(cred: Bearer.Credential, appVersion: String): EnrolResult = try {
        val body = "{\"installId\":${JsonPrimitive(cred.installId)},\"appVersion\":${JsonPrimitive(appVersion)}," +
            "\"schemaIds\":[${JsonPrimitive(SkeletonSchema.SCHEMA_ID)}]}"
        val response = execute(request("/v1/enroll", body.toByteArray(Charsets.UTF_8), cred, signed = false))
        when (response.status) {
            200 -> EnrolResult.Enrolled(response.json())
            409 -> EnrolResult.InstallExists
            401 -> if (response.error() == "revoked") EnrolResult.Revoked else EnrolResult.Unauthorized
            429 -> EnrolResult.RateLimited(response.retryAfter)
            408, in 500..599 -> EnrolResult.ServerUnavailable(response.status)
            else -> EnrolResult.RequestRejected(response.status)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        EnrolResult.TransportFailure(e.failureToken())
    }

    override suspend fun policy(): PolicyResult = try {
        val response = execute(Request.Builder().url("$baseUrl/v1/policy").header("User-Agent", USER_AGENT).get().build())
        if (response.status == 200) PolicyResult.Available(response.json()) else PolicyResult.Unavailable(response.status)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PolicyResult.TransportFailure(e.failureToken())
    }

    override suspend fun uploadSkeletons(
        cred: Bearer.Credential,
        batchId: String,
        itemsJson: List<String>,
    ): UploadResult = try {
        val bytes = batchBody(batchId, itemsJson)
        val response = execute(request("/v1/skeletons", bytes, cred, signed = true))
        when (response.status) {
            200 -> {
                val json = response.json()
                when (json.getValue("status").jsonPrimitive.content) {
                    "duplicate" -> UploadResult.Duplicate
                    "accepted" -> {
                        val budget = json.getValue("budget").jsonObject
                        UploadResult.Accepted(json.int("accepted"), json.int("duplicate"), reasons(json), CensusBudget(
                            budget.int("skeletonsRemainingToday"), budget.int("bytesRemainingToday"),
                            budget.int("batchesRemainingToday"), budget.int("resetInSeconds"),
                        ))
                    }
                    else -> UploadResult.TransportFailure("InvalidResponse")
                }
            }
            400 -> UploadResult.BadRequest
            401 -> if (response.error() == "revoked") UploadResult.Revoked else UploadResult.Unauthorized(response.dateMillis)
            413 -> UploadResult.PayloadTooLarge
            422 -> UploadResult.BatchQuality(reasons(response.json()))
            429 -> if (response.error() == "budget_exhausted") UploadResult.BudgetExhausted(response.retryAfter)
                else UploadResult.RateLimited(response.retryAfter)
            else -> UploadResult.ServerUnavailable(response.status)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UploadResult.TransportFailure(e.failureToken())
    }

    private fun request(path: String, body: ByteArray, cred: Bearer.Credential, signed: Boolean): Request {
        val builder = Request.Builder().url("$baseUrl$path")
            .header("User-Agent", USER_AGENT)
            .header(CensusHeaders.AUTHORIZATION, Bearer.format(cred.installId, cred.secret))
            .post(object : RequestBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength(): Long = body.size.toLong()
                // retryOnConnectionFailure(false) alone still permits a 503 Retry-After: 0 replay.
                override fun isOneShot(): Boolean = true
                override fun writeTo(sink: BufferedSink) { sink.write(body) }
            })
        if (signed) {
            val ts = (System.currentTimeMillis() / 1000).toString()
            builder.header(CensusHeaders.TIMESTAMP, ts)
            builder.header(CensusHeaders.SIGNATURE, RequestSigner.sign(cred.secret, RequestSigner.canonical("POST", path, ts, body)))
        }
        return builder.build()
    }

    private data class Reply(val status: Int, val body: String, val retryAfter: Long, val dateMillis: Long?) {
        fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject
        fun error(): String? = runCatching { json()["error"]?.jsonPrimitive?.content }.getOrNull()
        override fun toString(): String = "Reply(status=$status)"
    }

    private suspend fun execute(request: Request): Reply = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        val now = System.currentTimeMillis()
                        val retry = it.header("Retry-After")
                        val seconds = retry?.toLongOrNull()
                            ?: if (!retry.isNullOrEmpty() && retry.all { digit -> digit in '0'..'9' }) 86_400L
                            else parseDate(retry)?.let { date -> ((date - now + 999) / 1000).coerceAtLeast(1) } ?: 60L
                        if (it.body.contentLength() > MAX_RESPONSE_BYTES) throw OversizedResponse()
                        val body = it.peekBody(MAX_RESPONSE_BYTES)
                        // Unknown-length/chunked responses need one byte of lookahead to detect overflow.
                        if (body.contentLength() == MAX_RESPONSE_BYTES &&
                            it.body.source().request(MAX_RESPONSE_BYTES + 1)) throw OversizedResponse()
                        Reply(it.code, body.string(), seconds.coerceIn(1, 86_400), parseDate(it.header("Date")))
                    }
                    continuation.resume(reply)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }

    private class OversizedResponse : IOException()

    companion object {
        private const val MAX_RESPONSE_BYTES = 65_536L
        private fun Exception.failureToken(): String =
            if (this is OversizedResponse) "oversized_response" else javaClass.simpleName

        private const val USER_AGENT = "dashbuddy-census/1"

        fun batchId(fingerprints: List<String>): String = MessageDigest.getInstance("SHA-256")
            .digest(fingerprints.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(32)

        fun batchBody(batchId: String, itemsJson: List<String>): ByteArray =
            "{\"batchId\":${JsonPrimitive(batchId)},\"items\":[${itemsJson.joinToString(",")}]}".toByteArray(Charsets.UTF_8)

        private fun parseDate(value: String?): Long? = value?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        }

        private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int.also { require(it >= 0) }

        // Never pass arbitrary server strings into counters/logs. Unknown reasons collapse to one code.
        private val REASONS = setOf(
            "bad_item", "unknown_schema", "unknown_field", "plaintext_field", "too_deep", "too_many_nodes",
            "bad_kind", "bad_hash", "hash_on_withheld_kind", "missing_hash", "bad_id", "bad_class",
            "hash_domain_mismatch", "bad_platform", "bad_day", "stale_day", "bad_version", "too_large", "fingerprint_mismatch",
        )

        private fun reasons(json: JsonObject): Map<String, Int> {
            val result = sortedMapOf<String, Int>()
            json.getValue("rejected").jsonObject.forEach { (key, value) ->
                val reason = if (key in REASONS) key else "unknown_reason"
                val count = value.jsonPrimitive.int.also { require(it >= 0) }
                result[reason] = Math.addExact(result[reason] ?: 0, count)
            }
            return result
        }
    }
}
