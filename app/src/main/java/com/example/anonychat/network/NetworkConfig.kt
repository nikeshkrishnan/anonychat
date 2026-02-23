package com.example.anonychat.network

object NetworkConfig {
    const val SERVER_IP = "b286-43-254-162-76.ngrok-free.app"
    const val SERVER_PORT = 443
    const val BASE_URL = "https://$SERVER_IP:$SERVER_PORT/"
    const val WS_BASE_URL = "wss://$SERVER_IP:$SERVER_PORT/"
}
