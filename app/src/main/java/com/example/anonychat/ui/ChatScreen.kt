package com.example.anonychat.ui

import android.content.Context
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.Log
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
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.scale
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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.example.anonychat.model.Preferences
import com.example.anonychat.model.User
import com.example.anonychat.network.NetworkClient
import com.example.anonychat.network.WebSocketEvent
import com.example.anonychat.network.WebSocketManager
import kotlinx.coroutines.launch
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
    val peerRomanceMax: Float = 5f
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
        peerRomanceMax: Float? = null
    ) {
        val existingIndex = conversations.indexOfFirst { it.userEmail == peerEmail }
        if (existingIndex >= 0) {
            val existing = conversations[existingIndex]
            conversations[existingIndex] = existing.copy(
                username = peerUsername,
                lastMessage = preview,
                lastMessageTimestamp = timestamp,
                unreadCount = if (isIncoming) existing.unreadCount + 1 else existing.unreadCount,
                peerGender = peerGender ?: existing.peerGender,
                peerRomanceMin = peerRomanceMin ?: existing.peerRomanceMin,
                peerRomanceMax = peerRomanceMax ?: existing.peerRomanceMax
            )
        } else {
            conversations.add(
                ChatConversationEntry(
                    userEmail = peerEmail,
                    username = peerUsername,
                    lastMessage = preview,
                    lastMessageTimestamp = timestamp,
                    unreadCount = if (isIncoming) 1 else 0,
                    peerGender = peerGender ?: "male",
                    peerRomanceMin = peerRomanceMin ?: 1f,
                    peerRomanceMax = peerRomanceMax ?: 5f
                )
            )
        }
        // Sort: unread (incoming) entries first by timestamp desc, then rest by timestamp desc
        val sorted = conversations.sortedWith(
            compareByDescending<ChatConversationEntry> { it.unreadCount > 0 }
                .thenByDescending { it.lastMessageTimestamp }
        )
        conversations.clear()
        conversations.addAll(sorted)
        save()
    }

    fun clearUnread(peerEmail: String) {
        val idx = conversations.indexOfFirst { it.userEmail == peerEmail }
        if (idx >= 0) {
            conversations[idx] = conversations[idx].copy(unreadCount = 0)
            save()
        }
    }

    /**
     * Remove a conversation from the list by peer email.
     */
    fun remove(peerEmail: String) {
        val idx = conversations.indexOfFirst { it.userEmail == peerEmail }
        if (idx >= 0) {
            conversations.removeAt(idx)
            save()
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
                        isNewMatch: Boolean) -> Unit
) {
        // notify MainActivity that chat is now active
        LaunchedEffect(Unit) { onChatActive() }

        // when leaving ChatScreen
        DisposableEffect(Unit) { onDispose { onChatInactive() } }

        var searchQuery by remember { mutableStateOf("") }
        val context = LocalContext.current

        // Initialize ConversationRepository with context and load persisted data
        LaunchedEffect(Unit) {
                ConversationRepository.initialize(context)
        }

        // Use the singleton repository so the list persists across navigation
        val conversationList = ConversationRepository.conversations

        val scope = rememberCoroutineScope() // <--- ADD THIS LINE
        val userPrefsForCounts = remember { context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE) }
        var roses by remember { mutableStateOf(userPrefsForCounts.getInt("roses", 0)) }
        var sparks by remember { mutableStateOf(userPrefsForCounts.getInt("sparks_self", 0)) }
        var flowerKey by remember { mutableStateOf(0) }
        var thunderKey by remember { mutableStateOf(0) }
        val themePrefs = remember {
                context.getSharedPreferences("anonychat_theme", Context.MODE_PRIVATE)
        }
        var isDarkTheme by remember {
                mutableStateOf(themePrefs.getBoolean("is_dark_theme", false))
        }
        var showThemeDialog by remember { mutableStateOf(false) }
        var isLoading by remember { mutableStateOf(false) }

        if (showThemeDialog) {
                AlertDialog(
                        onDismissRequest = { showThemeDialog = false },
                        title = { Text("Choose Theme") },
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
                                }
                        },
                        confirmButton = {}
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

        // Fetch preferences from server on load — update sparks/roses state + SharedPreferences
        LaunchedEffect(Unit) {
                val userEmail = userPrefsForCounts.getString("user_email", null)
                if (userEmail != null) {
                        try {
                                val response = NetworkClient.api.getPreferences(userEmail)
                                if (response.isSuccessful && response.body() != null) {
                                        val serverPrefs = response.body()!!
                                        serverPrefs.sparks?.let {
                                                sparks = it
                                                userPrefsForCounts.edit().putInt("sparks_self", it).apply()
                                        }
                                        serverPrefs.totalRosesReceived?.let {
                                                roses = it
                                                userPrefsForCounts.edit().putInt("roses", it).apply()
                                        }
                                        serverPrefs.availableRoses?.let {
                                                userPrefsForCounts.edit().putInt("available_roses", it).apply()
                                        }
                                        android.util.Log.d("ChatScreen", "Prefs fetched: sparks=${serverPrefs.sparks}, roses=${serverPrefs.totalRosesReceived}")
                                }
                        } catch (e: Exception) {
                                android.util.Log.e("ChatScreen", "Error fetching prefs: ${e.message}")
                        }
                }
        }

        // Listen for live WebSocket events: update sparks + conversation list
        LaunchedEffect(Unit) {
                val myEmail = userPrefsForCounts.getString("user_email", "") ?: ""
                WebSocketManager.events.collect { event ->
                        when (event) {
                                is WebSocketEvent.SparkRight -> {
                                        sparks = userPrefsForCounts.getInt("sparks_self", sparks)
                                }
                                is WebSocketEvent.NewMessage -> {
                                        val msg = event.message
                                        val peerEmail = if (msg.from == myEmail) msg.to else msg.from
                                        val isIncoming = msg.from != myEmail
                                        val preview = when {
                                            msg.text.isNotBlank() -> msg.text
                                            msg.mediaType == "IMAGE" -> "📷 Photo"
                                            msg.mediaType == "VIDEO" -> "🎥 Video"
                                            msg.audioUri != null -> "🎵 Voice message"
                                            else -> "Message"
                                        }
                                        
                                        // Fetch actual username from API in background
                                        scope.launch {
                                            try {
                                                val peerPrefsResponse = NetworkClient.api.getPreferences(peerEmail)
                                                val peerPrefs = peerPrefsResponse.takeIf { it.isSuccessful }?.body()
                                                val actualUsername = peerPrefs?.username ?: peerEmail.substringBefore('@')
                                                
                                                ConversationRepository.upsert(
                                                    myEmail = myEmail,
                                                    peerEmail = peerEmail,
                                                    peerUsername = actualUsername,
                                                    preview = preview,
                                                    timestamp = msg.timestamp,
                                                    isIncoming = isIncoming,
                                                    peerGender = peerPrefs?.gender,
                                                    peerRomanceMin = peerPrefs?.romanceRange?.min?.toFloat(),
                                                    peerRomanceMax = peerPrefs?.romanceRange?.max?.toFloat()
                                                )
                                            } catch (e: Exception) {
                                                // Fallback to email prefix if API call fails
                                                ConversationRepository.upsert(
                                                    myEmail = myEmail,
                                                    peerEmail = peerEmail,
                                                    peerUsername = peerEmail.substringBefore('@'),
                                                    preview = preview,
                                                    timestamp = msg.timestamp,
                                                    isIncoming = isIncoming
                                                )
                                                Log.e("ChatScreen", "Failed to fetch username for $peerEmail", e)
                                            }
                                        }
                                }
                                else -> Unit
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
                                        val userPrefs = remember {
                                                context.getSharedPreferences(
                                                        "user_prefs",
                                                        Context.MODE_PRIVATE
                                                )
                                        }
                                        val gender = userPrefs.getString("gender", "male") ?: "male"
                                        val romanceStart = userPrefs.getFloat("romance_min", 2f)
                                        val romanceEnd = userPrefs.getFloat("romance_max", 9f)

                                        val emotion =
                                                romanceRangeToEmotion(romanceStart, romanceEnd)
                                        val resName =
                                                if (gender == "female") "female_exp$emotion"
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
                        val filteredConversations = if (searchQuery.isBlank()) {
                            conversationList
                        } else {
                            conversationList.filter {
                                it.username.contains(searchQuery, ignoreCase = true) ||
                                it.userEmail.contains(searchQuery, ignoreCase = true)
                            }
                        }

                        val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        Surface(
                                modifier =
                                        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = navigationBarPadding + 16.dp)
                                                .fillMaxWidth()
                                                .weight(1f),
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

                                        // --- CONVERSATION LIST ---
                                        LazyColumn(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                                items(filteredConversations, key = { it.userEmail }) { entry ->
                                                        ConversationListItem(
                                                                entry = entry,
                                                                isDarkTheme = isDarkTheme,
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

                                                                        // Asynchronously fetch fresh peer preferences in background
                                                                        scope.launch {
                                                                                try {
                                                                                        val peerPrefsResponse = NetworkClient.api.getPreferences(entry.userEmail)
                                                                                        val peerPrefsBody = peerPrefsResponse.takeIf { it.isSuccessful }?.body()

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
        BackHandler(enabled = isLoading) {
                isLoading = false // stop the heart overlay
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

                                                                // Check if conversation list is empty, if so delete all matches first
                                                                if (conversationList.isEmpty()) {
                                                                        try {
                                                                                val deleteAllResponse = NetworkClient.api.deleteAllMatches(myEmail)
                                                                                if (deleteAllResponse.isSuccessful) {
                                                                                        Log.d("ChatScreen", "All matches deleted before calling match API")
                                                                                } else {
                                                                                        Log.e("ChatScreen", "Failed to delete all matches: ${deleteAllResponse.code()}")
                                                                                }
                                                                        } catch (e: Exception) {
                                                                                Log.e("ChatScreen", "Error deleting all matches: ${e.message}")
                                                                        }
                                                                }

                                                                // 1️⃣ Call match API
                                                                val matchResponse =
                                                                        NetworkClient.api.callMatch(
                                                                                myEmail
                                                                        )

                                                                val matchObj =
                                                                        matchResponse
                                                                                ?.takeIf {
                                                                                        it.isSuccessful
                                                                                }
                                                                                ?.body()

                                                                val matchedEmail =
                                                                        matchObj?.gmail
                                                                                ?: matchObj?.match

                                                                if (matchedEmail.isNullOrBlank() ||
                                                                                matchObj == null
                                                                ) {
                                                                        Log.e(
                                                                                "ChatScreen",
                                                                                "No match found"
                                                                        )
                                                                        return@launch
                                                                }

                                                                // 2️⃣ Build matched user and prefs
                                                                // from matchObj
                                                                val matchedUsername =
                                                                        matchObj.username ?: ""
                                                                val matchedRomanceMin =
                                                                        matchObj.romanceRange?.min
                                                                                ?.toFloat()
                                                                                ?: 1f
                                                                val matchedRomanceMax =
                                                                        matchObj.romanceRange?.max
                                                                                ?.toFloat()
                                                                                ?: 5f
                                                                val matchedGender =
                                                                        matchObj.gender ?: "male"

                                                                val matchedPrefs =
                                                                        Preferences(
                                                                                romanceMin =
                                                                                        matchedRomanceMin,
                                                                                romanceMax =
                                                                                        matchedRomanceMax,
                                                                                gender =
                                                                                        matchedGender,
                                                                                isOnline =
                                                                                        matchObj.isOnline
                                                                                                ?: false,
                                                                                lastSeen =
                                                                                        matchObj.lastOnline
                                                                                                ?: 0L,
                                                                                giftedMeARose =
                                                                                        matchObj.giftedMeARose
                                                                                                ?: false,
                                                                                hasTakenBackRose =
                                                                                        matchObj.hasTakenBackRose
                                                                                                ?: false
                                                                        )

                                                                val matchedUser =
                                                                        User(
                                                                                username =
                                                                                        matchedUsername,
                                                                                profilePictureUrl =
                                                                                        null,
                                                                                id = matchedEmail
                                                                        )

                                                                // 3️⃣ Get current user info from
                                                                // prefs (NOT from matched user
                                                                // response!)
                                                                val myUsername =
                                                                        prefs.getString(
                                                                                "username",
                                                                                null
                                                                        )
                                                                                ?: myEmail.substringBefore(
                                                                                        '@'
                                                                                )
                                                                val myGender =
                                                                        prefs.getString(
                                                                                "gender",
                                                                                "male"
                                                                        )
                                                                                ?: "male"
                                                                val myRomanceMin =
                                                                        prefs.getFloat(
                                                                                "romance_min",
                                                                                1f
                                                                        )
                                                                val myRomanceMax =
                                                                        prefs.getFloat(
                                                                                "romance_max",
                                                                                5f
                                                                        )

                                                                val currentUserForNav =
                                                                        User(
                                                                                id = myEmail,
                                                                                username =
                                                                                        myUsername,
                                                                                profilePictureUrl =
                                                                                        null
                                                                        )
                                                                val myPrefsForNav =
                                                                        Preferences(
                                                                                romanceMin =
                                                                                        myRomanceMin,
                                                                                romanceMax =
                                                                                        myRomanceMax,
                                                                                gender = myGender
                                                                        )

                                                                // 4️⃣ NAVIGATE TO DIRECT CHAT
                                                                onNavigateToDirectChat(
                                                                        currentUserForNav,
                                                                        myPrefsForNav,
                                                                        matchedUser,
                                                                        matchedPrefs,
                                                                        true // From match API
                                                                )
                                                        } catch (e: Exception) {
                                                                Log.e(
                                                                        "ChatScreen",
                                                                        "Match flow failed",
                                                                        e
                                                                )
                                                        } finally {
                                                                isLoading = false
                                                        }
                                                }
                                        }
                )
        }
        LoadingHeartOverlay(isLoading = isLoading)
}

@Composable
fun ConversationListItem(
    entry: ChatConversationEntry,
    isDarkTheme: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val subTextColor = if (isDarkTheme) Color(0xFFB0BEC5) else Color.Gray
    val cardColor = if (isDarkTheme) Color(0xFF121821) else Color(0xF2FFFFFF)
    val avatarBgColor = if (isDarkTheme) Color(0xFF3A4552) else Color(0xFF87CEEB)
    val borderColor = if (isDarkTheme) Color(0xFF4A5562) else Color.White.copy(alpha = 0.6f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(enabled = onClick != null) { onClick?.invoke() },
        shape = RoundedCornerShape(20.dp),
        color = cardColor,
        border = if (isDarkTheme) null else BorderStroke(1.dp, borderColor),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Profile picture — same logic as KeyboardProofScreen avatar
            val context = LocalContext.current
            val staticImageLoader = remember { ImageLoader(context) }
            val emotion = remember(entry.peerRomanceMin, entry.peerRomanceMax) {
                romanceRangeToEmotion(entry.peerRomanceMin, entry.peerRomanceMax)
            }
            val avatarResName = if (entry.peerGender == "female") "female_exp$emotion" else "male_exp$emotion"
            val avatarResId = remember(avatarResName) {
                context.resources.getIdentifier(avatarResName, "raw", context.packageName)
            }
            val avatarUri = remember(avatarResId) {
                if (avatarResId != 0)
                    Uri.parse("android.resource://${context.packageName}/$avatarResId")
                else
                    Uri.parse("android.resource://${context.packageName}/${R.raw.male_exp1}")
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(avatarBgColor),
                contentAlignment = Alignment.Center
            ) {
                if (entry.profilePictureUrl != null) {
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
                            imageLoader = staticImageLoader
                        ),
                        contentDescription = "Profile",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Username + last message
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.username,
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

            // Timestamp + unread badge
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatMessageTime(entry.lastMessageTimestamp),
                    color = if (entry.unreadCount > 0) Color(0xFF7986CB) else subTextColor,
                    fontSize = 11.sp,
                    fontWeight = if (entry.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal
                )
                Spacer(modifier = Modifier.height(4.dp))
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
                } else {
                    Box(modifier = Modifier.size(22.dp))
                }
            }
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
