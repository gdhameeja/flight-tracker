package com.gdhameeja.flighttracker.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gdhameeja.flighttracker.data.AircraftResult
import com.gdhameeja.flighttracker.data.Alternatives
import com.gdhameeja.flighttracker.data.AirportWeather
import com.gdhameeja.flighttracker.data.Connection
import com.gdhameeja.flighttracker.data.Flight
import com.gdhameeja.flighttracker.data.FlightWeather
import com.gdhameeja.flighttracker.data.TrackedFlight
import com.gdhameeja.flighttracker.live.LeaveAlarm
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ── Connections ─────────────────────────────────────────────────────────────

fun Connection.tone(): Tone = when (level) {
    "at_risk" -> Tone.Bad
    "tight" -> Tone.Warn
    "long" -> Tone.Neutral
    else -> Tone.Good
}

fun Connection.headline(): String = when (level) {
    "at_risk" -> "At risk · ${Fmt.duration(layoverMin.toLong())} layover"
    "tight" -> "Tight · ${Fmt.duration(slackMin.toLong())} to spare"
    "long" -> "Long layover · ${Fmt.duration(layoverMin.toLong())}"
    else -> "Comfortable · ${Fmt.duration(layoverMin.toLong())} layover"
}

/** Between two flight cards in the list, and on the flight screen (expanded). */
@Composable
fun ConnectionCard(c: Connection, startExpanded: Boolean = false) {
    var open by remember { mutableStateOf(startExpanded) }
    val color = LocalTones.current.of(c.tone())
    Surface(
        onClick = { open = !open },
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔁", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Connection in ${c.airport}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(c.headline(), style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.SemiBold)
                }
                Text(if (open) "Hide" else "Why", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (open) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (c.selfTransfer) "Separate bookings: you transfer yourself" else "One booking: the airline handles the connection",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                c.steps.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text("• ${s.label}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(Fmt.duration(s.minutes.toLong()), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text("Needed / you have", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${Fmt.duration(c.neededMin.toLong())} / ${Fmt.duration(c.layoverMin.toLong())}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color)
                }
                c.notes.forEach {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ── Check-in and getting to the airport ─────────────────────────────────────

@Composable
fun CheckInCard(t: TrackedFlight) {
    val f = t.flight
    val x = t.extras ?: return
    if (f.isFinished() || f.isInAir()) return
    val ctx = LocalContext.current
    val now = Instant.now()
    val opens = Fmt.instant(x.checkinOpensAt)
    val open = opens == null || !opens.isAfter(now)
    DetailCard {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                if (open) "Online check-in is open" else "Online check-in opens ${Fmt.day(x.checkinOpensAt, f.departure.airport.timeZone)}, ${Fmt.time(x.checkinOpensAt, f.departure.airport.timeZone)}",
                style = MaterialTheme.typography.titleSmall,
            )
            if (!open) Text("You'll get a notification when it opens.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            x.checkinUrl?.let { url ->
                Spacer(Modifier.height(10.dp))
                val host = Uri.parse(url).host?.removePrefix("www.") ?: url
                if (open) Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("Check in on $host") }
                else OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("Open $host") }
            }
        }
    }
}

@Composable
fun AirportTripCard(t: TrackedFlight) {
    val f = t.flight
    val x = t.extras ?: return
    val beAt = Fmt.instant(x.beAtAirportBy) ?: return
    if (f.isFinished() || f.isInAir() || Duration.between(Instant.now(), beAt).toHours() > 36) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tz = f.departure.airport.timeZone
    var drive by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var alarmAt by remember { mutableStateOf(LeaveAlarm.scheduledAt(ctx, t.id)) }

    fun workOut() {
        val lat = f.departure.airport.lat
        val lon = f.departure.airport.lon
        if (lat == null || lon == null) { note = "Airport location unknown."; return }
        busy = true
        scope.launch {
            val here = LeaveAlarm.location(ctx)
            drive = here?.let { LeaveAlarm.driveMinutes(it.latitude, it.longitude, lat, lon) }
            note = when {
                here == null -> "Couldn't get your location."
                drive == null -> "Couldn't work out the drive."
                else -> null
            }
            busy = false
        }
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) workOut() else note = "Location permission is off." }
    LaunchedEffect(t.id) { if (LeaveAlarm.hasLocationPermission(ctx)) workOut() }

    DetailCard {
        Column(Modifier.padding(vertical = 12.dp)) {
            val term = f.departure.terminal?.let { " Terminal $it" } ?: ""
            Text("Be at ${f.departure.airport.code}$term by ${Fmt.time(x.beAtAirportBy, tz)}", style = MaterialTheme.typography.titleSmall)
            Text(
                if (x.international) "3 hours before an international flight (2½ with cabin bag only)." else "2 hours before a domestic flight (1½ with cabin bag only).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            val d = drive
            if (d != null) {
                val leave = beAt.minusSeconds(d * 60L)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Leave by ${Fmt.time(leave.toString(), tz)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("About ${Fmt.duration(d.toLong())} by car from here (no live traffic; buffer added)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val set = alarmAt
                    FilledTonalButton(onClick = {
                        if (set != null) { LeaveAlarm.cancel(ctx, t.id); alarmAt = null }
                        else if (leave.isAfter(Instant.now())) {
                            LeaveAlarm.schedule(ctx, t.id, leave.toEpochMilli(), "🚗 Time to leave for ${f.departure.airport.code}", "${f.number} departs ${Fmt.time(f.departure.best, tz)}. About ${Fmt.duration(d.toLong())} to the airport.")
                            alarmAt = leave.toEpochMilli()
                        } else note = "That time has passed: leave now!"
                    }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                        Text(if (set != null) "Alert set for ${Fmt.time(Instant.ofEpochMilli(set).toString(), tz)} ✕" else "Alert me when to leave")
                    }
                    MapsButton(f)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { if (LeaveAlarm.hasLocationPermission(ctx)) workOut() else askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                        enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f),
                    ) {
                        if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        Text("Drive time from here")
                    }
                    MapsButton(f)
                }
            }
            note?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun MapsButton(f: Flight) {
    val ctx = LocalContext.current
    val a = f.departure.airport
    OutlinedButton(onClick = {
        // Google Maps directions, with live traffic
        val dest = if (a.lat != null && a.lon != null) "${a.lat},${a.lon}" else Uri.encode("${a.name ?: a.code} airport")
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$dest&travelmode=driving")))
    }, shape = RoundedCornerShape(14.dp)) { Text("Maps") }
}

// ── Weather ─────────────────────────────────────────────────────────────────

@Composable
fun WeatherCard(f: Flight, load: suspend () -> Result<FlightWeather>) {
    var w by remember { mutableStateOf<FlightWeather?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(f.number, f.date) { load().onSuccess { w = it }.onFailure { failed = true } }
    val data = w
    if (data == null) {
        if (failed) Text("Weather unavailable right now.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Text("Loading weather…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    DetailCard {
        WeatherRow(f.departure.airport.code, "departure", data.departure)
        WeatherRow(f.arrival.airport.code, "arrival", data.arrival)
        Text(
            "Current reports and forecasts from aviationweather.gov. Forecasts reach about a day ahead.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp),
        )
    }
}

@Composable
private fun WeatherRow(code: String, which: String, w: AirportWeather?) {
    val tones = LocalTones.current
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(code, style = MaterialTheme.typography.titleSmall)
        if (w == null) { Text("No report", style = MaterialTheme.typography.bodySmall); return@Column }
        w.now?.let { n ->
            Text("Now: ${describe(n.weather, n.visibilityM, n.windKt, n.gustKt)}${n.tempC?.let { " · ${it.roundToInt()}°C" } ?: ""}", style = MaterialTheme.typography.bodyMedium)
        }
        w.atFlight?.let { a ->
            Text("At your $which: ${describe(a.weather, a.visibilityM, a.windKt, a.gustKt)}", style = MaterialTheme.typography.bodyMedium)
        }
        w.risk?.let { r ->
            Spacer(Modifier.height(4.dp))
            Text((if (r.level == "warning") "⚠️ " else "👀 ") + r.text, style = MaterialTheme.typography.bodyMedium, color = if (r.level == "warning") tones.bad else tones.warn, fontWeight = FontWeight.Medium)
        }
    }
}

private fun describe(wx: String?, vis: Int?, wind: Int?, gust: Int?): String {
    val words = mapOf("FG" to "fog", "BR" to "mist", "HZ" to "haze", "RA" to "rain", "TS" to "thunderstorms", "SH" to "showers", "DZ" to "drizzle", "FU" to "smoke", "DU" to "dust", "SN" to "snow")
    val w = wx?.let { s -> words.filterKeys { s.contains(it) }.values.distinct().joinToString(", ") }?.takeIf { it.isNotEmpty() } ?: "clear"
    val v = vis?.let { if (it >= 10_000) "10 km+" else if (it >= 1000) "%.1f km".format(it / 1000.0) else "${it} m" }
    val wnd = wind?.let { "wind ${it} kt" + (gust?.let { g -> ", gusts $g" } ?: "") }
    return listOfNotNull(w.replaceFirstChar { it.uppercase() }, v?.let { "visibility $it" }, wnd).joinToString(" · ")
}

// ── Alternatives when cancelled or very late ───────────────────────────────

@Composable
fun AlternativesCard(f: Flight, load: suspend () -> Result<Alternatives>) {
    val late = (Fmt.delayMinutes(f.departure) ?: 0) >= 120
    if (f.status != "cancelled" && !late) return
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<Alternatives?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val tz = f.departure.airport.timeZone
    DetailCard {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(if (f.status == "cancelled") "Other flights you could take" else "Very late: other flights on this route", style = MaterialTheme.typography.titleSmall)
            Text("${f.departure.airport.code} → ${f.arrival.airport.code}, next 12 hours. Show these at the airline desk.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val d = data
            if (d == null) {
                Button(onClick = {
                    busy = true
                    scope.launch { load().onSuccess { data = it }.onFailure { err = it.message }; busy = false }
                }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text("Find flights")
                }
            } else if (d.flights.isEmpty()) {
                Text("No other direct flights in the next 12 hours.", style = MaterialTheme.typography.bodyMedium)
            } else {
                d.flights.forEach { a ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.number, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(a.airline ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(Fmt.time(a.departure, tz) + (a.terminal?.let { " · T$it" } ?: ""), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

// ── Aircraft ────────────────────────────────────────────────────────────────

@Composable
fun AircraftCard(f: Flight, load: suspend () -> Result<AircraftResult>) {
    var data by remember { mutableStateOf<AircraftResult?>(null) }
    LaunchedEffect(f.aircraft.reg, f.airline.iata) { load().onSuccess { data = it } }
    val a = data?.aircraft
    val wifi = data?.wifi
    if (f.aircraft.model == null && f.aircraft.reg == null && wifi == null) return
    SectionTitle("Aircraft")
    DetailCard {
        (a?.typeName ?: a?.model ?: f.aircraft.model)?.let { DetailRow("Type", it) }
        f.aircraft.reg?.let { DetailRow("Registration", it) }
        a?.ageYears?.let { DetailRow("Age", if (it < 1) "Under a year" else "%.1f years".format(it)) }
        a?.seats?.let { DetailRow("Seats", it.toString()) }
        wifi?.let {
            DetailRow("Wi-Fi", when (it.likely) { "yes" -> "Likely"; "no" -> "No"; else -> "On some planes" })
            Text(it.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
        }
        if (f.aircraft.reg == null) Text("The exact plane is usually known a few hours before departure.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
    }
}

// ── Stats (Past tab) ────────────────────────────────────────────────────────

data class Stats(val flights: Int, val km: Int, val hours: Int, val airports: Int, val airlines: Int, val countries: Int)

fun stats(list: List<TrackedFlight>): Stats {
    val flown = list.map { it.flight }.filter { it.status != "cancelled" }
    var km = 0.0
    var minutes = 0L
    for (f in flown) {
        val a = f.departure.airport
        val b = f.arrival.airport
        if (a.lat != null && a.lon != null && b.lat != null && b.lon != null) km += haversineKm(a.lat, a.lon, b.lat, b.lon)
        // Scheduled block time; if unknown, about 800 km an hour plus half an hour
        minutes += Fmt.flightMinutes(f) ?: if (a.lat != null && b.lat != null) (haversineKm(a.lat, a.lon!!, b.lat, b.lon!!) / 800 * 60 + 30).toLong() else 0
    }
    return Stats(
        flights = flown.size,
        km = km.roundToInt(),
        hours = (minutes / 60).toInt(),
        airports = flown.flatMap { listOf(it.departure.airport.code, it.arrival.airport.code) }.toSet().size,
        airlines = flown.mapNotNull { it.airline.iata }.toSet().size,
        countries = flown.flatMap { listOfNotNull(it.departure.airport.countryCode, it.arrival.airport.countryCode) }.toSet().size,
    )
}

fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(sqrt(h))
}

@Composable
fun StatsCard(s: Stats) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Your flying", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                StatCell("Flights", s.flights.toString(), Modifier.weight(1f))
                StatCell("Distance", "%,d km".format(s.km), Modifier.weight(1.4f))
                StatCell("In the air", "${s.hours} h", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                StatCell("Airports", s.airports.toString(), Modifier.weight(1f))
                StatCell("Airlines", s.airlines.toString(), Modifier.weight(1.4f))
                StatCell("Countries", s.countries.toString(), Modifier.weight(1f))
            }
            if (s.km > 0) {
                Spacer(Modifier.height(8.dp))
                Text("That's %.1f× around the Earth.".format(s.km / 40_075.0), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
    }
}
