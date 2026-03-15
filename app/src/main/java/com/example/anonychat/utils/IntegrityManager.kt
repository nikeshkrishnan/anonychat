package com.example.anonychat.utils

import android.content.Context
import android.util.Log
import com.example.anonychat.BuildConfig
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.IntegrityTokenResponse
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manager for Google Play Integrity API
 * 
 * This class handles the generation of integrity tokens that prove:
 * 1. The app is genuine and unmodified
 * 2. The app is running on a genuine Android device
 * 3. The app was installed from Google Play Store
 * 
 * For backend validation, see PLAY_INTEGRITY_IMPLEMENTATION.md
 */
object IntegrityManager {
    private const val TAG = "IntegrityManager"
    
    // Cloud project number from Google Cloud Console
    // TODO: Replace with your actual cloud project number
    // Get this from: https://console.cloud.google.com/
    private const val CLOUD_PROJECT_NUMBER = 0L // Replace with your project number
    
    /**
     * Request an integrity token from Google Play Integrity API
     *
     * @param context Application context
     * @param nonce A unique nonce for this request (prevents replay attacks)
     * @param allowDebugApps If true, allows debug builds to get tokens (for development only)
     *                       Defaults to BuildConfig.ALLOW_DEBUG_INTEGRITY
     * @return IntegrityToken string or null if failed
     */
    suspend fun requestIntegrityToken(
        context: Context,
        nonce: String,
        allowDebugApps: Boolean = BuildConfig.ALLOW_DEBUG_INTEGRITY
    ): String? {
        return try {
            // Check if we should allow debug apps based on build configuration
            if (BuildConfig.DEBUG && !allowDebugApps) {
                Log.w(TAG, "Debug build detected but allowDebugApps is false. Skipping integrity check.")
                return "DEBUG_BUILD_SKIP_INTEGRITY"
            }
            
            // Log build type for debugging
            Log.d(TAG, "Build type: ${BuildConfig.BUILD_TYPE_NAME}, Allow debug: $allowDebugApps")
            
            // Validate cloud project number is set
            if (CLOUD_PROJECT_NUMBER == 0L) {
                Log.e(TAG, "CLOUD_PROJECT_NUMBER not configured! Please set it in IntegrityManager.kt")
                if (BuildConfig.DEBUG) {
                    return "DEBUG_NO_PROJECT_NUMBER"
                }
                return null
            }
            
            Log.d(TAG, "Requesting integrity token with nonce: $nonce")
            
            val integrityManager = IntegrityManagerFactory.create(context)
            
            // Create the integrity token request with nonce
            val integrityTokenRequest = IntegrityTokenRequest.builder()
                .setCloudProjectNumber(CLOUD_PROJECT_NUMBER)
                .setNonce(nonce)
                .build()
            
            // Request the token with timeout
            val response = withTimeoutOrNull(10_000L) {
                integrityManager.requestIntegrityToken(integrityTokenRequest).await()
            }
            
            if (response == null) {
                Log.e(TAG, "Integrity token request timed out")
                return null
            }
            
            val token = response.token()
            Log.d(TAG, "Successfully obtained integrity token (length: ${token.length})")
            
            return token
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request integrity token", e)
            
            // In debug builds, return a special token to allow testing
            if (BuildConfig.DEBUG && allowDebugApps) {
                Log.w(TAG, "Debug build: Returning debug token due to error")
                return "DEBUG_BUILD_ERROR:${e.message}"
            }
            
            return null
        }
    }
    
    /**
     * Generate a unique nonce for integrity token request
     * Nonce should be unique per request to prevent replay attacks
     *
     * @return Base64-encoded nonce string
     */
    fun generateNonce(): String {
        return try {
            val random = java.security.SecureRandom()
            val bytes = ByteArray(32) // 256 bits
            random.nextBytes(bytes)
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate nonce", e)
            // Fallback to UUID
            java.util.UUID.randomUUID().toString()
        }
    }
    
    /**
     * Check if the device supports Play Integrity API
     * 
     * @param context Application context
     * @return true if supported, false otherwise
     */
    fun isIntegrityApiSupported(context: Context): Boolean {
        return try {
            // Try to create the integrity manager
            IntegrityManagerFactory.create(context)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Play Integrity API not supported", e)
            false
        }
    }
}

// Made with Bob
