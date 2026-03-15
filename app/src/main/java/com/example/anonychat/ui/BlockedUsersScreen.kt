package com.example.anonychat.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.example.anonychat.R
import com.example.anonychat.network.BlockedUser
import com.example.anonychat.network.NetworkClient
import com.example.anonychat.network.WebSocketEvent
import com.example.anonychat.network.WebSocketManager
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * BlockedUsersScreen - Displays a list of blocked users with ability to unblock
 * Similar design to ChatScreen with transparent card and video background
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun BlockedUsersScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // State
    var blockedUsers by remember { mutableStateOf<List<BlockedUser>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedUsers = remember { mutableStateListOf<String>() }
    var userToUnblock by remember { mutableStateOf<BlockedUser?>(null) }
    
    // Theme
    val themePrefs = remember {
        context.getSharedPreferences("anonychat_theme", Context.MODE_PRIVATE)
    }
    val isDarkTheme = themePrefs.getBoolean("is_dark_theme", false)
    
    // Get token
    val userPrefs = remember {
        context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    }
    val token = userPrefs.getString("access_token", "") ?: ""
    
    Log.e("BlockedUsersScreen", "=== BLOCKED USERS SCREEN INIT ===")
    Log.e("BlockedUsersScreen", "Token retrieved: ${if (token.isEmpty()) "EMPTY" else "Present (${token.length} chars)"}")
    
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
    
    // Fetch blocked list on launch
    LaunchedEffect(Unit) {
        Log.e("BlockedUsersScreen", "=== FETCHING BLOCKED LIST ===")
        if (token.isNotEmpty()) {
            Log.e("BlockedUsersScreen", "Sending get blocked list request with token")
            WebSocketManager.sendGetBlockedList(token)
        } else {
            Log.e("BlockedUsersScreen", "ERROR: No authentication token found!")
            errorMessage = "No authentication token found"
            isLoading = false
        }
    }
    
    // Listen for WebSocket events
    LaunchedEffect(Unit) {
        WebSocketManager.events
            .filter { event ->
                event is WebSocketEvent.BlockedListData ||
                event is WebSocketEvent.BlockedListError ||
                event is WebSocketEvent.UnblockUserSuccess ||
                event is WebSocketEvent.UnblockUserError
            }
            .collect { event ->
                when (event) {
                    is WebSocketEvent.BlockedListData -> {
                        blockedUsers = event.blockedUsers
                        isLoading = false
                        errorMessage = null
                        Log.e("BlockedUsersScreen", "=== BLOCKED LIST SUCCESS ===")
                        Log.e("BlockedUsersScreen", "Received ${event.blockedUsers.size} blocked users")
                        event.blockedUsers.forEach { user ->
                            Log.e("BlockedUsersScreen", "  - ${user.username} (${user.email})")
                        }
                    }
                    is WebSocketEvent.BlockedListError -> {
                        errorMessage = event.error
                        isLoading = false
                        Log.e("BlockedUsersScreen", "=== BLOCKED LIST ERROR ===")
                        Log.e("BlockedUsersScreen", "Error: ${event.error}")
                    }
                    is WebSocketEvent.UnblockUserSuccess -> {
                        Log.d("BlockedUsersScreen", "User unblocked: ${event.unblockedEmail}")
                        // Remove from local list
                        blockedUsers = blockedUsers.filter { it.email != event.unblockedEmail }
                        // Show success message
                        android.widget.Toast.makeText(
                            context,
                            "User unblocked successfully",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    is WebSocketEvent.UnblockUserError -> {
                        Log.e("BlockedUsersScreen", "Error unblocking user: ${event.error}")
                        android.widget.Toast.makeText(
                            context,
                            "Failed to unblock: ${event.error}",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    else -> {}
                }
            }
    }
    
    // Handle back button in selection mode
    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedUsers.clear()
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
        
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                color = if (isDarkTheme) Color(0xFF121821) else Color.White,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 32.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = if (isDarkTheme) Color.White else Color.Black
                        )
                    }
                    
                    Text(
                        text = "Blocked Users",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isDarkTheme) Color.White else Color.Black,
                        modifier = Modifier.weight(1f)
                    )
                    
                    if (blockedUsers.isNotEmpty()) {
                        Text(
                            text = "${blockedUsers.size}",
                            fontSize = 16.sp,
                            color = if (isDarkTheme) Color.Gray else Color.DarkGray,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            }
            
            // Main content card
            val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Surface(
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = navigationBarPadding + 16.dp)
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(isSelectionMode) {
                        if (isSelectionMode) {
                            detectTapGestures(
                                onTap = {
                                    isSelectionMode = false
                                    selectedUsers.clear()
                                }
                            )
                        }
                    },
                shape = RoundedCornerShape(32.dp),
                color = if (isDarkTheme) Color(0x0DFFFFFF) else Color(0x4DFFFFFF),
                border = if (isDarkTheme) BorderStroke(1.dp, Color(0x08FFFFFF)) else null
            ) {
                Column {
                    // Selection mode controls
                    if (isSelectionMode && blockedUsers.isNotEmpty()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(),
                            shape = RoundedCornerShape(
                                topStart = 32.dp,
                                topEnd = 32.dp,
                                bottomStart = 8.dp,
                                bottomEnd = 8.dp
                            ),
                            color = if (isDarkTheme) Color(0xFF121821) else Color.White,
                            shadowElevation = 0.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Select All checkbox
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable {
                                        if (selectedUsers.size == blockedUsers.size) {
                                            selectedUsers.clear()
                                        } else {
                                            selectedUsers.clear()
                                            selectedUsers.addAll(blockedUsers.map { it.email })
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (selectedUsers.size == blockedUsers.size) 
                                            Icons.Default.CheckBox 
                                        else 
                                            Icons.Default.CheckBoxOutlineBlank,
                                        contentDescription = "Select All",
                                        tint = if (isDarkTheme) Color.White else Color.Black
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Select All",
                                        color = if (isDarkTheme) Color.White else Color.Black,
                                        fontSize = 16.sp
                                    )
                                }
                                
                                // Unblock button
                                if (selectedUsers.isNotEmpty()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(Color(0xFF4CAF50))
                                            .clickable {
                                                // Unblock selected users via API
                                                scope.launch {
                                                    val myEmail = userPrefs.getString("user_email", "") ?: ""
                                                    val myUserId = myEmail.substringBefore("@")
                                                    
                                                    selectedUsers.forEach { email ->
                                                        try {
                                                            val targetUserId = email.substringBefore("@")
                                                            Log.d("BlockedUsersScreen", "Unblocking user: $email (ID: $targetUserId)")
                                                            val response = NetworkClient.api.unblockUser(myUserId, targetUserId)
                                                            
                                                            if (response.isSuccessful) {
                                                                Log.d("BlockedUsersScreen", "Successfully unblocked: $email")
                                                                // Update SharedPreferences to sync with KeyboardProofScreen
                                                                userPrefs.edit()
                                                                    .putBoolean("blocked_$email", false)
                                                                    .apply()
                                                                // Remove from local list immediately
                                                                blockedUsers = blockedUsers.filter { it.email != email }
                                                            } else {
                                                                Log.e("BlockedUsersScreen", "Failed to unblock: $email")
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.e("BlockedUsersScreen", "Error unblocking $email: ${e.message}")
                                                        }
                                                    }
                                                    
                                                    // Clear selection and exit selection mode
                                                    selectedUsers.clear()
                                                    isSelectionMode = false
                                                    
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Users unblocked",
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = "🔓",
                                            fontSize = 20.sp
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Unblock (${selectedUsers.size})",
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    // Content
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            isLoading -> {
                                CircularProgressIndicator(
                                    color = if (isDarkTheme) Color.White else Color.Black
                                )
                            }
                            errorMessage != null -> {
                                Text(
                                    text = errorMessage ?: "Unknown error",
                                    color = Color.Red,
                                    fontSize = 16.sp
                                )
                            }
                            blockedUsers.isEmpty() -> {
                                Text(
                                    text = "No blocked users",
                                    color = if (isDarkTheme) Color.Gray else Color.DarkGray,
                                    fontSize = 16.sp
                                )
                            }
                            else -> {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    items(blockedUsers) { user ->
                                        BlockedUserItem(
                                            user = user,
                                            isDarkTheme = isDarkTheme,
                                            isSelected = selectedUsers.contains(user.email),
                                            isSelectionMode = isSelectionMode,
                                            onLongPress = {
                                                if (!isSelectionMode) {
                                                    // Enter multi-select mode on long press
                                                    isSelectionMode = true
                                                    selectedUsers.add(user.email)
                                                }
                                            },
                                            onClick = {
                                                if (isSelectionMode) {
                                                    // Toggle selection in multi-select mode
                                                    if (selectedUsers.contains(user.email)) {
                                                        selectedUsers.remove(user.email)
                                                        if (selectedUsers.isEmpty()) {
                                                            isSelectionMode = false
                                                        }
                                                    } else {
                                                        selectedUsers.add(user.email)
                                                    }
                                                } else {
                                                    // Single tap: show unblock confirmation dialog
                                                    userToUnblock = user
                                                }
                                            }
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }
                                }
                                
                                // Unblock confirmation dialog
                                userToUnblock?.let { user ->
                                    AlertDialog(
                                        onDismissRequest = { userToUnblock = null },
                                        title = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(text = "🔓", fontSize = 24.sp)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(text = "Unblock User")
                                            }
                                        },
                                        text = {
                                            Text("Do you want to unblock ${user.username}?")
                                        },
                                        confirmButton = {
                                            TextButton(
                                                onClick = {
                                                    scope.launch {
                                                        try {
                                                            val myEmail = userPrefs.getString("user_email", "") ?: ""
                                                            val myUserId = myEmail.substringBefore("@")
                                                            val targetUserId = user.email.substringBefore("@")
                                                            
                                                            Log.d("BlockedUsersScreen", "Unblocking user: ${user.email} (ID: $targetUserId)")
                                                            val response = NetworkClient.api.unblockUser(myUserId, targetUserId)
                                                            
                                                            if (response.isSuccessful) {
                                                                Log.d("BlockedUsersScreen", "Successfully unblocked: ${user.email}")
                                                                
                                                                // Update SharedPreferences to sync with KeyboardProofScreen
                                                                userPrefs.edit()
                                                                    .putBoolean("blocked_${user.email}", false)
                                                                    .apply()
                                                                
                                                                // Remove from local list immediately
                                                                blockedUsers = blockedUsers.filter { it.email != user.email }
                                                                
                                                                android.widget.Toast.makeText(
                                                                    context,
                                                                    "User unblocked successfully",
                                                                    android.widget.Toast.LENGTH_SHORT
                                                                ).show()
                                                            } else {
                                                                Log.e("BlockedUsersScreen", "Failed to unblock: ${user.email}")
                                                                android.widget.Toast.makeText(
                                                                    context,
                                                                    "Failed to unblock user",
                                                                    android.widget.Toast.LENGTH_SHORT
                                                                ).show()
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.e("BlockedUsersScreen", "Error unblocking user: ${e.message}")
                                                            android.widget.Toast.makeText(
                                                                context,
                                                                "Error: ${e.message}",
                                                                android.widget.Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                        userToUnblock = null
                                                    }
                                                }
                                            ) {
                                                Text("Unblock", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { userToUnblock = null }) {
                                                Text("Cancel")
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BlockedUserItem(
    user: BlockedUser,
    isDarkTheme: Boolean,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onLongPress: () -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    
    // Calculate emotion based on romance range
    val emotion = remember(user.romanceMin, user.romanceMax) {
        romanceRangeToEmotion(user.romanceMin, user.romanceMax)
    }
    
    // Get avatar resource based on gender and emotion
    val avatarResName = if (user.gender == "female") "female_exp$emotion" else "male_exp$emotion"
    val avatarResId = remember(avatarResName) {
        context.resources.getIdentifier(avatarResName, "raw", context.packageName)
    }
    val avatarUri = remember(avatarResId) {
        if (avatarResId != 0) {
            Uri.parse("android.resource://${context.packageName}/$avatarResId")
        } else {
            Uri.parse("android.resource://${context.packageName}/${R.raw.male_exp1}")
        }
    }
    
    // Create image loader for GIF support
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onLongPress() },
                    onTap = { onClick() }
                )
            }
            .then(
                if (isSelected) {
                    Modifier
                        .border(2.dp, Color(0xFF2196F3), RoundedCornerShape(16.dp))
                        .shadow(8.dp, RoundedCornerShape(16.dp))
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) {
            if (isDarkTheme) Color(0xFF1E3A5F) else Color(0xFFBBDEFB)
        } else {
            if (isDarkTheme) Color(0xFF1A1F2E) else Color.White
        },
        shadowElevation = if (isSelected) 8.dp else 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection checkbox
            if (isSelectionMode) {
                Icon(
                    imageVector = if (isSelected) 
                        Icons.Default.CheckBox 
                    else 
                        Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = "Select",
                    tint = if (isDarkTheme) Color.White else Color.Black,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            
            // Profile picture based on gender and romance range
            Image(
                painter = rememberAsyncImagePainter(
                    model = ImageRequest.Builder(context)
                        .data(avatarUri)
                        .build(),
                    imageLoader = imageLoader
                ),
                contentDescription = "Profile Picture",
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(if (isDarkTheme) Color(0xFF2C2C2C) else Color(0xFFE0E0E0)),
                contentScale = ContentScale.Crop
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // User info - only username
            Text(
                text = user.username,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDarkTheme) Color.White else Color.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// Made with Bob
