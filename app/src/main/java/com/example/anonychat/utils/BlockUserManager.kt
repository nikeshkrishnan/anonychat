package com.example.anonychat.utils

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.example.anonychat.network.NetworkClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * BlockUserManager - Handles block/unblock operations with application-scoped coroutines
 * that persist beyond UI lifecycle, preventing cancellation when navigating away
 */
object BlockUserManager {
    private val TAG = "BlockUserManager"
    
    // Application-scoped coroutine that survives UI lifecycle changes
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    /**
     * Block a user asynchronously
     * @param context Application context for SharedPreferences and Toast
     * @param myEmail Current user's email
     * @param targetEmail Target user's email to block
     * @param onSuccess Callback invoked on successful block (runs on main thread)
     * @param onError Callback invoked on error (runs on main thread)
     */
    fun blockUser(
        context: Context,
        myEmail: String,
        targetEmail: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        applicationScope.launch {
            try {
                val myUserId = myEmail.substringBefore("@")
                val targetUserId = targetEmail.substringBefore("@")
                
                Log.d(TAG, "Blocking user: $targetEmail (ID: $targetUserId)")
                val response = NetworkClient.api.blockUser(myUserId, targetUserId)
                
                if (response.isSuccessful) {
                    Log.d(TAG, "Successfully blocked: $targetEmail")
                    
                    // Update SharedPreferences
                    val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("blocked_$targetEmail", true)
                        .apply()
                    
                    // Show success message on main thread
                    launch(Dispatchers.Main) {
                        Toast.makeText(context, "User blocked successfully", Toast.LENGTH_SHORT).show()
                        onSuccess?.invoke()
                    }
                } else {
                    Log.e(TAG, "Failed to block: $targetEmail - Response code: ${response.code()}")
                    launch(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to block user", Toast.LENGTH_SHORT).show()
                        onError?.invoke("Failed to block user")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error blocking user $targetEmail: ${e.message}", e)
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    onError?.invoke(e.message ?: "Unknown error")
                }
            }
        }
    }
    
    /**
     * Unblock a user asynchronously
     * @param context Application context for SharedPreferences and Toast
     * @param myEmail Current user's email
     * @param targetEmail Target user's email to unblock
     * @param onSuccess Callback invoked on successful unblock (runs on main thread)
     * @param onError Callback invoked on error (runs on main thread)
     */
    fun unblockUser(
        context: Context,
        myEmail: String,
        targetEmail: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        applicationScope.launch {
            try {
                val myUserId = myEmail.substringBefore("@")
                val targetUserId = targetEmail.substringBefore("@")
                
                Log.d(TAG, "Unblocking user: $targetEmail (ID: $targetUserId)")
                val response = NetworkClient.api.unblockUser(myUserId, targetUserId)
                
                if (response.isSuccessful) {
                    Log.d(TAG, "Successfully unblocked: $targetEmail")
                    
                    // Update SharedPreferences
                    val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("blocked_$targetEmail", false)
                        .apply()
                    
                    // Show success message on main thread
                    launch(Dispatchers.Main) {
                        Toast.makeText(context, "User unblocked successfully", Toast.LENGTH_SHORT).show()
                        onSuccess?.invoke()
                    }
                } else {
                    Log.e(TAG, "Failed to unblock: $targetEmail - Response code: ${response.code()}")
                    launch(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to unblock user", Toast.LENGTH_SHORT).show()
                        onError?.invoke("Failed to unblock user")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error unblocking user $targetEmail: ${e.message}", e)
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    onError?.invoke(e.message ?: "Unknown error")
                }
            }
        }
    }
    
    /**
     * Unblock multiple users asynchronously
     * @param context Application context for SharedPreferences and Toast
     * @param myEmail Current user's email
     * @param targetEmails List of target user emails to unblock
     * @param onComplete Callback invoked when all operations complete (runs on main thread)
     */
    fun unblockMultipleUsers(
        context: Context,
        myEmail: String,
        targetEmails: List<String>,
        onComplete: ((successCount: Int, failCount: Int) -> Unit)? = null
    ) {
        applicationScope.launch {
            val myUserId = myEmail.substringBefore("@")
            var successCount = 0
            var failCount = 0
            
            targetEmails.forEach { email ->
                try {
                    val targetUserId = email.substringBefore("@")
                    Log.d(TAG, "Unblocking user: $email (ID: $targetUserId)")
                    val response = NetworkClient.api.unblockUser(myUserId, targetUserId)
                    
                    if (response.isSuccessful) {
                        Log.d(TAG, "Successfully unblocked: $email")
                        successCount++
                        
                        // Update SharedPreferences
                        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                        prefs.edit()
                            .putBoolean("blocked_$email", false)
                            .apply()
                    } else {
                        Log.e(TAG, "Failed to unblock: $email")
                        failCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error unblocking $email: ${e.message}")
                    failCount++
                }
            }
            
            // Show result on main thread
            launch(Dispatchers.Main) {
                val message = when {
                    failCount == 0 -> "All selected users unblocked successfully"
                    successCount == 0 -> "Failed to unblock users"
                    else -> "Unblocked $successCount users, $failCount failed"
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                onComplete?.invoke(successCount, failCount)
            }
        }
    }
}

// Made with Bob
