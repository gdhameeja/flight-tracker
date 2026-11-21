package com.gdhameeja.flighttracker

import com.gdhameeja.flighttracker.data.Airport
import com.gdhameeja.flighttracker.data.Flight
import com.gdhameeja.flighttracker.data.FlightEnd
import com.gdhameeja.flighttracker.ui.Fmt
import com.gdhameeja.flighttracker.ui.Tone
import com.gdhameeja.flighttracker.ui.statusInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FormatTest {
    private val del = Airport(iata = "DEL", timeZone = "Asia/Kolkata")
    private val dxb = Airport(iata = "DXB", timeZone = "Asia/Dubai")

    @Test fun timesAreLocalToEachAirport() {
        val iso = "2026-11-21T00:23:00Z"
        assertEquals("05:53", Fmt.time(iso, del.timeZone))
        assertEquals("04:23", Fmt.time(iso, dxb.timeZone))
    }

    @Test fun durations() {
        assertEquals("45 min", Fmt.duration(45))
        assertEquals("2 h", Fmt.duration(120))
        assertEquals("3 h 05 min", Fmt.duration(185))
    }

    @Test fun delayedBeforeDeparture() {
        val f = Flight(
            number = "6E 1406", date = "2026-11-21", status = "scheduled",
            departure = FlightEnd(airport = dxb, scheduled = "2026-11-21T10:00:00Z", estimated = "2026-11-21T10:50:00Z"),
            arrival = FlightEnd(airport = del, scheduled = "2026-11-21T13:30:00Z"),
        )
        val s = f.statusInfo(Instant.parse("2026-11-21T08:00:00Z"))
        assertEquals(Tone.Warn, s.tone)
        assertEquals("Delayed 50 min", s.label)
    }

    @Test fun cancelledWins() {
        val f = Flight(number = "AI 101", date = "2026-11-21", status = "cancelled")
        assertEquals(Tone.Bad, f.statusInfo().tone)
    }
}
