package com.gdhameeja.flighttracker.data

import kotlinx.serialization.Serializable

// Mirrors worker/src/model.ts. Times are ISO-8601 UTC; each airport has its time zone.

@Serializable
data class Airport(
    val iata: String? = null,
    val icao: String? = null,
    val name: String? = null,
    val city: String? = null,
    val countryCode: String? = null,
    val timeZone: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
) {
    val code: String get() = iata ?: icao ?: "???"
    val label: String get() = city ?: name ?: code
}

@Serializable
data class FlightEnd(
    val airport: Airport = Airport(),
    val scheduled: String? = null,
    val estimated: String? = null,
    val actual: String? = null,
    val terminal: String? = null,
    val gate: String? = null,
    val checkInDesk: String? = null,
    val baggageBelt: String? = null,
) {
    /** Best-known time: actual, then estimated, then scheduled. */
    val best: String? get() = actual ?: estimated ?: scheduled
}

@Serializable
data class Airline(val name: String? = null, val iata: String? = null, val icao: String? = null)

@Serializable
data class Aircraft(val model: String? = null, val reg: String? = null)

@Serializable
data class Flight(
    val number: String,
    val date: String,
    val airline: Airline = Airline(),
    val status: String = "unknown",
    val departure: FlightEnd = FlightEnd(),
    val arrival: FlightEnd = FlightEnd(),
    val aircraft: Aircraft = Aircraft(),
    val source: String = "",
    val fetchedAt: String = "",
)

@Serializable
data class FlightEvent(val at: String, val kind: String, val title: String, val body: String)

/** A tracked flight as the server returns it. */
@Serializable
data class TrackedFlight(
    val id: String,
    val flight: Flight,
    val nextCheckAt: String? = null,
    val events: List<FlightEvent> = emptyList(),
)

@Serializable
data class FlightList(val flights: List<TrackedFlight>)

/** One flight number, several legs: which one is yours? */
@Serializable
data class LegChoice(val from: String? = null, val to: String? = null, val departure: String? = null)

@Serializable
data class AddResult(
    val choices: List<LegChoice>? = null,
    val id: String? = null,
    val flight: Flight? = null,
    val nextCheckAt: String? = null,
    val events: List<FlightEvent> = emptyList(),
)

@Serializable
data class Usage(val aerodatabox: ProviderUsage? = null)

@Serializable
data class ProviderUsage(val unitsRemaining: Int? = null, val checkedAt: String? = null, val monthlyLimit: Int? = null)

@Serializable
data class ApiError(val error: String? = null)
