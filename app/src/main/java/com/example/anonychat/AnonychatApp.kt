package com.example.anonychat

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.anonychat.network.NetworkClient
import com.example.anonychat.network.WebSocketManager
import com.example.anonychat.repository.UserRepository
import com.example.anonychat.ui.ConversationRepository
import com.example.anonychat.utils.DeactivatedUsersManager
import com.example.anonychat.utils.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AnonychatApp : Application(), LifecycleObserver {

    override fun onCreate() {
        super.onCreate()

        NetworkClient.initialize(this)

        // Initialize UserRepository for userId mappings
        UserRepository.initialize(this)

        // Initialize deactivated users manager
        DeactivatedUsersManager.initialize(this)

        // Initialize notification channel for chat messages
        NotificationHelper.createNotificationChannel(this)

        // Load persisted conversation list from app storage
        ConversationRepository.initialize(this)

        // Initialize WebSocketManager in background to avoid blocking main thread
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            CoroutineScope(Dispatchers.IO).launch { WebSocketManager.init(applicationContext) }
        }

        // Register lifecycle observer to track app foreground/background state
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onAppForegrounded() {
        AppVisibility.onAppStarted()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onAppBackgrounded() {
        AppVisibility.onAppStopped()
    }
}
