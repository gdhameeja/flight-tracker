package com.gdhameeja.flighttracker.live

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gdhameeja.flighttracker.MainActivity
import com.gdhameeja.flighttracker.R
import com.gdhameeja.flighttracker.data.TrackedFlight
import com.gdhameeja.flighttracker.ui.Fmt
import com.gdhameeja.flighttracker.ui.isInAir
import com.gdhameeja.flighttracker.ui.progress
import com.gdhameeja.flighttracker.ui.statusInfo
import java.time.Duration
import java.time.Instant

/**
 * A pinned notification on travel day: from 6 hours before departure until
 * shortly after landing. Status, gate or belt, and a live countdown.
 * It updates whenever the app or a push brings new flight data.
 */
object LiveNotification {
    private const val CHANNEL = "travel_day"
    private const val ID = 4242

    /** The flight to pin right now, if any. */
    fun active(list: List<TrackedFlight>, now: Instant = Instant.now()): TrackedFlight? = list
        .filter { t ->
            val f = t.flight
            val dep = Fmt.instant(f.departure.best) ?: return@filter false
            val arr = Fmt.instant(f.arrival.best) ?: dep.plusSeconds(3 * 3600)
            val landedFor = Duration.between(arr, now).toMinutes()
            val untilDep = Duration.between(now, dep).toMinutes()
            when (f.status) {
                "arrived" -> landedFor < 45
                "cancelled", "diverted" -> untilDep > -120 && untilDep < 6 * 60
                else -> untilDep < 6 * 60 && landedFor < 45
            }
        }
        .minByOrNull { Fmt.instant(it.flight.departure.best) ?: Instant.MAX }

    fun update(context: Context, list: List<TrackedFlight>) {
        val nm = NotificationManagerCompat.from(context)
        val t = active(list)
        if (t == null) {
            nm.cancel(ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Travel day", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Pinned status of the flight you're taking today"
                setShowBadge(false)
            }
        )
        val f = t.flight
        val status = f.statusInfo()
        val route = "${f.departure.airport.code} → ${f.arrival.airport.code}"
        val inAir = f.isInAir()
        val landed = f.status == "arrived"
        val detail = when {
            landed -> f.arrival.baggageBelt?.let { "Baggage belt $it" } ?: "Landed at ${f.arrival.airport.code}"
            inAir -> "Lands ${Fmt.time(f.arrival.best, f.arrival.airport.timeZone)}" + (f.arrival.terminal?.let { " · T$it" } ?: "")
            else -> listOfNotNull(
                f.departure.terminal?.let { "T$it" },
                f.departure.gate?.let { "Gate $it" },
                "Departs ${Fmt.time(f.departure.best, f.departure.airport.timeZone)}",
            ).joinToString(" · ")
        }
        // Counts down by itself, no updates needed
        val target = Fmt.instant(if (inAir) f.arrival.best else f.departure.best)
        val open = PendingIntent.getActivity(
            context, ID,
            Intent(context, MainActivity::class.java).putExtra("flight_id", t.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${f.number} · ${status.label}")
            .setContentText("$route · $detail")
            .setSubText(if (inAir) "Lands in" else if (landed) null else "Departs in")
            .setOngoing(!landed)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (target != null && !landed && target.isAfter(Instant.now())) {
            b.setWhen(target.toEpochMilli()).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        }
        f.progress()?.let { b.setProgress(100, (it * 100).toInt(), false) }
        nm.notify(ID, b.build())
    }
}
