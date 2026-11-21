package com.gdhameeja.flighttracker.data

import android.content.Context
import java.io.File

/** Server address and app token, plus the last flight list for offline use (in the air). */
class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", null) ?: com.gdhameeja.flighttracker.BuildConfig.DEFAULT_SERVER_URL
        set(v) = prefs.edit().putString("server_url", v.trim()).apply()

    var token: String
        get() = prefs.getString("token", "").orEmpty()
        set(v) = prefs.edit().putString("token", v.trim()).apply()

    val isConnected: Boolean get() = serverUrl.isNotEmpty() && token.isNotEmpty()

    var lastUpdateCheck: Long
        get() = prefs.getLong("last_update_check", 0)
        set(v) = prefs.edit().putLong("last_update_check", v).apply()

    private val cacheFile get() = File(context.filesDir, "flights.json")

    fun saveFlights(list: FlightList) {
        runCatching { cacheFile.writeText(json.encodeToString(FlightList.serializer(), list)) }
    }

    fun loadFlights(): FlightList =
        runCatching { json.decodeFromString(FlightList.serializer(), cacheFile.readText()) }.getOrDefault(FlightList(emptyList()))
}
