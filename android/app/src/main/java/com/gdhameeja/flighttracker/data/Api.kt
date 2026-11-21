package com.gdhameeja.flighttracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String, val code: Int = 0) : Exception(message)

val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/** Talks to the Cloudflare Worker (worker/src/index.ts). */
class Api(private val settings: Settings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun flights(): FlightList = json.decodeFromString(call("GET", "/api/flights"))

    suspend fun weather(id: String): FlightWeather = json.decodeFromString(call("GET", "/api/flights/$id/weather"))

    suspend fun alternatives(id: String): Alternatives = json.decodeFromString(call("GET", "/api/flights/$id/alternatives"))

    suspend fun aircraft(id: String): AircraftResult = json.decodeFromString(call("GET", "/api/flights/$id/aircraft"))

    suspend fun userSettings(): UserSettings = json.decodeFromString(call("GET", "/api/settings"))

    suspend fun saveUserSettings(s: UserSettings): UserSettings =
        json.decodeFromString(call("PUT", "/api/settings", json.encodeToString(UserSettings.serializer(), s)))

    suspend fun flight(id: String): TrackedFlight = json.decodeFromString(call("GET", "/api/flights/$id"))

    suspend fun refresh(id: String): TrackedFlight = json.decodeFromString(call("POST", "/api/flights/$id/refresh"))

    suspend fun delete(id: String) {
        call("DELETE", "/api/flights/$id")
    }

    suspend fun add(number: String, date: String, from: String? = null): AddResult {
        val body = json.encodeToString(AddRequest.serializer(), AddRequest(number, date, from))
        return json.decodeFromString(call("POST", "/api/flights", body))
    }

    /** Trades the 6-digit pairing code for the app token (the one call without a token). */
    suspend fun pair(code: String): String {
        val body = json.encodeToString(PairRequest.serializer(), PairRequest(code))
        return json.decodeFromString(PairResult.serializer(), call("POST", "/api/pair/claim", body, auth = false)).token
    }

    suspend fun registerPush(token: String) {
        call("POST", "/api/push-token", json.encodeToString(PushToken.serializer(), PushToken(token)))
    }

    suspend fun usage(): Usage = json.decodeFromString(call("GET", "/api/usage"))

    private suspend fun call(method: String, path: String, body: String? = null, auth: Boolean = true): String = withContext(Dispatchers.IO) {
        val base = settings.serverUrl.trimEnd('/')
        if (base.isEmpty() || (auth && settings.token.isEmpty())) throw ApiException("Not connected to your server yet. Open Settings.")
        val req = Request.Builder()
            .url(base + path)
            .apply { if (auth) header("Authorization", "Bearer ${settings.token}") }
            .method(method, body?.toRequestBody("application/json".toMediaType()) ?: if (method == "POST") ByteArray(0).toRequestBody() else null)
            .build()
        try {
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    val msg = runCatching { json.decodeFromString<ApiError>(text).error }.getOrNull()
                    throw ApiException(msg ?: "Server said ${res.code}", res.code)
                }
                text
            }
        } catch (e: IOException) {
            throw ApiException("No connection to the server. Showing what was saved.")
        }
    }
}

@kotlinx.serialization.Serializable
private data class AddRequest(val number: String, val date: String, val from: String? = null)

@kotlinx.serialization.Serializable
private data class PairRequest(val code: String)

@kotlinx.serialization.Serializable
private data class PairResult(val token: String)

@kotlinx.serialization.Serializable
private data class PushToken(val token: String)
