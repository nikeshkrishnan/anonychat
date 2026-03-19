package com.example.anonychat.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.anonychat.R
import com.example.anonychat.model.User
import com.example.anonychat.network.AgeRange
import com.example.anonychat.network.NetworkClient
import com.example.anonychat.network.RomanceRange
import com.example.anonychat.network.UpdateUsernameRequest
import com.example.anonychat.network.WebSocketManager
import com.example.anonychat.utils.PlayIntegrityManager
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun UpdateUsernameScreen(
    currentUsername: String,
    onUsernameUpdated: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // State
    var username by remember { mutableStateOf(currentUsername) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Theme
    val themePrefs = remember {
        context.getSharedPreferences("anonychat_theme", Context.MODE_PRIVATE)
    }
    val isDarkTheme = themePrefs.getBoolean("is_dark_theme", false)
    
    // Setup video background player
    val exoPlayer = remember(context, isDarkTheme) {
        ExoPlayer.Builder(context).build().apply {
            val videoUri = if (isDarkTheme) {
                Uri.parse("android.resource://${context.packageName}/${R.raw.night}")
            } else {
                Uri.parse("android.resource://${context.packageName}/${R.raw.cloud}")
            }
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }
    
    // Cleanup on dispose
    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Video background
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize()
        )
        
        // Main content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Card with username input
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDarkTheme) Color(0xCC121821) else Color(0xCCFFFFFF)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Update Username",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isDarkTheme) Color.White else Color.Black
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Choose a new username for your account",
                        fontSize = 14.sp,
                        color = if (isDarkTheme) Color.Gray else Color.DarkGray
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    // Username input field
                    OutlinedTextField(
                        value = username,
                        onValueChange = { 
                            username = it
                            errorMessage = null
                        },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = if (isDarkTheme) Color.White else Color.Black,
                            unfocusedTextColor = if (isDarkTheme) Color.White else Color.Black,
                            focusedBorderColor = Color(0xFF2196F3),
                            unfocusedBorderColor = if (isDarkTheme) Color.Gray else Color.LightGray,
                            focusedLabelColor = Color(0xFF2196F3),
                            unfocusedLabelColor = if (isDarkTheme) Color.Gray else Color.DarkGray
                        ),
                        enabled = !isLoading
                    )
                    
                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage ?: "",
                            color = Color.Red,
                            fontSize = 12.sp
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    // Set Username button
                    Button(
                        onClick = {
                            if (username.isBlank()) {
                                errorMessage = "Username cannot be empty"
                                return@Button
                            }
                            
                            isLoading = true
                            errorMessage = null
                            
                            val client = com.google.android.gms.appset.AppSet.getClient(context)
                            client.appSetIdInfo.addOnSuccessListener { appSetIdInfo ->
                                val appSetId = appSetIdInfo.id
                                val androidId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
                                val fetchedUserId = "$androidId:$appSetId"
                                
                                scope.launch {
                                    try {
                                        // Fetch Google Play Integrity token
                                        Log.d("UpdateUsernameScreen", "Fetching Play Integrity token...")
                                        val integrityManager = PlayIntegrityManager(context)
                                        val integrityToken = integrityManager.requestIntegrityToken("update_username")
                                        
                                        if (integrityToken == null) {
                                            Log.w("UpdateUsernameScreen", "Failed to obtain integrity token, proceeding without it")
                                        } else {
                                            Log.d("UpdateUsernameScreen", "Successfully obtained integrity token")
                                        }
                                        
                                        val response = NetworkClient.api.updateUsername(
                                            UpdateUsernameRequest(
                                                userId = fetchedUserId,
                                                username = username.trim(),
                                                integrityToken = integrityToken
                                            )
                                        )
                                    
                                    if (response.isSuccessful && response.body() != null) {
                                        val updateResponse = response.body()!!
                                        Log.e("UpdateUsernameScreen", "Username update successful!")
                                        Log.e("UpdateUsernameScreen", "New email: ${updateResponse.email}")
                                        Log.e("UpdateUsernameScreen", "New username: ${updateResponse.username}")
                                        
                                        // CRITICAL: Stop WebSocketMonitorService BEFORE updating credentials
                                        // This prevents it from reconnecting with old email
                                        Log.e("UpdateUsernameScreen", "Stopping WebSocketMonitorService before credential update")
                                        com.example.anonychat.service.WebSocketMonitorService.stop(context)
                                        
                                        // Disconnect WebSocket with old email
                                        Log.e("UpdateUsernameScreen", "Disconnecting WebSocket with old email")
                                        WebSocketManager.disconnect()
                                        
                                        // Wait for service to fully stop
                                        kotlinx.coroutines.delay(1000)
                                        
                                        // Get user data from SharedPreferences
                                        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                                        val oldEmail = prefs.getString("user_email", "") ?: ""
                                        val userId = prefs.getString("user_id", "") ?: ""
                                        val token = prefs.getString("access_token", "") ?: ""
                                        
                                        // Update Room database with new email
                                        if (oldEmail.isNotEmpty() && updateResponse.email.isNotEmpty() && oldEmail != updateResponse.email) {
                                            Log.e("UpdateUsernameScreen", "Updating Room database emails from $oldEmail to ${updateResponse.email}")
                                            WebSocketManager.updateAllEmailsInDatabase(oldEmail, updateResponse.email)
                                        }
                                        
                                        // Create User object with updated username and NEW email from response
                                        val user = User(
                                            id = updateResponse.email,  // Use NEW email from backend
                                            username = updateResponse.username,
                                            profilePictureUrl = null,
                                            userId = userId
                                        )
                                        
                                        // Save User object as JSON and update username AND email
                                        val userJson = com.google.gson.Gson().toJson(user)
                                        prefs.edit().apply {
                                            putString("username", updateResponse.username)
                                            putString("user_email", updateResponse.email)  // Update to NEW email
                                            putString("user_json", userJson)
                                            remove("account_reset") // Clear the reset flag
                                        }.apply()
                                        
                                        Log.e("UpdateUsernameScreen", "User object saved with new email: $userJson")
                                        
                                        // Verify the email was saved correctly
                                        val verifyEmail = prefs.getString("user_email", null)
                                        Log.e("UpdateUsernameScreen", "Verified saved email: $verifyEmail")
                                        
                                        // CRITICAL: Release the email update lock now that both DB and SharedPreferences are updated
                                        WebSocketManager.unlockEmailUpdate()
                                        Log.e("UpdateUsernameScreen", "Email update complete, lock released")
                                        
                                        // Reconnect WebSocket with NEW email and token
                                        if (token.isNotEmpty()) {
                                            Log.e("UpdateUsernameScreen", "First reconnection with NEW email: ${updateResponse.email}")
                                            WebSocketManager.connect(token, updateResponse.email)
                                            
                                            // Wait for first connection to establish
                                            Log.e("UpdateUsernameScreen", "Waiting for first connection...")
                                            var attempts = 0
                                            while (attempts < 20 && !WebSocketManager.isConnected()) {
                                                kotlinx.coroutines.delay(500)
                                                attempts++
                                                Log.e("UpdateUsernameScreen", "First connection state: ${WebSocketManager.isConnected()}, attempt $attempts/20")
                                            }
                                            
                                            if (WebSocketManager.isConnected()) {
                                                Log.e("UpdateUsernameScreen", "First connection successful! Now forcing second reconnection...")
                                                
                                                // FORCE SECOND RECONNECTION to ensure new email is used
                                                Log.e("UpdateUsernameScreen", "Disconnecting for forced second reconnection")
                                                WebSocketManager.disconnect()
                                                kotlinx.coroutines.delay(1000)
                                                
                                                // Verify email in SharedPreferences before second reconnection
                                                val verifyEmailBeforeSecondReconnect = prefs.getString("user_email", null)
                                                Log.e("UpdateUsernameScreen", "Email in SharedPreferences before second reconnect: $verifyEmailBeforeSecondReconnect")
                                                Log.e("UpdateUsernameScreen", "Email from backend response: ${updateResponse.email}")
                                                Log.e("UpdateUsernameScreen", "Old email before update: $oldEmail")
                                                
                                                // Use email from SharedPreferences for second reconnection to ensure it's the saved one
                                                val emailForReconnect = verifyEmailBeforeSecondReconnect ?: updateResponse.email
                                                Log.e("UpdateUsernameScreen", "Second reconnection with email: $emailForReconnect")
                                                WebSocketManager.connect(token, emailForReconnect)
                                                
                                                // Wait for second connection to be ready
                                                Log.e("UpdateUsernameScreen", "Waiting for second connection to be ready...")
                                                attempts = 0
                                                while (attempts < 20 && !WebSocketManager.isConnected()) {
                                                    kotlinx.coroutines.delay(500)
                                                    attempts++
                                                    Log.e("UpdateUsernameScreen", "Second connection state: ${WebSocketManager.isConnected()}, attempt $attempts/20")
                                                }
                                                
                                                if (WebSocketManager.isConnected()) {
                                                    Log.e("UpdateUsernameScreen", "Second reconnection successful!")
                                                    
                                                    // Set flag to send default preferences in ChatScreen after account reset
                                                    Log.e("UpdateUsernameScreen", "Setting flag to send default preferences in ChatScreen")
                                                    prefs.edit().putBoolean("needs_default_preferences", true).apply()
                                                    
                                                    // Start WebSocketMonitorService with new credentials
                                                    Log.e("UpdateUsernameScreen", "Starting WebSocketMonitorService with new credentials")
                                                    com.example.anonychat.service.WebSocketMonitorService.start(context)
                                                    
                                                    // Reset loading state before navigation
                                                    isLoading = false
                                                    
                                                    // Navigate to ChatScreen
                                                    onUsernameUpdated()
                                                } else {
                                                    Log.e("UpdateUsernameScreen", "Second reconnection failed after 10 seconds")
                                                    errorMessage = "Failed to reconnect. Please restart the app."
                                                    isLoading = false
                                                }
                                            } else {
                                                Log.e("UpdateUsernameScreen", "First reconnection failed after 10 seconds")
                                                errorMessage = "Failed to reconnect. Please restart the app."
                                                isLoading = false
                                            }
                                        } else {
                                            Log.e("UpdateUsernameScreen", "Token is empty, cannot reconnect")
                                            errorMessage = "Authentication error. Please login again."
                                            isLoading = false
                                        }
                                    } else {
                                        errorMessage = "Failed to update username: ${response.code()}"
                                        isLoading = false
                                    }
                                } catch (e: Exception) {
                                    Log.e("UpdateUsernameScreen", "Error updating username", e)
                                    errorMessage = "Error: ${e.message}"
                                    isLoading = false
                                }
                            }
                        }.addOnFailureListener {
                            isLoading = false
                            errorMessage = "Failed to retrieve device ID"
                        }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2196F3),
                            disabledContainerColor = Color.Gray
                        ),
                        shape = RoundedCornerShape(16.dp),
                        enabled = !isLoading && username.isNotBlank()
                    ) {
                        Text(
                            text = if (isLoading) "Updating..." else "Set Username",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
        
        // Use the shared LoadingHeartOverlay component
        LoadingHeartOverlay(isLoading = isLoading)
    }
}

// Made with Bob
