package com.example.anonychat.network

object NetworkConfig {
    const val SERVER_IP = "ccde-2409-40f3-2058-ee53-785c-bd0b-9c4c-5a08.ngrok-free.app"
    const val SERVER_PORT = 443
    const val BASE_URL = "https://$SERVER_IP:$SERVER_PORT/"
    const val WS_BASE_URL = "wss://$SERVER_IP:$SERVER_PORT/"
}
