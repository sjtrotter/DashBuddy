package cloud.trotter.dashbuddy.core.network.di

import cloud.trotter.dashbuddy.core.network.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for Retrofit/OkHttp client assembly across [core:network].
 *
 * Before this existed, every API module hand-rolled its own [OkHttpClient] +
 * [Retrofit.Builder], and the two copies had already diverged on the #348 security
 * hardening (one redacted the `api_key` query param and set timeouts, the other did
 * neither). Centralizing the assembly here makes the secure path the *default* path:
 * any provider that calls [okHttpClient] gets debug-gated, Timber-piped BODY logging
 * **with secret redaction and standard timeouts baked in** — there is no longer a
 * per-module step to remember.
 *
 * Providers pass only what differs (base URL, logging tag, and — if the upstream
 * carries secrets in additional query params — extra param names to redact).
 */
object NetworkClientFactory {

    private const val DEFAULT_TIMEOUT_SECONDS = 30L

    /**
     * Query parameters redacted from HTTP logs by default (#348). The EIA gas-price API
     * carries its secret as an `api_key` query param, so it must never reach logcat or
     * the persisted file log. Redaction is the default so a new caller cannot forget it.
     */
    private val DEFAULT_REDACTED_QUERY_PARAMS = listOf("api_key")

    /**
     * Lenient JSON for government APIs whose schemas we do not control: ignore unknown
     * keys, tolerate loose typing, coerce nulls into defaults. Shared so both gov-API
     * providers parse identically.
     */
    val govApiJson: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * A [OkHttpClient] with the #348 hardening baked in as the default:
     *  - **Debug-only** Timber-piped BODY [HttpLoggingInterceptor] (release gets no HTTP
     *    logging at all), with [redactedQueryParams] stripped so secrets never reach logs.
     *  - Standard connect/read/write timeouts.
     *
     * @param logTag Timber tag for the HTTP log lines (debug builds only).
     * @param redactedQueryParams query-param names to redact from logs; defaults to the
     *   shared secret list (`api_key`). Pass a superset if the upstream carries more secrets.
     */
    fun okHttpClient(
        logTag: String,
        redactedQueryParams: List<String> = DEFAULT_REDACTED_QUERY_PARAMS,
    ): OkHttpClient = OkHttpClient.Builder()
        .apply {
            // Debug builds only — release gets no HTTP logging at all (#348).
            if (BuildConfig.DEBUG) {
                val timberLogger = HttpLoggingInterceptor.Logger { message ->
                    Timber.tag(logTag).v(message)
                }
                addInterceptor(
                    HttpLoggingInterceptor(timberLogger).apply {
                        level = HttpLoggingInterceptor.Level.BODY
                        redactedQueryParams.forEach { redactQueryParams(it) }
                    }
                )
            }
        }
        .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Builds a [Retrofit] for [baseUrl] over [client], decoding JSON via [json]
     * (defaults to [govApiJson]). The kotlinx-serialization converter is wired here so
     * no provider repeats the media-type/converter boilerplate.
     */
    fun retrofit(
        baseUrl: String,
        client: OkHttpClient,
        json: Json = govApiJson,
    ): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }
}
