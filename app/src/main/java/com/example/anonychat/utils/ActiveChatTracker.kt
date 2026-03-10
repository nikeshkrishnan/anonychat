package com.example.anonychat.utils

import java.util.concurrent.atomic.AtomicReference

/**
 * Singleton to track which user's chat is currently active/visible
 * Used to prevent notifications when user is already viewing that chat
 */
object ActiveChatTracker {
    private val _activeChatUser = AtomicReference<String?>(null)
    
    /**
     * Get the currently active chat user email
     */
    val activeChatUser: String?
        get() = _activeChatUser.get()
    
    /**
     * Set the currently active chat user
     * @param userEmail The email of the user whose chat is now active, or null if no chat is active
     */
    fun setActiveChatUser(userEmail: String?) {
        _activeChatUser.set(userEmail)
    }
    
    /**
     * Check if a specific user's chat is currently active
     */
    fun isChatActive(userEmail: String): Boolean {
        return _activeChatUser.get() == userEmail
    }
    
    /**
     * Clear the active chat user
     */
    fun clearActiveChatUser() {
        _activeChatUser.set(null)
    }
}

// Made with Bob
