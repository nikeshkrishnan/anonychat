package com.example.anonychat.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * UserRepository - Singleton repository for managing userId mappings
 * 
 * This repository stores and retrieves userId for ALL users (not just current user),
 * eliminating the need for email-based extraction which breaks after account resets.
 * 
 * Storage: Uses SharedPreferences for persistent storage
 * Pattern: Singleton with Application context
 */
object UserRepository {
    private const val TAG = "UserRepository"
    private const val PREFS_NAME = "user_id_mappings"
    private const val KEY_PREFIX = "userId_"
    
    private lateinit var prefs: SharedPreferences
    private var isInitialized = false
    
    /**
     * Initialize the repository with application context
     * Must be called once during app startup (e.g., in Application.onCreate())
     */
    fun initialize(context: Context) {
        if (!isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            isInitialized = true
            Log.d(TAG, "UserRepository initialized")
        }
    }
    
    /**
     * Store userId mapping for a given email
     * @param email User's email address
     * @param userId User's actual userId from backend
     */
    fun storeUserId(email: String, userId: String) {
        checkInitialized()
        
        if (email.isBlank() || userId.isBlank()) {
            Log.w(TAG, "Attempted to store empty email or userId")
            return
        }
        
        val key = KEY_PREFIX + email
        prefs.edit().putString(key, userId).apply()
        Log.d(TAG, "Stored userId mapping: $email -> $userId")
    }
    
    /**
     * Retrieve userId for a given email
     * @param email User's email address
     * @return userId if found, null otherwise
     */
    fun getUserId(email: String): String? {
        checkInitialized()
        
        if (email.isBlank()) {
            Log.w(TAG, "Attempted to get userId for empty email")
            return null
        }
        
        val key = KEY_PREFIX + email
        val userId = prefs.getString(key, null)
        
        if (userId != null) {
            Log.d(TAG, "Retrieved userId for $email: $userId")
        } else {
            Log.d(TAG, "No userId found for $email")
        }
        
        return userId
    }
    
    /**
     * Get userId or throw exception if not found
     * @param email User's email address
     * @return userId from repository
     * @throws IllegalStateException if userId not found for the given email
     */
    fun getUserIdOrThrow(email: String): String {
        val storedUserId = getUserId(email)
        if (storedUserId != null) {
            return storedUserId
        } else {
            val errorMsg = "UserId not found for email: $email. User may not have interacted yet or data is missing."
            Log.e(TAG, errorMsg)
            throw IllegalStateException(errorMsg)
        }
    }
    
    /**
     * Get userId with temporary fallback for initial interactions
     * This should ONLY be used during the transition period when users haven't interacted yet.
     * Once a user sends any WebSocket event (message, spark, match), their userId will be stored.
     *
     * @param email User's email address
     * @return userId from repository, or extracted from email with WARNING log
     */
    fun getUserIdWithFallback(email: String): String {
        val storedUserId = getUserId(email)
        return if (storedUserId != null) {
            storedUserId
        } else {
            // TEMPORARY fallback - this should only happen for users who haven't interacted yet
            val fallbackId = email.substringBefore("@")
            Log.w(TAG, "⚠️ FALLBACK: Using email extraction for $email -> $fallbackId (userId not yet stored)")
            Log.w(TAG, "⚠️ This user needs to send a WebSocket event to store their userId properly")
            fallbackId
        }
    }
    
    /**
     * Remove userId mapping for a given email
     * @param email User's email address
     */
    fun removeUserId(email: String) {
        checkInitialized()
        
        if (email.isBlank()) {
            Log.w(TAG, "Attempted to remove userId for empty email")
            return
        }
        
        val key = KEY_PREFIX + email
        prefs.edit().remove(key).apply()
        Log.d(TAG, "Removed userId mapping for $email")
    }
    
    /**
     * Clear all userId mappings (use with caution)
     */
    fun clearAll() {
        checkInitialized()
        prefs.edit().clear().apply()
        Log.d(TAG, "Cleared all userId mappings")
    }
    
    /**
     * Get all stored email-userId mappings (for debugging)
     */
    fun getAllMappings(): Map<String, String> {
        checkInitialized()
        
        val mappings = mutableMapOf<String, String>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(KEY_PREFIX) && value is String) {
                val email = key.removePrefix(KEY_PREFIX)
                mappings[email] = value
            }
        }
        return mappings
    }
    
    private fun checkInitialized() {
        if (!isInitialized) {
            throw IllegalStateException("UserRepository must be initialized before use. Call initialize() in Application.onCreate()")
        }
    }
}

// Made with Bob
