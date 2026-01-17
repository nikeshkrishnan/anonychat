package com.example.anonychat

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.anonychat.network.NetworkClient
import com.example.anonychat.network.WebSocketManager

class AnonychatApp : Application() {

    override fun onCreate() {
        super.onCreate()

        NetworkClient.initialize(this)


    }
}

