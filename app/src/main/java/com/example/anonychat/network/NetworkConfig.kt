package com.example.anonychat.network

object NetworkConfig {
    const val SERVER_IP = "652b-2409-40f3-204d-9b0d-5988-cdea-7815-232b.ngrok-free.app"
    const val SERVER_PORT = 443
    const val BASE_URL = "https://$SERVER_IP:$SERVER_PORT/"
    const val WS_BASE_URL = "wss://$SERVER_IP:$SERVER_PORT/"
}
