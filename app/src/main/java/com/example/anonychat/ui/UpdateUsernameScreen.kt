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
import com.example.anonychat.network.NetworkClient
import com.example.anonychat.network.UpdateUsernameRequest
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
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
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
                            
                            scope.launch {
                                try {
                                    val response = NetworkClient.api.updateUsername(
                                        UpdateUsernameRequest(username = username.trim())
                                    )
                                    
                                    if (response.isSuccessful) {
                                        Log.e("UpdateUsernameScreen", "Username update successful!")
                                        
                                        // Get user data from SharedPreferences
                                        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                                        val userEmail = prefs.getString("user_email", "") ?: ""
                                        val userId = prefs.getString("user_id", "") ?: ""
                                        
                                        // Create User object with updated username
                                        val user = User(
                                            id = userEmail,
                                            username = username.trim(),
                                            profilePictureUrl = null,
                                            userId = userId
                                        )
                                        
                                        // Save User object as JSON and update username
                                        val userJson = com.google.gson.Gson().toJson(user)
                                        prefs.edit().apply {
                                            putString("username", username.trim())
                                            putString("user_json", userJson)
                                            remove("account_reset") // Clear the reset flag
                                        }.apply()
                                        
                                        Log.e("UpdateUsernameScreen", "User object saved: $userJson")
                                        
                                        // Reset loading state before navigation
                                        isLoading = false
                                        
                                        // Navigate to ChatScreen
                                        onUsernameUpdated()
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
