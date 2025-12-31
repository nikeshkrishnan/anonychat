package com.example.anonychat.network

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object WebSocketManager {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()

    // Function for LoginScreen to establish connection and wait for "ws_ready"
    // Throws an exception on failure.
    suspend fun connect(context: Context) {
        // If already connected and session is active, do nothing.
        if (webSocket != null) {
            android.util.Log.d("WebSocketManager", "Already connected.")
            return
        }

        // suspendCancellableCoroutine allows the coroutine to be cancelled
        // and also bridges the callback-style API with coroutines.
        return suspendCancellableCoroutine { continuation ->
            val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val token = prefs.getString("access_token", null)
            val email = prefs.getString("user_email", null)

            if (token == null || email == null) {
                continuation.resumeWithException(Exception("Auth token or email not found"))
                return@suspendCancellableCoroutine
            }

            val url = "ws://192.168.1.66:8080/?token=${Uri.encode(token)}&gmail=${Uri.encode(email)}"
            val request = Request.Builder().url(url).build()

            val listener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    android.util.Log.d("WebSocketManager", "Connection OPEN.")
                    // Store the active WebSocket instance immediately
                    webSocket = ws
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    android.util.Log.d("WebSocketManager", "Received: $text")
                    try {
                        val json = org.json.JSONObject(text)
                        if (json.optString("type") == "ws_ready") {
                            android.util.Log.e("WebSocketManager", "Server is READY!")
                            // Signal that the connection is successful and ready for use.
                            if (continuation.isActive) {
                                continuation.resume(Unit)
                            }
                        }
                        // TODO: Here you would pass other message types (e.g., chat messages)
                        // to the ChatScreen using a SharedFlow or a callback.
                    } catch (e: Exception) {
                        android.util.Log.w("WebSocketManager", "Error parsing message: $text")
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    android.util.Log.e("WebSocketManager", "Connection FAILURE: ${t.message}", t)
                    webSocket = null // Clear instance on failure
                    if (continuation.isActive) {
                        continuation.resumeWithException(Exception("WebSocket connection failed: ${t.message}"))
                    }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    android.util.Log.d("WebSocketManager", "Connection CLOSING: $reason")
                    ws.close(1000, null)
                    webSocket = null
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    android.util.Log.d("WebSocketManager", "Connection CLOSED: $reason")
                    webSocket = null
                }
            }

            // Ensure resources are cleaned up if the coroutine is cancelled
            continuation.invokeOnCancellation {
                android.util.Log.d("WebSocketManager", "Coroutine cancelled, closing WebSocket.")
                webSocket?.close(1000, "Coroutine cancelled")
            }

            // Initiate the connection
            client.newWebSocket(request, listener)
        }
    }

    // Function for ChatScreen to send messages
    fun sendMessage(message: String) {
        if (webSocket == null) {
            android.util.Log.w("WebSocketManager", "Cannot send message, WebSocket is not connected.")
            return
        }
        android.util.Log.d("WebSocketManager", "Sending: $message")
        webSocket?.send(message)
    }

    // Function to close the connection (e.g., on logout or app exit)
    fun disconnect() {
        webSocket?.close(1000, "User initiated disconnect")
    }
}
