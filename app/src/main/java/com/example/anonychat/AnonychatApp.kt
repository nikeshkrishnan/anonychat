package com.example.anonychat

import android.app.Application
import com.example.anonychat.network.NetworkClient

class AnonychatApp : Application() {
    override fun onCreate() {        super.onCreate()
        // Initialize the NetworkClient with the application context
        NetworkClient.initialize(this)
    }
}
