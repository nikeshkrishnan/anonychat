package com.example.anonychat // or com.example.anonychat.util

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A simple singleton to track if the application is currently in the foreground.
 * This is updated by the Application's lifecycle observer.
 */
object AppVisibility {
    private val _isForeground = AtomicBoolean(false)

    val isForeground: Boolean
        get() = _isForeground.get()

    fun onAppStarted() {
        _isForeground.set(true)
    }

    fun onAppStopped() {
        _isForeground.set(false)
    }
}
    