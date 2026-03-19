package com.example.anonychat.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
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
import com.example.anonychat.service.ChatSocketService
import com.example.anonychat.service.WebSocketMonitorService
import com.example.anonychat.utils.ActiveChatTracker
import com.example.anonychat.utils.DeactivatedUsersManager
import com.example.anonychat.repository.UserRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.example.anonychat.model.Preferences
import com.example.anonychat.model.User
import com.example.anonychat.network.AgeRange
import com.example.anonychat.network.NetworkClient
import com.example.anonychat.network.RomanceRange
import com.example.anonychat.network.WebSocketEvent
import com.example.anonychat.network.WebSocketManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
/* ---------------------------------------------------
   CHAT CONVERSATION ENTRY MODEL
--------------------------------------------------- */
data class ChatConversationEntry(
    val userEmail: String,
    val username: String,
    val profilePictureUrl: String? = null,
    val lastMessage: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int = 0,
    // Peer avatar fields — same logic as KeyboardProofScreen
    val peerGender: String = "male",
    val peerRomanceMin: Float = 1f,
    val peerRomanceMax: Float = 5f,
    val userRating: Float? = null,  // User's average rating
    val isFavorite: Boolean = false  // Favorite status
)

/**
 * Singleton repository that persists the conversation list across navigation.
 * Both ChatScreen (display) and KeyboardProofScreen (send/receive) update this.
 */
object ConversationRepository {
    private const val PREFS_NAME = "conversation_repository"
    private const val KEY_CONVERSATIONS = "conversations_json"
    
    val conversations = androidx.compose.runtime.mutableStateListOf<ChatConversationEntry>()
    private var context: Context? = null
    private val gson = Gson()
    private val lock = Any()

    /**
     * Initialize the repository with a Context and load persisted data.
     * Call this once from Application or MainActivity.
     */
    fun initialize(ctx: Context) {
        if (context == null) {
            context = ctx.applicationContext
            load()
        }
    }

    /**
     * Save the current conversation list to SharedPreferences as JSON.
     */
    private fun save() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = gson.toJson(conversations.toList())
        prefs.edit().putString(KEY_CONVERSATIONS, json).apply()
    }

    /**
     * Load the conversation list from SharedPreferences.
     */
    private fun load() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CONVERSATIONS, null) ?: return
        try {
            val type = object : TypeToken<List<ChatConversationEntry>>() {}.type
            val loaded = gson.fromJson<List<ChatConversationEntry>>(json, type)
            conversations.clear()
            conversations.addAll(loaded)
        } catch (e: Exception) {
            Log.e("ConversationRepository", "Failed to load conversations", e)
        }
    }

    /**
     * @param peerGender     Optional: peer's gender ("male"/"female"). If null, keeps existing value.
     * @param peerRomanceMin Optional: peer's romance range min. If null, keeps existing value.
     * @param peerRomanceMax Optional: peer's romance range max. If null, keeps existing value.
     */
    fun upsert(
        myEmail: String,
        peerEmail: String,
        peerUsername: String,
        preview: String,
        timestamp: Long,
        isIncoming: Boolean,
        peerGender: String? = null,
        peerRomanceMin: Float? = null,
        peerRomanceMax: Float? = null,
        userRating: Float? = null,
        isFavorite: Boolean? = null
    ) {
        synchronized(lock) {
            // Find existing entry to preserve unread count and other data
            val existing = conversations.find { it.userEmail == peerEmail }
            
            // Remove ALL entries with this peerEmail (handles duplicates from corrupted data)
            conversations.removeAll { it.userEmail == peerEmail }
            
            // Add the new/updated entry
            conversations.add(
                ChatConversationEntry(
                    userEmail = peerEmail,
                    username = peerUsername,
                    lastMessage = preview,
                    lastMessageTimestamp = timestamp,
                    unreadCount = if (isIncoming) (existing?.unreadCount ?: 0) + 1 else (existing?.unreadCount ?: 0),
                    peerGender = peerGender ?: existing?.peerGender ?: "male",
                    peerRomanceMin = peerRomanceMin ?: existing?.peerRomanceMin ?: 1f,
                    peerRomanceMax = peerRomanceMax ?: existing?.peerRomanceMax ?: 5f,
                    userRating = userRating ?: existing?.userRating,
                    isFavorite = isFavorite ?: existing?.isFavorite ?: false
                )
            )
            
            // Sort: unread (incoming) entries first by timestamp desc, then rest by timestamp desc
            val sorted = conversations.sortedWith(
                compareByDescending<ChatConversationEntry> { it.unreadCount > 0 }
                    .thenByDescending { it.lastMessageTimestamp }
            )
            conversations.clear()
            conversations.addAll(sorted)
            save()
        }
    }

    fun clearUnread(peerEmail: String) {
        synchronized(lock) {
            val idx = conversations.indexOfFirst { it.userEmail == peerEmail }
            if (idx >= 0) {
                conversations[idx] = conversations[idx].copy(unreadCount = 0)
                save()
            }
        }
    }

    /**
     * Update favorite status for a conversation.
     */
    fun updateFavorite(peerEmail: String, isFavorite: Boolean) {
        synchronized(lock) {
            val idx = conversations.indexOfFirst { it.userEmail == peerEmail }
            if (idx >= 0) {
                conversations[idx] = conversations[idx].copy(isFavorite = isFavorite)
                save()
            }
        }
    }
    
    /**
     * Remove a conversation from the list by peer email.
     */
    fun remove(peerEmail: String) {
        synchronized(lock) {
            val idx = conversations.indexOfFirst { it.userEmail == peerEmail }
            if (idx >= 0) {
                conversations.removeAt(idx)
                save()
            }
        }
    }
}

private fun formatMessageTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000L -> "now"
        diff < 3_600_000L -> "${diff / 60_000}m"
        diff < 86_400_000L -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(timestamp))
    }
}

// Helper function to format numbers like YouTube (1k, 1.5k, 1M, etc.)
private fun formatCount(count: Int): String {
    return when {
        count < 1000 -> count.toString()
        count < 10000 -> String.format("%.1fk", count / 1000.0).replace(".0k", "k")
        count < 100000 -> String.format("%.0fk", count / 1000.0)
        count < 1000000 -> String.format("%.0fk", count / 1000.0)
        count < 10000000 -> String.format("%.1fM", count / 1000000.0).replace(".0M", "M")
        else -> String.format("%.0fM", count / 1000000.0)
    }
}


@RequiresApi(Build.VERSION_CODES.P)
private fun registerPlayOnceCallback(
        drawable: AnimatedImageDrawable,
        onEnd: () -> Unit
): Animatable2.AnimationCallback {
        val cb =
                object : Animatable2.AnimationCallback() {
                        override fun onAnimationEnd(d: Drawable?) {
                                android.util.Log.e(
                                        "ProfileAnim",
                                        "onAnimationEnd CALLED for drawable: ${d?.javaClass?.simpleName}"
                                )
                                try {
                                        (d as? android.graphics.drawable.Animatable)?.stop()
                                } catch (_: Exception) {}

                                // notify composable that animation ended
                                onEnd()

                                try {
                                        drawable.unregisterAnimationCallback(this)
                                } catch (_: Exception) {}
                        }
                }
        drawable.registerAnimationCallback(cb)
        return cb
}

object ChatScreenPipController {
        var onBeforeEnterPip: (() -> Unit)? = null
}

// private fun romanceRangeToEmotion(rangeStart: Float, rangeEnd: Float): Int {
//    // Explicit examples preserved: 1-2 -> 1, 9-10 -> 11
//    if (rangeEnd <= 2f) return 1
//    if (rangeStart >= 9f && rangeEnd == 10f) return 11
//
//    val mid = (rangeStart + rangeEnd) / 2f
//
//    return when {
//        mid < 1.95f -> 1
//        mid < 2.95f -> 2
//        mid < 3.95f -> 3
//        mid < 4.95f -> 4
//        mid < 5.95f -> 5
//        mid < 6.95f -> 6
//        mid < 7.95f -> 7
//        mid < 8.95f -> 8
//        mid < 9.5f -> 9
//        mid < 9.9f -> 10
//        else -> 11
//    }
// }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun ChatScreen(
        user: User,
        onChatActive: () -> Unit,
        onChatInactive: () -> Unit,
        onNavigateToProfile: (String) -> Unit,
        onNavigateToDirectChat:
                (
                        currentUser: User,
                        myPrefs: Preferences,
                        matchedUser: User,
                        matchedPrefs: Preferences,
                        isNewMatch: Boolean) -> Unit,
        onNavigateToBlockedUsers: () -> Unit = {},
        onNavigateToLogin: () -> Unit = {}
) {
        // notify MainActivity that chat is now active
        LaunchedEffect(Unit) { onChatActive() }

        // when leaving ChatScreen
        DisposableEffect(Unit) { onDispose { onChatInactive() } }

        var searchQuery by remember { mutableStateOf("") }
        var ratingFilter by remember { mutableStateOf("None") } // "None", "Highest", "Lowest"
        var showFavoritesOnly by remember { mutableStateOf(false) }
        var isSelectionMode by remember { mutableStateOf(false) }
        val selectedChats = remember { mutableStateListOf<String>() }
        val context = LocalContext.current
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        
        // State to track if we're waiting for first preference update
        var isWaitingForFirstPreferenceUpdate by remember { mutableStateOf(false) }
        
        // State to track if this is first time opening ChatScreen (WebSocket not yet healthy)
        val prefs = remember { context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE) }
        var isFirstTimeSetup by remember {
            mutableStateOf(prefs.getBoolean("first_time_chat_screen", true))
        }

        // Monitor WebSocket health on first launch
        LaunchedEffect(Unit) {
            if (isFirstTimeSetup) {
                Log.e("ChatScreen", "🟡 First time setup detected - waiting for WebSocket to be ready")
                // Poll WebSocket ready status (connected AND received ready_ack)
                while (isFirstTimeSetup) {
                    if (WebSocketManager.isReady()) {
                        Log.e("ChatScreen", "✅ WebSocket is now ready (connected + ready_ack received) - hiding overlay")
                        isFirstTimeSetup = false
                        // Save flag so we don't show overlay again
                        prefs.edit().putBoolean("first_time_chat_screen", false).apply()
                        break
                    }

                    kotlinx.coroutines.delay(500) // Check every 500ms
                }
            }
        }
        
        // Initialize ConversationRepository with context and load persisted data
        LaunchedEffect(Unit) {
                ConversationRepository.initialize(context)
                
                // Start ChatSocketService if not already running
                try {
                    val intent = Intent(context, ChatSocketService::class.java)
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: Exception) {
                    Log.e("ChatScreen", "Failed to start ChatSocketService", e)
                }
                
                // Send default preferences if flag is set (from first login or account reset)
                val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val token = prefs.getString("access_token", null)
                val needsDefaultPreferences = prefs.getBoolean("needs_default_preferences", false)
                
                // Show loading overlay if this is the first preference update
                if (needsDefaultPreferences) {
                    isWaitingForFirstPreferenceUpdate = true
                }
                
                Log.e("ChatScreen", "=== PREFERENCE UPDATE CHECK ===")
                Log.e("ChatScreen", "Token exists: ${token != null}")
                Log.e("ChatScreen", "needs_default_preferences flag: $needsDefaultPreferences")
                
                if (needsDefaultPreferences && token != null) {
                    Log.e("ChatScreen", "🔵 FLAG DETECTED - Sending default preferences via HTTP API (not waiting for WebSocket)")
                    
                    // Use HTTP API to send preferences immediately (don't wait for WebSocket)
                    try {
                        val userEmail = prefs.getString("user_email", "") ?: ""
                        if (userEmail.isNotEmpty()) {
                            val preferencesRequest = com.example.anonychat.network.PreferencesRequest(
                                userId = userEmail,
                                age = 18,
                                gender = "male",
                                preferredGender = "any",
                                preferredAgeRange = AgeRange(min = 18, max = 100),
                                romanceRange = RomanceRange(min = 1, max = 5),
                                random = true
                            )
                            
                            val response = com.example.anonychat.network.NetworkClient.api.setPreferences(preferencesRequest)
                            if (response.isSuccessful) {
                                Log.e("ChatScreen", "✅ Default preferences sent successfully via HTTP API")
                            } else {
                                Log.e("ChatScreen", "❌ Failed to send preferences via HTTP: ${response.code()}")
                            }
                        } else {
                            Log.e("ChatScreen", "❌ Cannot send preferences - user email is empty")
                        }
                    } catch (e: Exception) {
                        Log.e("ChatScreen", "❌ Exception sending preferences via HTTP", e)
                    }
                    
                    // Save default preferences locally and clear the flag
                    prefs.edit().apply {
                        putString("gender", "male")
                        putInt("age", 18)
                        putString("preferred_gender", "any")
                        putFloat("preferred_age_min", 18f)
                        putFloat("preferred_age_max", 100f)
                        putFloat("romance_min", 1f)
                        putFloat("romance_max", 5f)
                        putBoolean("random_match", true)
                        remove("needs_default_preferences")
                        apply()
                    }
                    Log.e("ChatScreen", "✅ Default preferences saved locally, flag cleared")
                } else {
                    Log.e("ChatScreen", "⚪ No flag set - skipping immediate default preference send")
                }
                
                // Safety mechanism: Always send update preferences after all checks are settled
                // This ensures preferences are synchronized with server regardless of flags
                if (token != null) {
                    Log.e("ChatScreen", "🟢 SAFETY MECHANISM - Reading current preferences")
                    // Get current preferences from SharedPreferences
                    val gender = prefs.getString("gender", "male") ?: "male"
                    val age = prefs.getInt("age", 18)
                    val preferredGender = prefs.getString("preferred_gender", "any") ?: "any"
                    val preferredAgeMin = prefs.getFloat("preferred_age_min", 18f)
                    val preferredAgeMax = prefs.getFloat("preferred_age_max", 100f)
                    val romanceMin = prefs.getFloat("romance_min", 1f)
                    val romanceMax = prefs.getFloat("romance_max", 5f)
                    val randomMatch = prefs.getBoolean("random_match", true)
                    
                    Log.e("ChatScreen", "🟢 Current preferences from SharedPreferences:")
                    Log.e("ChatScreen", "  - gender: $gender")
                    Log.e("ChatScreen", "  - age: $age")
                    Log.e("ChatScreen", "  - preferredGender: $preferredGender")
                    Log.e("ChatScreen", "  - preferredAgeRange: ${preferredAgeMin.toInt()}-${preferredAgeMax.toInt()}")
                    Log.e("ChatScreen", "  - romanceRange: ${romanceMin.toInt()}-${romanceMax.toInt()}")
                    Log.e("ChatScreen", "  - randomMatch: $randomMatch")
                    
                    // Use HTTP API to send preferences (don't wait for WebSocket)
                    Log.e("ChatScreen", "🟢 SAFETY MECHANISM - Sending current preferences via HTTP API")
                    
                    try {
                        val userEmail = prefs.getString("user_email", "") ?: ""
                        if (userEmail.isNotEmpty()) {
                            val preferencesRequest = com.example.anonychat.network.PreferencesRequest(
                                userId = userEmail,
                                age = age,
                                gender = gender,
                                preferredGender = preferredGender,
                                preferredAgeRange = AgeRange(min = preferredAgeMin.toInt(), max = preferredAgeMax.toInt()),
                                romanceRange = RomanceRange(min = romanceMin.toInt(), max = romanceMax.toInt()),
                                random = randomMatch
                            )
                            
                            val response = com.example.anonychat.network.NetworkClient.api.setPreferences(preferencesRequest)
                            if (response.isSuccessful) {
                                Log.e("ChatScreen", "✅ SAFETY MECHANISM - Preferences sent successfully via HTTP API")
                                // Hide loading overlay after first preference update is complete
                                isWaitingForFirstPreferenceUpdate = false
                            } else {
                                Log.e("ChatScreen", "❌ SAFETY MECHANISM - Failed to send preferences via HTTP: ${response.code()}")
                                // Hide loading overlay even on failure to prevent infinite loading
                                isWaitingForFirstPreferenceUpdate = false
                            }
                        } else {
                            Log.e("ChatScreen", "❌ SAFETY MECHANISM - Cannot send preferences - user email is empty")
                        }
                    } catch (e: Exception) {
                        Log.e("ChatScreen", "❌ SAFETY MECHANISM - Exception sending preferences via HTTP", e)
                        // Hide loading overlay even on exception to prevent infinite loading
                        isWaitingForFirstPreferenceUpdate = false
                    }
                } else {
                    Log.e("ChatScreen", "❌ SAFETY MECHANISM - No token available, cannot send preferences")
                }
                
                Log.e("ChatScreen", "=== PREFERENCE UPDATE CHECK COMPLETE ===")
        }

        // Use the singleton repository so the list persists across navigation
        val conversationList = ConversationRepository.conversations
        
        // Auto-scroll to top when conversation list changes (new message arrives)
        LaunchedEffect(conversationList.size, conversationList.firstOrNull()?.lastMessageTimestamp) {
                if (conversationList.isNotEmpty() && searchQuery.isBlank()) {
                        listState.animateScrollToItem(0)
                }
        }

        val scope = rememberCoroutineScope() // <--- ADD THIS LINE
        val userPrefsForCounts = remember { context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE) }
        var roses by remember { mutableStateOf(userPrefsForCounts.getInt("roses", 0)) }
        var sparks by remember { mutableStateOf(userPrefsForCounts.getInt("sparks_self", 0)) }
        var flowerKey by remember { mutableStateOf(0) }
        var thunderKey by remember { mutableStateOf(0) }
        
        // State for user profile picture - will be updated from server
        var userGender by remember { mutableStateOf(userPrefsForCounts.getString("gender", "male") ?: "male") }
        var userRomanceMin by remember { mutableStateOf(userPrefsForCounts.getFloat("romance_min", 1f)) }
        var userRomanceMax by remember { mutableStateOf(userPrefsForCounts.getFloat("romance_max", 5f)) }
        val themePrefs = remember {
                context.getSharedPreferences("anonychat_theme", Context.MODE_PRIVATE)
        }
        var isDarkTheme by remember {
                mutableStateOf(themePrefs.getBoolean("is_dark_theme", false))
        }
        var showThemeDialog by remember { mutableStateOf(false) }
        var showResetDialog by remember { mutableStateOf(false) }
        var showLogoutDialog by remember { mutableStateOf(false) }
        var isLoading by remember { mutableStateOf(false) }

        if (showThemeDialog) {
                AlertDialog(
                        onDismissRequest = { showThemeDialog = false },
                        title = { Text("Settings") },
                        text = {
                                Column {
                                        TextButton(
                                                onClick = {
                                                        isDarkTheme = false
                                                        showThemeDialog = false
                                                        themePrefs
                                                                .edit()
                                                                .putBoolean("is_dark_theme", false)
                                                                .apply()
                                                }
                                        ) { Text("Light Theme") }
                                        TextButton(
                                                onClick = {
                                                        isDarkTheme = true
                                                        showThemeDialog = false
                                                        themePrefs
                                                                .edit()
                                                                .putBoolean("is_dark_theme", true)
                                                                .apply()
                                                }
                                        ) { Text("Dark Theme") }
                                        TextButton(
                                                onClick = {
                                                        showThemeDialog = false
                                                        onNavigateToBlockedUsers()
                                                }
                                        ) { Text("Blocked Users") }
                                        TextButton(
                                                onClick = {
                                                        showThemeDialog = false
                                                        showResetDialog = true
                                                }
                                        ) { Text("🔄 Reset Account", color = Color(0xFFE53935)) }
                                        TextButton(
                                                onClick = {
                                                        showThemeDialog = false
                                                        showLogoutDialog = true
                                                }
                                        ) { Text("🚪 Logout", color = Color(0xFFFF6F00)) }
                                }
                        },
                        confirmButton = {}
                )
        }
        
                // Reset Account Dialog
                if (showResetDialog) {
                        ResetAccountDialog(
                                onDismiss = { showResetDialog = false },
                                onConfirm = {
                                        showResetDialog = false
                                        isLoading = true
                                        scope.launch {
                                                try {
                                                        val userId = userPrefsForCounts.getString("user_id", null)
                                                        if (userId != null) {
                                                                val response = NetworkClient.api.resetAccount(userId)
                                                                if (response.isSuccessful) {
                                                                        Toast.makeText(
                                                                                context,
                                                                                response.body()?.message ?: "Account reset successful",
                                                                                Toast.LENGTH_LONG
                                                                        ).show()
                                                                        
                                                                        // Save credentials before clearing (for auto-login after reset)
                                                                        val savedUsername = userPrefsForCounts.getString("username", "")
                                                                        val savedEmail = userPrefsForCounts.getString("user_email", "")
                                                                        val savedUserId = userPrefsForCounts.getString("user_id", "")
                                                                        val savedPassword = userPrefsForCounts.getString("password", "")
                                                                        
                                                                        Log.e("ChatScreen", "=== ACCOUNT RESET - SAVING CREDENTIALS ===")
                                                                        Log.e("ChatScreen", "Saved username: $savedUsername")
                                                                        Log.e("ChatScreen", "Saved email: $savedEmail")
                                                                        Log.e("ChatScreen", "Saved userId: $savedUserId")
                                                                        Log.e("ChatScreen", "Saved password: ${if (savedPassword.isNullOrEmpty()) "EMPTY" else "Present"}")
                                                                        
                                                                        // Close all WebSocket connections first
                                                                        WebSocketManager.disconnect()
                                                                        
                                                                        // Clear conversation repository BEFORE clearing SharedPreferences
                                                                        ConversationRepository.conversations.clear()
                                                                        
                                                                        // Clear deactivated users manager
                                                                        DeactivatedUsersManager.clearAll()
                                                                        
                                                                        // Clear user repository
                                                                        UserRepository.clearAll()
                                                                        
                                                                        // Clear ALL SharedPreferences files
                                                                        context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit().clear().commit()
                                                                        context.getSharedPreferences("anonychat_theme", Context.MODE_PRIVATE).edit().clear().commit()
                                                                        context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).edit().clear().commit()
                                                                        context.getSharedPreferences("deactivated_users", Context.MODE_PRIVATE).edit().clear().commit()
                                                                        context.getSharedPreferences("unread_messages", Context.MODE_PRIVATE).edit().clear().commit()
                                                                        context.getSharedPreferences("conversations", Context.MODE_PRIVATE).edit().clear().commit()
                                                                        context.getSharedPreferences("user_ids", Context.MODE_PRIVATE).edit().clear().commit()
                                                                        
                                                                        // Restore credentials for auto-login
                                                                        val resetPrefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                                                                        resetPrefs.edit().apply {
                                                                                putString("username", savedUsername)
                                                                                putString("user_email", savedEmail)
                                                                                putString("user_id", savedUserId)
                                                                                putString("password", savedPassword)
                                                                                putBoolean("account_reset", true) // Flag to trigger username update flow after auto-login
                                                                        }.commit()
                                                                        
                                                                        Log.e("ChatScreen", "=== ACCOUNT RESET - CREDENTIALS RESTORED ===")
                                                                        Log.e("ChatScreen", "Restored username: $savedUsername")
                                                                        Log.e("ChatScreen", "Restored email: $savedEmail")
                                                                        Log.e("ChatScreen", "Restored userId: $savedUserId")
                                                                        Log.e("ChatScreen", "Restored password: ${if (savedPassword.isNullOrEmpty()) "EMPTY" else "Present"}")
                                                                        Log.e("ChatScreen", "Account reset flag set: true")
                                                                        Log.e("ChatScreen", "User will need to login manually and will be redirected to UpdateUsernameScreen")
                                                                        
                                                                        // Clear app cache and data directories
                                                                        try {
                                                                                context.cacheDir.deleteRecursively()
                                                                                context.filesDir.deleteRecursively()
                                                                        } catch (e: Exception) {
                                                                                Log.e("ChatScreen", "Error clearing cache/files: ${e.message}")
                                                                        }
                                                                        
                                                                        // Small delay to ensure everything is cleared
                                                                        kotlinx.coroutines.delay(500)
                                                                        
                                                                        // Navigate to login for auto-login
                                                                        onNavigateToLogin()
                                                                } else {
                                                                        Toast.makeText(
                                                                                context,
                                                                                "Failed to reset account: ${response.code()}",
                                                                                Toast.LENGTH_LONG
                                                                        ).show()
                                                                }
                                                        } else {
                                                                Toast.makeText(
                                                                        context,
                                                                        "User ID not found",
                                                                        Toast.LENGTH_SHORT
                                                                ).show()
                                                        }
                                                } catch (e: Exception) {
                                                        Toast.makeText(
                                                                context,
                                                                "Error: ${e.message}",
                                                                Toast.LENGTH_LONG
                                                        ).show()
                                                } finally {
                                                        isLoading = false
                                                }
                                        }
                                },
                                isDarkTheme = isDarkTheme
                        )
                }
                
                // Logout Dialog
                if (showLogoutDialog) {
                        AlertDialog(
                                onDismissRequest = { showLogoutDialog = false },
                                title = { Text("Logout") },
                                text = { Text("Are you sure you want to logout?") },
                                confirmButton = {
                                        TextButton(
                                                onClick = {
                                                        showLogoutDialog = false
                                                        isLoading = true
                                                        scope.launch {
                                                                try {
                                                                        Log.d("ChatScreen", "Logout: Calling logout API")
                                                                        val response = NetworkClient.api.logout()
                                                                        
                                                                        if (response.isSuccessful) {
                                                                                Log.d("ChatScreen", "Logout: API call successful")
                                                                                Toast.makeText(
                                                                                        context,
                                                                                        "Logged out successfully",
                                                                                        Toast.LENGTH_SHORT
                                                                                ).show()
                                                                        } else {
                                                                                Log.w("ChatScreen", "Logout: API call failed with code ${response.code()}")
                                                                        }
                                                                } catch (e: Exception) {
                                                                        Log.e("ChatScreen", "Logout: API call error", e)
                                                                }
                                                                
                                                                // Disconnect WebSocket and prevent reconnection
                                                                Log.d("ChatScreen", "Logout: Disconnecting WebSocket")
                                                                WebSocketManager.disconnect()
                                                                
                                                                // Stop WebSocketMonitorService
                                                                Log.d("ChatScreen", "Logout: Stopping WebSocketMonitorService")
                                                                WebSocketMonitorService.stop(context)
                                                                
                                                                // Clear some in-memory data
                                                                Log.d("ChatScreen", "Logout: Clearing session data")
                                                                DeactivatedUsersManager.clearAll()
                                                                UserRepository.clearAll()
                                                                
                                                                // NOTE: We do NOT clear:
                                                                // - ConversationRepository.conversations (in-memory list)
                                                                // - "conversation_repository" SharedPreferences (persisted conversation list)
                                                                // - Room database (chat history)
                                                                // These should all persist across logout/login
                                                                Log.d("ChatScreen", "Logout: Conversation list and chat history will persist for next login")
                                                                
                                                                // Clear session-related SharedPreferences only
                                                                context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit().clear().commit()
                                                                context.getSharedPreferences("anonychat_theme", Context.MODE_PRIVATE).edit().clear().commit()
                                                                context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).edit().clear().commit()
                                                                context.getSharedPreferences("deactivated_users", Context.MODE_PRIVATE).edit().clear().commit()
                                                                context.getSharedPreferences("unread_messages", Context.MODE_PRIVATE).edit().clear().commit()
                                                                // NOTE: NOT clearing "conversation_repository" - it should persist
                                                                // NOTE: NOT clearing "conversations" either
                                                                context.getSharedPreferences("user_ids", Context.MODE_PRIVATE).edit().clear().commit()
                                                                
                                                                Log.d("ChatScreen", "Logout: Navigating to login screen")
                                                                isLoading = false
                                                                onNavigateToLogin()
                                                        }
                                                }
                                        ) {
                                                Text("Logout", color = Color(0xFFFF6F00))
                                        }
                                },
                                dismissButton = {
                                        TextButton(onClick = { showLogoutDialog = false }) {
                                                Text("Cancel")
                                        }
                                }
                        )
                }

        val exoPlayer =
                remember(context, isDarkTheme) {
                        ExoPlayer.Builder(context).build().apply {
                                val videoUri =
                                        if (isDarkTheme) {
                                                Uri.parse(
                                                        "android.resource://${context.packageName}/${R.raw.night}"
                                                )
                                        } else {
                                                Uri.parse(
                                                        "android.resource://${context.packageName}/${R.raw.cloud}"
                                                )
                                        }
                                setMediaItem(MediaItem.fromUri(videoUri))
                                repeatMode = Player.REPEAT_MODE_ONE
                                volume = 0f
                                playWhenReady = true
                                prepare()
                        }
                }
        // Register cleanup callback for PiP mode
        LaunchedEffect(exoPlayer) {
                ChatScreenPipController.onBeforeEnterPip = {
                        exoPlayer.pause()
                        exoPlayer.stop()
                        exoPlayer.clearVideoSurface()
                }
        }

        // Fetch preferences from server on load via WebSocket — update sparks/roses state + SharedPreferences
        LaunchedEffect(Unit) {
                val userEmail = userPrefsForCounts.getString("user_email", null)
                val token = userPrefsForCounts.getString("access_token", null)
                if (userEmail != null && token != null) {
                        try {
                                WebSocketManager.sendGetPreferences(token)
                                
                                val prefEvent = withTimeoutOrNull(10_000) {
                                        WebSocketManager.events.first { event ->
                                                (event is WebSocketEvent.PreferencesData && event.userEmail == userEmail) ||
                                                (event is WebSocketEvent.PreferencesError && event.email == userEmail)
                                        }
                                }
                                
                                if (prefEvent is WebSocketEvent.PreferencesData) {
                                        val serverPrefs = prefEvent.preferences
                                        
                                        // Update state variables immediately for reactive UI
                                        serverPrefs.sparks?.let { sparks = it }
                                        serverPrefs.totalRosesReceived?.let { roses = it }
                                        serverPrefs.gender?.let { userGender = it }
                                        serverPrefs.romanceRange?.let {
                                                userRomanceMin = it.min.toFloat()
                                                userRomanceMax = it.max.toFloat()
                                        }
                                        
                                        // Update SharedPreferences with all fetched values
                                        with(userPrefsForCounts.edit()) {
                                                serverPrefs.sparks?.let { putInt("sparks_self", it) }
                                                serverPrefs.totalRosesReceived?.let { putInt("roses", it) }
                                                serverPrefs.availableRoses?.let { putInt("available_roses", it) }
                                                // Save gender and romanceRange to fix profile picture inconsistency
                                                serverPrefs.gender?.let { putString("gender", it) }
                                                serverPrefs.romanceRange?.let {
                                                        putFloat("romance_min", it.min.toFloat())
                                                        putFloat("romance_max", it.max.toFloat())
                                                }
                                                // Save other user info
                                                serverPrefs.username?.takeIf { it.isNotBlank() }?.let { putString("username", it) }
                                                serverPrefs.age?.let { putInt("age", it) }
                                                serverPrefs.preferredGender?.let { putString("preferred_gender", it) }
                                                serverPrefs.preferredAgeRange?.let {
                                                        putFloat("preferred_age_min", it.min.toFloat())
                                                        putFloat("preferred_age_max", it.max.toFloat())
                                                }
                                                apply()
                                        }
                                        android.util.Log.d("ChatScreen", "Prefs fetched via WS: sparks=${serverPrefs.sparks}, roses=${serverPrefs.totalRosesReceived}, gender=${serverPrefs.gender}, romanceRange=${serverPrefs.romanceRange}")
                                }
                        } catch (e: Exception) {
                                android.util.Log.e("ChatScreen", "Error fetching prefs via WS: ${e.message}")
                        }
                }
        }
        
        // Fetch ratings for all users in conversation list
        LaunchedEffect(conversationList.size) {
                val token = userPrefsForCounts.getString("access_token", null)
                if (token != null && conversationList.isNotEmpty()) {
                        conversationList.forEach { entry ->
                                launch {
                                        try {
                                                WebSocketManager.sendGetAverageRating(token, entry.userEmail)
                                                
                                                val ratingEvent = withTimeoutOrNull(5_000) {
                                                        WebSocketManager.events.first { event ->
                                                                (event is WebSocketEvent.AverageRatingData && event.userEmail == entry.userEmail) ||
                                                                (event is WebSocketEvent.AverageRatingError && event.userEmail == entry.userEmail)
                                                        }
                                                }
                                                
                                                if (ratingEvent is WebSocketEvent.AverageRatingData && ratingEvent.avgRating != null && ratingEvent.userEmail == entry.userEmail) {
                                                        // Update the conversation entry with the rating
                                                        val myEmail = userPrefsForCounts.getString("user_email", null)
                                                        if (myEmail != null) {
                                                                ConversationRepository.upsert(
                                                                        myEmail = myEmail,
                                                                        peerEmail = entry.userEmail,
                                                                        peerUsername = entry.username,
                                                                        preview = entry.lastMessage,
                                                                        timestamp = entry.lastMessageTimestamp,
                                                                        isIncoming = false,
                                                                        peerGender = entry.peerGender,
                                                                        peerRomanceMin = entry.peerRomanceMin,
                                                                        peerRomanceMax = entry.peerRomanceMax,
                                                                        userRating = ratingEvent.avgRating
                                                                )
                                                        }
                                                }
                                        } catch (e: Exception) {
                                                Log.e("ChatScreen", "Failed to fetch rating for ${entry.userEmail}", e)
                                        }
                                }
                        }
                }
        }
        
        // Listen for live WebSocket events: update sparks + conversation list
        // State to hold matched user info for navigation
        var pendingMatchNavigation by remember { mutableStateOf<Pair<User, Preferences>?>(null) }
        
        // Handle back button press
        BackHandler {
            // If we're in loading state (searching for match) and have a pending match, skip it
            if (isLoading && pendingMatchNavigation != null) {
                val matchedUser = pendingMatchNavigation?.first
                val token = userPrefsForCounts.getString("access_token", null)
                
                if (matchedUser != null && token != null) {
                    // Call skip endpoint to ignore this match
                    WebSocketManager.sendSkipUser(matchedUser.id, token)
                    Log.d("ChatScreen", "Skipping match with ${matchedUser.id} due to back button press")
                }
                
                // Clear pending match and stop loading
                pendingMatchNavigation = null
                isLoading = false
            } else {
                // Normal back button behavior - exit app
                (context as? android.app.Activity)?.finish()
            }
        }
        
        LaunchedEffect(Unit) {
                val myEmail = userPrefsForCounts.getString("user_email", "") ?: ""
                WebSocketManager.events.collect { event ->
                        when (event) {
                                is WebSocketEvent.SparkRight -> {
                                        sparks = userPrefsForCounts.getInt("sparks_self", sparks)
                                }
                                is WebSocketEvent.MatchFound -> {
                                        Log.d("ChatScreen", "Match found via WebSocket: ${event.match.gmail}")
                                        val matchObj = event.match
                                        val matchedEmail = matchObj.gmail ?: matchObj.match
                                        
                                        if (!matchedEmail.isNullOrBlank()) {
                                                // Build matched user and prefs from WebSocket event
                                                val matchedUsername = matchObj.username ?: ""
                                                val matchedRomanceMin = matchObj.romanceRange?.min?.toFloat() ?: 1f
                                                val matchedRomanceMax = matchObj.romanceRange?.max?.toFloat() ?: 5f
                                                val matchedGender = matchObj.gender ?: "male"
                                                
                                                val matchedPrefs = Preferences(
                                                        romanceMin = matchedRomanceMin,
                                                        romanceMax = matchedRomanceMax,
                                                        gender = matchedGender,
                                                        isOnline = matchObj.isOnline ?: false,
                                                        lastSeen = matchObj.lastOnline ?: 0L,
                                                        giftedMeARose = matchObj.giftedMeARose ?: false,
                                                        hasTakenBackRose = matchObj.hasTakenBackRose ?: false
                                                )
                                                
                                                val matchedUser = User(
                                                        username = matchedUsername,
                                                        profilePictureUrl = null,
                                                        id = matchedEmail
                                                )
                                                
                                                // Store for navigation trigger
                                                pendingMatchNavigation = Pair(matchedUser, matchedPrefs)
                                                
                                                // Fetch rating for matched user asynchronously
                                                scope.launch {
                                                        try {
                                                                val token = userPrefsForCounts.getString("access_token", null)
                                                                if (token != null) {
                                                                        WebSocketManager.sendGetAverageRating(token, matchedEmail)
                                                                        
                                                                        val ratingEvent = withTimeoutOrNull(5_000) {
                                                                                WebSocketManager.events.first { event ->
                                                                                        (event is WebSocketEvent.AverageRatingData && event.userEmail == matchedEmail) ||
                                                                                        (event is WebSocketEvent.AverageRatingError && event.userEmail == matchedEmail)
                                                                                }
                                                                        }
                                                                        
                                                                        if (ratingEvent is WebSocketEvent.AverageRatingData && ratingEvent.avgRating != null && ratingEvent.userEmail == matchedEmail) {
                                                                                // Update conversation entry with rating
                                                                                ConversationRepository.upsert(
                                                                                        myEmail = myEmail,
                                                                                        peerEmail = matchedEmail,
                                                                                        peerUsername = matchedUsername,
                                                                                        preview = "New match!",
                                                                                        timestamp = System.currentTimeMillis(),
                                                                                        isIncoming = false,
                                                                                        peerGender = matchedGender,
                                                                                        peerRomanceMin = matchedRomanceMin,
                                                                                        peerRomanceMax = matchedRomanceMax,
                                                                                        userRating = ratingEvent.avgRating
                                                                                )
                                                                                Log.d("ChatScreen", "Fetched rating for matched user: ${ratingEvent.avgRating}")
                                                                        }
                                                                }
                                                        } catch (e: Exception) {
                                                                Log.e("ChatScreen", "Failed to fetch rating for matched user", e)
                                                        }
                                                }
                                        } else {
                                                // No match found (empty match object), stop the heart overlay
                                                Log.w("ChatScreen", "No match found (empty match object). Stopping heart overlay.")
                                                isLoading = false
                                        }
                                }
                                is WebSocketEvent.MatchError -> {
                                        Log.e("ChatScreen", "Match error via WebSocket: ${event.error}")
                                        isLoading = false
                                }
                                // NewMessage handling removed - now handled by ChatSocketService globally
                                // This prevents double-counting of unread messages
                                else -> Unit
                        }
                }
        }
        
        // Handle navigation when match is found
        LaunchedEffect(pendingMatchNavigation) {
                pendingMatchNavigation?.let { (matchedUser, matchedPrefs) ->
                        // Get current user info from prefs
                        val myEmail = userPrefsForCounts.getString("user_email", null)
                        val myUsername = userPrefsForCounts.getString("username", null)
                                ?: myEmail?.substringBefore('@') ?: ""
                        val myGender = userPrefsForCounts.getString("gender", "male") ?: "male"
                        val myRomanceMin = userPrefsForCounts.getFloat("romance_min", 1f)
                        val myRomanceMax = userPrefsForCounts.getFloat("romance_max", 5f)
                        
                        if (myEmail != null) {
                                val currentUserForNav = User(
                                        id = myEmail,
                                        username = myUsername,
                                        profilePictureUrl = null
                                )
                                val myPrefsForNav = Preferences(
                                        romanceMin = myRomanceMin,
                                        romanceMax = myRomanceMax,
                                        gender = myGender
                                )
                                
                                // Navigate to direct chat
                                onNavigateToDirectChat(
                                        currentUserForNav,
                                        myPrefsForNav,
                                        matchedUser,
                                        matchedPrefs,
                                        true // From match
                                )
                                
                                // Clear the pending navigation
                                pendingMatchNavigation = null
                                isLoading = false
                        }
                }
        }
        
        DisposableEffect(exoPlayer) {
                onDispose {
                        ChatScreenPipController.onBeforeEnterPip = null
                        exoPlayer.release()
                }
        }

        Box(modifier = Modifier.fillMaxSize()) {
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

                Column(modifier = Modifier.fillMaxSize()) {
                        // Profile Header
                        Row(
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .padding(
                                                        start = 24.dp,
                                                        top = 50.dp,
                                                        end = 24.dp,
                                                        bottom = 24.dp
                                                ),
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                                Box(
                                        contentAlignment = Alignment.Center,
                                        modifier =
                                                Modifier.size(90.dp)
                                                        .border(4.dp, Color.White, CircleShape)
                                                        .background(
                                                                if (isDarkTheme) Color(0xFF142235)
                                                                else Color(0xFF87CEEB),
                                                                CircleShape
                                                        )
                                                        .clip(CircleShape)
                                                        .clickable {
                                                                onNavigateToProfile(user.username)
                                                        }
                                ) {
                                        // Use state variables that are updated from server
                                        val emotion = romanceRangeToEmotion(userRomanceMin, userRomanceMax)
                                        val resName =
                                                if (userGender == "female") "female_exp$emotion"
                                                else "male_exp$emotion"
                                        val imageResId =
                                                context.resources.getIdentifier(
                                                        resName,
                                                        "raw",
                                                        context.packageName
                                                )
                                        val imageUri =
                                                if (imageResId != 0)
                                                        Uri.parse(
                                                                "android.resource://${context.packageName}/$imageResId"
                                                        )
                                                else
                                                        Uri.parse(
                                                                "android.resource://${context.packageName}/${R.raw.male_exp1}"
                                                        )

                                        Crossfade(
                                                targetState = imageUri,
                                                animationSpec = tween(500)
                                        ) { uri ->
                                                Image(
                                                        painter =
                                                                rememberAsyncImagePainter(
                                                                        model =
                                                                                ImageRequest
                                                                                        .Builder(
                                                                                                context
                                                                                        )
                                                                                        .data(uri)
                                                                                        .build()
                                                                ),
                                                        contentDescription = "Profile Picture",
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                )
                                        }
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {

                                                // 1. ROSE COUNT (3D & Bright)
                                                Text(
                                                        text = formatCount(roses),
                                                        style =
                                                                TextStyle(
                                                                        color =
                                                                                Color(
                                                                                        0xFFFF1053
                                                                                ), // Vibrant
                                                                        // Red-Pink
                                                                        fontSize = 18.sp,
                                                                        fontWeight =
                                                                                FontWeight
                                                                                        .ExtraBold,
                                                                        shadow =
                                                                                Shadow(
                                                                                        color =
                                                                                                Color.Black
                                                                                                        .copy(
                                                                                                                alpha =
                                                                                                                        0.5f
                                                                                                        ),
                                                                                        offset =
                                                                                                Offset(
                                                                                                        3f,
                                                                                                        3f
                                                                                                ), // Moves shadow
                                                                                        // down-right for 3D look
                                                                                        blurRadius =
                                                                                                3f
                                                                                )
                                                                )
                                                )

                                                Spacer(modifier = Modifier.width(4.dp))

                                                val imageLoader =
                                                        remember(context) {
                                                                ImageLoader.Builder(context)
                                                                        .components {
                                                                                if (Build.VERSION
                                                                                                .SDK_INT >=
                                                                                                28
                                                                                ) {
                                                                                        add(
                                                                                                ImageDecoderDecoder
                                                                                                        .Factory()
                                                                                        )
                                                                                } else {
                                                                                        add(
                                                                                                GifDecoder
                                                                                                        .Factory()
                                                                                        )
                                                                                }
                                                                        }
                                                                        .build()
                                                        }
                                                Image(
                                                        painter =
                                                                rememberAsyncImagePainter(
                                                                        model =
                                                                                ImageRequest
                                                                                        .Builder(
                                                                                                context
                                                                                        )
                                                                                        .data(
                                                                                                R.drawable
                                                                                                        .roseg
                                                                                        )
                                                                                        .memoryCacheKey(
                                                                                                flowerKey
                                                                                                        .toString()
                                                                                        )
                                                                                        .build(),
                                                                        imageLoader = imageLoader
                                                                ),
                                                        contentDescription = "Roses",
                                                        modifier =
                                                                Modifier.size(24.dp).clickable {
                                                                        flowerKey++
                                                                }
                                                )

                                                Spacer(modifier = Modifier.width(16.dp))

                                                // 2. SPARK COUNT (3D & Bright)
                                                Text(
                                                        text = formatCount(sparks),
                                                        style =
                                                                TextStyle(
                                                                        color =
                                                                                Color(
                                                                                        0xFFFFD700
                                                                                ), // Bright
                                                                        // Gold/Yellow
                                                                        fontSize = 18.sp,
                                                                        fontWeight =
                                                                                FontWeight
                                                                                        .ExtraBold,
                                                                        shadow =
                                                                                Shadow(
                                                                                        color =
                                                                                                Color.Black
                                                                                                        .copy(
                                                                                                                alpha =
                                                                                                                        0.5f
                                                                                                        ),
                                                                                        offset =
                                                                                                Offset(
                                                                                                        3f,
                                                                                                        3f
                                                                                                ), // Moves shadow
                                                                                        // down-right for 3D look
                                                                                        blurRadius =
                                                                                                3f
                                                                                )
                                                                )
                                                )

                                                Spacer(modifier = Modifier.width(4.dp))

                                                Image(
                                                        painter =
                                                                rememberAsyncImagePainter(
                                                                        model =
                                                                                ImageRequest
                                                                                        .Builder(
                                                                                                context
                                                                                        )
                                                                                        .data(
                                                                                                R.drawable
                                                                                                        .sparkling
                                                                                        )
                                                                                        .memoryCacheKey(
                                                                                                thunderKey
                                                                                                        .toString()
                                                                                        )
                                                                                        .build(),
                                                                        imageLoader = imageLoader
                                                                ),
                                                        contentDescription = "Sparks",
                                                        modifier =
                                                                Modifier.size(20.dp).clickable {
                                                                        thunderKey++
                                                                }
                                                )
                                        }
                                }
                                Spacer(modifier = Modifier.weight(1f))

                                IconButton(onClick = { showThemeDialog = true }) {
                                        Icon(
                                                imageVector = Icons.Default.Settings,
                                                contentDescription = "Settings",
                                                tint = if (isDarkTheme) Color.White else Color.Black
                                        )
                                }
                        }

                        // --- Floating Glassy Card for Search and List ---
                        // Force recomposition by observing the list content, not just size
                        val filteredConversations = remember(conversationList.toList(), searchQuery, ratingFilter, showFavoritesOnly) {
                            var filtered = if (searchQuery.isBlank()) {
                                conversationList.toList()
                            } else {
                                conversationList.filter {
                                    it.username.contains(searchQuery, ignoreCase = true) ||
                                    it.userEmail.contains(searchQuery, ignoreCase = true)
                                }
                            }
                            
                            // Apply favorites filter
                            if (showFavoritesOnly) {
                                filtered = filtered.filter { it.isFavorite }
                            }
                            
                            // Apply rating filter
                            when (ratingFilter) {
                                "Highest" -> filtered.sortedByDescending { it.userRating ?: 0f }
                                "Lowest" -> filtered.sortedBy { it.userRating ?: Float.MAX_VALUE }
                                else -> filtered
                            }
                        }

                        val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        Surface(
                                modifier =
                                        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = navigationBarPadding + 16.dp)
                                                .fillMaxWidth()
                                                .weight(1f)
                                                .pointerInput(isSelectionMode) {
                                                    if (isSelectionMode) {
                                                        detectTapGestures(
                                                            onTap = {
                                                                isSelectionMode = false
                                                                selectedChats.clear()
                                                            }
                                                        )
                                                    }
                                                },
                                shape = RoundedCornerShape(32.dp),
                                color = if (isDarkTheme) Color(0x0DFFFFFF) else Color(0x4DFFFFFF), // Almost invisible in dark, glassy in light
                                border = if (isDarkTheme) BorderStroke(1.dp, Color(0x08FFFFFF)) else null
                        ) {
                                Column {
                                        // --- SEARCH BAR ---
                                        Surface(
                                                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                                                shape = RoundedCornerShape(
                                                        topStart = 32.dp,
                                                        topEnd = 32.dp,
                                                        bottomStart = 8.dp,
                                                        bottomEnd = 8.dp
                                                ),
                                                color = if (isDarkTheme) Color(0xFF121821) else Color.White,
                                                shadowElevation = 0.dp
                                        ) {
                                                TextField(
                                                        value = searchQuery,
                                                        onValueChange = { searchQuery = it },
                                                        modifier = Modifier.fillMaxWidth()
                                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                                                .wrapContentHeight(),
                                                        shape = RoundedCornerShape(50),
                                                        placeholder = {
                                                                Text(
                                                                        text = "Search",
                                                                        color = if (isDarkTheme) Color(0xFF8FA4C0) else Color(0xFF5A6B88),
                                                                        fontSize = 16.sp
                                                                )
                                                        },
                                                        leadingIcon = {
                                                                Icon(
                                                                        imageVector = Icons.Default.Search,
                                                                        contentDescription = "Search Icon",
                                                                        tint = if (isDarkTheme) Color(0xFF7A8FA9) else Color.Gray,
                                                                        modifier = Modifier.size(24.dp)
                                                                )
                                                        },
                                                        trailingIcon = {
                                                                IconButton(
                                                                        onClick = { showFavoritesOnly = !showFavoritesOnly }
                                                                ) {
                                                                        Icon(
                                                                                imageVector = if (showFavoritesOnly) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                                                contentDescription = if (showFavoritesOnly) "Show all" else "Show favorites only",
                                                                                tint = if (showFavoritesOnly) Color(0xFF9C27B0) else (if (isDarkTheme) Color(0xFF7A8FA9) else Color.Gray),
                                                                                modifier = Modifier.size(24.dp)
                                                                        )
                                                                }
                                                        },
                                                        colors = if (isDarkTheme) {
                                                                TextFieldDefaults.colors(
                                                                        focusedContainerColor = Color(0xFF3A4552),
                                                                        unfocusedContainerColor = Color(0xFF3A4552),
                                                                        disabledContainerColor = Color(0xFF3A4552),
                                                                        cursorColor = Color(0xFFEFF3FA),
                                                                        focusedTextColor = Color.White,
                                                                        unfocusedTextColor = Color.White,
                                                                        focusedIndicatorColor = Color.Transparent,
                                                                        unfocusedIndicatorColor = Color.Transparent,
                                                                        disabledIndicatorColor = Color.Transparent
                                                                )
                                                        } else {
                                                                TextFieldDefaults.colors(
                                                                        focusedContainerColor = Color(0xFFEFF3FA),
                                                                        unfocusedContainerColor = Color(0xFFEFF3FA),
                                                                        disabledContainerColor = Color(0xFFEFF3FA),
                                                                        cursorColor = Color(0xFF5A6B88),
                                                                        focusedIndicatorColor = Color.Transparent,
                                                                        unfocusedIndicatorColor = Color.Transparent,
                                                                        disabledIndicatorColor = Color.Transparent,
                                                                        focusedTextColor = Color(0xFF2D3648),
                                                                        unfocusedTextColor = Color(0xFF2D3648)
                                                                )
                                                        },
                                                        singleLine = true
                                                )
                                        }
                                        
                                        // --- RATING FILTER BUTTONS OR SELECTION MODE CONTROLS ---
                                        if (isSelectionMode) {
                                            // Selection mode controls
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Select All checkbox
                                                Surface(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(40.dp)
                                                        .clickable {
                                                            if (selectedChats.size == filteredConversations.size) {
                                                                selectedChats.clear()
                                                            } else {
                                                                selectedChats.clear()
                                                                selectedChats.addAll(filteredConversations.map { it.userEmail })
                                                            }
                                                        },
                                                    shape = RoundedCornerShape(20.dp),
                                                    color = if (isDarkTheme) Color(0xFF2D3648) else Color(0xFFE8EAF6)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 12.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = if (selectedChats.size == filteredConversations.size && filteredConversations.isNotEmpty())
                                                                Icons.Default.CheckBox
                                                            else
                                                                Icons.Default.CheckBoxOutlineBlank,
                                                            contentDescription = "Select All",
                                                            tint = if (isDarkTheme) Color.White else Color(0xFF5A6B88),
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(
                                                            text = "Select All",
                                                            color = if (isDarkTheme) Color.White else Color(0xFF5A6B88),
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                    }
                                                }
                                                
                                                // Delete button
                                                Surface(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(40.dp)
                                                        .clickable(enabled = selectedChats.isNotEmpty()) {
                                                            scope.launch {
                                                                try {
                                                                    val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                                                                    val myEmail = prefs.getString("user_email", "") ?: ""
                                                                    
                                                                    selectedChats.forEach { peerEmail ->
                                                                        try {
                                                                            WebSocketManager.clearChatHistory(myEmail, peerEmail)
                                                                            NetworkClient.api.deleteMatch(myEmail, peerEmail)
                                                                            ConversationRepository.remove(peerEmail)
                                                                        } catch (e: Exception) {
                                                                            Log.e("ChatScreen", "Failed to delete chat with $peerEmail", e)
                                                                        }
                                                                    }
                                                                    
                                                                    selectedChats.clear()
                                                                    isSelectionMode = false
                                                                    android.widget.Toast.makeText(context, "Chats deleted", android.widget.Toast.LENGTH_SHORT).show()
                                                                } catch (e: Exception) {
                                                                    Log.e("ChatScreen", "Error deleting chats", e)
                                                                    android.widget.Toast.makeText(context, "Failed to delete chats", android.widget.Toast.LENGTH_SHORT).show()
                                                                }
                                                            }
                                                        },
                                                    shape = RoundedCornerShape(20.dp),
                                                    color = if (selectedChats.isEmpty()) {
                                                        if (isDarkTheme) Color(0xFF1A1F2E) else Color(0xFFE0E0E0)
                                                    } else {
                                                        Color(0xFFE53935)
                                                    }
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 12.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Delete,
                                                            contentDescription = "Delete",
                                                            tint = if (selectedChats.isEmpty()) {
                                                                if (isDarkTheme) Color(0xFF5A6B88) else Color(0xFFBDBDBD)
                                                            } else {
                                                                Color.White
                                                            },
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(
                                                            text = if (selectedChats.isEmpty()) "Delete" else "Delete (${selectedChats.size})",
                                                            color = if (selectedChats.isEmpty()) {
                                                                if (isDarkTheme) Color(0xFF5A6B88) else Color(0xFFBDBDBD)
                                                            } else {
                                                                Color.White
                                                            },
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            // Rating filter buttons
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                // None button
                                                Surface(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(36.dp)
                                                    .clickable { ratingFilter = "None" },
                                                shape = RoundedCornerShape(18.dp),
                                                color = if (ratingFilter == "None") {
                                                    if (isDarkTheme) Color(0xFF5A6B88) else Color(0xFF7986CB)
                                                } else {
                                                    if (isDarkTheme) Color(0xFF2D3648) else Color(0xFFE8EAF6)
                                                }
                                            ) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)
                                                ) {
                                                    Text(
                                                        text = "Most Relevant",
                                                        color = if (isDarkTheme) Color.White else {
                                                            if (ratingFilter == "None") Color.White else Color(0xFF5A6B88)
                                                        },
                                                        fontSize = 11.sp,
                                                        fontWeight = if (ratingFilter == "None") FontWeight.Bold else FontWeight.Normal
                                                    )
                                                }
                                            }
                                            
                                            // Highest button
                                            Surface(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(36.dp)
                                                    .clickable { ratingFilter = "Highest" },
                                                shape = RoundedCornerShape(18.dp),
                                                color = if (ratingFilter == "Highest") {
                                                    if (isDarkTheme) Color(0xFF5A6B88) else Color(0xFF7986CB)
                                                } else {
                                                    if (isDarkTheme) Color(0xFF2D3648) else Color(0xFFE8EAF6)
                                                }
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.Center
                                                    ) {
                                                        Text(
                                                            text = "Highest",
                                                            color = if (isDarkTheme) Color.White else {
                                                                if (ratingFilter == "Highest") Color.White else Color(0xFF5A6B88)
                                                            },
                                                            fontSize = 13.sp,
                                                            fontWeight = if (ratingFilter == "Highest") FontWeight.Bold else FontWeight.Normal
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Icon(
                                                            imageVector = Icons.Default.Star,
                                                            contentDescription = "Highest rating",
                                                            tint = Color.Red,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            
                                            // Lowest button
                                            Surface(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(36.dp)
                                                    .clickable { ratingFilter = "Lowest" },
                                                shape = RoundedCornerShape(18.dp),
                                                color = if (ratingFilter == "Lowest") {
                                                    if (isDarkTheme) Color(0xFF5A6B88) else Color(0xFF7986CB)
                                                } else {
                                                    if (isDarkTheme) Color(0xFF2D3648) else Color(0xFFE8EAF6)
                                                }
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.Center
                                                    ) {
                                                        Text(
                                                            text = "Lowest",
                                                            color = if (isDarkTheme) Color.White else {
                                                                if (ratingFilter == "Lowest") Color.White else Color(0xFF5A6B88)
                                                            },
                                                            fontSize = 13.sp,
                                                            fontWeight = if (ratingFilter == "Lowest") FontWeight.Bold else FontWeight.Normal
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Icon(
                                                            imageVector = Icons.Default.Star,
                                                            contentDescription = "Lowest rating",
                                                            tint = Color.Red,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        }

                                        // --- CONVERSATION LIST ---
                                        LazyColumn(
                                                state = listState,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                                items(filteredConversations, key = { it.userEmail }) { entry ->
                                                        ConversationListItem(
                                                                entry = entry,
                                                                isDarkTheme = isDarkTheme,
                                                                isSelectionMode = isSelectionMode,
                                                                isSelected = selectedChats.contains(entry.userEmail),
                                                                onLongPress = {
                                                                    isSelectionMode = true
                                                                    if (!selectedChats.contains(entry.userEmail)) {
                                                                        selectedChats.add(entry.userEmail)
                                                                    }
                                                                },
                                                                onSelectionToggle = {
                                                                    if (selectedChats.contains(entry.userEmail)) {
                                                                        selectedChats.remove(entry.userEmail)
                                                                        if (selectedChats.isEmpty()) {
                                                                            isSelectionMode = false
                                                                        }
                                                                    } else {
                                                                        selectedChats.add(entry.userEmail)
                                                                    }
                                                                },
                                                                onClick = {
                                                                        val prefs = context.getSharedPreferences(
                                                                                "user_prefs",
                                                                                Context.MODE_PRIVATE
                                                                        )
                                                                        val myEmail = prefs.getString("user_email", null)
                                                                        if (myEmail != null) {

                                                                        // Build current user from SharedPreferences
                                                                        val myUsername = prefs.getString("username", null)
                                                                                ?: myEmail.substringBefore('@')
                                                                        val myGender = prefs.getString("gender", "male") ?: "male"
                                                                        val myRomanceMin = prefs.getFloat("romance_min", 1f)
                                                                        val myRomanceMax = prefs.getFloat("romance_max", 5f)

                                                                        val currentUserForNav = User(
                                                                                id = myEmail,
                                                                                username = myUsername,
                                                                                profilePictureUrl = null
                                                                        )
                                                                        val myPrefsForNav = Preferences(
                                                                                romanceMin = myRomanceMin,
                                                                                romanceMax = myRomanceMax,
                                                                                gender = myGender
                                                                        )

                                                                        // Use cached peer data from entry to open chat immediately
                                                                        val matchedPrefs = Preferences(
                                                                                romanceMin = entry.peerRomanceMin,
                                                                                romanceMax = entry.peerRomanceMax,
                                                                                gender = entry.peerGender,
                                                                                isOnline = false,
                                                                                lastSeen = 0L
                                                                        )
                                                                        val matchedUser = User(
                                                                                username = entry.username,
                                                                                profilePictureUrl = null,
                                                                                id = entry.userEmail
                                                                        )

                                                                        // Open chat immediately with cached data
                                                                        onNavigateToDirectChat(
                                                                                currentUserForNav,
                                                                                myPrefsForNav,
                                                                                matchedUser,
                                                                                matchedPrefs,
                                                                                false // From conversation list
                                                                        )

                                                                        // Asynchronously fetch fresh peer preferences in background via WebSocket
                                                                        scope.launch {
                                                                                try {
                                                                                        val token = prefs.getString("access_token", null)
                                                                                        if (token != null) {
                                                                                                android.util.Log.d("ChatScreen", "Fetching peer preferences via WebSocket for ${entry.userEmail}")
                                                                                                WebSocketManager.sendGetPreferences(token, entry.userEmail)
                                                                                                
                                                                                                val prefEvent = withTimeoutOrNull(10_000) {
                                                                                                        WebSocketManager.events.first { event ->
                                                                                                                (event is WebSocketEvent.PreferencesData && event.userEmail == entry.userEmail) ||
                                                                                                                (event is WebSocketEvent.PreferencesError && event.email == entry.userEmail)
                                                                                                        }
                                                                                                }
                                                                                                
                                                                                                val peerPrefsBody = (prefEvent as? WebSocketEvent.PreferencesData)?.preferences

                                                                                                if (peerPrefsBody != null) {
                                                                                                        // Update conversation repository with fresh data
                                                                                                        ConversationRepository.upsert(
                                                                                                                myEmail = myEmail,
                                                                                                                peerEmail = entry.userEmail,
                                                                                                                peerUsername = peerPrefsBody.username ?: entry.username,
                                                                                                                preview = entry.lastMessage,
                                                                                                                timestamp = entry.lastMessageTimestamp,
                                                                                                                isIncoming = false,
                                                                                                                peerGender = peerPrefsBody.gender,
                                                                                                                peerRomanceMin = peerPrefsBody.romanceRange?.min?.toFloat(),
                                                                                                                peerRomanceMax = peerPrefsBody.romanceRange?.max?.toFloat()
                                                                                                        )
                                                                                                        
                                                                                                        // Update sparks and roses in SharedPreferences
                                                                                                        peerPrefsBody.sparks?.let { sparksValue ->
                                                                                                                prefs.edit().putInt("sparks_self", sparksValue).apply()
                                                                                                                sparks = sparksValue
                                                                                                        }
                                                                                                        peerPrefsBody.totalRosesReceived?.let { rosesValue ->
                                                                                                                prefs.edit().putInt("roses", rosesValue).apply()
                                                                                                                roses = rosesValue
                                                                                                        }
                                                                                                        
                                                                                                        Log.d("ChatScreen", "Background refresh: Updated prefs for ${entry.userEmail}, sparks=$sparks, roses=$roses")
                                                                                                }
                                                                                                
                                                                                                // Also fetch rating for this user
                                                                                                WebSocketManager.sendGetAverageRating(token, entry.userEmail)
                                                                                                
                                                                                                val ratingEvent = withTimeoutOrNull(5_000) {
                                                                                                        WebSocketManager.events.first { event ->
                                                                                                                (event is WebSocketEvent.AverageRatingData && event.userEmail == entry.userEmail) ||
                                                                                                                (event is WebSocketEvent.AverageRatingError && event.userEmail == entry.userEmail)
                                                                                                        }
                                                                                                }
                                                                                                
                                                                                                if (ratingEvent is WebSocketEvent.AverageRatingData && ratingEvent.avgRating != null && ratingEvent.userEmail == entry.userEmail) {
                                                                                                        // Update conversation entry with rating
                                                                                                        ConversationRepository.upsert(
                                                                                                                myEmail = myEmail,
                                                                                                                peerEmail = entry.userEmail,
                                                                                                                peerUsername = peerPrefsBody?.username ?: entry.username,
                                                                                                                preview = entry.lastMessage,
                                                                                                                timestamp = entry.lastMessageTimestamp,
                                                                                                                isIncoming = false,
                                                                                                                peerGender = peerPrefsBody?.gender ?: entry.peerGender,
                                                                                                                peerRomanceMin = peerPrefsBody?.romanceRange?.min?.toFloat() ?: entry.peerRomanceMin,
                                                                                                                peerRomanceMax = peerPrefsBody?.romanceRange?.max?.toFloat() ?: entry.peerRomanceMax,
                                                                                                                userRating = ratingEvent.avgRating
                                                                                                        )
                                                                                                        Log.d("ChatScreen", "Fetched rating for ${entry.userEmail}: ${ratingEvent.avgRating}")
                                                                                                }
                                                                                        }
                                                                                } catch (e: Exception) {
                                                                                        Log.e("ChatScreen", "Background refresh failed for ${entry.userEmail}", e)
                                                                                }
                                                                        }
                                                                        } else {
                                                                                Log.e("ChatScreen", "User email missing for conv open")
                                                                        }
                                                                }
                                                        )
                                                }
                                        }
                                }
                        }
                }
        }
        // ... (Your existing AndroidView and Column code) ...
        
        // Handle back button for selection mode
        BackHandler(enabled = isSelectionMode) {
                isSelectionMode = false
                selectedChats.clear()
        }
        
        // Handle back button during match search (can be cancelled)
        BackHandler(enabled = isLoading && !isFirstTimeSetup) {
                isLoading = false // stop the heart overlay
        }
        
        // During first time setup, back button does nothing (overlay continues)
        BackHandler(enabled = isFirstTimeSetup) {
                // Do nothing - prevent back navigation during first time setup
                Log.d("ChatScreen", "Back button pressed during first time setup - ignoring")
        }
        // --- BIRD GIF (Bottom Right Corner) ---
        val birdImageLoader =
                remember(context) {
                        ImageLoader.Builder(context)
                                .components {
                                        if (Build.VERSION.SDK_INT >= 28) {
                                                add(ImageDecoderDecoder.Factory())
                                        } else {
                                                add(GifDecoder.Factory())
                                        }
                                }
                                .build()
                }

        Box(
                modifier = Modifier.fillMaxSize().padding(end = 2.dp, bottom = 50.dp),
                contentAlignment = Alignment.BottomEnd
        ) {
                val interactionSource = remember {
                        androidx.compose.foundation.interaction.MutableInteractionSource()
                }

                // 1. Detect if the user is pressing down
                val isPressed by interactionSource.collectIsPressedAsState()
                // 2. Animate scale: Shrink to 0.9f when pressed, back to 1f when released
                val scale by
                        animateFloatAsState(
                                targetValue = if (isPressed) 0.7f else 1f, // Changed 0.9f to 0.7f
                                animationSpec =
                                        androidx.compose.animation.core.spring(
                                                dampingRatio =
                                                        androidx.compose.animation.core.Spring
                                                                .DampingRatioHighBouncy, // Makes it
                                                // wobble/bounce on
                                                // release
                                                stiffness =
                                                        androidx.compose.animation.core.Spring
                                                                .StiffnessMedium
                                        ),
                                label = "bird_bounce"
                        )

                Image(
                        painter =
                                rememberAsyncImagePainter(
                                        model =
                                                ImageRequest.Builder(context)
                                                        // Ensure this path matches your file (raw
                                                        // vs
                                                        // drawable)
                                                        .data(
                                                                Uri.parse(
                                                                        "android.resource://${context.packageName}/${if (isDarkTheme) R.drawable.owlt else R.drawable.bird}"
                                                                )
                                                        )
                                                        .build(),
                                        imageLoader = birdImageLoader
                                ),
                        contentDescription = "Search Bird",
                        modifier =
                                Modifier.size(if (isDarkTheme) 150.dp else 120.dp)
                                        // 3. Apply the bounce animation
                                        .scale(scale)
                                        .clickable(
                                                interactionSource = interactionSource,
                                                indication =
                                                        null // Keep this null to avoid the square
                                                // shadow
                                                // box
                                                ) {
                                                scope.launch {
                                                        isLoading = true
                                                        try {
                                                                val prefs =
                                                                        context.getSharedPreferences(
                                                                                "user_prefs",
                                                                                Context.MODE_PRIVATE
                                                                        )
                                                                val myEmail =
                                                                        prefs.getString(
                                                                                "user_email",
                                                                                null
                                                                        )

                                                                if (myEmail == null) {
                                                                        Log.e(
                                                                                "ChatScreen",
                                                                                "User email missing"
                                                                        )
                                                                        return@launch
                                                                }

                                                                // Get auth token for WebSocket request
                                                                val token = prefs.getString("access_token", null)
                                                                
                                                                // Check if conversation list is empty, if so delete all matches first via WebSocket
                                                                if (conversationList.isEmpty() && token != null) {
                                                                        try {
                                                                                Log.d("ChatScreen", "Deleting all matches via WebSocket before finding new match")
                                                                                WebSocketManager.sendDeleteAllMatches(token)
                                                                                
                                                                                val deleteEvent = withTimeoutOrNull(10_000) {
                                                                                        WebSocketManager.events.first { event ->
                                                                                                event is WebSocketEvent.DeleteAllMatchesSuccess ||
                                                                                                event is WebSocketEvent.DeleteAllMatchesError
                                                                                        }
                                                                                }
                                                                                
                                                                                when (deleteEvent) {
                                                                                        is WebSocketEvent.DeleteAllMatchesSuccess -> {
                                                                                                Log.d("ChatScreen", "All matches deleted before calling match API")
                                                                                        }
                                                                                        is WebSocketEvent.DeleteAllMatchesError -> {
                                                                                                Log.e("ChatScreen", "Failed to delete all matches: ${deleteEvent.error}")
                                                                                        }
                                                                                        null -> {
                                                                                                Log.e("ChatScreen", "Delete all matches request timed out")
                                                                                        }
                                                                                        else -> {
                                                                                                Log.w("ChatScreen", "Unexpected event type received: ${deleteEvent?.javaClass?.simpleName}")
                                                                                        }
                                                                                }
                                                                        } catch (e: Exception) {
                                                                                Log.e("ChatScreen", "Error deleting all matches: ${e.message}")
                                                                        }
                                                                }
                                                                
                                                                if (token == null) {
                                                                        Log.e("ChatScreen", "No auth token found")
                                                                        isLoading = false
                                                                        return@launch
                                                                }

                                                                // Send find match request via WebSocket
                                                                Log.d("ChatScreen", "Sending find_match via WebSocket")
                                                                WebSocketManager.sendFindMatch(token)
                                                                
                                                                // isLoading will be set to false when navigation to KeyboardProofScreen happens
                                                                // or when MatchError event is received
                                                        } catch (e: Exception) {
                                                                Log.e(
                                                                        "ChatScreen",
                                                                        "Match flow failed",
                                                                        e
                                                                )
                                                                isLoading = false
                                                        }
                                                }
                                        }
                )
        }
        // Show loading overlay during match search OR first preference update OR first time WebSocket setup
        LoadingHeartOverlay(isLoading = isLoading || isWaitingForFirstPreferenceUpdate || isFirstTimeSetup)
}

@Composable
fun ConversationListItem(
    entry: ChatConversationEntry,
    isDarkTheme: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onSelectionToggle: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val subTextColor = if (isDarkTheme) Color(0xFFB0BEC5) else Color.Gray
    val cardColor = if (isSelected) {
        if (isDarkTheme) Color(0xFF2D4A6E) else Color(0xFFBBDEFB)
    } else {
        if (isDarkTheme) Color(0xFF121821) else Color(0xF2FFFFFF)
    }
    val avatarBgColor = if (isDarkTheme) Color(0xFF3A4552) else Color(0xFF87CEEB)
    val borderColor = if (isSelected) {
        Color(0xFF7986CB)
    } else {
        if (isDarkTheme) Color(0xFF4A5562) else Color.White.copy(alpha = 0.6f)
    }
    
    // Track if a long press just occurred to prevent tap from firing immediately after
    var longPressConsumed by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .pointerInput(isSelectionMode) {
                detectTapGestures(
                    onLongPress = {
                        longPressConsumed = true
                        onLongPress?.invoke()
                    },
                    onTap = {
                        // If long press just consumed, ignore this tap
                        if (longPressConsumed) {
                            longPressConsumed = false
                            return@detectTapGestures
                        }
                        
                        if (isSelectionMode) {
                            onSelectionToggle?.invoke()
                        } else {
                            onClick?.invoke()
                        }
                    }
                )
            },
        shape = RoundedCornerShape(20.dp),
        color = cardColor,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
        shadowElevation = if (isSelected) 4.dp else 2.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
            // Profile picture — same logic as KeyboardProofScreen avatar
            val context = LocalContext.current
            val imageLoader = remember {
                ImageLoader.Builder(context)
                    .components {
                        if (Build.VERSION.SDK_INT >= 28) {
                            add(ImageDecoderDecoder.Factory())
                        } else {
                            add(GifDecoder.Factory())
                        }
                    }
                    .build()
            }
            val staticImageLoader = remember { ImageLoader(context) }
            
            // Check if user is deactivated - observe StateFlow for reactive updates
            val deactivatedEmails by com.example.anonychat.utils.DeactivatedUsersManager.deactivatedEmailsFlow.collectAsState()
            val isUserDeactivated = remember(entry.userEmail, deactivatedEmails) {
                com.example.anonychat.utils.DeactivatedUsersManager.isDeactivated(entry.userEmail)
            }
            
            val emotion = remember(entry.peerRomanceMin, entry.peerRomanceMax) {
                romanceRangeToEmotion(entry.peerRomanceMin, entry.peerRomanceMax)
            }
            val avatarResName = if (entry.peerGender == "female") "female_exp$emotion" else "male_exp$emotion"
            val avatarResId = remember(avatarResName) {
                context.resources.getIdentifier(avatarResName, "raw", context.packageName)
            }
            val avatarUri = remember(avatarResId, isUserDeactivated) {
                if (isUserDeactivated) {
                    // Show ghost animation for deactivated users
                    Uri.parse("android.resource://${context.packageName}/${R.drawable.ghost_animation}")
                } else if (avatarResId != 0) {
                    Uri.parse("android.resource://${context.packageName}/$avatarResId")
                } else {
                    Uri.parse("android.resource://${context.packageName}/${R.raw.male_exp1}")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Checkbox in selection mode
                if (isSelectionMode) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = if (isSelected) "Selected" else "Not selected",
                        tint = if (isSelected) Color(0xFF7986CB) else (if (isDarkTheme) Color(0xFF5A6B88) else Color.Gray),
                        modifier = Modifier
                            .size(24.dp)
                            .padding(end = 8.dp)
                    )
                }
                
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy((-4).dp)
                ) {
                    // Profile picture circle
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(avatarBgColor),
                        contentAlignment = Alignment.Center
                    ) {
                    if (entry.profilePictureUrl != null && !isUserDeactivated) {
                        Image(
                            painter = rememberAsyncImagePainter(
                                model = entry.profilePictureUrl,
                                imageLoader = staticImageLoader
                            ),
                            contentDescription = "Profile",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Image(
                            painter = rememberAsyncImagePainter(
                                avatarUri,
                                imageLoader = if (isUserDeactivated) imageLoader else staticImageLoader
                            ),
                            contentDescription = "Profile",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                
                // Rating text below profile picture
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(top = 0.dp)
                ) {
                    Text(
                        text = if (entry.userRating != null && entry.userRating > 0f) {
                            String.format("%.1f", entry.userRating)
                        } else {
                            "N/A"
                        },
                        color = if (isDarkTheme) Color.White else Color.Black,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Rating",
                        tint = Color.Red,
                        modifier = Modifier.size(12.5.dp)
                    )
                }
            }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Username + last message
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isUserDeactivated) "Deactivated" else entry.username,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = entry.lastMessage,
                    color = subTextColor,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Heart icon (if favorite) + unread badge
            Column(horizontalAlignment = Alignment.End) {
                // Heart icon for favorites
                if (entry.isFavorite) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Favorite",
                        tint = Color(0xFF9C27B0),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                
                // Unread badge
                if (entry.unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF7986CB)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (entry.unreadCount > 99) "99+" else entry.unreadCount.toString(),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (!entry.isFavorite) {
                    Box(modifier = Modifier.size(22.dp))
                }
            }
            }
            
            // Timestamp at bottom right
            Text(
                text = formatMessageTime(entry.lastMessageTimestamp),
                color = if (entry.unreadCount > 0) Color(0xFF7986CB) else subTextColor,
                fontSize = 11.sp,
                fontWeight = if (entry.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 2.dp, end = 2.dp)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF87CEEB)
@Composable
private fun ConversationListItemPreview() {
    Column(modifier = Modifier.padding(8.dp)) {
        ConversationListItem(
            entry = ChatConversationEntry(
                userEmail = "alice@example.com",
                username = "Alice",
                lastMessage = "Hey! How are you? 😊",
                lastMessageTimestamp = System.currentTimeMillis() - 2 * 60_000,
                unreadCount = 3
            )
        )
        ConversationListItem(
            entry = ChatConversationEntry(
                userEmail = "bob@example.com",
                username = "Bob",
                lastMessage = "See you tomorrow!",
                lastMessageTimestamp = System.currentTimeMillis() - 30 * 60_000,
                unreadCount = 0
            )
        )
        ConversationListItem(
            entry = ChatConversationEntry(
                userEmail = "carol@example.com",
                username = "Carol",
                lastMessage = "📷 Photo",
                lastMessageTimestamp = System.currentTimeMillis() - 2 * 3_600_000,
                unreadCount = 1
            ),
            isDarkTheme = true
        )
    }
}

// Keep legacy UserListItem for backward compatibility
@Composable
fun UserListItem(user: User) {
    ConversationListItem(
        entry = ChatConversationEntry(
            userEmail = user.id ?: user.username,
            username = user.username,
            profilePictureUrl = user.profilePictureUrl,
            lastMessage = "",
            lastMessageTimestamp = System.currentTimeMillis()
        )
    )
}

@Composable
private fun ResetAccountDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    isDarkTheme: Boolean
) {
    val backgroundColor = if (isDarkTheme) Color(0xFF1E1E1E) else Color.White
    val textColor = if (isDarkTheme) Color(0xFFF5F5F5) else Color(0xFF1E1E1E)
    val warningColor = Color(0xFFE53935)
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = backgroundColor,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "🔄",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Reset Account",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "This clears your matches and preferences.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor
                )
                
                Spacer(Modifier.height(4.dp))
                
                Text(
                    text = "What stays:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
                Text(
                    text = "• Bans, suspensions, blocked users",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.8f)
                )
                
                Spacer(Modifier.height(4.dp))
                
                Text(
                    text = "What happens:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
                Text(
                    text = "• People you chatted with will see your account as deactivated",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.8f)
                )
                Text(
                    text = "• You may match with them again as a new user with a username of your choice",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.8f)
                )
                
                Spacer(Modifier.height(8.dp))
                
                Text(
                    text = "⚠️ This action cannot be undone",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = warningColor
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = warningColor,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Reset", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = textColor
                )
            ) {
                Text("Cancel")
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}
