package com.example.anonychat.network

object NetworkConfig {
    const val SERVER_IP = "0a97-2409-40f3-20cd-68e5-f164-189c-8271-5e95.ngrok-free.app"
    const val SERVER_PORT = 443
    const val BASE_URL = "https://$SERVER_IP:$SERVER_PORT/"
    const val WS_BASE_URL = "wss://$SERVER_IP:$SERVER_PORT/"
}
