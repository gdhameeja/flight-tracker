package com.gdhameeja.flighttracker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gdhameeja.flighttracker.data.AddResult
import com.gdhameeja.flighttracker.data.Api
import com.gdhameeja.flighttracker.data.ApiException
import com.gdhameeja.flighttracker.data.Connection
import com.gdhameeja.flighttracker.data.FlightList
import com.gdhameeja.flighttracker.data.Settings
import com.gdhameeja.flighttracker.data.TrackedFlight
import com.gdhameeja.flighttracker.data.Usage
import com.gdhameeja.flighttracker.data.UserSettings
import com.gdhameeja.flighttracker.live.Sync
import com.gdhameeja.flighttracker.update.Updater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val flights: List<TrackedFlight> = emptyList(),
    val connections: List<Connection> = emptyList(),
    val userSettings: UserSettings? = null,
    val refreshing: Boolean = false,
    val connected: Boolean = false,
    val message: String? = null,
    val update: Updater.Release? = null,
    val updateProgress: Float? = null,
    val updateChecking: Boolean = false,
    val usage: Usage? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    private val api = Api(settings)
    val updater = Updater(app)

    private val _state = settings.loadFlights().let { MutableStateFlow(UiState(flights = it.flights, connections = it.connections, connected = settings.isConnected)) }
    val state: StateFlow<UiState> = _state

    init {
        if (settings.isConnected) load()
        // Look for a new version at most twice a day
        if (System.currentTimeMillis() - settings.lastUpdateCheck > 12 * 3600_000L) checkForUpdate(manual = false)
    }

    fun flight(id: String): TrackedFlight? = _state.value.flights.firstOrNull { it.id == id }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun say(msg: String) = _state.update { it.copy(message = msg) }

    /** Reads the stored list from the server (costs no API units). */
    fun load(quiet: Boolean = false) = viewModelScope.launch {
        if (!quiet) _state.update { it.copy(refreshing = true) }
        try {
            val list = api.flights()
            store(list)
        } catch (e: ApiException) {
            if (!quiet) say(e.message ?: "Couldn't load")
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
    }

    /** Asks the server to look the flight up right now (uses API units). */
    fun refresh(id: String) = viewModelScope.launch {
        _state.update { it.copy(refreshing = true) }
        try {
            replace(api.refresh(id))
        } catch (e: ApiException) {
            say(e.message ?: "Couldn't refresh")
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
    }

    /** Re-reads one flight from the server's copy (free); used while its screen is open. */
    fun reload(id: String) = viewModelScope.launch {
        runCatching { replace(api.flight(id)) }
    }

    private fun replace(f: TrackedFlight) {
        val s = _state.value
        val list = if (s.flights.any { it.id == f.id }) s.flights.map { if (it.id == f.id) f else it } else s.flights + f
        store(FlightList(list, s.connections))
        // Times changed: the connections may have too
        load(quiet = true)
    }

    /** Keeps the list, saves it for offline, and updates the widget and travel-day notification. */
    private fun store(list: FlightList) {
        settings.saveFlights(list)
        _state.update { it.copy(flights = list.flights, connections = list.connections) }
        Sync.onFlights(getApplication(), list)
    }

    suspend fun add(number: String, date: String, from: String?): Result<AddResult> = runCatching {
        val r = api.add(number, date, from)
        if (r.id != null && r.flight != null) replace(TrackedFlight(r.id, r.flight, r.nextCheckAt, r.events))
        r
    }

    fun delete(id: String) = viewModelScope.launch {
        try {
            api.delete(id)
            val s = _state.value
            store(FlightList(s.flights.filterNot { it.id == id }, s.connections.filterNot { it.fromId == id || it.toId == id }))
        } catch (e: ApiException) {
            say(e.message ?: "Couldn't delete")
        }
    }

    /** Saves the server address and token, then checks they work. */
    fun connect(url: String, token: String, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        settings.serverUrl = url
        settings.token = token
        try {
            val u = api.usage()
            _state.update { it.copy(connected = true, usage = u) }
            say("Connected to your server")
            com.gdhameeja.flighttracker.push.Push.register(getApplication())
            load()
            onDone(true)
        } catch (e: ApiException) {
            _state.update { it.copy(connected = false) }
            say(e.message ?: "Couldn't connect")
            onDone(false)
        }
    }

    /** Connects with the 6-digit code from the laptop (see docs/SETUP.md). */
    fun pair(code: String, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        try {
            val token = api.pair(code)
            connect(settings.serverUrl, token, onDone)
        } catch (e: ApiException) {
            say(e.message ?: "Couldn't pair")
            onDone(false)
        }
    }

    fun loadUsage() = viewModelScope.launch {
        runCatching { api.usage() }.onSuccess { u -> _state.update { it.copy(usage = u) } }
        runCatching { api.userSettings() }.onSuccess { u -> _state.update { it.copy(userSettings = u) } }
    }

    fun setCheckedBags(on: Boolean) = viewModelScope.launch {
        _state.update { it.copy(userSettings = UserSettings(on)) }
        runCatching { api.saveUserSettings(UserSettings(on)) }
            .onSuccess { load(quiet = true) }
            .onFailure { say("Couldn't save: ${it.message}") }
    }

    // On-demand extras for the flight screen (weather is free; the others use AeroDataBox units)
    suspend fun weather(id: String) = runCatching { api.weather(id) }
    suspend fun alternatives(id: String) = runCatching { api.alternatives(id) }
    suspend fun aircraft(id: String) = runCatching { api.aircraft(id) }

    fun checkForUpdate(manual: Boolean) = viewModelScope.launch {
        _state.update { it.copy(updateChecking = true) }
        try {
            val rel = updater.check()
            settings.lastUpdateCheck = System.currentTimeMillis()
            _state.update { it.copy(update = rel) }
            if (rel != null) say("Update available: ${rel.name}")
            else if (manual) say("You're on the latest version")
        } catch (e: Exception) {
            if (manual) say("Couldn't check for updates: ${e.message}")
        } finally {
            _state.update { it.copy(updateChecking = false) }
        }
    }

    fun installUpdate() = viewModelScope.launch {
        val rel = _state.value.update ?: return@launch
        if (!updater.canInstall()) {
            say("Allow installs from Flights, then tap Install again")
            updater.openInstallPermission()
            return@launch
        }
        try {
            _state.update { it.copy(updateProgress = 0f) }
            val apk = updater.download(rel) { p -> _state.update { it.copy(updateProgress = p) } }
            updater.install(apk)
        } catch (e: Exception) {
            say("Update failed: ${e.message}")
        } finally {
            _state.update { it.copy(updateProgress = null) }
        }
    }
}
