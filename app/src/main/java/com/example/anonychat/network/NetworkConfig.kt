package com.example.anonychat.network

object NetworkConfig {
    const val SERVER_IP = "7347-2409-40f3-201c-359f-a8b9-6821-db8e-437b.ngrok-free.app"
    const val SERVER_PORT = 443
    const val BASE_URL = "https://$SERVER_IP:$SERVER_PORT/"
    const val WS_BASE_URL = "wss://$SERVER_IP:$SERVER_PORT/"
}
