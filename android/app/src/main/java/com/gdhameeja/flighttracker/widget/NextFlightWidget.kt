package com.gdhameeja.flighttracker.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.gdhameeja.flighttracker.MainActivity
import com.gdhameeja.flighttracker.data.Settings
import com.gdhameeja.flighttracker.data.TrackedFlight
import com.gdhameeja.flighttracker.ui.Fmt
import com.gdhameeja.flighttracker.ui.Tone
import com.gdhameeja.flighttracker.ui.endsAt
import com.gdhameeja.flighttracker.ui.statusInfo
import java.time.Instant

/** Home-screen widget: your next flight, from the copy the app keeps for offline use. */
class NextFlightWidget : GlanceAppWidget() {
    companion object {
        suspend fun refresh(context: Context) = NextFlightWidget().updateAll(context)

        fun next(list: List<TrackedFlight>, now: Instant = Instant.now()): TrackedFlight? = list
            .filter { t -> t.flight.endsAt()?.let { it.plusSeconds(3600).isAfter(now) } ?: false }
            .minByOrNull { Fmt.instant(it.flight.departure.best) ?: Instant.MAX }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val t = next(Settings(context).loadFlights().flights)
        provideContent { GlanceTheme { Content(t) } }
    }

    @androidx.compose.runtime.Composable
    private fun Content(t: TrackedFlight?) {
        val bg = ColorProvider(Color(0xFF0B1B3A))
        val dim = ColorProvider(Color(0xFFB4C5FF))
        val white = ColorProvider(Color.White)
        Column(
            GlanceModifier.fillMaxSize().background(bg).cornerRadius(20.dp).padding(14.dp).clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (t == null) {
                Text("No upcoming flights", style = TextStyle(color = white, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                return@Column
            }
            val f = t.flight
            val s = f.statusInfo()
            val tone = when (s.tone) {
                Tone.Good -> Color(0xFF5BD18E)
                Tone.Warn -> Color(0xFFFFB454)
                Tone.Bad -> Color(0xFFFF7A70)
                Tone.Live -> Color(0xFF8FB0FF)
                Tone.Neutral -> Color(0xFFA3A9BA)
            }
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(f.number, style = TextStyle(color = dim, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.defaultWeight())
                Text(s.label, style = TextStyle(color = ColorProvider(tone), fontSize = 13.sp, fontWeight = FontWeight.Bold))
            }
            Spacer(GlanceModifier.height(4.dp))
            Text("${f.departure.airport.code} → ${f.arrival.airport.code}", style = TextStyle(color = white, fontSize = 24.sp, fontWeight = FontWeight.Bold))
            val tz = f.departure.airport.timeZone
            val where = listOfNotNull(f.departure.terminal?.let { "T$it" }, f.departure.gate?.let { "Gate $it" }, f.arrival.baggageBelt?.let { "Belt $it" })
            Text(
                "${Fmt.day(f.departure.best, tz)} · ${Fmt.time(f.departure.best, tz)}" + if (where.isNotEmpty()) " · ${where.joinToString(" · ")}" else "",
                style = TextStyle(color = dim, fontSize = 13.sp),
            )
            Text("Departs ${Fmt.relative(f.departure.best)}", style = TextStyle(color = white, fontSize = 13.sp))
        }
    }
}

class NextFlightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextFlightWidget()
}
