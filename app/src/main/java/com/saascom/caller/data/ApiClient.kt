package com.saascom.caller.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Failures the UI can tell apart. */
sealed class ApiException(message: String?, cause: Throwable? = null) : Exception(message, cause) {
    /** No answer from the server (no internet, timeout, DNS…). Worth retrying later. */
    class Network(cause: Throwable) : ApiException(cause.message, cause)

    /**
     * The server answered with an error; [message] is the CRM's Arabic message when there is one,
     * [body] the raw JSON (e.g. the existing lead_id of a 409).
     */
    class Http(val code: Int, message: String?, val body: String? = null) : ApiException(message) {
        /**
         * A 403 from a policy check (the lead belongs to another agent). The CRM sends Laravel's
         * untranslated English text for it, unlike its own Arabic 403 («الحساب معطّل»).
         */
        val isPolicyDenial: Boolean get() = code == 403 && (message.isNullOrBlank() || message == LARAVEL_DENIED)

        /** What to show the agent: the CRM's Arabic text, [forbidden] for a policy denial, else [fallback]. */
        fun userMessage(forbidden: String, fallback: String): String = when {
            isPolicyDenial -> forbidden
            else -> message?.takeIf { it.isNotBlank() } ?: fallback
        }

        companion object {
            const val LARAVEL_DENIED = "This action is unauthorized."
        }
    }
}

/** Result of POST /leads. */
sealed interface CreateLeadResult {
    data class Created(val leadId: Long) : CreateLeadResult

    /** 409: the number already belongs to this lead. */
    data class Exists(val leadId: Long, val message: String?) : CreateLeadResult
}

/** Result of GET /directory with If-None-Match. */
sealed interface DirectoryResult {
    data object NotModified : DirectoryResult
    data class Updated(val body: DirectoryResponse, val etag: String?) : DirectoryResult
}

/**
 * Client for /api/mobile/… (phases/api-contract.md).
 *
 * [onUnauthorized] runs on every 401: the token is gone (logout elsewhere, user disabled,
 * phone disconnected by an admin), so the app must go back to the login screen.
 */
class ApiClient(
    private val baseUrl: () -> String,
    private val token: () -> String?,
    private val appVersion: Int,
    private val onUnauthorized: () -> Unit,
    baseClient: OkHttpClient = OkHttpClient(),
) {
    private val client = baseClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** While the phone rings the card must not wait long: give up after 3 s and show local info. */
    private val lookupClient = client.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    // Every call switches to Dispatchers.IO itself: the response body is read from the socket,
    // which Android forbids on the main thread. Callers can use these from any coroutine.

    suspend fun login(email: String, password: String, deviceName: String): LoginResponse = io {
        val json = ApiJson.encodeToString(LoginRequest.serializer(), LoginRequest(email, password, deviceName))
        val request = request(url("login"), authenticated = false).post(json.toRequestBody(JSON)).build()
        execute(client, request).use { decode(it, LoginResponse.serializer()) }
    }

    suspend fun logout() = io {
        execute(client, request("logout").post(ByteArray(0).toRequestBody()).build()).close()
    }

    suspend fun directory(etag: String?): DirectoryResult = io {
        val builder = request("directory")
        if (!etag.isNullOrEmpty()) builder.header("If-None-Match", etag)
        execute(client, builder.build()).use { response ->
            if (response.code == 304) {
                DirectoryResult.NotModified
            } else {
                DirectoryResult.Updated(decode(response, DirectoryResponse.serializer()), response.header("ETag"))
            }
        }
    }

    suspend fun lookup(phone: String): LookupResponse = io {
        val url = url("lookup").newBuilder().addQueryParameter("phone", phone).build()
        execute(lookupClient, request(url).build()).use { decode(it, LookupResponse.serializer()) }
    }

    suspend fun appVersion(): AppVersionDto = io {
        execute(client, request("app-version").build()).use { decode(it, AppVersionDto.serializer()) }
    }

    /** POST /leads/{id}/calls. Build [body] with [logCallBody]. */
    suspend fun logCall(leadId: Long, body: JsonObject): LogCallResponse = io {
        execute(client, post(callsPath(leadId), body.toString())).use { decode(it, LogCallResponse.serializer()) }
    }

    /** POST /leads: a 409 is a normal answer here (the number already exists), not an error. */
    suspend fun createLead(body: CreateLeadRequest): CreateLeadResult = io {
        val json = ApiJson.encodeToString(CreateLeadRequest.serializer(), body)
        try {
            execute(client, post(LEADS_PATH, json)).use {
                CreateLeadResult.Created(decode(it, CreateLeadResponse.serializer()).leadId ?: 0)
            }
        } catch (e: ApiException.Http) {
            val existing = e.body?.let { runCatching { ApiJson.decodeFromString(CreateLeadResponse.serializer(), it) }.getOrNull() }
            if (e.code == 409 && existing?.leadId != null) CreateLeadResult.Exists(existing.leadId, e.message) else throw e
        }
    }

    /** POST /leads/{id}/requests: an SAV request (client_request note). Returns its id. */
    suspend fun createRequest(leadId: Long, body: CreateRequestRequest): Long = io {
        val json = ApiJson.encodeToString(CreateRequestRequest.serializer(), body)
        execute(client, post(requestsPath(leadId), json)).use { decode(it, CreateRequestResponse.serializer()).requestId }
    }

    /** Replays a saved write (offline queue). [path] is relative to /api/mobile/, e.g. "leads/12/calls". */
    suspend fun postRaw(path: String, json: String) = io {
        execute(client, post(path, json)).close()
    }

    private fun post(path: String, json: String): Request = request(path).post(json.toRequestBody(JSON)).build()

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    // --- plumbing -------------------------------------------------------------------------

    private fun url(path: String) = "${baseUrl().trimEnd('/')}/api/mobile/$path".toHttpUrl()

    private fun request(path: String): Request.Builder = request(url(path))

    private fun request(url: okhttp3.HttpUrl, authenticated: Boolean = true): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-App-Version", appVersion.toString())
        if (authenticated) token()?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun <T> decode(response: Response, serializer: KSerializer<T>): T {
        val text = response.body?.string().orEmpty()
        return ApiJson.decodeFromString(serializer, text)
    }

    /** Runs the call; throws [ApiException.Http] for 4xx/5xx (except 304) after handling 401. */
    private suspend fun execute(client: OkHttpClient, request: Request): Response {
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw ApiException.Network(e)
        }
        if (response.isSuccessful || response.code == 304) return response

        val body = response.use { runCatching { it.body?.string() }.getOrNull() }
        val message = body?.let { runCatching { ApiJson.decodeFromString(ErrorDto.serializer(), it).message }.getOrNull() }
        if (response.code == 401 && request.header("Authorization") != null) onUnauthorized()
        throw ApiException.Http(response.code, message, body)
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = continuation.resume(response)
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
        })
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val LEADS_PATH = "leads"

        fun callsPath(leadId: Long) = "leads/$leadId/calls"

        fun requestsPath(leadId: Long) = "leads/$leadId/requests"
    }
}
