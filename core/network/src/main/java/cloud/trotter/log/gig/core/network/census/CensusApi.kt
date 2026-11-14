package cloud.trotter.log.gig.core.network.census

import cloud.trotter.census.contract.SkeletonRejectionReason
import cloud.trotter.census.contract.auth.Bearer
import cloud.trotter.census.contract.auth.CensusHeaders
import cloud.trotter.census.contract.auth.RequestSigner
import cloud.trotter.log.gig.core.network.di.ClientProfile
import cloud.trotter.log.gig.core.network.di.NetworkClientFactory
import cloud.trotter.log.gig.domain.census.CensusEnrolRejectReason
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
import timber.log.Timber
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface CensusTransport {
    /** [schemaIds] is what this identity declares it can produce (#1522: the caller narrows it to the policy). */
    suspend fun enrol(cred: Bearer.Credential, appVersion: String, schemaIds: List<String>): EnrolResult
    suspend fun postHealth(cred: Bearer.Credential, reportsJson: List<String>): HealthResult
    suspend fun policy(): PolicyResult
    suspend fun uploadSkeletons(cred: Bearer.Credential, batchId: String, itemsJson: List<String>): UploadResult
    suspend fun uploadEnvelopes(cred: Bearer.Credential, batchId: String, itemsJson: List<String>): UploadResult
    /** #1622: `DELETE /v1/installs/me/data`, signed, empty body. The credential stays valid after a deletion. */
    suspend fun deleteData(cred: Bearer.Credential): DeleteResult
    /**
     * #1625: signed `POST /v1/contributor/grant` with the body [CensusApi.GRANT_REQUEST_BODY], `{"eligible":true}`.
     * The phone computes eligibility and calls this only when it is true, so there is no parameter: no count and no
     * `false` is ever sent.
     */
    suspend fun requestContributorGrant(cred: Bearer.Credential): GrantResult
    /** #1625: signed `GET /v1/contributor/grant`, the grant's state on the server. */
    suspend fun contributorGrant(cred: Bearer.Credential): GrantResult
}

/** #1625: the server's answer about a contributor grant. Only the closed fields below leave the transport. */
sealed interface GrantResult {
    /** [grantedDay] and [expiresDay] are the server's UTC days, checked to be real `YYYY-MM-DD` dates. */
    data class Granted(val grantedDay: String, val expiresDay: String) : GrantResult
    /** The state read: the grant ran out. */
    data object Expired : GrantResult
    /** The state read: this install holds no grant. */
    data object None : GrantResult
    /** `403 grant_revoked`, or `revoked` on the state read: the operator's revocation stands. */
    data object GrantRevoked : GrantResult
    /** `403 install_too_new`: enrolled fewer than 8 UTC days ago. */
    data object InstallTooNew : GrantResult
    /** `403 not_eligible`. */
    data object NotEligible : GrantResult
    data class Unauthorized(val serverDateMillis: Long?) : GrantResult
    /** `401 revoked`: the census ID itself is revoked. */
    data object InstallRevoked : GrantResult
    data class RateLimited(val retryAfter: Long) : GrantResult
    data class ServerUnavailable(val status: Int) : GrantResult
    data class TransportFailure(val failureClass: String) : GrantResult
}

/**
 * #1622: told the exact bytes of each census request body the server read (it answered 200, 400 or 422), for the
 * phone's "What gig.log sent" record. A failure here never changes the request's result.
 */
fun interface CensusSentObserver {
    suspend fun read(path: String, body: ByteArray)

    companion object {
        val NONE = CensusSentObserver { _, _ -> }
    }
}

/** #1622: the server's answer to "delete my census data". Only the closed fields below leave the transport. */
sealed interface DeleteResult {
    /** [deletedDay] is the server's UTC day, checked to be `YYYY-MM-DD`; [rows] is the count it erased. */
    data class Deleted(val rows: Int, val deletedDay: String) : DeleteResult
    data class Unauthorized(val serverDateMillis: Long?) : DeleteResult
    data object Revoked : DeleteResult
    data class RateLimited(val retryAfter: Long) : DeleteResult
    /** 404 or 405: the server does not serve the endpoint yet (census#79 not deployed). */
    data class NotAvailable(val status: Int) : DeleteResult
    data class ServerUnavailable(val status: Int) : DeleteResult
    data class TransportFailure(val failureClass: String) : DeleteResult
}

sealed interface EnrolResult {
    data class Enrolled(val policy: JsonObject) : EnrolResult
    data object InstallExists : EnrolResult
    data object Revoked : EnrolResult
    data class RateLimited(val retryAfter: Long) : EnrolResult
    data class ServerUnavailable(val status: Int) : EnrolResult
    data class RequestRejected(val status: Int, val reason: CensusEnrolRejectReason) : EnrolResult
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
    data object NotTrusted : UploadResult
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

sealed interface HealthResult {
    data class Accepted(val accepted: Int, val rejected: Map<String, Int>) : HealthResult
    data class BatchQuality(val rejected: Map<String, Int>) : HealthResult
    data object BadRequest : HealthResult
    data object PayloadTooLarge : HealthResult
    data class BudgetExhausted(val retryAfterSeconds: Long) : HealthResult
    data class RateLimited(val retryAfter: Long) : HealthResult
    data class Unauthorized(val serverDateMillis: Long?) : HealthResult
    data object Revoked : HealthResult
    data class ServerUnavailable(val status: Int) : HealthResult
    data class TransportFailure(val failureClass: String) : HealthResult
}

/** Factory is the injectable seam for workers; callers never configure their own HTTP logging. */
@Singleton
open class CensusApiFactory @Inject constructor(private val sent: CensusSentObserver) {
    private val client by lazy { NetworkClientFactory.okHttpClient("Census", ClientProfile.Census) }
    open fun create(baseUrl: String): CensusTransport = CensusApi(client, baseUrl, sent)
}

/**
 * Plain OkHttp; no transport retry, redirect or body re-serialization. The worker owns retries.
 * Application request data is limited to the bearer, timestamp and signature (skeletons, health and deletion),
 * constant User-Agent `dashbuddy-census/1`, Content-Type `application/json`, and the body:
 * protocol-required installId/appVersion/schemaIds on enrol, batchId/items on skeleton upload, reports on health.
 * Enrol uses the bearer without timestamp/signature; policy is a bodyless, unauthenticated GET
 * with the same User-Agent; deletion is a signed DELETE with an empty body; the contributor grant (#1625) is a signed
 * POST of exactly [GRANT_REQUEST_BODY] or a signed bodyless GET. No other application metadata is sent.
 * [sent] is told each upload body the server read, byte for byte (#1622).
 */
class CensusApi(
    client: OkHttpClient,
    private val baseUrl: String,
    private val sent: CensusSentObserver = CensusSentObserver.NONE,
) : CensusTransport {
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

    override suspend fun enrol(cred: Bearer.Credential, appVersion: String, schemaIds: List<String>): EnrolResult = try {
        val body = "{\"installId\":${JsonPrimitive(cred.installId)},\"appVersion\":${JsonPrimitive(appVersion)}," +
            "\"schemaIds\":[${schemaIds.joinToString(",") { JsonPrimitive(it).toString() }}]}"
        val response = execute(request("/v1/enroll", body.toByteArray(Charsets.UTF_8), cred, signed = false))
        when (response.status) {
            200 -> EnrolResult.Enrolled(response.json())
            409 -> EnrolResult.InstallExists
            401 -> if (response.isRevoked()) EnrolResult.Revoked else response.rejected()
            429 -> EnrolResult.RateLimited(response.retryAfter)
            408, in 500..599 -> EnrolResult.ServerUnavailable(response.status)
            else -> response.rejected()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        EnrolResult.TransportFailure(e.failureToken())
    }

    // The body is already bounded by MAX_RESPONSE_BYTES; only the closed enum leaves this method.
    private fun Reply.rejected(): EnrolResult = EnrolResult.RequestRejected(status,
        CensusEnrolRejectReason.fromWire(error()) ?: CensusEnrolRejectReason.UNKNOWN_REASON)

    override suspend fun policy(): PolicyResult = try {
        val response = execute(Request.Builder().url("$baseUrl/v1/policy").header("User-Agent", USER_AGENT).get().build())
        if (response.status == 200) PolicyResult.Available(response.json()) else PolicyResult.Unavailable(response.status)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PolicyResult.TransportFailure(e.failureToken())
    }

    override suspend fun uploadSkeletons(cred: Bearer.Credential, batchId: String, itemsJson: List<String>): UploadResult =
        upload("/v1/skeletons", cred, batchId, itemsJson)

    override suspend fun uploadEnvelopes(cred: Bearer.Credential, batchId: String, itemsJson: List<String>): UploadResult =
        upload("/v1/envelopes", cred, batchId, itemsJson)

    private suspend fun upload(
        path: String,
        cred: Bearer.Credential,
        batchId: String,
        itemsJson: List<String>,
    ): UploadResult = try {
        val bytes = batchBody(batchId, itemsJson)
        val response = execute(request(path, bytes, cred, signed = true))
        recordRead(path, response.status, bytes)
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
            401 -> if (response.isRevoked()) UploadResult.Revoked else UploadResult.Unauthorized(response.dateMillis)
            403 -> if (path == "/v1/envelopes" && response.error() == "not_trusted") UploadResult.NotTrusted
                else UploadResult.ServerUnavailable(response.status)
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

    override suspend fun postHealth(cred: Bearer.Credential, reportsJson: List<String>): HealthResult = try {
        val bytes = ("{\"reports\":[" + reportsJson.joinToString(",") + "]}").toByteArray(Charsets.UTF_8)
        val response = execute(request("/v1/health", bytes, cred, signed = true))
        recordRead("/v1/health", response.status, bytes)
        when (response.status) {
            200 -> {
                val json = response.json()
                if (json.getValue("status").jsonPrimitive.content == "accepted") {
                    HealthResult.Accepted(json.int("accepted"), reasons(json))
                } else HealthResult.TransportFailure("InvalidResponse")
            }
            400 -> HealthResult.BadRequest
            401 -> if (response.isRevoked()) HealthResult.Revoked else HealthResult.Unauthorized(response.dateMillis)
            413 -> HealthResult.PayloadTooLarge
            422 -> HealthResult.BatchQuality(reasons(response.json()))
            429 -> if (response.error() == "budget_exhausted") HealthResult.BudgetExhausted(response.retryAfter)
                else HealthResult.RateLimited(response.retryAfter)
            else -> HealthResult.ServerUnavailable(response.status)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        HealthResult.TransportFailure(e.failureToken())
    }

    override suspend fun deleteData(cred: Bearer.Credential): DeleteResult = try {
        val response = execute(request(DELETE_DATA_PATH, ByteArray(0), cred, signed = true, method = "DELETE"))
        when (response.status) {
            200 -> {
                val json = response.json()
                val day = json.getValue("deletedDay").jsonPrimitive.content
                if (json.getValue("status").jsonPrimitive.content == "deleted" && DAY.matches(day)) {
                    // Parsing the day checks it is a real date, so only a date ever reaches the phone's store.
                    DeleteResult.Deleted(json.int("rows"), java.time.LocalDate.parse(day).toString())
                } else DeleteResult.TransportFailure("InvalidResponse")
            }
            401 -> if (response.isRevoked()) DeleteResult.Revoked else DeleteResult.Unauthorized(response.dateMillis)
            429 -> DeleteResult.RateLimited(response.retryAfter)
            404, 405 -> DeleteResult.NotAvailable(response.status)
            else -> DeleteResult.ServerUnavailable(response.status)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DeleteResult.TransportFailure(e.failureToken())
    }

    override suspend fun requestContributorGrant(cred: Bearer.Credential): GrantResult =
        grant(request(GRANT_PATH, GRANT_REQUEST_BODY.toByteArray(Charsets.UTF_8), cred, signed = true))

    override suspend fun contributorGrant(cred: Bearer.Credential): GrantResult =
        grant(request(GRANT_PATH, ByteArray(0), cred, signed = true, method = "GET"))

    private suspend fun grant(request: Request): GrantResult = try {
        val response = execute(request)
        when (response.status) {
            200 -> {
                val json = response.json()
                when (json.getValue("status").jsonPrimitive.content) {
                    "granted" -> {
                        val granted = day(json, "grantedDay")
                        val expires = day(json, "expiresDay")
                        if (granted != null && expires != null) GrantResult.Granted(granted, expires)
                        else GrantResult.TransportFailure("InvalidResponse")
                    }
                    "expired" -> GrantResult.Expired
                    "revoked" -> GrantResult.GrantRevoked
                    "none" -> GrantResult.None
                    else -> GrantResult.TransportFailure("InvalidResponse")
                }
            }
            401 -> if (response.isRevoked()) GrantResult.InstallRevoked else GrantResult.Unauthorized(response.dateMillis)
            403 -> when (response.error()) {
                "install_too_new" -> GrantResult.InstallTooNew
                "not_eligible" -> GrantResult.NotEligible
                "grant_revoked" -> GrantResult.GrantRevoked
                else -> GrantResult.ServerUnavailable(response.status)
            }
            429 -> GrantResult.RateLimited(response.retryAfter)
            else -> GrantResult.ServerUnavailable(response.status)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GrantResult.TransportFailure(e.failureToken())
    }

    /** Only a real date leaves the transport: the day is parsed, so a malformed one is no day at all. */
    private fun day(json: JsonObject, key: String): String? {
        val value = json[key]?.jsonPrimitive?.content ?: return null
        if (!DAY.matches(value)) return null
        return runCatching { java.time.LocalDate.parse(value).toString() }.getOrNull()
    }

    /** #1622: a body the server read goes to the sent record; the record can never change the request's result. */
    private suspend fun recordRead(path: String, status: Int, body: ByteArray) {
        if (status != 200 && status != 400 && status != 422) return
        try {
            sent.read(path, body)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Timber.tag("Census").w("census sent record failed")
        }
    }

    private fun request(path: String, body: ByteArray, cred: Bearer.Credential, signed: Boolean, method: String = "POST"): Request {
        val requestBody = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength(): Long = body.size.toLong()
            // retryOnConnectionFailure(false) alone still permits a 503 Retry-After: 0 replay.
            override fun isOneShot(): Boolean = true
            override fun writeTo(sink: BufferedSink) { sink.write(body) }
        }
        val builder = Request.Builder().url("$baseUrl$path")
            .header("User-Agent", USER_AGENT)
            .header(CensusHeaders.AUTHORIZATION, Bearer.format(cred.installId, cred.secret))
        // A GET carries no body; it is signed over the empty body.
        if (method == "GET") builder.get() else builder.method(method, requestBody)
        if (signed) {
            val ts = (System.currentTimeMillis() / 1000).toString()
            builder.header(CensusHeaders.TIMESTAMP, ts)
            builder.header(CensusHeaders.SIGNATURE, RequestSigner.sign(cred.secret, RequestSigner.canonical(method, path, ts, body)))
        }
        return builder.build()
    }

    private data class Reply(val status: Int, val body: String, val retryAfter: Long, val dateMillis: Long?) {
        fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject
        fun isRevoked(): Boolean = error() == "revoked"
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
        const val DELETE_DATA_PATH = "/v1/installs/me/data"
        const val GRANT_PATH = "/v1/contributor/grant"
        /** #1625: the whole grant request: a yes, never a count. */
        const val GRANT_REQUEST_BODY = "{\"eligible\":true}"
        private val DAY = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

        fun batchId(fingerprints: List<String>): String = MessageDigest.getInstance("SHA-256")
            .digest(fingerprints.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }.take(32)

        fun envelopeBatchId(itemsJson: List<String>): String = "env-" + MessageDigest.getInstance("SHA-256")
            .digest(itemsJson.joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }.take(32)

        fun batchBody(batchId: String, itemsJson: List<String>): ByteArray =
            "{\"batchId\":${JsonPrimitive(batchId)},\"items\":[${itemsJson.joinToString(",")}]}".toByteArray(Charsets.UTF_8)

        private fun parseDate(value: String?): Long? = value?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        }

        private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int.also { require(it >= 0) }

        // Never pass arbitrary server strings into counters/logs. Unknown reasons collapse to one code.
        private val REASONS = SkeletonRejectionReason.entries.map { it.wire }.toSet() +
            setOf("bad_count", "bad_rule_id") // Health report reasons share this bounded decoder.

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
