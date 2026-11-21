package com.gdhameeja.flighttracker.ui

import com.gdhameeja.flighttracker.data.Flight
import com.gdhameeja.flighttracker.data.FlightEnd
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Times shown local to the airport they happen at. */
object Fmt {
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun instant(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

    fun zone(tz: String?): ZoneId = tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    fun time(iso: String?, tz: String?): String = instant(iso)?.atZone(zone(tz))?.format(hhmm) ?: "--:--"

    fun day(iso: String?, tz: String?): String = instant(iso)?.atZone(zone(tz))?.format(dayFmt) ?: ""

    /** "2 h 05 min", "45 min" */
    fun duration(minutes: Long): String {
        val m = abs(minutes)
        return when {
            m < 60 -> "$m min"
            m % 60 == 0L -> "${m / 60} h"
            else -> "${m / 60} h ${"%02d".format(m % 60)} min"
        }
    }

    /** "in 3 h 20 min", "12 min ago", "in 2 days" */
    fun relative(iso: String?, now: Instant = Instant.now()): String {
        val t = instant(iso) ?: return ""
        val min = Duration.between(now, t).toMinutes()
        val body = if (abs(min) >= 48 * 60) "${abs(min) / (24 * 60)} days" else duration(min)
        return when {
            abs(min) < 1 -> "now"
            min > 0 -> "in $body"
            else -> "$body ago"
        }
    }

    /** Minutes late against the schedule (negative = early), or null. */
    fun delayMinutes(end: FlightEnd): Long? {
        val best = instant(end.actual ?: end.estimated) ?: return null
        val sched = instant(end.scheduled) ?: return null
        return Duration.between(sched, best).toMinutes()
    }

    /** Gate-to-gate time. */
    fun flightMinutes(f: Flight): Long? {
        val d = instant(f.departure.best) ?: return null
        val a = instant(f.arrival.best) ?: return null
        return Duration.between(d, a).toMinutes().takeIf { it > 0 }
    }
}

enum class Tone { Good, Warn, Bad, Live, Neutral }

data class StatusInfo(val label: String, val tone: Tone)

private val IN_AIR = setOf("departed", "en_route", "approaching")

fun Flight.isInAir() = status in IN_AIR

/**
 * When the flight is over: landing time, else take-off, else (bookings with no
 * times in the email) the end of its date at the departure airport.
 */
fun Flight.endsAt(): Instant? =
    Fmt.instant(arrival.best ?: departure.best)
        ?: runCatching { java.time.LocalDate.parse(date).atTime(23, 59).atZone(Fmt.zone(departure.airport.timeZone)).toInstant() }.getOrNull()

fun Flight.isFinished() = status == "arrived" || status == "cancelled" || status == "diverted"

fun Flight.statusInfo(now: Instant = Instant.now()): StatusInfo {
    val depLate = Fmt.delayMinutes(departure) ?: 0
    val arrLate = Fmt.delayMinutes(arrival) ?: 0
    // Over, but never seen landing (history from email, or tracking stopped): just "Flown"
    val over = endsAt()?.let { Duration.between(it, now).toHours() >= 2 } == true
    if (over && status !in setOf("arrived", "cancelled", "diverted")) return StatusInfo("Flown", Tone.Neutral)
    return when (status) {
        "cancelled" -> StatusInfo("Cancelled", Tone.Bad)
        "diverted" -> StatusInfo("Diverted", Tone.Bad)
        "arrived" -> if (arrLate >= 15) StatusInfo("Landed ${Fmt.duration(arrLate)} late", Tone.Warn) else StatusInfo("Landed", Tone.Good)
        in IN_AIR -> if (arrLate >= 15) StatusInfo("In the air · ${Fmt.duration(arrLate)} late", Tone.Warn) else StatusInfo("In the air", Tone.Live)
        "boarding" -> StatusInfo("Boarding", Tone.Live)
        "gate_closed" -> StatusInfo("Gate closed", Tone.Warn)
        else -> when {
            depLate >= 15 -> StatusInfo("Delayed ${Fmt.duration(depLate)}", Tone.Warn)
            status == "check_in" -> StatusInfo("Check-in open", Tone.Good)
            Fmt.instant(departure.best)?.let { Duration.between(now, it).toHours() in 0..47 } == true -> StatusInfo("On time", Tone.Good)
            else -> StatusInfo("Scheduled", Tone.Neutral)
        }
    }
}

/** 0..1 through the flight, for the progress bar; null when not in the air. */
fun Flight.progress(now: Instant = Instant.now()): Float? {
    if (!isInAir()) return null
    val d = Fmt.instant(departure.actual ?: departure.best) ?: return null
    val a = Fmt.instant(arrival.best) ?: return null
    val total = Duration.between(d, a).seconds.toFloat()
    if (total <= 0) return null
    return (Duration.between(d, now).seconds / total).coerceIn(0.02f, 0.98f)
}
