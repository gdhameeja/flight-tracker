package com.gdhameeja.flighttracker.live

import android.content.Context
import com.gdhameeja.flighttracker.data.Api
import com.gdhameeja.flighttracker.data.FlightList
import com.gdhameeja.flighttracker.data.Settings
import com.gdhameeja.flighttracker.widget.NextFlightWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Whenever the flight list changes: the travel-day notification and the home-screen widget follow. */
object Sync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun onFlights(context: Context, list: FlightList) {
        val app = context.applicationContext
        LiveNotification.update(app, list.flights)
        scope.launch { runCatching { NextFlightWidget.refresh(app) } }
    }

    /** Fetch the latest list in the background (after a push), then update everything. */
    fun pull(context: Context) {
        val app = context.applicationContext
        val settings = Settings(app)
        if (!settings.isConnected) return
        scope.launch {
            runCatching { Api(settings).flights() }.onSuccess { list ->
                settings.saveFlights(list)
                onFlights(app, list)
            }
        }
    }
}
