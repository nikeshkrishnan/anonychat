package com.example.anonychat.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.example.anonychat.network.WebSocketManager
import kotlinx.coroutines.*

/**
 * Background service that continuously monitors WebSocket connection health
 * and forces reconnection if the connection is down.
 * 
 * This service runs independently of WebSocketManager to ensure reliable
 * connection monitoring and automatic recovery.
 */
class WebSocketMonitorService : Service() {
    
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitorJob: Job? = null
    
    companion object {
        private const val TAG = "WebSocketMonitor"
        private const val CHECK_INTERVAL_MS = 5000L // Check every 5 seconds
        private const val PING_TIMEOUT_MS = 5000L // Wait 5 seconds for ping response
        private const val RECONNECT_DELAY_MS = 2000L // Wait 2 seconds before reconnecting
        
        fun start(context: Context) {
            val intent = Intent(context, WebSocketMonitorService::class.java)
            context.startService(intent)
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, WebSocketMonitorService::class.java)
            context.stopService(intent)
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "WebSocket Monitor Service created")
        startMonitoring()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "WebSocket Monitor Service started")
        return START_STICKY // Restart service if killed by system
    }
    
    private fun startMonitoring() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            Log.i(TAG, "Starting WebSocket connection monitoring")
            
            while (isActive) {
                try {
                    checkAndReconnect()
                    delay(CHECK_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in monitoring loop", e)
                    delay(CHECK_INTERVAL_MS)
                }
            }
        }
    }
    
    private suspend fun checkAndReconnect() {
        val isHealthy = checkWebSocketHealth()
        
        if (!isHealthy) {
            Log.w(TAG, "WebSocket connection is DOWN or unresponsive - forcing reconnection")
            forceReconnect()
        } else {
            Log.d(TAG, "WebSocket connection is healthy")
        }
    }
    
    /**
     * Checks WebSocket health by sending a ping and waiting for pong response
     * Returns true if server responds within timeout
     */
    private suspend fun checkWebSocketHealth(): Boolean {
        return try {
            // Check if WebSocket exists
            val ws = WebSocketManager.getWebSocket()
            if (ws == null) {
                Log.w(TAG, "WebSocket is null")
                return false
            }
            
            // Check if WebSocketManager reports connected state
            if (!WebSocketManager.isConnected()) {
                Log.w(TAG, "WebSocketManager reports disconnected")
                return false
            }
            
            // Send ping and wait for pong
            Log.d(TAG, "Sending health check ping...")
            val pongReceived = WebSocketManager.sendPingAndWaitForPong(PING_TIMEOUT_MS)
            
            if (pongReceived) {
                Log.d(TAG, "WebSocket connection is healthy - pong received")
                true
            } else {
                Log.w(TAG, "WebSocket connection is unhealthy - no pong received within ${PING_TIMEOUT_MS}ms")
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during health check", e)
            false
        }
    }
    
    private suspend fun forceReconnect() {
        try {
            Log.i(TAG, "Attempting to reconnect WebSocket...")
            
            // Get stored credentials
            val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val token = prefs.getString("access_token", null)
            val email = prefs.getString("user_email", null)
            
            if (token.isNullOrBlank() || email.isNullOrBlank()) {
                Log.w(TAG, "Cannot reconnect - missing credentials (token or email)")
                return
            }
            
            // Close existing connection if any
            WebSocketManager.disconnect()
            
            // Wait a bit before reconnecting
            delay(RECONNECT_DELAY_MS)
            
            // Reconnect with stored credentials
            withContext(Dispatchers.Main) {
                WebSocketManager.connect(token, email)
            }
            
            Log.i(TAG, "WebSocket reconnection initiated")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reconnect WebSocket", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "WebSocket Monitor Service destroyed")
        monitorJob?.cancel()
        serviceScope.cancel()
    }
}

// Made with Bob
