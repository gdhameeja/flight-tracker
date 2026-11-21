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

/** Found in your booking email. */
@Serializable
data class Booking(
    val pnr: String? = null,
    val seats: String? = null,
    val passengers: List<String> = emptyList(),
    val airlineName: String? = null,
)

/** Check-in and airport timing, worked out by the server. */
@Serializable
data class Extras(
    val checkinUrl: String? = null,
    val checkinOpensAt: String? = null,
    val beAtAirportBy: String? = null,
    val international: Boolean = false,
)

/** A tracked flight as the server returns it. */
@Serializable
data class TrackedFlight(
    val id: String,
    val flight: Flight,
    val nextCheckAt: String? = null,
    val events: List<FlightEvent> = emptyList(),
    val booking: Booking? = null,
    val extras: Extras? = null,
)

@Serializable
data class Step(val label: String, val minutes: Int)

/** Two flights in a row at the same airport (worker/src/connections.ts). */
@Serializable
data class Connection(
    val fromId: String,
    val toId: String,
    val airport: String,
    val layoverMin: Int,
    val neededMin: Int,
    val slackMin: Int,
    val level: String, // comfortable, tight, at_risk, long
    val selfTransfer: Boolean,
    val steps: List<Step> = emptyList(),
    val notes: List<String> = emptyList(),
)

@Serializable
data class FlightList(val flights: List<TrackedFlight>, val connections: List<Connection> = emptyList())

@Serializable
data class Conditions(
    val visibilityM: Int? = null,
    val windKt: Int? = null,
    val gustKt: Int? = null,
    val weather: String? = null,
    val tempC: Double? = null,
)

@Serializable
data class WeatherRisk(val level: String, val text: String)

@Serializable
data class AirportWeather(val icao: String, val now: Conditions? = null, val atFlight: Conditions? = null, val risk: WeatherRisk? = null)

@Serializable
data class FlightWeather(val departure: AirportWeather? = null, val arrival: AirportWeather? = null)

@Serializable
data class Alternative(val number: String, val airline: String? = null, val departure: String? = null, val terminal: String? = null, val status: String = "")

@Serializable
data class Alternatives(val flights: List<Alternative> = emptyList())

@Serializable
data class Wifi(val likely: String, val note: String)

@Serializable
data class AircraftInfo(
    val reg: String,
    val model: String? = null,
    val typeName: String? = null,
    val airline: String? = null,
    val ageYears: Double? = null,
    val firstFlight: String? = null,
    val seats: Int? = null,
    val engines: String? = null,
)

@Serializable
data class AircraftResult(val aircraft: AircraftInfo? = null, val wifi: Wifi? = null)

@Serializable
data class UserSettings(val checkedBags: Boolean = true)

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
