package com.example.anonychat.utils

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManager
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.IntegrityTokenResponse
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import com.google.android.gms.tasks.Task
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/**
 * IntegrityManager - Handles Google Play Integrity API operations
 * 
 * This module provides a reusable interface for obtaining and managing
 * Google Play Integrity tokens for various authentication endpoints.
 * 
 * Usage:
 * ```
 * val integrityManager = PlayIntegrityManager(context)
 * val token = integrityManager.requestIntegrityToken("register")
 * ```
 */
class PlayIntegrityManager(private val context: Context) {
    
    private val integrityManager: IntegrityManager by lazy {
        IntegrityManagerFactory.create(context)
    }
    
    companion object {
        private const val TAG = "PlayIntegrityManager"
        private const val TIMEOUT_MS = 10000L // 10 seconds timeout
        
        // Cloud Project Number from Google Cloud Console
        // Project: anonychat-470006
        private const val CLOUD_PROJECT_NUMBER = 661845947677L
    }
    
    /**
     * Generate a valid nonce (minimum 16 bytes)
     * Combines the action with timestamp for uniqueness
     * Must be base64 URL-safe no-wrap encoded
     */
    private fun generateNonce(action: String): String {
        val timestamp = System.currentTimeMillis().toString()
        val data = "$action:$timestamp:${System.nanoTime()}"
        // Use URL_SAFE and NO_WRAP as required by Play Integrity API
        return Base64.encodeToString(
            data.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }
    
    /**
     * Request an integrity token for a specific action
     *
     * @param requestHash A unique identifier for the request (e.g., "register", "login")
     * @return IntegrityToken as a String, or null if the request fails
     */
    suspend fun requestIntegrityToken(requestHash: String): String? {
        return try {
            Log.d(TAG, "Requesting integrity token for action: $requestHash")
            
            // Generate a valid nonce (minimum 16 bytes)
            val nonce = generateNonce(requestHash)
            Log.d(TAG, "Generated nonce length: ${nonce.length}")
            
            // Create the integrity token request
            val integrityTokenRequest = IntegrityTokenRequest.builder()
                .setCloudProjectNumber(CLOUD_PROJECT_NUMBER)
                .setNonce(nonce)
                .build()
            
            // Request the token with timeout
            val response: IntegrityTokenResponse = withTimeout(TIMEOUT_MS) {
                integrityManager.requestIntegrityToken(integrityTokenRequest).await()
            }
            
            val token = response.token()
            Log.d(TAG, "Successfully obtained integrity token")
            token
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to obtain integrity token", e)
            null
        }
    }
    
    /**
     * Request an integrity token with a custom nonce
     * 
     * @param nonce A unique nonce value (should be generated server-side ideally)
     * @return IntegrityToken as a String, or null if the request fails
     */
    suspend fun requestIntegrityTokenWithNonce(nonce: String): String? {
        return try {
            Log.d(TAG, "Requesting integrity token with custom nonce")
            
            val integrityTokenRequest = IntegrityTokenRequest.builder()
                .setCloudProjectNumber(CLOUD_PROJECT_NUMBER)
                .setNonce(nonce)
                .build()
            
            val response: IntegrityTokenResponse = withTimeout(TIMEOUT_MS) {
                integrityManager.requestIntegrityToken(integrityTokenRequest).await()
            }
            
            val token = response.token()
            Log.d(TAG, "Successfully obtained integrity token with custom nonce")
            token
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to obtain integrity token with custom nonce", e)
            null
        }
    }
    
}

/**
 * Result wrapper for integrity token operations
 */
sealed class IntegrityResult {
    data class Success(val token: String) : IntegrityResult()
    data class Error(val message: String, val exception: Exception? = null) : IntegrityResult()
}

/**
 * Extension function to get integrity token with result wrapper
 */
suspend fun PlayIntegrityManager.getIntegrityTokenResult(requestHash: String): IntegrityResult {
    return try {
        val token = requestIntegrityToken(requestHash)
        if (token != null) {
            IntegrityResult.Success(token)
        } else {
            IntegrityResult.Error("Failed to obtain integrity token")
        }
    } catch (e: Exception) {
        IntegrityResult.Error("Exception while obtaining integrity token: ${e.message}", e)
    }
}

// Made with Bob
