package com.example.anonychat

import android.app.Application
import com.example.anonychat.network.NetworkClient
import com.example.anonychat.network.WebSocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AnonychatApp : Application() {

    override fun onCreate() {
        super.onCreate()

        NetworkClient.initialize(this)

        // Initialize WebSocketManager in background to avoid blocking main thread
        CoroutineScope(Dispatchers.IO).launch { WebSocketManager.init(applicationContext) }
    }
}
