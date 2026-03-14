package com.example.anonychat.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the list of deactivated users (users whose preferences were not found).
 * Persists the list across app restarts.
 */
object DeactivatedUsersManager {
    private const val PREFS_NAME = "deactivated_users"
    private const val KEY_DEACTIVATED_EMAILS = "deactivated_emails"
    
    private var prefs: SharedPreferences? = null
    private val deactivatedEmails = mutableSetOf<String>()
    
    // StateFlow to notify observers of changes
    private val _deactivatedEmailsFlow = MutableStateFlow<Set<String>>(emptySet())
    val deactivatedEmailsFlow: StateFlow<Set<String>> = _deactivatedEmailsFlow.asStateFlow()
    
    /**
     * Initialize the manager with application context.
     * Call this once from Application or MainActivity.
     */
    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadDeactivatedUsers()
        }
    }
    
    /**
     * Load deactivated users from SharedPreferences.
     */
    private fun loadDeactivatedUsers() {
        val savedEmails = prefs?.getStringSet(KEY_DEACTIVATED_EMAILS, emptySet()) ?: emptySet()
        deactivatedEmails.clear()
        deactivatedEmails.addAll(savedEmails)
        _deactivatedEmailsFlow.value = deactivatedEmails.toSet()
    }
    
    /**
     * Mark a user as deactivated by their email.
     */
    fun markAsDeactivated(email: String) {
        if (email.isNotBlank()) {
            deactivatedEmails.add(email)
            saveDeactivatedUsers()
            _deactivatedEmailsFlow.value = deactivatedEmails.toSet()
        }
    }
    
    /**
     * Check if a user is deactivated.
     */
    fun isDeactivated(email: String?): Boolean {
        return email != null && deactivatedEmails.contains(email)
    }
    
    /**
     * Remove a user from deactivated list (if they reactivate).
     */
    fun markAsActive(email: String) {
        if (deactivatedEmails.remove(email)) {
            saveDeactivatedUsers()
            _deactivatedEmailsFlow.value = deactivatedEmails.toSet()
        }
    }
    
    /**
     * Save deactivated users to SharedPreferences.
     */
    private fun saveDeactivatedUsers() {
        prefs?.edit()?.putStringSet(KEY_DEACTIVATED_EMAILS, deactivatedEmails.toSet())?.apply()
    }
    
    /**
     * Clear all deactivated users (for testing or reset).
     */
    fun clearAll() {
        deactivatedEmails.clear()
        saveDeactivatedUsers()
        _deactivatedEmailsFlow.value = emptySet()
    }
}

// Made with Bob
