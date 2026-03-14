package com.example.anonychat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.anonychat.AppVisibility
import com.example.anonychat.R
import com.example.anonychat.network.WebSocketEvent
import com.example.anonychat.network.WebSocketManager
import com.example.anonychat.network.WebSocketManager.reconnectIfNeeded
import com.example.anonychat.ui.ConversationRepository
import com.example.anonychat.utils.ActiveChatTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatSocketService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val appLifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> {
                // App moved to foreground

                Log.e("APP TO FORGROUND!!!!!!!!!!!", "ONLINE")
                AppVisibility.onAppStarted()
                WebSocketManager.reconnectIfNeeded(applicationContext)
                WebSocketManager.sendPresence("online")
            }
            Lifecycle.Event.ON_STOP -> {
                Log.e("APP TO BACKD=GROUND!!!!!!!!!!!", "OFFLINE")
                AppVisibility.onAppStopped()
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
        ProcessLifecycleOwner.Companion.get().lifecycle.addObserver(appLifecycleObserver)

        serviceScope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                WebSocketManager.init(applicationContext)
                WebSocketManager.registerNetworkCallback(applicationContext)
            }

            try {
                WebSocketManager.connect(applicationContext)
            } catch (e: Exception) {
                Log.e("ChatSocketService", "Connection init failed", e)
            }
        }
        
        // Listen to WebSocket events to update conversation unread counts
        serviceScope.launch {
            val prefs = applicationContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            WebSocketManager.events.collect { event ->
                when (event) {
                    is WebSocketEvent.NewMessage -> {
                        val msg = event.message
                        val myEmail = prefs.getString("user_email", "") ?: ""
                        if (myEmail.isBlank()) return@collect
                        
                        val peerEmail = if (msg.from == myEmail) msg.to else msg.from
                        val isIncoming = msg.from != myEmail
                        
                        // Check if user is actively viewing this chat
                        val isViewingThisChat = ActiveChatTracker.isChatActive(peerEmail)
                        
                        // Only increment unread if incoming AND not viewing this chat
                        val shouldIncrementUnread = isIncoming && !isViewingThisChat
                        
                        val preview = when {
                            msg.text.isNotBlank() -> msg.text
                            msg.mediaType == "IMAGE" -> "📷 Photo"
                            msg.mediaType == "VIDEO" -> "🎥 Video"
                            msg.audioUri != null -> "🎵 Voice message"
                            else -> "Message"
                        }
                        
                        // Fetch actual username from API in background
                        try {
                            val token = prefs.getString("access_token", null)
                            
                            if (token != null) {
                                WebSocketManager.sendGetPreferences(token, peerEmail)
                                
                                val prefEvent = withTimeoutOrNull(10_000) {
                                    WebSocketManager.events.first { ev ->
                                        when (ev) {
                                            is WebSocketEvent.PreferencesData -> {
                                                // Verify this is the correct peer's preferences
                                                ev.preferences.gmail == peerEmail
                                            }
                                            is WebSocketEvent.PreferencesError -> true
                                            else -> false
                                        }
                                    }
                                }
                                
                                val peerPrefs = (prefEvent as? WebSocketEvent.PreferencesData)?.preferences
                                
                                // Double-check the email matches to prevent mixing up users
                                if (peerPrefs != null && peerPrefs.gmail != peerEmail) {
                                    Log.e("ChatSocketService", "⚠️ Preferences mismatch! Expected $peerEmail but got ${peerPrefs.gmail}")
                                    // Use fallback instead of wrong data
                                    withContext(Dispatchers.Main) {
                                        ConversationRepository.upsert(
                                            myEmail = myEmail,
                                            peerEmail = peerEmail,
                                            peerUsername = peerEmail.substringBefore('@'),
                                            preview = preview,
                                            timestamp = msg.timestamp,
                                            isIncoming = shouldIncrementUnread
                                        )
                                    }
                                    return@collect
                                }
                                
                                val actualUsername = peerPrefs?.username ?: peerEmail.substringBefore('@')
                                
                                withContext(Dispatchers.Main) {
                                    ConversationRepository.upsert(
                                        myEmail = myEmail,
                                        peerEmail = peerEmail,
                                        peerUsername = actualUsername,
                                        preview = preview,
                                        timestamp = msg.timestamp,
                                        isIncoming = shouldIncrementUnread,
                                        peerGender = peerPrefs?.gender,
                                        peerRomanceMin = peerPrefs?.romanceRange?.min?.toFloat(),
                                        peerRomanceMax = peerPrefs?.romanceRange?.max?.toFloat()
                                    )
                                }
                                
                                // Fetch rating for this user
                                try {
                                    WebSocketManager.sendGetAverageRating(token, peerEmail)
                                    
                                    val ratingEvent = withTimeoutOrNull(5_000) {
                                        WebSocketManager.events.first { ev ->
                                            (ev is WebSocketEvent.AverageRatingData && ev.userEmail == peerEmail) ||
                                            ev is WebSocketEvent.AverageRatingError
                                        }
                                    }
                                    
                                    if (ratingEvent is WebSocketEvent.AverageRatingData && ratingEvent.avgRating != null && ratingEvent.userEmail == peerEmail) {
                                        withContext(Dispatchers.Main) {
                                            ConversationRepository.upsert(
                                                myEmail = myEmail,
                                                peerEmail = peerEmail,
                                                peerUsername = actualUsername,
                                                preview = preview,
                                                timestamp = msg.timestamp,
                                                isIncoming = false, // Don't increment unread again
                                                peerGender = peerPrefs?.gender,
                                                peerRomanceMin = peerPrefs?.romanceRange?.min?.toFloat(),
                                                peerRomanceMax = peerPrefs?.romanceRange?.max?.toFloat(),
                                                userRating = ratingEvent.avgRating
                                            )
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("ChatSocketService", "Failed to fetch rating for $peerEmail", e)
                                }
                            } else {
                                // No token - fallback
                                withContext(Dispatchers.Main) {
                                    ConversationRepository.upsert(
                                        myEmail = myEmail,
                                        peerEmail = peerEmail,
                                        peerUsername = peerEmail.substringBefore('@'),
                                        preview = preview,
                                        timestamp = msg.timestamp,
                                        isIncoming = shouldIncrementUnread
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            // Fallback to email prefix if WS call fails
                            withContext(Dispatchers.Main) {
                                ConversationRepository.upsert(
                                    myEmail = myEmail,
                                    peerEmail = peerEmail,
                                    peerUsername = peerEmail.substringBefore('@'),
                                    preview = preview,
                                    timestamp = msg.timestamp,
                                    isIncoming = shouldIncrementUnread
                                )
                            }
                            Log.e("ChatSocketService", "Failed to fetch username for $peerEmail via WS", e)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            WebSocketManager.unregisterNetworkCallback()
        }

        ProcessLifecycleOwner.Companion.get().lifecycle.removeObserver(appLifecycleObserver)

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

        // Make notification invisible by not setting title or text
        // Group with chat notifications but don't show in shade
        builder.setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .setGroup("anonychat_messages")
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(channelId)
        }

        val notification =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    builder.build()
                } else {
                    @Suppress("DEPRECATION") builder.getNotification()
                }

        return notification
    }

    private fun createNotificationChannel(): String {
        val channelId = "chat_socket_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                    NotificationChannel(
                            channelId,
                            "Chat Connection",
                            NotificationManager.IMPORTANCE_NONE
                    )
            channel.setShowBadge(false)
            channel.enableVibration(false)
            channel.setSound(null, null)
            channel.description = "Keeps the app connected to receive messages"

            val manager =
                    applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as
                            NotificationManager

            manager.createNotificationChannel(channel)
        }

        return channelId
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
