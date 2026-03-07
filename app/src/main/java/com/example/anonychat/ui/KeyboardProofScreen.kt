package com.example.anonychat.ui
// VERIFIED_MEDIA_SUPPORT_ADDED_2026_01_25

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.AppCompatEditText
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.OnImageSavedCallback
import androidx.camera.core.ImageCapture.OutputFileResults
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.animation.core.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ContentInfoCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.example.anonychat.MainActivity
import com.example.anonychat.R
import com.example.anonychat.model.Preferences
import com.example.anonychat.model.User
import com.example.anonychat.network.NetworkClient
import com.example.anonychat.network.WebSocketEvent
import com.example.anonychat.network.WebSocketManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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


/* ---------------- MESSAGE STATUS ---------------- */

enum class MessageStatus {
    Pending,
    Sending,
    Delivered,
    Read,
    Failed
}

/* ---------------- CHAT MESSAGE MODEL (with status) ---------------- */

data class ChatMessage(
        val id: String = UUID.randomUUID().toString(),
        val from: String, // email/gmail
        val to: String, // email/gmail
        val text: String,
        val audioUri: String? = null, // Path/URI for playable audio
        val amplitudes: List<Float> = emptyList(), // Amplitude data for waveforms
        val mediaUri: String? = null, // URI for photo/video
        val mediaType: String? = null, // "IMAGE" or "VIDEO"
        val mediaId: String? = null, // Server-side media ID
        val isDownloading: Boolean = false, // True when media is being fetched
        val timestamp: Long = System.currentTimeMillis(),
        val status: MessageStatus = MessageStatus.Delivered
)

sealed class ChatHistoryItem {
    data class Message(val message: ChatMessage) : ChatHistoryItem()
    data class DateHeader(val date: String, val timestamp: Long) : ChatHistoryItem()
}

private fun getFormattedDate(timestamp: Long): String {
    val now = Calendar.getInstance()
    val time = Calendar.getInstance().apply { timeInMillis = timestamp }

    return when {
        isSameDay(now, time) -> "Today"
        isYesterday(now, time) -> "Yesterday"
        now.get(Calendar.YEAR) == time.get(Calendar.YEAR) -> {
            SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(timestamp))
        }
        else -> {
            SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(timestamp))
        }
    }
}

private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

private fun isYesterday(now: Calendar, then: Calendar): Boolean {
    val yesterday =
            Calendar.getInstance().apply {
                timeInMillis = now.timeInMillis
                add(Calendar.DAY_OF_YEAR, -1)
            }
    return yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
}

private var mediaRecorder: MediaRecorder? = null
private var currentAudioFile: File? = null
private var isRecording = false

private fun startRecording(context: Context) {
    currentAudioFile =
            File.createTempFile("AUD_${System.currentTimeMillis()}", ".m4a", context.cacheDir)
    mediaRecorder =
            MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(currentAudioFile!!.absolutePath)
                prepare()
                start()
            }
    isRecording = true
}

@RequiresApi(Build.VERSION_CODES.N)
private fun stopRecordingAndSend(
        context: Context,
        peerEmail: String,
        messages: SnapshotStateList<ChatMessage>,
        amplitudes: List<Float>
) {
    try {

        mediaRecorder?.stop()
    } catch (e: RuntimeException) {
        Log.e("AudioRecorder", "Stop failed: likely too short", e)
        // If stop fails, we shouldn't try to send the garbage file
        mediaRecorder?.release()
        mediaRecorder = null
        isRecording = false
        currentAudioFile?.delete()
        return
    }

    mediaRecorder?.release()
    mediaRecorder = null
    isRecording = false

    currentAudioFile?.let {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
        val localId = "media-${UUID.randomUUID()}"

        // --- ADDED: Local UI update ---
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val localGmail = prefs.getString("user_email", "") ?: ""

        val newMsg =
                ChatMessage(
                        id = localId,
                        from = localGmail,
                        to = peerEmail,
                        text = "[Audio Message]",
                        audioUri = uri.toString(),
                        amplitudes = amplitudes,
                        status =
                                MessageStatus.Pending // Start as Pending, will update via WebSocket
                        // events
                        )
        messages.upsert(newMsg)
        WebSocketManager.sendMedia(peerEmail, uri, "audio/m4a", localId, amplitudes)
        // Note: This is in stopRecordingAndSend function - hasMessageBeenSent will be set in the calling context
    }
}

// Camera / Gallery - static variables removed in favor of internal flow states

// Replace with real peer to-email when sending (set when opening chat)
private var currentPeerEmail: String = "peer@example.com"

private fun SnapshotStateList<ChatMessage>.upsert(msg: ChatMessage) {
    val index = indexOfFirst { it.id == msg.id }
    if (index == -1) {
        add(msg)
    } else {
        this[index] = msg
    }
}

/* ---------------- KEYBOARD VISIBILITY DETECTOR ---------------- */

@Composable
private fun rememberKeyboardVisible(): State<Boolean> {
    val view = LocalView.current
    val keyboardVisible = remember { mutableStateOf(false) }

    DisposableEffect(view) {
        val listener =
                ViewTreeObserver.OnGlobalLayoutListener {
                    val rect = Rect()
                    view.getWindowVisibleDisplayFrame(rect)
                    val screenHeight = view.rootView.height
                    val keypadHeight = screenHeight - rect.bottom
                    keyboardVisible.value = keypadHeight > screenHeight * 0.15
                }

        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        listener.onGlobalLayout()

        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }

    return keyboardVisible
}

/* ---------------- SCREEN ---------------- */

@androidx.annotation.OptIn(UnstableApi::class)
@RequiresApi(Build.VERSION_CODES.N)
@OptIn(
        ExperimentalMaterial3Api::class,
        ExperimentalLayoutApi::class,
        androidx.compose.foundation.ExperimentalFoundationApi::class,
        androidx.compose.ui.ExperimentalComposeUiApi::class
)
@Composable
fun KeyboardProofScreen(
        currentUser: User,
        matchedUser: User,
        matchedUserPrefs: Preferences,
        matchedUserGmail: String, // <-- NEW: recipient's gmail/email for sending & filtering
        onBack: () -> Unit,
        initialMessages: List<ChatMessage> = emptyList(),
        forceDarkTheme: Boolean? = null,
        isNewMatch: Boolean = false // <-- NEW: true if opened from match API, false if from conversation list
) {
    val context = LocalContext.current
    val view = LocalView.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val imageLoader = remember {
        ImageLoader.Builder(context)
                .components {
                    if (SDK_INT >= 28) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .build()
    }

    val staticImageLoader = remember { ImageLoader(context) }

    var isRecording by remember { mutableStateOf(false) }
    var cancelRecording by remember { mutableStateOf(false) }
    var startX by remember { mutableStateOf(0f) }

    var input by remember { mutableStateOf("") }
    var isMediaProcessing by remember { mutableStateOf(false) }
    var fullScreenVideoUri by remember { mutableStateOf<String?>(null) }
    var fullScreenImageUri by remember { mutableStateOf<String?>(null) }
    var showFullScreenCamera by remember { mutableStateOf(false) }
    var capturedImageUri by remember { mutableStateOf<Uri?>(null) }
    var showAdvancedMediaPicker by remember { mutableStateOf(false) }
    var showThunderGif by remember { mutableStateOf(false) }
    var showMutualGif by remember { mutableStateOf(false) }
    var showMissGif by remember { mutableStateOf(false) }
    var showRoseOverlay by remember { mutableStateOf(false) }
    var isRoseDead by remember { mutableStateOf(false) }
    var overlayModeIsDead by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<String?>(null) }
    var hasMessageBeenSent by remember { mutableStateOf(false) }
    
    // Menu and Report Dialog states
    var showMenu by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var selectedReportReason by remember { mutableStateOf<String?>(null) }
    var otherReportText by remember { mutableStateOf("") }

    val messages = remember { mutableStateListOf<ChatMessage>().apply { addAll(initialMessages) } }

    val chatItems by remember {
        derivedStateOf {
            val items = mutableListOf<ChatHistoryItem>()
            val grouped =
                    messages.groupBy {
                        val cal = Calendar.getInstance().apply { timeInMillis = it.timestamp }
                        cal.set(Calendar.HOUR_OF_DAY, 0)
                        cal.set(Calendar.MINUTE, 0)
                        cal.set(Calendar.SECOND, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        cal.timeInMillis
                    }

            val sortedDates = grouped.keys.sortedDescending()
            for (date in sortedDates) {
                val dayMessages = grouped[date]?.sortedByDescending { it.timestamp } ?: emptyList()
                dayMessages.forEach { items.add(ChatHistoryItem.Message(it)) }
                items.add(ChatHistoryItem.DateHeader(getFormattedDate(date), date))
            }
            items
        }
    }

    val floatingDate by remember {
        derivedStateOf {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf null

            val topItemInfo = visibleItems.maxByOrNull { it.index }
            val index = topItemInfo?.index ?: return@derivedStateOf null

            if (index < chatItems.size) {
                val item = chatItems[index]
                val timestamp =
                        when (item) {
                            is ChatHistoryItem.Message -> item.message.timestamp
                            is ChatHistoryItem.DateHeader -> item.timestamp
                        }
                getFormattedDate(timestamp)
            } else null
        }
    }

    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var showFloatingDateBanner by remember { mutableStateOf(false) }
    var isAppInForeground by remember { mutableStateOf(true) }

    LaunchedEffect(isDragged, listState.isScrollInProgress) {
        if (isDragged) {
            showFloatingDateBanner = true
        } else if (!listState.isScrollInProgress) {
            delay(1500)
            showFloatingDateBanner = false
        }
    }
    // Moved after currentMatchedPrefs to use reactive state
    val userPrefs = remember { context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE) }
    
    // Load blocked status from SharedPreferences
    var isUserBlocked by remember { mutableStateOf(userPrefs.getBoolean("blocked_$matchedUserGmail", false)) }
    
    // receivedRoses: shown in top header next to matched user's name (totalRosesReceived)
    var roses by remember { mutableStateOf(userPrefs.getInt("roses", 0)) }
    // availableRoses: shown near text input box (availableRoses from server), decremented on gifting
    var availableRoses by remember { mutableStateOf(userPrefs.getInt("available_roses", 0)) }

    // Fetch roses counts from server on load
    LaunchedEffect(Unit) {
        val userEmail = userPrefs.getString("user_email", null)
        if (userEmail != null) {
            try {
                val response = NetworkClient.api.getPreferences(userEmail)
                if (response.isSuccessful && response.body() != null) {
                    val serverPrefs = response.body()!!
                    serverPrefs.totalRosesReceived?.let {
                        roses = it
                        userPrefs.edit().putInt("roses", it).apply()
                    }
                    serverPrefs.availableRoses?.let {
                        availableRoses = it
                        userPrefs.edit().putInt("available_roses", it).apply()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("KeyboardProofScreen", "Error fetching roses: ${e.message}")
            }
        }
    }

    var sparks by remember {
        // Use peer-specific key so each peer's spark count is tracked separately
        mutableStateOf(userPrefs.getInt("sparks_${matchedUserGmail}", 0))
    }
    // var sparks by remember { mutableStateOf(3700) } // Test: 3.7k
    // var sparks by remember { mutableStateOf(150000) } // Test: 150k
    // var sparks by remember { mutableStateOf(5000000) } // Test: 5M
    
    // State to show heart/heartbreak icon on profile picture
    // Initialize from match response: giftedMeARose=true → heart, hasTakenBackRose=true → heartbreak
    var showProfileIcon by remember {
        mutableStateOf<String?>(
            when {
                matchedUserPrefs.giftedMeARose && !matchedUserPrefs.hasTakenBackRose -> "heart"
                matchedUserPrefs.hasTakenBackRose -> "heartbreak"
                else -> null
            }
        )
    }
    Log.e(
            "KeyboardProofScreen",
            "DEBUG: Initialized with isOnline=${matchedUserPrefs.isOnline}, lastSeen=${matchedUserPrefs.lastSeen}"
    )

    Log.e("KeyboardProofScreen", "start → $matchedUserGmail")

    var recordingDuration by remember { mutableStateOf(0L) }
    val recordingAmplitudes = remember { mutableStateListOf<Float>() }
    val galleryImages = remember { mutableStateListOf<Uri>() }
    val selectedGalleryImages = remember { mutableStateListOf<Uri>() }

    // Handle back button for overlays
    if (showThunderGif) {
        BackHandler { showThunderGif = false }
    } else if (fullScreenVideoUri != null) {
        BackHandler { fullScreenVideoUri = null }
    } else if (fullScreenImageUri != null) {
        BackHandler { fullScreenImageUri = null }
    } else if (capturedImageUri != null) {
        BackHandler { capturedImageUri = null }
    } else if (showFullScreenCamera) {
        BackHandler { showFullScreenCamera = false }
    } else if (showMutualGif) {
        BackHandler { showMutualGif = false }
    } else if (showMissGif) {
        BackHandler { showMissGif = false }
    } else if (showRoseOverlay) {
        BackHandler { showRoseOverlay = false }
    } else if (showAdvancedMediaPicker) {
        BackHandler {
            showAdvancedMediaPicker = false
            selectedGalleryImages.clear()
        }
    } else {
        // Default back button behavior - call skip or accept endpoint only for new matches
        BackHandler {
            if (isNewMatch) {
                scope.launch {
                    try {
                        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                        val myEmail = prefs.getString("user_email", "") ?: ""
                        
                        if (hasMessageBeenSent) {
                            // Accept match if message was sent
                            Log.e("KeyboardProofScreen", "[SYSTEM BACK] Calling ACCEPT match API: myEmail=$myEmail, candGmail=$matchedUserGmail")
                            val response = NetworkClient.api.acceptMatch(myEmail, matchedUserGmail)
                            Log.e("KeyboardProofScreen", "[SYSTEM BACK] Accept match response: isSuccessful=${response.isSuccessful}, code=${response.code()}, message=${response.message()}")
                            if (response.isSuccessful) {
                                Log.e("KeyboardProofScreen", "[SYSTEM BACK] Accept match SUCCESS for $matchedUserGmail")
                            } else {
                                Log.e("KeyboardProofScreen", "[SYSTEM BACK] Accept match FAILED: ${response.errorBody()?.string()}")
                            }
                        } else {
                            // Skip match if no message was sent
                            Log.e("KeyboardProofScreen", "[SYSTEM BACK] Calling SKIP match API: myEmail=$myEmail, candGmail=$matchedUserGmail")
                            val response = NetworkClient.api.skipMatch(myEmail, matchedUserGmail)
                            Log.e("KeyboardProofScreen", "[SYSTEM BACK] Skip match response: isSuccessful=${response.isSuccessful}, code=${response.code()}, message=${response.message()}")
                            if (response.isSuccessful) {
                                Log.e("KeyboardProofScreen", "[SYSTEM BACK] Skip match SUCCESS for $matchedUserGmail")
                            } else {
                                Log.e("KeyboardProofScreen", "[SYSTEM BACK] Skip match FAILED: ${response.errorBody()?.string()}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("KeyboardProofScreen", "[SYSTEM BACK] EXCEPTION calling match endpoint: ${e.message}", e)
                    }
                }
            } else {
                Log.e("KeyboardProofScreen", "[SYSTEM BACK] Skipping match API call - opened from conversation list")
            }
            onBack()
        }
    }

    val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    val localGmail = prefs.getString("user_email", "") ?: ""

    // Track mutual chat session for "feel that spark" overlay
    var peerChatOpenReceived by remember {
        mutableStateOf(WebSocketManager.isUserInChatWithUs(matchedUserGmail))
    }
    var mutualChatStartTime by remember { mutableStateOf<Long?>(null) }
    var sparkOverlayShown by remember { mutableStateOf(false) }
    var alreadySparked by remember { mutableStateOf(false) }

    // Track if chat_open has been sent to prevent duplicates
    var chatOpenSent by remember { mutableStateOf(false) }
    
    // Track spark swipe choices
    var mySparkChoice by remember { mutableStateOf<Boolean?>(null) } // null = not swiped, true = right, false = left
    var peerSparkChoice by remember { mutableStateOf<Boolean?>(null) } // null = not received, true = right, false = left
    
    // Check if users have already sparked on screen open
    LaunchedEffect(Unit) {
        Log.e("SparkOverlay", "=".repeat(60))
        Log.e("SparkOverlay", "Screen opened - peer already in chat: $peerChatOpenReceived")
        // Extract user IDs from email addresses (remove @email.com suffix)
        val localUserId = localGmail.substringBefore("@")
        val matchedUserId = matchedUserGmail.substringBefore("@")
        
        Log.e("SparkOverlay", "Checking spark status for: $localUserId <-> $matchedUserId")
        Log.e("SparkOverlay", "  (Full emails: $localGmail <-> $matchedUserGmail)")
        
        // Check if this user pair has already sparked
        try {
            val request = com.example.anonychat.network.SparkRequest(
                userA = localUserId,
                userB = matchedUserId
            )
            Log.e("SparkOverlay", "Calling isSparked API with request: $request")
            
            val response = NetworkClient.api.isSparked(request)
            
            Log.e("SparkOverlay", "isSparked API Response:")
            Log.e("SparkOverlay", "  - Status Code: ${response.code()}")
            Log.e("SparkOverlay", "  - Is Successful: ${response.isSuccessful}")
            Log.e("SparkOverlay", "  - Response Body: ${response.body()}")
            Log.e("SparkOverlay", "  - Sparked: ${response.body()?.sparked}")
            
            if (response.isSuccessful && response.body()?.sparked == true) {
                alreadySparked = true
                Log.e("SparkOverlay", "✅ Users have ALREADY sparked - overlay will NOT be shown")
            } else {
                Log.e("SparkOverlay", "✅ Users have NOT sparked yet - timer will start when conditions met")
            }
        } catch (e: Exception) {
            Log.e("SparkOverlay", "❌ EXCEPTION checking spark status: ${e.message}", e)
            Log.e("SparkOverlay", "Stack trace: ${e.stackTraceToString()}")
        }
        Log.e("SparkOverlay", "=".repeat(60))
    }

    LaunchedEffect(matchedUserGmail) {
        if (matchedUserGmail.isNotBlank() && localGmail.isNotBlank()) {
            // Load History - clear and reload to preserve chronological order
            WebSocketManager.getChatHistory(localGmail, matchedUserGmail).collect { history ->
                messages.clear()
                messages.addAll(history)
            }
        }
    }

    DisposableEffect(matchedUserGmail) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    isAppInForeground = true
                    // Screen became visible (app foreground + screen on top)
                    if (matchedUserGmail.isNotBlank() && localGmail.isNotBlank() && !chatOpenSent) {
                        Log.e("ChatLifecycle", "CHAT OPENED → me: $localGmail  ↔  partner: $matchedUserGmail")
                        WebSocketManager.sendChatOpen(matchedUserGmail)
                        chatOpenSent = true
                    }
                    // Clear unread count for this conversation when user opens the chat
                    ConversationRepository.clearUnread(matchedUserGmail)
                }
                Lifecycle.Event.ON_STOP -> {
                    isAppInForeground = false
                    // Screen no longer visible (either backgrounded or navigated away)
                    if (matchedUserGmail.isNotBlank() && localGmail.isNotBlank() && chatOpenSent) {
                        Log.e("ChatLifecycle", "!!! CHAT HIDDEN → $matchedUserGmail !!!")
                        WebSocketManager.sendChatClose(matchedUserGmail)
                        chatOpenSent = false
                        // Reset mutual chat session
                        peerChatOpenReceived = false
                        mutualChatStartTime = null
                        Log.e("SparkOverlay", "Mutual chat session ended (user left)")
                    }
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            // Only send chat_close if we actually sent chat_open
            if (matchedUserGmail.isNotBlank() && localGmail.isNotBlank() && chatOpenSent) {
                Log.e(
                        "ChatLifecycle",
                        "!!! CHAT CLOSED → me: $localGmail  ↔  partner: $matchedUserGmail !!!"
                )
                WebSocketManager.sendChatClose(matchedUserGmail)
                chatOpenSent = false
            }
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Monitor mutual chat session and trigger "feel that spark" overlay after timer.
    // SYNC STRATEGY: Only the lexicographically smaller email acts as "initiator".
    // The initiator sends a spark_trigger WebSocket event when the timer fires.
    // The receiver shows the overlay only upon receiving spark_trigger.
    // This ensures both users see the overlay at the same wall-clock time regardless of
    // when each user's chat_open event was received.
    val isInitiator = localGmail < matchedUserGmail
    LaunchedEffect(chatOpenSent, peerChatOpenReceived, alreadySparked) {
        // Only start timer when BOTH users are in the chat AND haven't sparked before
        if (chatOpenSent && peerChatOpenReceived && !sparkOverlayShown && !alreadySparked) {
            // Set start time if not already set
            if (mutualChatStartTime == null) {
                mutualChatStartTime = System.currentTimeMillis()
                Log.e("SparkOverlay", "Mutual session started at $mutualChatStartTime (isInitiator=$isInitiator)")
            }

            if (isInitiator) {
                // INITIATOR: run the timer and send spark_trigger to peer when it fires
                val startTime = mutualChatStartTime
                Log.e("SparkOverlay", "[INITIATOR] Timer started, waiting 3 seconds...")
                delay(3000)

                if (chatOpenSent && peerChatOpenReceived && mutualChatStartTime == startTime && !alreadySparked) {
                    Log.e("SparkOverlay", "=".repeat(60))
                    Log.e("SparkOverlay", "[INITIATOR] ⏰ Timer elapsed - sending spark_trigger to $matchedUserGmail")

                    // Mark users as sparked in the backend
                    val localUserId = localGmail.substringBefore("@")
                    val matchedUserId = matchedUserGmail.substringBefore("@")
                    try {
                        val request = com.example.anonychat.network.SparkRequest(
                            userA = localUserId,
                            userB = matchedUserId
                        )
                        Log.e("SparkOverlay", "[INITIATOR] Calling hasSparked API: $request")
                        val response = NetworkClient.api.hasSparked(request)
                        Log.e("SparkOverlay", "[INITIATOR] hasSparked response: ${response.code()} sparked=${response.body()?.sparked}")
                        if (response.isSuccessful) {
                            alreadySparked = true
                            Log.e("SparkOverlay", "[INITIATOR] ✅ Marked as sparked in backend")
                        } else {
                            Log.e("SparkOverlay", "[INITIATOR] ❌ hasSparked failed: ${response.errorBody()?.string()}")
                        }
                    } catch (e: Exception) {
                        Log.e("SparkOverlay", "[INITIATOR] ❌ hasSparked exception: ${e.message}", e)
                    }

                    // Send trigger to peer so they show overlay at the same time
                    WebSocketManager.sendSparkTrigger(matchedUserGmail, localGmail)

                    Log.e("SparkOverlay", "[INITIATOR] 🎆 SHOWING SPARK OVERLAY NOW!")
                    showThunderGif = true
                    sparkOverlayShown = true
                    Log.e("SparkOverlay", "=".repeat(60))
                } else {
                    Log.e("SparkOverlay", "[INITIATOR] ❌ Conditions not met after timer - overlay skipped")
                    Log.e("SparkOverlay", "  chatOpenSent=$chatOpenSent peerChatOpenReceived=$peerChatOpenReceived alreadySparked=$alreadySparked")
                }
            } else {
                // RECEIVER: do NOT run a timer - wait for spark_trigger WebSocket event from initiator
                Log.e("SparkOverlay", "[RECEIVER] Waiting for spark_trigger from $matchedUserGmail (no local timer)")
            }
        } else if (alreadySparked) {
            Log.e("SparkOverlay", "⏭️  Users have already sparked - skipping timer completely")
        }
    }

    /* ---------------- SYSTEM UI ---------------- */
    SideEffect {
        val window = (view.context as Activity).window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
    }

    /* ---------------- THEME ---------------- */
    val themePrefs = context.getSharedPreferences("anonychat_theme", Context.MODE_PRIVATE)
    val isDarkTheme = forceDarkTheme ?: themePrefs.getBoolean("is_dark_theme", false)

    val incomingBubbleColor = if (isDarkTheme) Color(0xFF141024) else Color.White
    val outgoingBubbleColor = if (isDarkTheme) Color(0xFF1E2A6D) else Color(0xFF1E88E5)
    val messageTextColor = if (isDarkTheme) Color(0xFFEAEAF0) else Color.Black

    val inputBackgroundColor = if (isDarkTheme) Color(0xFF1C2430) else Color(0xFFF2F6FB)
    val inputBorderColor = if (isDarkTheme) Color(0x33FFFFFF) else Color(0x22000000)
    val sendButtonColor = if (isDarkTheme) Color(0xFF0A84FF) else Color(0xFF1E88E5)
    val headerContentColor = if (isDarkTheme) Color(0xFFE3F2FD) else Color.Black
    val avatarRingColor = if (isDarkTheme) Color(0xFF2E3A46) else Color(0xFF87CEEB)

    /* ---------------- AVATAR ---------------- */
    // Use mutable state for preferences so they can be updated when fresh data arrives
    var currentMatchedPrefs by remember { mutableStateOf(matchedUserPrefs) }
    var presenceStatus by remember {
        mutableStateOf(if (matchedUserPrefs.isOnline) "online" else "offline")
    }
    var lastSeenTime by remember { mutableStateOf(matchedUserPrefs.lastSeen) }
    
    // Fetch fresh preferences in background when screen opens
    LaunchedEffect(matchedUserGmail) {
        try {
            val response = NetworkClient.api.getPreferences(matchedUserGmail)
            if (response.isSuccessful && response.body() != null) {
                val freshPrefs = response.body()!!
                currentMatchedPrefs = Preferences(
                    romanceMin = freshPrefs.romanceRange?.min?.toFloat() ?: currentMatchedPrefs.romanceMin,
                    romanceMax = freshPrefs.romanceRange?.max?.toFloat() ?: currentMatchedPrefs.romanceMax,
                    gender = freshPrefs.gender ?: currentMatchedPrefs.gender,
                    isOnline = freshPrefs.isOnline ?: currentMatchedPrefs.isOnline,
                    lastSeen = freshPrefs.lastOnline ?: currentMatchedPrefs.lastSeen,
                    giftedMeARose = currentMatchedPrefs.giftedMeARose,
                    hasTakenBackRose = currentMatchedPrefs.hasTakenBackRose
                )
                // Update presence status and last seen time
                presenceStatus = if (freshPrefs.isOnline == true) "online" else "offline"
                lastSeenTime = freshPrefs.lastOnline ?: lastSeenTime
                Log.d("KeyboardProofScreen", "Fresh prefs loaded for $matchedUserGmail: isOnline=${freshPrefs.isOnline}, lastOnline=${freshPrefs.lastOnline}")
            }
        } catch (e: Exception) {
            Log.e("KeyboardProofScreen", "Failed to fetch fresh prefs for $matchedUserGmail", e)
        }
    }
    
    val emotion = remember(currentMatchedPrefs.romanceMin, currentMatchedPrefs.romanceMax) {
        romanceRangeToEmotion(currentMatchedPrefs.romanceMin, currentMatchedPrefs.romanceMax)
    }
    val avatarResName =
            if (currentMatchedPrefs.gender == "female") "female_exp$emotion" else "male_exp$emotion"
    val avatarResId = remember(avatarResName) {
        context.resources.getIdentifier(avatarResName, "raw", context.packageName)
    }
    val avatarUri = remember(avatarResId) {
        if (avatarResId != 0) Uri.parse("android.resource://${context.packageName}/$avatarResId")
        else Uri.parse("android.resource://${context.packageName}/${R.raw.male_exp1}")
    }

    /* ---------------- BACKGROUND VIDEO ---------------- */
    // Logic to pause background video when other media is playing or camera is open
    // to prevent it from overlaying or conflicting
    val isMediaOverlayActive =
            fullScreenVideoUri != null ||
                    fullScreenImageUri != null ||
                    showFullScreenCamera ||
                    capturedImageUri != null ||
                    showAdvancedMediaPicker

    val exoPlayer =
            remember(isDarkTheme) {
                ExoPlayer.Builder(context).build().apply {
                    val videoUri =
                            if (isDarkTheme)
                                    Uri.parse(
                                            "android.resource://${context.packageName}/${R.raw.night}"
                                    )
                            else
                                    Uri.parse(
                                            "android.resource://${context.packageName}/${R.raw.cloud}"
                                    )
                    setMediaItem(MediaItem.fromUri(videoUri))
                    repeatMode = Player.REPEAT_MODE_ONE
                    volume = 0f
                    playWhenReady = true
                    prepare()
                }
            }

    LaunchedEffect(isMediaOverlayActive) {
        if (isMediaOverlayActive) {
            exoPlayer.pause()
            // Hide keyboard when entering fullscreen media mode
            keyboardController?.hide()
        } else {
            exoPlayer.play()
        }
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    /* ---------------- KEYBOARD VISIBILITY ---------------- */
    val isKeyboardVisible by rememberKeyboardVisible()

    /* ---------------- AUTO SCROLL: NEW MESSAGES ---------------- */

    /* ---------------- AUTO SCROLL: KEYBOARD OPEN ---------------- */
    LaunchedEffect(isKeyboardVisible) {
        if (isKeyboardVisible && messages.isNotEmpty()) {
            snapshotFlow { listState.layoutInfo.totalItemsCount }.filter { it > 0 }.first()

            var stableCount = 0
            var previousHeight = 0
            while (stableCount < 2) {
                delay(25)
                val currentHeight = listState.layoutInfo.viewportSize.height
                if (currentHeight == previousHeight && currentHeight > 0) stableCount++
                else stableCount = 0
                previousHeight = currentHeight
            }

            // Animate scroll to the last message for smoother handling of dynamic content (e.g.,
            // video thumbnails)
            // Adding a short delay ensures layout has settled
            delay(100)
            // In reverse layout, Index 0 is the bottom (newest message).
            listState.animateScrollToItem(0)
        }
    }

    /* ---------------- WEBSOCKET EVENTS ---------------- */
    LaunchedEffect(Unit) {
        WebSocketManager.events.collect { event ->
            when (event) {
                is WebSocketEvent.NewMessage -> {
                    val msg = event.message
                    if (msg.from == matchedUserGmail && msg.to == localGmail) {
                        messages.upsert(msg.copy(status = MessageStatus.Delivered))
                        // Update conversation list — unreadCount = 0 since user is actively in chat
                        val preview = when {
                            msg.text.isNotBlank() -> msg.text
                            msg.mediaType == "IMAGE" -> "📷 Photo"
                            msg.mediaType == "VIDEO" -> "🎥 Video"
                            msg.audioUri != null -> "🎵 Voice message"
                            else -> "Message"
                        }
                        ConversationRepository.upsert(
                            myEmail = localGmail,
                            peerEmail = matchedUserGmail,
                            peerUsername = matchedUser.username.ifBlank { matchedUserGmail.substringBefore('@') },
                            preview = preview,
                            timestamp = msg.timestamp,
                            isIncoming = false, // user is in chat, don't increment unread
                            peerGender = matchedUserPrefs.gender,
                            peerRomanceMin = matchedUserPrefs.romanceMin,
                            peerRomanceMax = matchedUserPrefs.romanceMax
                        )
                    }
                }
                is WebSocketEvent.MessageSentAck -> {
                    val index = messages.indexOfFirst { it.id == event.messageId }
                    if (index != -1) {
                        // Update to Sending (single tick) when server confirms receipt
                        messages[index] = messages[index].copy(status = MessageStatus.Sending)
                    }
                }
                is WebSocketEvent.DeliveryAck -> {
                    Log.e("ChatWebSocket", "-> Message delivered")
                    val index = messages.indexOfFirst { it.id == event.messageId }
                    Log.e("ChatWebSocket", "-> Message delivered $index")

                    if (index != -1) {
                        // Only update to Delivered if it's not already Read
                        if (messages[index].status != MessageStatus.Read) {
                            messages[index] = messages[index].copy(status = MessageStatus.Delivered)
                        }
                    }
                }
                is WebSocketEvent.MessageReadAck -> {
                    Log.e("ChatWebSocket", "-> Message read")
                    val index = messages.indexOfFirst { it.id == event.messageId }
                    if (index != -1) {
                        messages[index] = messages[index].copy(status = MessageStatus.Read)
                    }
                }
                is WebSocketEvent.DeliveryFailed -> {
                    Log.e("ChatWebSocket", "-> Message failed")
                    val index = messages.indexOfFirst { it.id == event.messageId }
                    if (index != -1) {
                        messages[index] = messages[index].copy(status = MessageStatus.Failed)
                    }
                }
                is WebSocketEvent.PeerPresence -> {
                    if (event.from == matchedUserGmail) {
                        presenceStatus = event.status
                        lastSeenTime = event.lastSeen
                    }
                }
                is WebSocketEvent.ChatOpen -> {
                    if (event.from == matchedUserGmail) {
                        Log.e("SparkOverlay", "Received chat_open from $matchedUserGmail")
                        peerChatOpenReceived = true
                    }
                }
                is WebSocketEvent.ChatClose -> {
                    if (event.from == matchedUserGmail) {
                        Log.e("SparkOverlay", "Received chat_close from $matchedUserGmail")
                        peerChatOpenReceived = false
                        mutualChatStartTime = null
                        Log.e("SparkOverlay", "Mutual chat session ended - timer reset")
                    }
                }
                is WebSocketEvent.SparkLeft -> {
                    if (event.from == matchedUserGmail) {
                        Log.e("SparkSwipe", "Received spark_left from $matchedUserGmail (senderUserId: ${event.senderUserId})")
                        peerSparkChoice = false
                        
                        // If I already swiped, determine the result
                        if (mySparkChoice != null) {
                            Log.e("SparkSwipe", "Both users have swiped. My choice: $mySparkChoice, Peer choice: false")
                            showThunderGif = false
                            // Peer swiped left, so always show miss/fail
                            showMissGif = true
                        }
                    }
                }
                is WebSocketEvent.SparkRight -> {
                    if (event.from == matchedUserGmail) {
                        Log.e("SparkSwipe", "Received spark_right from $matchedUserGmail (senderUserId: ${event.senderUserId})")
                        peerSparkChoice = true

                        // Peer swiped right on me → my own spark count increases
                        val currentSelfSparks = prefs.getInt("sparks_self", 0)
                        val newSelfSparks = currentSelfSparks + 1
                        prefs.edit().putInt("sparks_self", newSelfSparks).apply()
                        Log.e("SparkSwipe", "✅ My own sparks incremented to $newSelfSparks (peer swiped right on me)")

                        // If I already swiped, determine the result
                        if (mySparkChoice != null) {
                            Log.e("SparkSwipe", "Both users have swiped. My choice: $mySparkChoice, Peer choice: true")
                            showThunderGif = false
                            if (mySparkChoice == true) {
                                // Both swiped right - mutual!
                                Log.e("SparkSwipe", "MUTUAL MATCH! Both swiped right")
                                showMutualGif = true
                            } else {
                                // I swiped left, peer swiped right - miss
                                Log.e("SparkSwipe", "MISS - I swiped left, peer swiped right")
                                showMissGif = true
                            }
                        }
                    }
                }
                is WebSocketEvent.RoseGifted -> {
                    Log.e("RoseEvent", "!!! RECEIVED rose_gifted from: ${event.from} (matchedUser: $matchedUserGmail)")
                    if (event.from == matchedUserGmail) {
                        Log.e("RoseEvent", "✅ rose_gifted matches current chat → showing heart icon on profile")
                        showProfileIcon = "heart"
                    }
                }
                is WebSocketEvent.RoseTakenBack -> {
                    Log.e("RoseEvent", "!!! RECEIVED rose_taken_back from: ${event.from} (matchedUser: $matchedUserGmail)")
                    if (event.from == matchedUserGmail) {
                        Log.e("RoseEvent", "✅ rose_taken_back matches current chat → showing heartbreak icon on profile")
                        showProfileIcon = "heartbreak"
                    }
                }
                is WebSocketEvent.SparkTrigger -> {
                    Log.e("SparkOverlay", "!!! RECEIVED spark_trigger from: ${event.from} (matchedUser: $matchedUserGmail)")
                    if (event.from == matchedUserGmail && !sparkOverlayShown && !alreadySparked) {
                        Log.e("SparkOverlay", "[RECEIVER] ✅ spark_trigger matches current chat → showing spark overlay NOW")
                        alreadySparked = true
                        showThunderGif = true
                        sparkOverlayShown = true
                    }
                }
                is WebSocketEvent.WarningNotification -> {
                    // Warning notifications are handled globally in MainActivity
                    // No action needed here
                }
                is WebSocketEvent.SuspensionNotification -> {
                    // Suspension notifications are handled globally in MainActivity
                    // No action needed here
                }
                is WebSocketEvent.BanNotification -> {
                    // Ban notifications are handled globally in MainActivity
                    // No action needed here
                }
            }
        }
    }

    LaunchedEffect(messages.size) {
        val ids = messages.map { it.id }
        if (ids.size != ids.distinct().size) {
            Log.e("ChatBUG", "DUPLICATE MESSAGE IDS DETECTED: $ids")
        }
    }

    // Visibility-based read acknowledgements
    // Sends read acks for incoming TEXT messages when they become visible on screen
    // Works across app restarts because message status is persisted in Room DB
    // NOTE: Pauses during fullscreen media viewing - acks sent when exiting fullscreen
    // NOTE: Skips audio/image/video/gif - those get read acks on play/download complete
    LaunchedEffect(listState, messages.size, isMediaOverlayActive, isAppInForeground) {
        // Skip read acks while in fullscreen media mode or app is in background
        if (isMediaOverlayActive || !isAppInForeground) return@LaunchedEffect

        snapshotFlow { listState.layoutInfo.visibleItemsInfo }.collect { visibleItems ->
            visibleItems.forEach { visibleItem ->
                // Convert from lazy list index to actual message index
                // In reverseLayout, item at visibleItem.index maps to messages[messages.size - 1 -
                // index]
                val messageIndex = messages.size - 1 - visibleItem.index
                if (messageIndex in messages.indices) {
                    val msg = messages[messageIndex]
                    // Only process incoming TEXT messages that haven't been read yet
                    // Skip audio (read ack on play), image/video/gif (read ack on download
                    // complete)
                    val isMediaMessage = msg.audioUri != null || msg.mediaType != null
                    if (msg.from != localGmail &&
                                    msg.status != MessageStatus.Read &&
                                    !isMediaMessage
                    ) {
                        Log.d("ReadAck", "Sending read ack for visible text message: ${msg.id}")
                        // Send read ack to server
                        WebSocketManager.sendMessageReadAck(msg.id, msg.from)
                        // Update local state
                        messages[messageIndex] = msg.copy(status = MessageStatus.Read)
                        // Persist to Room DB
                        WebSocketManager.updateMessageStatus(msg.id, MessageStatus.Read)
                    }
                }
            }
        }
    }

    // Fetch gallery images
    LaunchedEffect(showAdvancedMediaPicker) {
        if (showAdvancedMediaPicker) {
            val hasPermission =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.READ_MEDIA_IMAGES
                        ) == PackageManager.PERMISSION_GRANTED
                    } else {
                        ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.READ_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED
                    }

            val fetchImages = {
                val projection =
                        arrayOf(
                                MediaStore.Files.FileColumns._ID,
                                MediaStore.Files.FileColumns.MEDIA_TYPE,
                                MediaStore.Video.Media.DURATION
                        )
                val selection =
                        (MediaStore.Files.FileColumns.MEDIA_TYPE +
                                "=" +
                                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE +
                                " OR " +
                                MediaStore.Files.FileColumns.MEDIA_TYPE +
                                "=" +
                                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

                context.contentResolver.query(
                                MediaStore.Files.getContentUri("external"),
                                projection,
                                selection,
                                null,
                                sortOrder
                        )
                        ?.use { cursor ->
                            val idColumn =
                                    cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                            val typeColumn =
                                    cursor.getColumnIndexOrThrow(
                                            MediaStore.Files.FileColumns.MEDIA_TYPE
                                    )
                            val durationColumn =
                                    cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

                            galleryImages.clear()
                            var count = 0
                            while (cursor.moveToNext() && count < 60) {
                                val id = cursor.getLong(idColumn)
                                val type = cursor.getInt(typeColumn)
                                val duration = cursor.getLong(durationColumn)

                                val contentUri =
                                        if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                                            ContentUris.withAppendedId(
                                                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                                    id
                                            )
                                        } else {
                                            ContentUris.withAppendedId(
                                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                                    id
                                            )
                                        }

                                // We store all, but will filter/toast on selection for videos > 5s
                                galleryImages.add(contentUri)
                                count++
                            }
                        }
            }

            if (hasPermission) {
                fetchImages()
            } else {
                (context as MainActivity).requestGalleryPermissionAndRun { fetchImages() }
            }
        }
    }

    // Launchers for media picking
    val galleryLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                isMediaProcessing = false
                uri?.let {
                    val type =
                            if (it.toString().contains("video") || it.toString().contains("mp4"))
                                    "VIDEO"
                            else "IMAGE"
                    val localId = "media-${UUID.randomUUID()}"

                    // Determine content type based on media type
                    val contentType = if (type == "VIDEO") "video/mp4" else "image/jpeg"

                    val newMsg =
                            ChatMessage(
                                    id = localId,
                                    from = localGmail,
                                    to = matchedUserGmail,
                                    text =
                                            if (type == "VIDEO") "[Video Message]"
                                            else "[Photo Message]",
                                    mediaUri = it.toString(),
                                    mediaType = type,
                                    status = MessageStatus.Pending
                            )
                    messages.upsert(newMsg)
                    WebSocketManager.sendMedia(matchedUserGmail, it, contentType, localId)
                    hasMessageBeenSent = true
                }
            }

    val cameraVideoLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
                if (success) {
                    currentAudioFile?.let { file
                        -> // Using currentAudioFile as a temp for video too for now, or create new
                        val uri =
                                FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                )
                        val localId = "media-${UUID.randomUUID()}"
                        val newMsg =
                                ChatMessage(
                                        id = localId,
                                        from = localGmail,
                                        to = matchedUserGmail,
                                        text = "[Video Message]",
                                        mediaUri = uri.toString(),
                                        mediaType = "VIDEO",
                                        status = MessageStatus.Pending
                                )
                        messages.upsert(newMsg)
                        WebSocketManager.sendMedia(matchedUserGmail, uri, "video/mp4", localId)
                        hasMessageBeenSent = true
                    }
                }
            }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            val startTime = System.currentTimeMillis()
            while (isActive) {
                recordingDuration = (System.currentTimeMillis() - startTime) / 1000

                // Polling amplitudes for waveform
                try {
                    val amp = mediaRecorder?.maxAmplitude ?: 0
                    // Normalize to 0.0 - 1.0 (maxAmplitude is roughly 32767)
                    recordingAmplitudes.add(amp.toFloat() / 32767f)
                } catch (e: Exception) {
                    Log.e("AudioRecorder", "Failed to poll amplitude", e)
                }

                delay(100)
            }
        } else {
            recordingDuration = 0L
        }
    }

    /* ---------------- UI ---------------- */
    CompositionLocalProvider(LocalImageLoader provides imageLoader) {
        Box(Modifier.fillMaxSize()) {
            if (!isMediaOverlayActive) {
                AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = {
                            PlayerView(it).apply {
                                player = exoPlayer
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                            }
                        }
                )
            } else {
                // Shielding layer to ensure background is strictly black during camera load
                Box(Modifier.fillMaxSize().background(Color.Black))
            }

            Scaffold(
                    modifier = Modifier.fillMaxSize().imePadding(),
                    containerColor = Color.Transparent,
                    topBar = {
                        Surface(
                                modifier = Modifier.fillMaxWidth().statusBarsPadding().zIndex(1f),
                                color = Color.Transparent
                        ) {
                            Row(
                                    modifier =
                                            Modifier.fillMaxWidth()
                                                    .height(56.dp)
                                                    .padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                        
                                        // Call skip or accept endpoint only for new matches
                                        if (isNewMatch) {
                                            scope.launch {
                                                try {
                                                    val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                                                    val myEmail = prefs.getString("user_email", "") ?: ""
                                                    
                                                    if (hasMessageBeenSent) {
                                                        // Accept match if message was sent
                                                        Log.e("KeyboardProofScreen", "[UI BACK] Calling ACCEPT match API: myEmail=$myEmail, candGmail=$matchedUserGmail")
                                                        val response = NetworkClient.api.acceptMatch(myEmail, matchedUserGmail)
                                                        Log.e("KeyboardProofScreen", "[UI BACK] Accept match response: isSuccessful=${response.isSuccessful}, code=${response.code()}, message=${response.message()}")
                                                        if (response.isSuccessful) {
                                                            Log.e("KeyboardProofScreen", "[UI BACK] Accept match SUCCESS for $matchedUserGmail")
                                                        } else {
                                                            Log.e("KeyboardProofScreen", "[UI BACK] Accept match FAILED: ${response.errorBody()?.string()}")
                                                        }
                                                    } else {
                                                        // Skip match if no message was sent
                                                        Log.e("KeyboardProofScreen", "[UI BACK] Calling SKIP match API: myEmail=$myEmail, candGmail=$matchedUserGmail")
                                                        val response = NetworkClient.api.skipMatch(myEmail, matchedUserGmail)
                                                        Log.e("KeyboardProofScreen", "[UI BACK] Skip match response: isSuccessful=${response.isSuccessful}, code=${response.code()}, message=${response.message()}")
                                                        if (response.isSuccessful) {
                                                            Log.e("KeyboardProofScreen", "[UI BACK] Skip match SUCCESS for $matchedUserGmail")
                                                        } else {
                                                            Log.e("KeyboardProofScreen", "[UI BACK] Skip match FAILED: ${response.errorBody()?.string()}")
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    Log.e("KeyboardProofScreen", "[UI BACK] EXCEPTION calling match endpoint: ${e.message}", e)
                                                }
                                            }
                                        } else {
                                            Log.e("KeyboardProofScreen", "[UI BACK] Skipping match API call - opened from conversation list")
                                        }
                                        
                                        onBack()
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(Icons.Default.ArrowBack, "Back", tint = headerContentColor)
                                }
                                Spacer(Modifier.width(8.dp))

                                Box(modifier = Modifier.size(46.dp)) {
                                    Surface(
                                            shape = CircleShape,
                                            color = avatarRingColor,
                                            border = BorderStroke(2.dp, Color.White),
                                            modifier = Modifier.size(46.dp)
                                    ) {
                                        Image(
                                                painter =
                                                        rememberAsyncImagePainter(
                                                                avatarUri,
                                                                imageLoader = staticImageLoader
                                                        ),
                                                contentDescription = null,
                                                modifier = Modifier.padding(2.dp).clip(CircleShape),
                                                contentScale = ContentScale.Crop
                                        )
                                    }
                                    
                                    // Small heart or heartbreak icon overlay
                                    if (showProfileIcon != null) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .size(20.dp)
                                                .offset(y = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (showProfileIcon == "heart") {
                                                Image(
                                                    painter = rememberAsyncImagePainter(
                                                        R.drawable.heart,
                                                        imageLoader = imageLoader
                                                    ),
                                                    contentDescription = "Rose Sent",
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Fit
                                                )
                                            } else {
                                                Text(
                                                    text = "🥀",
                                                    fontSize = 14.sp,
                                                    lineHeight = 14.sp
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.width(12.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    // Username with Roses and Sparks on same line
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // For testing: uncomment the line below and comment the line after it
                                   //   val displayUsername = "TestUser99"
                                        val displayUsername = matchedUser.username
                                        
                                        Text(
                                                text = displayUsername,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 16.sp,
                                                color = headerContentColor
                                        )
                                        
                                        Spacer(modifier = Modifier.width(8.dp))
                                        
                                        // Roses count
                                        Text(
                                                text = formatCount(roses),
                                                style = TextStyle(
                                                        color = Color(0xFFFF1053),
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        shadow = Shadow(
                                                                color = Color.Black.copy(alpha = 0.5f),
                                                                offset = Offset(2f, 2f),
                                                                blurRadius = 2f
                                                        )
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Image(
                                                painter = rememberAsyncImagePainter(
                                                        R.drawable.roseg,
                                                        imageLoader = imageLoader
                                                ),
                                                contentDescription = "Roses",
                                                modifier = Modifier.size(18.dp)
                                        )
                                        
                                        Spacer(modifier = Modifier.width(12.dp))
                                        
                                        // Sparks count
                                        Text(
                                                text = formatCount(sparks),
                                                style = TextStyle(
                                                        color = Color(0xFFFFD700),
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        shadow = Shadow(
                                                                color = Color.Black.copy(alpha = 0.5f),
                                                                offset = Offset(2f, 2f),
                                                                blurRadius = 2f
                                                        )
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Image(
                                                painter = rememberAsyncImagePainter(
                                                        R.drawable.sparkling,
                                                        imageLoader = imageLoader
                                                ),
                                                contentDescription = "Sparks",
                                                modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    
                                    val presenceText =
                                            if (presenceStatus == "online") {
                                                "Online"
                                            } else if (lastSeenTime > 0) {
                                                val now = System.currentTimeMillis()
                                                val diffMillis = now - lastSeenTime
                                                val diffDays = diffMillis / (1000 * 60 * 60 * 24)

                                                if (diffDays >= 1) {
                                                    "Last seen $diffDays day${if (diffDays > 1L) "s" else ""} ago"
                                                } else {
                                                    "Last seen ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(lastSeenTime))}"
                                                }
                                            } else {
                                                ""
                                            }
                                    Text(
                                            text = presenceText,
                                            fontWeight = FontWeight.Normal,
                                            fontSize = 12.sp,
                                            color = headerContentColor.copy(alpha = 0.7f)
                                    )
                                }
                                
                                Spacer(Modifier.weight(1f))
                                
                                // Three-dot menu
                                Box {
                                    IconButton(
                                        onClick = { showMenu = true },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.MoreVert,
                                            contentDescription = "More options",
                                            tint = headerContentColor
                                        )
                                    }
                                    
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Delete Chat") },
                                            onClick = {
                                                showMenu = false
                                                showDeleteConfirmDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (isUserBlocked) "Unblock User" else "Block User") },
                                            onClick = {
                                                showMenu = false
                                                scope.launch {
                                                    try {
                                                        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                                                        val myEmail = prefs.getString("user_email", "") ?: ""
                                                        val myUserId = myEmail.substringBefore("@")
                                                        val targetUserId = matchedUserGmail.substringBefore("@")
                                                        
                                                        val response = if (isUserBlocked) {
                                                            NetworkClient.api.unblockUser(myUserId, targetUserId)
                                                        } else {
                                                            NetworkClient.api.blockUser(myUserId, targetUserId)
                                                        }
                                                        
                                                        if (response.isSuccessful) {
                                                            isUserBlocked = !isUserBlocked
                                                            // Persist blocked status to SharedPreferences
                                                            userPrefs.edit()
                                                                .putBoolean("blocked_$matchedUserGmail", isUserBlocked)
                                                                .apply()
                                                            
                                                            val message = if (isUserBlocked) "User blocked successfully" else "User unblocked successfully"
                                                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            Toast.makeText(context, "Failed to ${if (isUserBlocked) "unblock" else "block"} user", Toast.LENGTH_SHORT).show()
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.e("KeyboardProofScreen", "Error blocking/unblocking user: ${e.message}")
                                                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Report User") },
                                            onClick = {
                                                showMenu = false
                                                showReportDialog = true
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
            ) { padding ->
                Box(
                        modifier =
                                Modifier.fillMaxSize()
                                        .padding(padding)
                                        .pointerInput(Unit) {
                                            detectHorizontalDragGestures { _, dragAmount ->
                                                if (dragAmount < -100) {
                                                    showMissGif = true
                                                }
                                            }
                                        }
                ) {
                    LazyColumn(
                            state = listState,
                            reverseLayout = true,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                            contentPadding = PaddingValues(bottom = 60.dp, top = 8.dp)
                    ) {
                        items(
                                count = chatItems.size,
                                key = { index ->
                                    when (val item = chatItems[index]) {
                                        is ChatHistoryItem.Message -> item.message.id
                                        is ChatHistoryItem.DateHeader -> "header_${item.timestamp}"
                                    }
                                }
                        ) { index ->
                            when (val item = chatItems[index]) {
                                is ChatHistoryItem.Message -> {
                                    val msg = item.message
                                    KeyboardMessageBubble(
                                            message = msg,
                                            isMe = msg.from == localGmail,
                                            incomingBubbleColor = incomingBubbleColor,
                                            outgoingBubbleColor = outgoingBubbleColor,
                                            messageTextColor = messageTextColor,
                                            onVideoClick = { fullScreenVideoUri = it },
                                            onImageClick = { fullScreenImageUri = it },
                                            onStatusUpdate = { id, status ->
                                                val idx = messages.indexOfFirst { it.id == id }
                                                if (idx != -1) {
                                                    messages[idx] =
                                                            messages[idx].copy(status = status)
                                                }
                                                WebSocketManager.updateMessageStatus(id, status)
                                            }
                                    )
                                }
                                is ChatHistoryItem.DateHeader -> {
                                    Box(
                                            modifier =
                                                    Modifier.fillMaxWidth()
                                                            .padding(vertical = 12.dp),
                                            contentAlignment = Alignment.Center
                                    ) {
                                        Surface(
                                                color =
                                                        if (isDarkTheme) {
                                                            Color(0xCC000000)
                                                                    .compositeOver(
                                                                            Color.Gray.copy(
                                                                                    alpha = 0.2f
                                                                            )
                                                                    )
                                                        } else {
                                                            Color(0xCCF0F0F0)
                                                                    .compositeOver(
                                                                            Color.White.copy(
                                                                                    alpha = 0.9f
                                                                            )
                                                                    )
                                                        },
                                                shape = RoundedCornerShape(12.dp),
                                                border =
                                                        if (!isDarkTheme)
                                                                BorderStroke(
                                                                        0.5.dp,
                                                                        Color.LightGray.copy(
                                                                                alpha = 0.5f
                                                                        )
                                                                )
                                                        else null,
                                                modifier =
                                                        Modifier.padding(
                                                                horizontal = 16.dp,
                                                                vertical = 4.dp
                                                        )
                                        ) {
                                            Text(
                                                    text = item.date,
                                                    color =
                                                            if (isDarkTheme)
                                                                    Color.White.copy(alpha = 0.9f)
                                                            else Color.Black.copy(alpha = 0.8f),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    modifier =
                                                            Modifier.padding(
                                                                    horizontal = 12.dp,
                                                                    vertical = 6.dp
                                                            )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Floating Date Banner
                    AnimatedVisibility(
                            visible = showFloatingDateBanner && floatingDate != null,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).zIndex(1f)
                    ) {
                        Box(contentAlignment = Alignment.TopCenter) {
                            floatingDate?.let { date ->
                                Surface(
                                        color =
                                                if (isDarkTheme) {
                                                    Color(0xCC000000)
                                                            .compositeOver(
                                                                    Color.Gray.copy(alpha = 0.2f)
                                                            )
                                                } else {
                                                    Color(0xCCF0F0F0)
                                                            .compositeOver(
                                                                    Color.White.copy(alpha = 0.9f)
                                                            )
                                                },
                                        shape = RoundedCornerShape(12.dp),
                                        border =
                                                if (!isDarkTheme)
                                                        BorderStroke(
                                                                0.5.dp,
                                                                Color.LightGray.copy(alpha = 0.5f)
                                                        )
                                                else null,
                                        modifier =
                                                Modifier.padding(
                                                        horizontal = 16.dp,
                                                        vertical = 4.dp
                                                )
                                ) {
                                    Text(
                                            text = date,
                                            color =
                                                    if (isDarkTheme) Color.White.copy(alpha = 0.9f)
                                                    else Color.Black.copy(alpha = 0.8f),
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier =
                                                    Modifier.padding(
                                                            horizontal = 12.dp,
                                                            vertical = 6.dp
                                                    )
                                    )
                                }
                            }
                        }
                    }

                    LaunchedEffect(messages.size) {
                        if (messages.isNotEmpty()) {
                            // With reverseLayout, the "bottom" is index 0.
                            // We scroll to 0 to show the newest message.
                            listState.animateScrollToItem(0)
                        }
                    }

                    Row(
                            modifier =
                                    Modifier.align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .padding(12.dp),
                            verticalAlignment = Alignment.Bottom
                    ) {
                        Surface(
                                modifier = Modifier.weight(1f).heightIn(54.dp, 220.dp),
                                shape = RoundedCornerShape(24.dp),
                                color = inputBackgroundColor,
                                border = BorderStroke(1.dp, inputBorderColor)
                        ) {
                            if (isRecording) {
                                Row(
                                        modifier =
                                                Modifier.fillMaxWidth()
                                                        .height(54.dp)
                                                        .padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.End
                                ) {
                                    // Timer text during recording inside the input bar
                                    val minutes = recordingDuration / 60
                                    val seconds = recordingDuration % 60
                                    val timeString = String.format("%02d:%02d", minutes, seconds)

                                    Text(
                                            text = "$timeString   Slide to cancel <<< ",
                                            color = messageTextColor,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Medium
                                    )
                                }
                            } else {
                                // Replaced BasicTextField with AndroidView backed EditText for GIF
                                // support
                                Row(
                                        modifier =
                                                Modifier.fillMaxWidth()
                                                        .padding(
                                                                horizontal = 14.dp,
                                                                vertical = 6.dp
                                                        ),
                                        verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                            onClick = {
                                                keyboardController?.hide()
                                                focusManager.clearFocus()
                                                showAdvancedMediaPicker = true
                                            },
                                            modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                                Icons.Default.AttachFile,
                                                contentDescription = "Attach",
                                                tint = Color.Gray
                                        )
                                    }

                                    Spacer(Modifier.width(8.dp))

                                    AndroidView(
                                            factory = { ctx ->
                                                object : AppCompatEditText(ctx) {
                                                            override fun onCreateInputConnection(
                                                                    outAttrs: EditorInfo
                                                            ): InputConnection? {
                                                                val ic =
                                                                        super.onCreateInputConnection(
                                                                                outAttrs
                                                                        )
                                                                EditorInfoCompat
                                                                        .setContentMimeTypes(
                                                                                outAttrs,
                                                                                arrayOf(
                                                                                        "image/gif",
                                                                                        "image/png",
                                                                                        "image/webp",
                                                                                        "video/mp4",
                                                                                        "image/jpeg"
                                                                                )
                                                                        )
                                                                return ic
                                                            }
                                                        }
                                                        .apply {
                                                            setBackground(null)
                                                            setHint("Message")
                                                            setHintTextColor(
                                                                    android.graphics.Color.GRAY
                                                            )
                                                            setTextColor(messageTextColor.toArgb())
                                                            setTextSize(
                                                                    TypedValue.COMPLEX_UNIT_SP,
                                                                    16f
                                                            )
                                                            inputType =
                                                                    android.text.InputType
                                                                            .TYPE_CLASS_TEXT or
                                                                            android.text.InputType
                                                                                    .TYPE_TEXT_FLAG_MULTI_LINE or
                                                                            android.text.InputType
                                                                                    .TYPE_TEXT_FLAG_CAP_SENTENCES
                                                            maxLines = 6

                                                            addTextChangedListener(
                                                                    object : TextWatcher {
                                                                        override fun beforeTextChanged(
                                                                                s: CharSequence?,
                                                                                start: Int,
                                                                                count: Int,
                                                                                after: Int
                                                                        ) {}
                                                                        override fun onTextChanged(
                                                                                s: CharSequence?,
                                                                                start: Int,
                                                                                before: Int,
                                                                                count: Int
                                                                        ) {}
                                                                        override fun afterTextChanged(
                                                                                s: Editable?
                                                                        ) {
                                                                            val newText =
                                                                                    s?.toString()
                                                                                            ?: ""
                                                                            if (newText != input) {
                                                                                input = newText
                                                                            }
                                                                        }
                                                                    }
                                                            )

                                                            ViewCompat.setOnReceiveContentListener(
                                                                    this,
                                                                    arrayOf(
                                                                            "image/gif",
                                                                            "image/png",
                                                                            "image/webp",
                                                                            "video/mp4",
                                                                            "image/jpeg"
                                                                    ),
                                                                    object :
                                                                            androidx.core.view.OnReceiveContentListener {
                                                                        override fun onReceiveContent(
                                                                                view:
                                                                                        android.view.View,
                                                                                payload:
                                                                                        ContentInfoCompat
                                                                        ): ContentInfoCompat? {
                                                                            val split =
                                                                                    payload
                                                                                            .partition {
                                                                                                    item
                                                                                                ->
                                                                                                item.uri !=
                                                                                                        null
                                                                                            }
                                                                            val uriContent =
                                                                                    split.first

                                                                            if (uriContent != null
                                                                            ) {
                                                                                for (i in
                                                                                        0 until
                                                                                                uriContent
                                                                                                        .clip
                                                                                                        .itemCount) {
                                                                                    val item =
                                                                                            uriContent
                                                                                                    .clip
                                                                                                    .getItemAt(
                                                                                                            i
                                                                                                    )
                                                                                    val uri =
                                                                                            item.uri
                                                                                    if (uri != null
                                                                                    ) {
                                                                                        val mimeType =
                                                                                                context.contentResolver
                                                                                                        .getType(
                                                                                                                uri
                                                                                                        )
                                                                                        if (mimeType !=
                                                                                                        null
                                                                                        ) {
                                                                                            val type =
                                                                                                    when {
                                                                                                        mimeType ==
                                                                                                                "image/gif" ->
                                                                                                                "GIF"
                                                                                                        mimeType.startsWith(
                                                                                                                "image/"
                                                                                                        ) ->
                                                                                                                "IMAGE"
                                                                                                        mimeType.startsWith(
                                                                                                                "video/"
                                                                                                        ) ->
                                                                                                                "VIDEO"
                                                                                                        else ->
                                                                                                                null
                                                                                                    }

                                                                                            if (type !=
                                                                                                            null
                                                                                            ) {
                                                                                                val localId =
                                                                                                        "media-${java.util.UUID.randomUUID()}"
                                                                                                val newMsg =
                                                                                                        ChatMessage(
                                                                                                                id =
                                                                                                                        localId,
                                                                                                                from =
                                                                                                                        localGmail,
                                                                                                                to =
                                                                                                                        matchedUserGmail,
                                                                                                                text =
                                                                                                                        when (type
                                                                                                                        ) {
                                                                                                                            "VIDEO" ->
                                                                                                                                    "[Video Message]"
                                                                                                                            "GIF" ->
                                                                                                                                    "[GIF Message]"
                                                                                                                            else ->
                                                                                                                                    "[Photo Message]"
                                                                                                                        },
                                                                                                                mediaUri =
                                                                                                                        uri.toString(),
                                                                                                                mediaType =
                                                                                                                        type,
                                                                                                                status =
                                                                                                                        MessageStatus
                                                                                                                                .Pending
                                                                                                        )
                                                                                                messages.upsert(
                                                                                                        newMsg
                                                                                                )
                                                                                                WebSocketManager
                                                                                                        .sendMedia(
                                                                                                                matchedUserGmail,
                                                                                                                uri,
                                                                                                                mimeType,
                                                                                                                localId
                                                                                                        )
                                                                                            }
                                                                                        }
                                                                                    }
                                                                                }
                                                                            }
                                                                            return split.second
                                                                        }
                                                                    }
                                                            )
                                                        }
                                            },
                                            modifier = Modifier.weight(1f),
                                            update = { view ->
                                                if (view.text.toString() != input) {
                                                    view.setText(input)
                                                    view.setSelection(view.text?.length ?: 0)
                                                }
                                            }
                                    )

                                    if (input.isEmpty() && !isKeyboardVisible) {
                                        Image(
                                            painter = rememberAsyncImagePainter(
                                                if (isRoseDead) R.drawable.roseff else R.drawable.roseg,
                                                imageLoader = imageLoader
                                            ),
                                            contentDescription = "Rose Hint",
                                            modifier = Modifier
                                                .padding(end = 4.dp)
                                                .size(42.dp)
                                                .clickable {
                                                    overlayModeIsDead = isRoseDead
                                                    showRoseOverlay = true
                                                }
                                        )
                                    }
                                }

                                // Removed old showAttachmentChoices Row
                            }
                        }

                        val sendMessage = {
                            val text = input.trim()
                            if (text.isNotBlank() &&
                                            localGmail.isNotEmpty() &&
                                            matchedUserGmail.isNotEmpty()
                            ) {
                                val localId = UUID.randomUUID().toString()
                                val newMsg =
                                        ChatMessage(
                                                id = localId,
                                                from = localGmail,
                                                to = matchedUserGmail,
                                                text = text,
                                                status = MessageStatus.Pending
                                        )
                                messages.upsert(newMsg)
                                input = ""
                                Log.e("from to!!!!!!!!!!!!", "${localGmail} to ${matchedUserGmail}")

                                WebSocketManager.sendMessage(matchedUserGmail, text, localId)
                                hasMessageBeenSent = true // Mark that a message has been sent
                                // Update conversation list so it appears when navigating back
                                ConversationRepository.upsert(
                                    myEmail = localGmail,
                                    peerEmail = matchedUserGmail,
                                    peerUsername = matchedUser.username.ifBlank { matchedUserGmail.substringBefore('@') },
                                    preview = text,
                                    timestamp = newMsg.timestamp,
                                    isIncoming = false,
                                    peerGender = matchedUserPrefs.gender,
                                    peerRomanceMin = matchedUserPrefs.romanceMin,
                                    peerRomanceMax = matchedUserPrefs.romanceMax
                                )
                            }
                        }

                        Spacer(Modifier.width(8.dp))
                        val activity = context as MainActivity

                        val micScale by
                                androidx.compose.animation.core.animateFloatAsState(
                                        targetValue = if (isRecording) 2.0f else 1.0f,
                                        label = "micScale"
                                )

                        if (input.isNotBlank()) {
                            Surface(
                                    modifier = Modifier.size(44.dp),
                                    shape = CircleShape,
                                    color = sendButtonColor
                            ) {
                                IconButton(onClick = sendMessage) {
                                    Icon(Icons.Default.Send, null, tint = Color.White)
                                }
                            }
                        } else {
                            Box(
                                    modifier =
                                            Modifier.size(48.dp).pointerInput(Unit) {
                                                awaitPointerEventScope {
                                                    while (true) {
                                                        val down =
                                                                awaitFirstDown(
                                                                        requireUnconsumed = false
                                                                )
                                                        // 1) ACTION DOWN: Start Recording
                                                        startX = down.position.x
                                                        cancelRecording = false

                                                        val hasPermission =
                                                                ContextCompat.checkSelfPermission(
                                                                        context,
                                                                        Manifest.permission
                                                                                .RECORD_AUDIO
                                                                ) ==
                                                                        PackageManager
                                                                                .PERMISSION_GRANTED

                                                        if (hasPermission) {
                                                            startRecording(context)
                                                            isRecording = true
                                                        } else {
                                                            activity.requestMicPermissionAndRun {
                                                                startRecording(context)
                                                                isRecording = true
                                                            }
                                                        }

                                                        var change = down
                                                        // 2) ACTION MOVE / DRAG
                                                        while (change.pressed &&
                                                                change.id == down.id) {
                                                            val currentX = change.position.x
                                                            // Check Swipe Left (threshold: 100px)
                                                            if (startX - currentX > 100) {
                                                                cancelRecording = true
                                                            } else {
                                                                cancelRecording = false
                                                            }

                                                            val event = awaitPointerEvent()
                                                            val nextChange =
                                                                    event.changes.firstOrNull {
                                                                        it.id == down.id
                                                                    }
                                                            if (nextChange != null) {
                                                                change = nextChange
                                                            } else {
                                                                break
                                                            }
                                                        }

                                                        // 3) ACTION UP (Released)
                                                        if (isRecording) {
                                                            if (cancelRecording) {
                                                                // Cancelled
                                                                try {
                                                                    mediaRecorder?.stop()
                                                                } catch (e: RuntimeException) {
                                                                    Log.e(
                                                                            "AudioRecorder",
                                                                            "Stop failed",
                                                                            e
                                                                    )
                                                                }
                                                                mediaRecorder?.release()
                                                                mediaRecorder = null
                                                                currentAudioFile?.delete()
                                                                recordingAmplitudes.clear()
                                                            } else {
                                                                // Send
                                                                stopRecordingAndSend(
                                                                        context,
                                                                        matchedUserGmail,
                                                                        messages,
                                                                        recordingAmplitudes.toList()
                                                                )
                                                                hasMessageBeenSent = true
                                                                recordingAmplitudes.clear()
                                                            }
                                                        }
                                                        isRecording = false
                                                        cancelRecording = false
                                                    }
                                                }
                                            },
                                    contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                        imageVector = Icons.Default.KeyboardVoice,
                                        contentDescription = "Record",
                                        tint =
                                                when {
                                                    cancelRecording -> Color.Red
                                                    isRecording -> Color(0xFFFF9800)
                                                    else -> sendButtonColor
                                                },
                                        modifier = Modifier.scale(micScale)
                                )
                            }
                        }
                    }
                }
            }

            if (isMediaProcessing) {
                ProcessingOverlay()
            }

            if (showAdvancedMediaPicker) {
                MediaPickerScreen(
                        galleryImages = galleryImages,
                        selectedImages = selectedGalleryImages.toSet(),
                        onClose = {
                            showAdvancedMediaPicker = false
                            selectedGalleryImages.clear()
                        },
                        onImageToggle = { uri ->
                            val isVideo = uri.toString().contains("video")
                            var isLong = false

                            if (isVideo) {
                                try {
                                    context.contentResolver.query(
                                                    uri,
                                                    arrayOf(MediaStore.Video.Media.DURATION),
                                                    null,
                                                    null,
                                                    null
                                            )
                                            ?.use { cursor ->
                                                if (cursor.moveToFirst()) {
                                                    val duration = cursor.getLong(0)
                                                    if (duration > 5500) { // 5s + small buffer
                                                        isLong = true
                                                    }
                                                }
                                            }
                                } catch (e: Exception) {
                                    Log.e("MediaPicker", "Error checking duration", e)
                                }
                            }

                            if (isLong) {
                                Toast.makeText(
                                                context,
                                                "Video must be less than 5 seconds",
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                            } else {
                                if (selectedGalleryImages.contains(uri)) {
                                    selectedGalleryImages.remove(uri)
                                } else {
                                    selectedGalleryImages.add(uri)
                                }
                            }
                        },
                        onSend = {
                            Log.e(
                                    "MediaSending",
                                    "[TRACE] AdvancedMediaPicker onSend() ENTRY → selectedGalleryImages.size=${selectedGalleryImages.size}"
                            )
                            isMediaProcessing = true
                            selectedGalleryImages.forEachIndexed { index, uri ->
                                Log.e(
                                        "MediaSending",
                                        "[TRACE] Processing image #${index + 1}/${selectedGalleryImages.size} → uri=$uri"
                                )
                                val isVid = uri.toString().contains("video")
                                Log.e(
                                        "MediaSending",
                                        "[TRACE] Detected media type → isVideo=$isVid"
                                )

                                val localId = "media-${UUID.randomUUID()}"
                                Log.e("MediaSending", "[TRACE] Generated localId=$localId")

                                val contentType = if (isVid) "video/mp4" else "image/jpeg"
                                Log.e("MediaSending", "[TRACE] Content type=$contentType")

                                val newMsg =
                                        ChatMessage(
                                                id = localId,
                                                from = localGmail,
                                                to = matchedUserGmail,
                                                text =
                                                        if (isVid) "[Video Message]"
                                                        else "[Photo Message]",
                                                mediaUri = uri.toString(),
                                                mediaType = if (isVid) "VIDEO" else "IMAGE",
                                                status = MessageStatus.Pending
                                        )
                                Log.e(
                                        "MediaSending",
                                        "[TRACE] Created ChatMessage → id=$localId, type=${newMsg.mediaType}, status=${newMsg.status}"
                                )

                                messages.upsert(newMsg)
                                Log.e("MediaSending", "[TRACE] Message upserted to UI")

                                Log.e(
                                        "MediaSending",
                                        "[TRACE] Calling WebSocketManager.sendMedia() → toEmail=$matchedUserGmail, uri=$uri, contentType=$contentType, localId=$localId"
                                )
                                WebSocketManager.sendMedia(
                                        matchedUserGmail,
                                        uri,
                                        contentType,
                                        localId
                                )
                                hasMessageBeenSent = true
                                Log.e("MediaSending", "[TRACE] WebSocketManager.sendMedia() called")
                            }
                            Log.e(
                                    "MediaSending",
                                    "[TRACE] All images processed, clearing selection"
                            )
                            isMediaProcessing = false
                            selectedGalleryImages.clear()
                            showAdvancedMediaPicker = false
                            Log.e("MediaSending", "[TRACE] AdvancedMediaPicker onSend() EXIT")
                        },
                        onCameraClick = {
                            focusManager.clearFocus()
                            val hasPermission =
                                    ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.CAMERA
                                    ) == PackageManager.PERMISSION_GRANTED

                            if (hasPermission) {
                                showFullScreenCamera = true
                                showAdvancedMediaPicker = false
                            } else {
                                (context as MainActivity).requestCameraPermissionAndRun {
                                    showFullScreenCamera = true
                                    showAdvancedMediaPicker = false
                                }
                            }
                        }
                )
            }

            if (showFullScreenCamera) {
                FullScreenCameraScreen(
                        onClose = { showFullScreenCamera = false },
                        onCaptured = { uri ->
                            capturedImageUri = uri
                            showFullScreenCamera = false
                        },
                        isProcessing = isMediaProcessing,
                        setProcessing = { isMediaProcessing = it }
                )
            }

            capturedImageUri?.let { uri ->
                CameraConfirmationScreen(
                        imageUri = uri,
                        onRetake = {
                            capturedImageUri = null
                            showFullScreenCamera = true
                        },
                        onConfirm = {
                            Log.e(
                                    "MediaSending",
                                    "[TRACE] CameraConfirmationScreen onConfirm() ENTRY → uri=$uri"
                            )
                            val isVid =
                                    uri.toString().contains("VID") ||
                                            uri.toString().contains("video")
                            Log.e("MediaSending", "[TRACE] Detected media type → isVideo=$isVid")

                            val localId = "media-${UUID.randomUUID()}"
                            Log.e("MediaSending", "[TRACE] Generated localId=$localId")

                            val contentType = if (isVid) "video/mp4" else "image/jpeg"
                            Log.e("MediaSending", "[TRACE] Content type=$contentType")

                            val newMsg =
                                    ChatMessage(
                                            id = localId,
                                            from = localGmail,
                                            to = matchedUserGmail,
                                            text =
                                                    if (isVid) "[Video Message]"
                                                    else "[Photo Message]",
                                            mediaUri = uri.toString(),
                                            mediaType = if (isVid) "VIDEO" else "IMAGE",
                                            status = MessageStatus.Pending
                                    )
                            Log.e(
                                    "MediaSending",
                                    "[TRACE] Created ChatMessage → id=$localId, type=${newMsg.mediaType}, status=${newMsg.status}"
                            )

                            messages.upsert(newMsg)
                            Log.e("MediaSending", "[TRACE] Message upserted to UI")

                            Log.e(
                                    "MediaSending",
                                    "[TRACE] Calling WebSocketManager.sendMedia() → toEmail=$matchedUserGmail, uri=$uri, contentType=$contentType, localId=$localId"
                            )
                            WebSocketManager.sendMedia(matchedUserGmail, uri, contentType, localId)
                            hasMessageBeenSent = true
                            Log.e("MediaSending", "[TRACE] WebSocketManager.sendMedia() called")

                            capturedImageUri = null
                            Log.e(
                                    "MediaSending",
                                    "[TRACE] CameraConfirmationScreen onConfirm() EXIT"
                            )
                        },
                        onClose = { capturedImageUri = null }
                )
            }

            fullScreenVideoUri?.let { uri ->
                FullScreenVideoPlayer(videoUri = uri, onClose = { fullScreenVideoUri = null })
            }

            fullScreenImageUri?.let { uri ->
                FullScreenImagePlayer(imageUri = uri, onClose = { fullScreenImageUri = null })
            }

            if (showThunderGif) {
                Log.d(
                        "ThunderDebug",
                        "Recomposing: showThunderGif is true, calling ThunderGifOverlay"
                )
                ThunderGifOverlay(
                        isDarkTheme = isDarkTheme,
                        onDismiss = { isRightSwipe ->
                            Log.d(
                                    "ThunderDebug",
                                    "ThunderGifOverlay dismissed from within component, isRightSwipe: $isRightSwipe"
                            )
                            
                            // Track my choice
                            mySparkChoice = isRightSwipe
                            Log.e("SparkSwipe", "My spark choice: ${if (isRightSwipe) "RIGHT" else "LEFT"}")
                            
                            // Send WebSocket event for spark swipe
                            val localUserId = localGmail.substringBefore("@")
                            if (isRightSwipe) {
                                Log.e("SparkSwipe", "Sending spark_right to $matchedUserGmail (from: $localUserId)")
                                WebSocketManager.sendSparkRight(matchedUserGmail, localUserId)

                                // Call spark API - we are sending a spark TO the peer
                                kotlinx.coroutines.MainScope().launch {
                                    try {
                                        val response = NetworkClient.api.sparkUser(
                                            com.example.anonychat.network.SparkRoseRequest(toGmail = matchedUserGmail)
                                        )
                                        if (response.isSuccessful) {
                                            val body = response.body()
                                            Log.e("SparkSwipe", "✅ sparkUser API success: ${body?.message}")
                                            // Increment peer's spark count in top bar and persist
                                            sparks += 1
                                            prefs.edit().putInt("sparks_${matchedUserGmail}", sparks).apply()
                                            Log.e("SparkSwipe", "✅ Peer sparks incremented to $sparks (receiverTotalSparks: ${body?.receiverTotalSparks})")
                                        } else {
                                            Log.e("SparkSwipe", "❌ sparkUser API failed - code: ${response.code()}")
                                        }
                                    } catch (e: Exception) {
                                        Log.e("SparkSwipe", "❌ sparkUser API exception: ${e.message}", e)
                                    }
                                }
                            } else {
                                Log.e("SparkSwipe", "Sending spark_left to $matchedUserGmail (from: $localUserId)")
                                WebSocketManager.sendSparkLeft(matchedUserGmail, localUserId)
                            }
                            
                            // Determine what to show based on my choice and peer's choice (if received)
                            showThunderGif = false
                            
                            if (!isRightSwipe) {
                                // I swiped left - always show miss immediately
                                Log.e("SparkSwipe", "I swiped LEFT - showing miss immediately")
                                showMissGif = true
                            } else if (peerSparkChoice != null) {
                                // I swiped right AND peer already responded
                                if (peerSparkChoice == true) {
                                    // Both swiped right - mutual!
                                    Log.e("SparkSwipe", "MUTUAL! Both swiped right")
                                    showMutualGif = true
                                } else {
                                    // I swiped right, peer swiped left - miss
                                    Log.e("SparkSwipe", "MISS - I swiped right, peer swiped left")
                                    showMissGif = true
                                }
                            } else {
                                // I swiped right but waiting for peer's response
                                // Don't show anything yet - will be handled by WebSocket event
                                Log.e("SparkSwipe", "I swiped RIGHT - waiting for peer's response...")
                            }
                        }
                )
            }

            if (showMutualGif) {
                MutualGifOverlay(onDismiss = { showMutualGif = false })
            }

            if (showMissGif) {
                MissGifOverlay(onDismiss = { showMissGif = false })
            }

            if (showRoseOverlay) {
                RoseGifOverlay(
                    onDismiss = { showRoseOverlay = false },
                    isRoseDead = overlayModeIsDead,
                    onToggleRoseState = {
                        val wasDeadBeforeToggle = overlayModeIsDead
                        overlayModeIsDead = !overlayModeIsDead
                        isRoseDead = !isRoseDead

                        // wasDeadBeforeToggle=false → rose was alive → swiped right → gift the rose
                        // wasDeadBeforeToggle=true  → rose was dead  → swiped right → take back the rose
                        if (!wasDeadBeforeToggle) {
                            // Rose was alive (non-dead) and user swiped right = gift the rose
                            scope.launch {
                                try {
                                    val response = NetworkClient.api.giveRose(
                                        com.example.anonychat.network.SparkRoseRequest(toGmail = matchedUserGmail)
                                    )
                                    if (response.isSuccessful) {
                                        // Decrement availableRoses (sender's roses left to give)
                                        val newCount = (availableRoses - 1).coerceAtLeast(0)
                                        availableRoses = newCount
                                        userPrefs.edit().putInt("available_roses", newCount).apply()
                                        // Increment peer's rose counter shown in the top header
                                        roses += 1
                                        android.util.Log.d("KeyboardProofScreen", "Rose gifted to $matchedUserGmail. Remaining: $newCount, peer roses: $roses")
                                        // Notify peer via WebSocket
                                        WebSocketManager.sendRoseGifted(matchedUserGmail, localGmail)
                                    } else {
                                        val errorBodyStr = response.errorBody()?.string() ?: ""
                                        android.util.Log.e("KeyboardProofScreen", "giveRose failed: $errorBodyStr")
                                        val errorMsg = try {
                                            org.json.JSONObject(errorBodyStr).optString("error", "")
                                        } catch (_: Exception) { "" }
                                        if (errorMsg.isNotEmpty()) {
                                            toastMessage = errorMsg
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("KeyboardProofScreen", "giveRose exception: ${e.message}")
                                }
                            }
                        } else {
                            // Rose was dead and user swiped right = take back the rose
                            scope.launch {
                                try {
                                    val response = NetworkClient.api.takeBackRose(
                                        com.example.anonychat.network.SparkRoseRequest(toGmail = matchedUserGmail)
                                    )
                                    if (response.isSuccessful) {
                                        // Increment availableRoses (sender gets rose back)
                                        val newCount = availableRoses + 1
                                        availableRoses = newCount
                                        userPrefs.edit().putInt("available_roses", newCount).apply()
                                        // Decrement peer's rose counter shown in the top header
                                        roses = (roses - 1).coerceAtLeast(0)
                                        android.util.Log.d("KeyboardProofScreen", "Rose taken back from $matchedUserGmail. Available: $newCount, peer roses: $roses")
                                        // Notify peer via WebSocket
                                        WebSocketManager.sendRoseTakenBack(matchedUserGmail, localGmail)
                                    } else {
                                        val errorBodyStr = response.errorBody()?.string() ?: ""
                                        android.util.Log.e("KeyboardProofScreen", "takeBackRose failed: $errorBodyStr")
                                        val errorMsg = try {
                                            org.json.JSONObject(errorBodyStr).optString("error", "")
                                        } catch (_: Exception) { "" }
                                        if (errorMsg.isNotEmpty()) {
                                            toastMessage = errorMsg
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("KeyboardProofScreen", "takeBackRose exception: ${e.message}")
                                }
                            }
                        }
                    }
                )
            }

            toastMessage?.let { msg ->
                LaunchedEffect(msg) {
                    kotlinx.coroutines.delay(3000)
                    toastMessage = null
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .align(Alignment.Center)
                        .zIndex(10f),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = Color(0xFF1A1A1A).copy(alpha = 0.92f),
                        shadowElevation = 8.dp
                    ) {
                        Text(
                            text = msg,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                }
            }
            
            // Delete Chat Confirmation Dialog
            if (showDeleteConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirmDialog = false },
                    title = { Text("Delete Chat?") },
                    text = {
                        Column {
                            Text(
                                "Choose how you want to proceed:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "• Delete Only: Removes conversation history but you may match with this user again.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "• Block & Delete: Permanently prevents matching with this user and stops all messages.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    confirmButton = {
                        Column {
                            TextButton(
                                onClick = {
                                    showDeleteConfirmDialog = false
                                    scope.launch {
                                        try {
                                            val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                                            val myEmail = prefs.getString("user_email", "") ?: ""
                                            val myUserId = myEmail.substringBefore("@")
                                            val targetUserId = matchedUserGmail.substringBefore("@")
                                            
                                            // Block user first
                                            val blockResponse = NetworkClient.api.blockUser(myUserId, targetUserId)
                                            
                                            if (blockResponse.isSuccessful) {
                                                // Then delete the match
                                                val deleteResponse = NetworkClient.api.deleteMatch(myEmail, matchedUserGmail)
                                                
                                                if (deleteResponse.isSuccessful) {
                                                    // Update blocked status in SharedPreferences
                                                    userPrefs.edit()
                                                        .putBoolean("blocked_$matchedUserGmail", true)
                                                        .apply()
                                                    
                                                    // Remove from conversation list
                                                    ConversationRepository.remove(matchedUserGmail)
                                                    
                                                    Toast.makeText(context, "User blocked and chat deleted", Toast.LENGTH_SHORT).show()
                                                    
                                                    // Navigate back to ChatScreen
                                                    onBack()
                                                } else {
                                                    Toast.makeText(context, "User blocked but failed to delete chat", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                Toast.makeText(context, "Failed to block user", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            Log.e("KeyboardProofScreen", "Error blocking and deleting: ${e.message}")
                                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Block & Delete")
                            }
                            TextButton(
                                onClick = {
                                    showDeleteConfirmDialog = false
                                    scope.launch {
                                        try {
                                            val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                                            val myEmail = prefs.getString("user_email", "") ?: ""
                                            
                                            val response = NetworkClient.api.deleteMatch(myEmail, matchedUserGmail)
                                            
                                            if (response.isSuccessful) {
                                                // Clear chat data from SharedPreferences
                                                userPrefs.edit()
                                                    .remove("blocked_$matchedUserGmail")
                                                    .apply()
                                                
                                                // Remove from conversation list
                                                ConversationRepository.remove(matchedUserGmail)
                                                
                                                Toast.makeText(context, "Chat deleted successfully", Toast.LENGTH_SHORT).show()
                                                
                                                // Navigate back to ChatScreen
                                                onBack()
                                            } else {
                                                Toast.makeText(context, "Failed to delete chat", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            Log.e("KeyboardProofScreen", "Error deleting chat: ${e.message}")
                                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Delete Only")
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirmDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Report User Dialog
            if (showReportDialog) {
                ReportUserDialog(
                    onDismiss = {
                        showReportDialog = false
                        selectedReportReason = null
                        otherReportText = ""
                    },
                    selectedReason = selectedReportReason,
                    onReasonSelected = { selectedReportReason = it },
                    otherText = otherReportText,
                    onOtherTextChanged = { otherReportText = it },
                    onReport = {
                        scope.launch {
                            try {
                                val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                                val myEmail = prefs.getString("user_email", "") ?: ""
                                val myUserId = myEmail.substringBefore("@")
                                val targetUserId = matchedUserGmail.substringBefore("@")
                                
                                val reason = if (selectedReportReason == "Other") {
                                    "Other: $otherReportText"
                                } else {
                                    selectedReportReason ?: ""
                                }
                                
                                val response = NetworkClient.api.reportUser(
                                    myUserId,
                                    targetUserId,
                                    com.example.anonychat.network.ReportRequest(reason)
                                )
                                
                                if (response.isSuccessful) {
                                    Toast.makeText(
                                        context,
                                        "Report submitted successfully",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    // Handle error responses, especially 409 Conflict
                                    val errorMsg = when (response.code()) {
                                        409 -> {
                                            // Parse the error body for duplicate report
                                            try {
                                                val errorBody = response.errorBody()?.string()
                                                if (errorBody != null) {
                                                    val gson = com.google.gson.Gson()
                                                    val errorResponse = gson.fromJson(
                                                        errorBody,
                                                        com.example.anonychat.network.ReportErrorResponse::class.java
                                                    )
                                                    errorResponse.message ?: "You have already reported this user"
                                                } else {
                                                    "You have already reported this user"
                                                }
                                            } catch (e: Exception) {
                                                "You have already reported this user"
                                            }
                                        }
                                        else -> response.body()?.message ?: "Failed to submit report"
                                    }
                                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                Log.e("KeyboardProofScreen", "Error reporting user: ${e.message}")
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                            
                            showReportDialog = false
                            selectedReportReason = null
                            otherReportText = ""
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ThunderGifOverlay(isDarkTheme: Boolean, onDismiss: (Boolean) -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    
    // Core interaction states
    val swipeOffsetX = remember { androidx.compose.animation.core.Animatable(0f) }
    val cardScale = remember { androidx.compose.animation.core.Animatable(1f) }
    
    val swipeThreshold = 300f
    var thresholdCrossed by remember { mutableStateOf(false) }

    val imageLoader = remember {
        ImageLoader.Builder(context)
                .components {
                    if (SDK_INT >= 28) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .build()
    }

    Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
    ) {
        // --- 1. Background Blur & Dim ---
        val dragProgress = (kotlin.math.abs(swipeOffsetX.value) / swipeThreshold).coerceIn(0f, 1.5f)
        val blurRadius = (dragProgress * 10f).dp
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blur(blurRadius)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.3f),
                            Color.Black.copy(alpha = 0.6f)
                        )
                    )
                )
        )

        // --- 2. Directional Glows ---
        val rightSweepGlowAlpha = (swipeOffsetX.value / swipeThreshold).coerceIn(0f, 0.4f)
        val leftSweepDimAlpha = (-swipeOffsetX.value / swipeThreshold).coerceIn(0f, 0.4f)

        if (rightSweepGlowAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            1f to Color(0xFFFFD700).copy(alpha = rightSweepGlowAlpha)
                        )
                    )
            )
        }
        
        if (leftSweepDimAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = leftSweepDimAlpha))
            )
        }

        // Colors based on theme
        val leftArrowColor = if (isDarkTheme) Color(0xFF4CC9F0) else Color(0xFF9FA8DA)
        val rightArrowColor = if (isDarkTheme) Color(0xFFFFE066) else Color(0xFFFF9E4A)

        // Sideways Nudge Animation
        val infiniteTransition = rememberInfiniteTransition()
        val nudgeOffset by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 15f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        )

        // --- 3. Dynamic Side Icons ---
        
        // 3a. Hint Arrows (Visible when idle, fade out on swipe)
        val hintAlpha = (1f - (kotlin.math.abs(swipeOffsetX.value) / 100f)).coerceIn(0f, 0.5f)
        if (hintAlpha > 0f) {
            // Left Hint
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = null,
                tint = leftArrowColor.copy(alpha = hintAlpha),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp)
                    .offset(x = (-nudgeOffset).dp)
                    .size(48.dp)
            )
            // Right Hint
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
                tint = rightArrowColor.copy(alpha = hintAlpha),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
                    .offset(x = nudgeOffset.dp)
                    .size(48.dp)
            )
        }

        // 3b. Progress Icons (Tick/Cross - fade in as user swipes)
        val leftProgressAlpha = ((-swipeOffsetX.value / 150f).coerceIn(0f, 1f))
        if (leftProgressAlpha > 0f) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = leftArrowColor.copy(alpha = leftProgressAlpha),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 40.dp)
                    .size(64.dp)
                    .scale(0.8f + (leftProgressAlpha * 0.4f))
            )
        }

        val rightProgressAlpha = ((swipeOffsetX.value / 150f).coerceIn(0f, 1f))
        if (rightProgressAlpha > 0f) {
             Icon(
                imageVector = Icons.Default.Done,
                contentDescription = null,
                tint = rightArrowColor.copy(alpha = rightProgressAlpha),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 40.dp)
                    .size(64.dp)
                    .scale(0.8f + (rightProgressAlpha * 0.4f))
            )
        }

        // --- 4. Main Card (Tilt & Interaction) ---
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(32.dp)
                .graphicsLayer {
                    translationX = swipeOffsetX.value
                    rotationZ = swipeOffsetX.value * 0.05f
                    scaleX = cardScale.value - (kotlin.math.abs(swipeOffsetX.value) / 2000f)
                    scaleY = scaleX
                    if (swipeOffsetX.value < 0) {
                        alpha = (1f - (kotlin.math.abs(swipeOffsetX.value) / 600f)).coerceIn(0.3f, 1f)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            scope.launch {
                                cardScale.animateTo(1.1f, animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f))
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            tryAwaitRelease()
                            scope.launch { cardScale.animateTo(1f) }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (kotlin.math.abs(swipeOffsetX.value) > swipeThreshold) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    swipeOffsetX.animateTo(if (swipeOffsetX.value > 0) 1000f else -1000f)
                                    onDismiss(swipeOffsetX.value > 0)
                                } else {
                                    thresholdCrossed = false
                                    swipeOffsetX.animateTo(0f)
                                }
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                val newValue = swipeOffsetX.value + dragAmount
                                swipeOffsetX.snapTo(newValue)
                                if (kotlin.math.abs(newValue) > swipeThreshold && !thresholdCrossed) {
                                    thresholdCrossed = true
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                } else if (kotlin.math.abs(newValue) < swipeThreshold) {
                                    thresholdCrossed = false
                                }
                            }
                        }
                    )
                }
        ) {
            Image(
                painter = rememberAsyncImagePainter(R.drawable.spark, imageLoader = imageLoader),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(0.7f).aspectRatio(1f),
                contentScale = ContentScale.Fit
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // --- 5. Reactive Text ---
            val textAlpha = (1f - (kotlin.math.abs(swipeOffsetX.value) / 400f)).coerceIn(0.2f, 1f)
            val textTranslationY = (-kotlin.math.abs(swipeOffsetX.value) / 10f).dp
            
            Text(
                    text = "Feel the spark?",
                    style =
                            TextStyle(
                                    color = Color(0xFFFFD700),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontStyle = FontStyle.Italic,
                                    shadow = Shadow(
                                        color = Color(0xFFFFD700).copy(alpha = 0.5f),
                                        offset = Offset(0f, 0f),
                                        blurRadius = 15f
                                    )
                            ),
                    modifier = Modifier
                        .offset(y = textTranslationY)
                        .graphicsLayer { alpha = textAlpha }
            )
        }
    }
}


@Composable
private fun MutualGifOverlay(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val imageLoader = remember {
        ImageLoader.Builder(context)
                .components {
                    if (SDK_INT >= 28) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .build()
    }

    LaunchedEffect(Unit) {
        delay(4850) // 4350ms duration + 500ms pause
        onDismiss()
    }

    Box(
            modifier = Modifier.fillMaxSize().background(Color.Transparent).clickable {},
            contentAlignment = Alignment.Center
    ) {
        Image(
                painter =
                        rememberAsyncImagePainter(
                                ImageRequest.Builder(LocalContext.current)
                                        .data(R.drawable.suc)
                                        .setParameter("coil#repeat_count", 0) // Play once
                                        .build(),
                                imageLoader = imageLoader
                        ),
                contentDescription = "Success",
                modifier = Modifier.fillMaxWidth(0.8f).aspectRatio(1f),
                contentScale = ContentScale.Fit
        )
    }
}

/* ---------------- MESSAGE BUBBLE WITH STATUS ICONS ---------------- */

@Composable
private fun DownloadOverlay(
        isDownloading: Boolean = false,
        modifier: Modifier = Modifier,
        onDownload: () -> Unit
) {
    // Track if download was initiated to show buffering immediately
    var downloadInitiated by remember { mutableStateOf(false) }
    val showBuffering = isDownloading || downloadInitiated

    // Reset downloadInitiated when isDownloading becomes true (actual download started)
    LaunchedEffect(isDownloading) { if (isDownloading) downloadInitiated = false }

    Box(
            modifier =
                    modifier.background(Color.Black.copy(alpha = 0.7f)).clickable {
                        if (!showBuffering) {
                            downloadInitiated = true
                            onDownload()
                        }
                    },
            contentAlignment = Alignment.Center
    ) {
        if (showBuffering) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
        } else {
            Icon(
                    imageVector = Icons.Default.FileDownload,
                    contentDescription = "Download",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
            )
        }
    }
}

@Composable
private fun KeyboardMessageBubble(
        message: ChatMessage,
        isMe: Boolean,
        incomingBubbleColor: Color,
        outgoingBubbleColor: Color,
        messageTextColor: Color,
        onVideoClick: (String) -> Unit,
        onImageClick: (String) -> Unit = {},
        onStatusUpdate: (String, MessageStatus) -> Unit
) {
    val time =
            remember(message.timestamp) {
                SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(message.timestamp))
            }

    val isTransparentMedia = message.mediaType == "GIF"
    val isAnyMedia =
            isTransparentMedia || message.mediaType == "IMAGE" || message.mediaType == "VIDEO"
    Column(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        Surface(
                shape = RoundedCornerShape(12.dp),
                color =
                        if (isAnyMedia) Color.Transparent
                        else (if (isMe) outgoingBubbleColor else incomingBubbleColor),
                modifier = Modifier.widthIn(max = 280.dp)
        ) {
            when {
                message.audioUri != null -> {
                    AudioPlayerBubble(
                            audioUri = message.audioUri,
                            amplitudes = message.amplitudes,
                            contentColor = messageTextColor,
                            onPlayAcknowledged = {
                                if (!isMe && message.status != MessageStatus.Read) {
                                    WebSocketManager.sendMessageReadAck(message.id, message.from)
                                    onStatusUpdate(message.id, MessageStatus.Read)
                                }
                            }
                    )
                }
                message.mediaType == "IMAGE" -> {
                    val isRemote = message.mediaUri?.startsWith("http") == true
                    Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                    Modifier.fillMaxWidth()
                                            .heightIn(max = 300.dp)
                                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        Image(
                                painter = rememberAsyncImagePainter(message.mediaUri),
                                contentDescription = null,
                                modifier =
                                        Modifier.fillMaxWidth().clickable {
                                            if (!isRemote) onImageClick(message.mediaUri ?: "")
                                        },
                                contentScale = ContentScale.FillWidth,
                                alpha = if (isRemote) 0.5f else 1f
                        )
                        androidx.compose.animation.AnimatedVisibility(
                                visible = isRemote,
                                enter = fadeIn(),
                                exit = fadeOut()
                        ) {
                            DownloadOverlay(
                                    isDownloading = message.isDownloading,
                                    modifier = Modifier.matchParentSize()
                            ) {
                                WebSocketManager.downloadMediaManually(
                                        message.mediaUri!!,
                                        message.mediaId!!,
                                        message
                                )
                            }
                        }
                    }
                }
                message.mediaType == "GIF" -> {
                    val isRemote = message.mediaUri?.startsWith("http") == true
                    Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                    ) {
                        Image(
                                painter = rememberAsyncImagePainter(message.mediaUri),
                                contentDescription = null,
                                modifier =
                                        Modifier.fillMaxWidth().clickable {
                                            if (!isRemote) onImageClick(message.mediaUri ?: "")
                                        },
                                contentScale = ContentScale.FillWidth,
                                alpha = if (isRemote) 0.5f else 1f
                        )
                        androidx.compose.animation.AnimatedVisibility(
                                visible = isRemote,
                                enter = fadeIn(),
                                exit = fadeOut()
                        ) {
                            DownloadOverlay(
                                    isDownloading = message.isDownloading,
                                    modifier = Modifier.matchParentSize()
                            ) {
                                WebSocketManager.downloadMediaManually(
                                        message.mediaUri!!,
                                        message.mediaId!!,
                                        message
                                )
                            }
                        }
                    }
                }
                message.mediaType == "VIDEO" -> {
                    val isRemote = message.mediaUri?.startsWith("http") == true
                    Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                    Modifier.fillMaxWidth()
                                            .heightIn(max = 300.dp)
                                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        VideoMessageBubble(
                                videoUri = if (isRemote) "" else (message.mediaUri ?: ""),
                                showPlayButton = !isRemote,
                                onPlayClick = {
                                    if (!isRemote) {
                                        onVideoClick(message.mediaUri ?: "")
                                        if (message.status != MessageStatus.Read && !isMe) {
                                            WebSocketManager.sendMessageReadAck(
                                                    message.id,
                                                    message.from
                                            )
                                            onStatusUpdate(message.id, MessageStatus.Read)
                                        }
                                    }
                                }
                        )
                        androidx.compose.animation.AnimatedVisibility(
                                visible = isRemote,
                                enter = fadeIn(),
                                exit = fadeOut()
                        ) {
                            DownloadOverlay(
                                    isDownloading = message.isDownloading,
                                    modifier = Modifier.matchParentSize()
                            ) {
                                WebSocketManager.downloadMediaManually(
                                        message.mediaUri!!,
                                        message.mediaId!!,
                                        message
                                )
                            }
                        }
                    }
                }
                else -> {
                    Text(message.text, Modifier.padding(12.dp), color = messageTextColor)
                }
            }
        }

        Row(
                modifier =
                        Modifier.padding(
                                top = 4.dp,
                                start = if (isMe) 0.dp else 4.dp,
                                end = if (isMe) 4.dp else 0.dp
                        ),
                horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
        ) {
            Text(time, fontSize = 11.sp, color = Color.Gray)

            if (isMe) {
                Spacer(Modifier.width(4.dp))
                when (message.status) {
                    MessageStatus.Pending ->
                            CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.Gray
                            )
                    MessageStatus.Sending ->
                            Icon(
                                    Icons.Default.Done,
                                    null,
                                    tint = Color.Gray,
                                    modifier = Modifier.size(16.dp)
                            )
                    MessageStatus.Delivered ->
                            Icon(
                                    Icons.Default.DoneAll,
                                    null,
                                    tint = Color.Gray,
                                    modifier = Modifier.size(16.dp)
                            )
                    MessageStatus.Read ->
                            Icon(
                                    Icons.Default.DoneAll,
                                    null,
                                    tint = Color.Green,
                                    modifier = Modifier.size(16.dp)
                            )
                    MessageStatus.Failed ->
                            Icon(
                                    Icons.Default.Close,
                                    null,
                                    tint = Color.Red,
                                    modifier = Modifier.size(14.dp).offset(x = (-5).dp)
                            )
                }
            }
        }
    }
}

/* ---------------- AUDIO PLAYER BUBBLE ---------------- */

@Composable
fun AudioPlayerBubble(
        audioUri: String,
        amplitudes: List<Float>,
        contentColor: Color,
        onPlayAcknowledged: () -> Unit = {}
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var isPrepared by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var duration by remember { mutableStateOf(1f) }

    val mediaPlayer = remember(audioUri) { android.media.MediaPlayer() }

    DisposableEffect(audioUri) {
        mediaPlayer.setOnPreparedListener {
            duration = it.duration.toFloat()
            isPrepared = true
            Log.i("AudioPlayer", "Media prepared: $audioUri, duration: $duration")
        }
        mediaPlayer.setOnCompletionListener {
            isPlaying = false
            progress = 0f
            mediaPlayer.seekTo(0)
        }
        mediaPlayer.setOnErrorListener { mp, what, extra ->
            Log.e("AudioPlayer", "Error playing $audioUri: what=$what, extra=$extra")
            true
        }

        try {
            mediaPlayer.setDataSource(context, Uri.parse(audioUri))
            mediaPlayer.prepareAsync()
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Failed to set data source: $audioUri", e)
        }

        onDispose {
            try {
                if (mediaPlayer.isPlaying) {
                    mediaPlayer.stop()
                }
                mediaPlayer.release()
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Error releasing player", e)
            }
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isPlaying) {
                try {
                    if (mediaPlayer.isPlaying) {
                        progress = mediaPlayer.currentPosition.toFloat()
                    }
                } catch (e: Exception) {
                    // Handle potential race where player is released but loop runs once more
                }
                delay(100)
            }
        }
    }

    Row(
            modifier = Modifier.padding(8.dp).width(240.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
                onClick = {
                    if (isPrepared) {
                        if (isPlaying) {
                            try {
                                mediaPlayer.pause()
                            } catch (e: Exception) {
                                Log.e("AudioPlayer", "Error pausing", e)
                            }
                        } else {
                            try {
                                mediaPlayer.start()
                                onPlayAcknowledged()
                            } catch (e: Exception) {
                                Log.e("AudioPlayer", "Error starting", e)
                            }
                        }
                        isPlaying = !isPlaying
                    }
                },
                modifier = Modifier.size(36.dp)
        ) {
            if (!isPrepared) {
                CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = contentColor,
                        strokeWidth = 2.dp
                )
            } else {
                Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = contentColor
                )
            }
        }

        Spacer(Modifier.width(4.dp))

        // --- NEW: Audio Waveform ---
        AudioWaveform(
                amplitudes = amplitudes,
                progress = if (duration > 0) progress / duration else 0f,
                modifier = Modifier.weight(1f).height(32.dp),
                color = contentColor
        )

        Spacer(Modifier.width(8.dp))

        val timeString =
                remember(isPlaying, progress, duration) {
                    val displayMs = if (isPlaying || progress > 0) progress else duration
                    val sec = (displayMs / 1000).toInt()
                    String.format("%d:%02d", sec / 60, sec % 60)
                }
        Text(timeString, fontSize = 11.sp, color = contentColor)
    }
}

@Composable
fun AudioWaveform(
        amplitudes: List<Float>,
        progress: Float,
        modifier: Modifier = Modifier,
        color: Color
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val barWidth = 3.dp.toPx()
        val barGap = 2.dp.toPx()

        // Use a subset of amplitudes if there are too many, or expand if too few
        // For simple UI, we'll just draw what we have
        val totalBars = (width / (barWidth + barGap)).toInt()

        // If we have no amplitudes, show a flat line or dummy data
        val safeAmplitudes =
                if (amplitudes.isEmpty()) {
                    List(totalBars) { 0.1f }
                } else {
                    // Sample the amplitudes to fit the totalBars
                    List(totalBars) { i ->
                        val index =
                                (i * amplitudes.size / totalBars).coerceIn(0, amplitudes.size - 1)
                        amplitudes[index]
                    }
                }

        safeAmplitudes.forEachIndexed { index, amplitude ->
            val barHeight = (amplitude * height).coerceAtLeast(4.dp.toPx())
            val x = index * (barWidth + barGap)
            val isPlayed = (index.toFloat() / totalBars) < progress

            drawRoundRect(
                    color = if (isPlayed) color else color.copy(alpha = 0.3f),
                    topLeft = Offset(x, (height - barHeight) / 2),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )
        }
    }
}

@Composable
fun VideoMessageBubble(videoUri: String, showPlayButton: Boolean, onPlayClick: () -> Unit) {
    val context = LocalContext.current
    val imageLoader =
            remember(context) {
                coil.ImageLoader.Builder(context)
                        .components { add(coil.decode.VideoFrameDecoder.Factory()) }
                        .build()
            }

    Box(
            modifier =
                    Modifier.fillMaxWidth() // Fill width
                            .wrapContentHeight() // Auto-height based on aspect ratio
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPlayClick() },
            contentAlignment = Alignment.Center
    ) {
        // Use Coil AsyncImage to load the first frame
        // This automatically handles native aspect ratio scaling
        coil.compose.AsyncImage(
                model = videoUri,
                imageLoader = imageLoader,
                contentDescription = "Video Thumbnail",
                modifier = Modifier.fillMaxWidth(), // Fill the width of the bubble
                contentScale = ContentScale.FillWidth, // ensure it scales to width, height adapts
        )

        if (showPlayButton) {
            IconButton(onClick = onPlayClick, modifier = Modifier.size(64.dp)) {
                Icon(
                        Icons.Default.PlayCircle,
                        contentDescription = "Play Video",
                        tint = Color.White,
                        modifier = Modifier.size(64.dp)
                )
            }
        }
    }
}

@Composable
fun MediaPickerScreen(
        galleryImages: List<Uri>,
        selectedImages: Set<Uri>,
        onClose: () -> Unit,
        onImageToggle: (Uri) -> Unit,
        onSend: () -> Unit,
        onCameraClick: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize()) {
                // Header
                Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, null, tint = Color.White)
                    }
                    Text(
                            "Select Media",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                    )
                }

                // Grid
                LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier =
                                Modifier.weight(1f)
                                        .padding(
                                                bottom =
                                                        if (selectedImages.isNotEmpty()) 80.dp
                                                        else 0.dp
                                        ),
                        contentPadding = PaddingValues(1.dp)
                ) {
                    // Camera Slot
                    item {
                        Box(
                                Modifier.aspectRatio(1f)
                                        .padding(1.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { onCameraClick() }
                        ) {
                            CameraPreviewItem()
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                        Icons.Default.CameraAlt,
                                        null,
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }

                    // Gallery Images
                    items(galleryImages) { uri ->
                        val isSelected = selectedImages.contains(uri)
                        Box(
                                Modifier.aspectRatio(1f)
                                        .padding(1.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { onImageToggle(uri) }
                        ) {
                            val context = LocalContext.current
                            val imageLoader =
                                    remember(context) {
                                        coil.ImageLoader.Builder(context)
                                                .components {
                                                    add(coil.decode.VideoFrameDecoder.Factory())
                                                }
                                                .build()
                                    }
                            coil.compose.AsyncImage(
                                    model = uri,
                                    imageLoader = imageLoader,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                            )

                            val isVideo = uri.toString().contains("video")
                            if (isVideo) {
                                Box(Modifier.align(Alignment.BottomStart).padding(4.dp)) {
                                    Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            if (isSelected) {
                                Box(
                                        Modifier.fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.3f))
                                )
                                Box(
                                        Modifier.align(Alignment.TopEnd)
                                                .padding(6.dp)
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF1E88E5)),
                                        contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                            Icons.Default.Done,
                                            null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Send Button
        if (selectedImages.isNotEmpty()) {
            Box(
                    modifier =
                            Modifier.align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(32.dp)
            ) {
                FloatingActionButton(
                        onClick = onSend,
                        containerColor = Color(0xFF1E88E5),
                        contentColor = Color.White,
                        shape = CircleShape
                ) {
                    Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${selectedImages.size}")
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.Send, null)
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreviewItem() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val executor = ContextCompat.getMainExecutor(ctx)
                cameraProviderFuture.addListener(
                        {
                            val hasPermission =
                                    ContextCompat.checkSelfPermission(
                                            ctx,
                                            Manifest.permission.CAMERA
                                    ) == PackageManager.PERMISSION_GRANTED
                            if (!hasPermission) return@addListener

                            val cameraProvider = cameraProviderFuture.get()
                            val preview =
                                    androidx.camera.core.Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }
                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        cameraSelector,
                                        preview
                                )
                            } catch (e: Exception) {
                                Log.e("CameraPreview", "Binding failed", e)
                            }
                        },
                        executor
                )
                previewView
            },
            modifier = Modifier.fillMaxSize()
    )
}

/* ---------------- PREVIEW ---------------- */

@Composable
private fun ProcessingOverlay() {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.6f)) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(48.dp)
                )
                if (androidx.compose.ui.platform.LocalInspectionMode.current.not()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                            "Processing...",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun FullScreenCameraScreen(
        onClose: () -> Unit,
        onCaptured: (Uri) -> Unit,
        isProcessing: Boolean,
        setProcessing: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val recorder = remember {
        Recorder.Builder()
                .setQualitySelector(
                        QualitySelector.from(Quality.SD)
                ) // Low quality for fast processing
                .build()
    }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashEnabled by remember { mutableStateOf(false) }
    var isRecordingVideo by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableStateOf(0) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(lensFacing, flashEnabled) {
        val cameraProvider = cameraProviderFuture.get()
        val preview =
                androidx.camera.core.Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

        imageCapture.flashMode =
                if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF

        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        try {
            cameraProvider.unbindAll()
            camera =
                    cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture,
                            videoCapture
                    )
        } catch (e: Exception) {
            Log.e("CameraCapture", "Use case binding failed", e)
        }
    }

    // Auto-stop at 5 seconds
    LaunchedEffect(isRecordingVideo) {
        if (isRecordingVideo) {
            recordingSeconds = 0
            while (isRecordingVideo && recordingSeconds < 5) {
                delay(1000)
                recordingSeconds++
            }
            if (isRecordingVideo) {
                activeRecording?.stop()
                activeRecording = null
                isRecordingVideo = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Header
        Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, null, tint = Color.White) }

            if (!isRecordingVideo) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        IconButton(onClick = { flashEnabled = !flashEnabled }) {
                            Icon(
                                    if (flashEnabled) Icons.Default.FlashOn
                                    else Icons.Default.FlashOff,
                                    null,
                                    tint = if (flashEnabled) Color.Yellow else Color.White
                            )
                        }
                    }
                    IconButton(
                            onClick = {
                                lensFacing =
                                        if (lensFacing == CameraSelector.LENS_FACING_BACK)
                                                CameraSelector.LENS_FACING_FRONT
                                        else CameraSelector.LENS_FACING_BACK
                            }
                    ) { Icon(Icons.Default.Cameraswitch, null, tint = Color.White) }
                }
            } else {
                Text(
                        text = "00:0${recordingSeconds}",
                        color = Color.Red,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 16.dp)
                )
            }
        }

        // Shutter Button (Tap for Photo, Long Press for Video)
        Box(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(48.dp),
                contentAlignment = Alignment.Center
        ) {
            Box(
                    Modifier.size(80.dp)
                            .clip(CircleShape)
                            .background(
                                    if (isRecordingVideo) Color.Red.copy(alpha = 0.5f)
                                    else Color.White.copy(alpha = 0.3f)
                            )
                            .pointerInput(isProcessing) {
                                if (isProcessing) return@pointerInput
                                detectTapGestures(
                                        onTap = {
                                            // Take Photo
                                            setProcessing(true)
                                            val file =
                                                    File.createTempFile(
                                                            "IMG_${System.currentTimeMillis()}",
                                                            ".jpg",
                                                            context.cacheDir
                                                    )
                                            val outputOptions =
                                                    ImageCapture.OutputFileOptions.Builder(file)
                                                            .build()
                                            imageCapture.takePicture(
                                                    outputOptions,
                                                    ContextCompat.getMainExecutor(context),
                                                    object : OnImageSavedCallback {
                                                        override fun onImageSaved(
                                                                output: OutputFileResults
                                                        ) {
                                                            val uri =
                                                                    FileProvider.getUriForFile(
                                                                            context,
                                                                            "${context.packageName}.fileprovider",
                                                                            file
                                                                    )
                                                            setProcessing(false)
                                                            onCaptured(uri)
                                                        }
                                                        override fun onError(
                                                                exc: ImageCaptureException
                                                        ) {
                                                            Log.e(
                                                                    "CameraCapture",
                                                                    "Photo capture failed: ${exc.message}",
                                                                    exc
                                                            )
                                                            setProcessing(false)
                                                        }
                                                    }
                                            )
                                        },
                                        onLongPress = {
                                            // Start Recording
                                            isRecordingVideo = true

                                            // Torch for video
                                            if (flashEnabled &&
                                                            lensFacing ==
                                                                    CameraSelector.LENS_FACING_BACK
                                            ) {
                                                camera?.cameraControl?.enableTorch(true)
                                            }

                                            val file =
                                                    File.createTempFile(
                                                            "VID_${System.currentTimeMillis()}",
                                                            ".mp4",
                                                            context.cacheDir
                                                    )
                                            val outputOptions =
                                                    FileOutputOptions.Builder(file).build()

                                            activeRecording =
                                                    videoCapture
                                                            .output
                                                            .prepareRecording(
                                                                    context,
                                                                    outputOptions
                                                            )
                                                            .apply {
                                                                if (ContextCompat
                                                                                .checkSelfPermission(
                                                                                        context,
                                                                                        Manifest.permission
                                                                                                .RECORD_AUDIO
                                                                                ) ==
                                                                                PackageManager
                                                                                        .PERMISSION_GRANTED
                                                                ) {
                                                                    withAudioEnabled()
                                                                }
                                                            }
                                                            .start(
                                                                    ContextCompat.getMainExecutor(
                                                                            context
                                                                    )
                                                            ) { event ->
                                                                if (event is
                                                                                VideoRecordEvent.Finalize
                                                                ) {
                                                                    if (!event.hasError()) {
                                                                        val uri =
                                                                                FileProvider
                                                                                        .getUriForFile(
                                                                                                context,
                                                                                                "${context.packageName}.fileprovider",
                                                                                                file
                                                                                        )
                                                                        onCaptured(uri)
                                                                    }
                                                                    setProcessing(false)
                                                                    isRecordingVideo = false
                                                                    camera?.cameraControl
                                                                            ?.enableTorch(false)
                                                                }
                                                            }
                                        },
                                        onPress = {
                                            tryAwaitRelease()
                                            if (isRecordingVideo) {
                                                activeRecording?.stop()
                                                activeRecording = null
                                                isRecordingVideo = false
                                                camera?.cameraControl?.enableTorch(false)
                                                setProcessing(true)
                                            }
                                        }
                                )
                            },
                    contentAlignment = Alignment.Center
            ) {
                Box(
                        Modifier.size(if (isRecordingVideo) 40.dp else 64.dp)
                                .clip(
                                        if (isRecordingVideo) RoundedCornerShape(8.dp)
                                        else CircleShape
                                )
                                .background(if (isRecordingVideo) Color.Red else Color.White)
                )
            }
        }

        if (!isRecordingVideo) {
            Text(
                    "Hold for video, tap for photo",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier =
                            Modifier.align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(bottom = 24.dp)
            )
        }

        if (isProcessing) {
            ProcessingOverlay()
        }
    }
}

@Composable
private fun CameraConfirmationScreen(
        imageUri: Uri,
        onRetake: () -> Unit,
        onConfirm: () -> Unit,
        onClose: () -> Unit
) {
    val context = LocalContext.current
    val isVideo = imageUri.toString().contains("VID") || imageUri.toString().contains("video")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isVideo) {
            val exoPlayer = remember {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(imageUri))
                    repeatMode = Player.REPEAT_MODE_ONE
                    playWhenReady = true
                    prepare()
                }
            }
            DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

            AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    modifier = Modifier.fillMaxSize()
            )
        } else {
            Image(
                    painter = rememberAsyncImagePainter(imageUri),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
            )
        }

        // Header
        Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, null, tint = Color.White) }
            IconButton(onClick = onRetake) { Icon(Icons.Default.Refresh, null, tint = Color.White) }
        }

        // Bottom Action (Confirm)
        Box(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(32.dp),
                contentAlignment = Alignment.Center
        ) {
            FloatingActionButton(
                    onClick = onConfirm,
                    containerColor = Color(0xFF1E88E5),
                    contentColor = Color.White,
                    shape = CircleShape
            ) { Icon(Icons.Default.Done, null) }
        }
    }
}

@Composable
private fun FullScreenVideoPlayer(videoUri: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(videoUri)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    Box(
            modifier = Modifier.fillMaxSize().background(Color.Black).clickable(onClick = {}),
            contentAlignment = Alignment.Center
    ) {
        AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        controllerShowTimeoutMs = 2000
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.fillMaxSize().navigationBarsPadding()
        )

        // Close Button
        Box(
                modifier = Modifier.fillMaxSize().statusBarsPadding().padding(16.dp),
                contentAlignment = Alignment.TopStart
        ) {
            IconButton(
                    onClick = onClose,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) { Icon(Icons.Default.Close, null, tint = Color.White) }
        }
    }
}

@Composable
private fun FullScreenImagePlayer(imageUri: String, onClose: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
            modifier =
                    Modifier.fillMaxSize()
                            .background(Color.Black)
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    if (scale > 1f) {
                                        offset += pan
                                    } else {
                                        offset = Offset.Zero
                                    }
                                }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                        onDoubleTap = {
                                            if (scale > 1f) {
                                                scale = 1f
                                                offset = Offset.Zero
                                            } else {
                                                scale = 3f
                                            }
                                        },
                                        onTap = {
                                            // Optional: hide/show close button on tap?
                                            // But standard behavior is just to keep it
                                        }
                                )
                            },
            contentAlignment = Alignment.Center
    ) {
        Image(
                painter = rememberAsyncImagePainter(imageUri),
                contentDescription = null,
                modifier =
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                contentScale = ContentScale.Fit
        )

        // Close Button
        Box(
                modifier = Modifier.fillMaxSize().statusBarsPadding().padding(16.dp),
                contentAlignment = Alignment.TopStart
        ) {
            IconButton(
                    onClick = onClose,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) { Icon(Icons.Default.Close, null, tint = Color.White) }
        }
    }
}
@Composable
private fun MissGifOverlay(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val imageLoader = remember {
        ImageLoader.Builder(context)
                .components {
                    if (SDK_INT >= 28) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .build()
    }

    LaunchedEffect(Unit) {
        delay(4850) // 4350ms duration + 500ms pause
        onDismiss()
    }

    Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable {
                onDismiss()
            },
            contentAlignment = Alignment.Center
    ) {
        Image(
                painter =
                        rememberAsyncImagePainter(
                                ImageRequest.Builder(LocalContext.current)
                                        .data(R.drawable.arrowfail)
                                        .setParameter("coil#repeat_count", 0) // Play once
                                        .build(),
                                imageLoader = imageLoader
                        ),
                contentDescription = "Miss",
                modifier = Modifier.size(250.dp),
                contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun RoseGifOverlay(
    onDismiss: () -> Unit,
    isRoseDead: Boolean,
    onToggleRoseState: () -> Unit
) {
    val context = LocalContext.current
    val imageLoader = remember {
        ImageLoader.Builder(context)
                .components {
                    if (SDK_INT >= 28) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .build()
    }

    Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().clickable(enabled = false) { } // Prevent dismiss on internal clicks
        ) {
            Text(
                text = if (isRoseDead) "Was the rose a mistake?" else "Do you want to gift this rose?",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 40.dp)
            )

            Image(
                painter = rememberAsyncImagePainter(if (isRoseDead) R.drawable.roseff else R.drawable.roseg, imageLoader = imageLoader),
                contentDescription = "Rose",
                modifier = Modifier.fillMaxWidth(0.5f).aspectRatio(1f),
                contentScale = ContentScale.Fit
            )

            Spacer(Modifier.height(16.dp))

            RoseSlider(
                onCancel = onDismiss,
                onSend = {
                    // Toggle between dead and non-dead rose state when slider is swiped right
                    onToggleRoseState()
                    onDismiss()
                },
                isRoseDead = isRoseDead,
                imageLoader = imageLoader
            )
        }
    }
}

@Composable
private fun RoseSlider(
    onCancel: () -> Unit,
    onSend: () -> Unit,
    isRoseDead: Boolean,
    imageLoader: ImageLoader
) {
    val haptic = LocalHapticFeedback.current
    val thumbSize = 64.dp
    val trackHeight = 80.dp
    val trackWidth = 320.dp
    val maxOffsetPx = with(androidx.compose.ui.platform.LocalDensity.current) { (trackWidth - thumbSize).toPx() }
    
    var offsetX by remember { mutableStateOf(0f) }
    val animatedOffset by animateFloatAsState(targetValue = offsetX, label = "thumbOffset")

    // Dynamic weights to make the split follow the thumb
    val leftWeight = (1f + (offsetX / (maxOffsetPx / 2 + 0.001f))).coerceIn(0.01f, 1.99f)
    val rightWeight = 2f - leftWeight

    Box(
        modifier = Modifier
            .width(trackWidth)
            .height(trackHeight)
            .clip(RoundedCornerShape(36.dp)),
        contentAlignment = Alignment.CenterStart
    ) {
        // Track sections with 3D Gradients
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(leftWeight)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 36.dp, bottomStart = 36.dp))
                    .background(
                        Brush.verticalGradient(
                            if (isRoseDead) {
                                listOf(Color(0xFFE53935), Color(0xFFB71C1C)) // Red when dead
                            } else {
                                listOf(Color(0xFF43A047), Color(0xFF1B5E20)) // Green when alive
                            }
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .weight(rightWeight)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = 36.dp, bottomEnd = 36.dp))
                    .background(
                        Brush.verticalGradient(
                            if (isRoseDead) {
                                listOf(Color(0xFF43A047), Color(0xFF1B5E20)) // Green when dead
                            } else {
                                listOf(Color(0xFFE53935), Color(0xFFB71C1C)) // Red when alive
                            }
                        )
                    )
            )
        }

        // Labels and Icons with 3D Shadows
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                if (isRoseDead) {
                    Image(
                        painter = rememberAsyncImagePainter(R.drawable.rosalv, imageLoader = imageLoader),
                        contentDescription = "Rose Alive",
                        modifier = Modifier.size(36.dp)
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.crosswn),
                        contentDescription = "Don't Send",
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    if (isRoseDead) "Let it stay" else "Don't Send",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                if (isRoseDead) {
                    Image(
                        painter = rememberAsyncImagePainter(R.drawable.heartbreak, imageLoader = imageLoader),
                        contentDescription = "Broken Heart",
                        modifier = Modifier.size(36.dp)
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.gift),
                        contentDescription = "Send Gift",
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    if (isRoseDead) "Take It Back" else "Send Gift",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        // Draggable Thumb with 3D Pop
        Box(
            modifier = Modifier
                .offset { IntOffset(animatedOffset.toInt() + (maxOffsetPx / 2).toInt(), 0) }
                .size(thumbSize)
                .shadow(10.dp, CircleShape) // High Elevation for 3D Pop
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White, Color(0xFFE0E0E0))
                    )
                )
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX > maxOffsetPx * 0.4f) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSend()
                            } else if (offsetX < -maxOffsetPx * 0.4f) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onCancel()
                            } else {
                                offsetX = 0f
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val newOffset = offsetX + dragAmount
                            if (newOffset >= -maxOffsetPx / 2 && newOffset <= maxOffsetPx / 2) {
                            offsetX = newOffset
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
           Image(
                painter = rememberAsyncImagePainter(if (isRoseDead) R.drawable.rosestatic else R.drawable.croprose, imageLoader = imageLoader),
                contentDescription = "Thumb Rose",
                modifier = Modifier.size(60.dp),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun ReportUserDialog(
    onDismiss: () -> Unit,
    selectedReason: String?,
    onReasonSelected: (String) -> Unit,
    otherText: String,
    onOtherTextChanged: (String) -> Unit,
    onReport: () -> Unit
) {
    val reportReasons = listOf(
        "Harassment or Bullying",
        "Hate Speech",
        "Sexual Harassment / Explicit Content",
        "Fake Profile / Impersonation",
        "Scam or Fraud",
        "Spam or Advertising",
        "Threats or Violence",
        "Illegal Activity",
        "Underage User",
        "Inappropriate Messages",
        "Other"
    )
    
    // Theme-aware colors
    val titleColor = MaterialTheme.colorScheme.onSurface
    val subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurface
    val buttonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "Report User",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = titleColor
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Please select a reason for reporting this user:",
                    fontSize = 14.sp,
                    color = subtitleColor,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                reportReasons.forEach { reason ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onReasonSelected(reason) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedReason == reason,
                            onClick = { onReasonSelected(reason) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = reason,
                            fontSize = 15.sp,
                            color = textColor
                        )
                    }
                }
                
                // Show text field when "Other" is selected
                if (selectedReason == "Other") {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = otherText,
                        onValueChange = onOtherTextChanged,
                        label = {
                            Text(
                                "Please describe the issue",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        maxLines = 4,
                        placeholder = {
                            Text(
                                "Enter details here...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedTextColor = textColor,
                            unfocusedTextColor = textColor,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onReport,
                enabled = selectedReason != null && (selectedReason != "Other" || otherText.isNotBlank()),
                colors = buttonColors
            ) {
                Text("Report", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text("Cancel")
            }
        }
    )
}
