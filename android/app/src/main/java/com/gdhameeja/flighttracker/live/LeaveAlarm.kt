package com.gdhameeja.flighttracker.live

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.gdhameeja.flighttracker.push.Push
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Drive time to the airport from where you are now, and a "time to leave" alert. */
object LeaveAlarm {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    fun hasLocationPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Where you are (a rough fix is plenty), or null. */
    @SuppressLint("MissingPermission")
    suspend fun location(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (Build.VERSION.SDK_INT >= 30 && providers.isNotEmpty()) {
            val fresh = withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    lm.getCurrentLocation(providers.first(), null, context.mainExecutor) { cont.resume(it) }
                }
            }
            if (fresh != null) return fresh
        }
        return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    /**
     * Minutes by car (OSRM's free public router; no live traffic, so a buffer
     * is added: +25% and 10 minutes). Null when it can't be worked out.
     */
    suspend fun driveMinutes(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://router.project-osrm.org/route/v1/driving/$fromLon,$fromLat;$toLon,$toLat?overview=false"
            http.newCall(Request.Builder().url(url).header("User-Agent", "flight-tracker (personal use)").build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val sec = JSONObject(r.body!!.string()).getJSONArray("routes").getJSONObject(0).getDouble("duration")
                (sec / 60 * 1.25 + 10).toInt()
            }
        }.getOrNull()
    }

    /** Alert at [atMillis] (exact even in Doze: USE_EXACT_ALARM, since this app isn't on the Play Store). */
    fun schedule(context: Context, flightId: String, atMillis: Long, title: String, body: String) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, flightId, title, body)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        context.getSharedPreferences("leave", Context.MODE_PRIVATE).edit().putLong(flightId, atMillis).apply()
    }

    fun cancel(context: Context, flightId: String) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, flightId, "", ""))
        context.getSharedPreferences("leave", Context.MODE_PRIVATE).edit().remove(flightId).apply()
    }

    /** When the alert is set for, or null. */
    fun scheduledAt(context: Context, flightId: String): Long? =
        context.getSharedPreferences("leave", Context.MODE_PRIVATE).getLong(flightId, 0).takeIf { it > System.currentTimeMillis() }

    private fun pending(context: Context, flightId: String, title: String, body: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, flightId.hashCode(),
            Intent(context, LeaveReceiver::class.java).putExtra("flight_id", flightId).putExtra("title", title).putExtra("body", body),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

class LeaveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("flight_id") ?: return
        Push.createChannels(context)
        Push.show(context, id.hashCode(), intent.getStringExtra("title") ?: "Time to leave", intent.getStringExtra("body").orEmpty(), id)
    }
}
