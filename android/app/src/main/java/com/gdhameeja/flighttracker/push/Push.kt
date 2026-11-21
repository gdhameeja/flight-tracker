package com.gdhameeja.flighttracker.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gdhameeja.flighttracker.MainActivity
import com.gdhameeja.flighttracker.R
import com.gdhameeja.flighttracker.data.Api
import com.gdhameeja.flighttracker.data.Settings
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object Push {
    const val CHANNEL_ALERTS = "flight_alerts"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Built without google-services.json: no Firebase, no push. */
    fun available(context: Context) = FirebaseApp.getApps(context).isNotEmpty()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Flight alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Delays, gate changes, cancellations, boarding, landing and baggage belt"
            }
        )
    }

    /** Sends this phone's push token to the server (on start, and when Firebase changes it). */
    fun register(context: Context, token: String? = null) {
        if (!available(context)) return
        val settings = Settings(context)
        if (!settings.isConnected) return
        scope.launch {
            runCatching {
                val t = token ?: FirebaseMessaging.getInstance().token.await()
                Api(settings).registerPush(t)
            }
        }
    }

    fun show(context: Context, id: Int, title: String, body: String, flightId: String?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("flight_id", flightId)
        val pi = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}

/** Receives alerts from the backend (worker/src/push.ts) as data messages. */
class FlightMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = Push.register(applicationContext, token)

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        val title = d["title"] ?: message.notification?.title ?: return
        val body = d["body"] ?: message.notification?.body.orEmpty()
        val id = d["eventId"]?.toIntOrNull() ?: (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        Push.createChannels(applicationContext)
        Push.show(applicationContext, id, title, body, d["flightId"])
    }
}
