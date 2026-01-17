package com.example.anonychat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.anonychat.R
import com.example.anonychat.network.WebSocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
class ChatSocketService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val appLifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> {
                // App moved to foreground

                WebSocketManager.reconnectIfNeeded(applicationContext)
                WebSocketManager.sendPresence("online")
            }

            Lifecycle.Event.ON_STOP -> {
                // App moved to background
                WebSocketManager.sendPresence("offline")
            }

            else -> Unit
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        // Observe app foreground/background
        ProcessLifecycleOwner.Companion.get()
            .lifecycle
            .addObserver(appLifecycleObserver)

        serviceScope.launch {
            WebSocketManager.init(applicationContext)
            WebSocketManager.connect(applicationContext)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        ProcessLifecycleOwner.Companion.get()
            .lifecycle
            .removeObserver(appLifecycleObserver)

        serviceScope.launch {
            WebSocketManager.sendPresence("offline")
            WebSocketManager.disconnect(forceStop = true)
        }

        serviceScope.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /* ---------------------------------------------------
       NOTIFICATION
    --------------------------------------------------- */

    private fun buildNotification(): Notification {
        val channelId = createNotificationChannel()

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, channelId)
            } else {
                Notification.Builder(this)
            }

        builder
            .setContentTitle("AnonyChat")
            .setContentText("Connected")
            .setSmallIcon(R.drawable.ic_notification)

        val notification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                builder.build()
            } else {
                @Suppress("DEPRECATION")
                builder.getNotification()
            }

        notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT
        return notification
    }

    private fun createNotificationChannel(): String {
        val channelId = "chat_socket_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Chat Connection",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)

            val manager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            manager.createNotificationChannel(channel)
        }

        return channelId
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}