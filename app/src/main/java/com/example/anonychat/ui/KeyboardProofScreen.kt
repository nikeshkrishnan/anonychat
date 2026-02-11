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
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.AppCompatEditText
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.OnImageSavedCallback
import androidx.camera.core.ImageCapture.OutputFileOptions
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.anonychat.MainActivity
import com.example.anonychat.R
import com.example.anonychat.model.Preferences
import com.example.anonychat.model.User
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
import kotlinx.coroutines.flow.isActive
import kotlinx.coroutines.isActive

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
        forceDarkTheme: Boolean? = null
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

    var showFloatingDateBanner by remember { mutableStateOf(false) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            showFloatingDateBanner = true
        } else {
            delay(1500)
            showFloatingDateBanner = false
        }
    }
    var presenceStatus by remember {
        mutableStateOf(if (matchedUserPrefs.isOnline) "online" else "offline")
    }
    var lastSeenTime by remember { mutableStateOf(matchedUserPrefs.lastSeen) }
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
    if (fullScreenVideoUri != null) {
        BackHandler { fullScreenVideoUri = null }
    } else if (fullScreenImageUri != null) {
        BackHandler { fullScreenImageUri = null }
    } else if (capturedImageUri != null) {
        BackHandler { capturedImageUri = null }
    } else if (showFullScreenCamera) {
        BackHandler { showFullScreenCamera = false }
    } else if (showAdvancedMediaPicker) {
        BackHandler {
            showAdvancedMediaPicker = false
            selectedGalleryImages.clear()
        }
    }

    val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    val localGmail = prefs.getString("user_email", "") ?: ""
    LaunchedEffect(matchedUserGmail) {
        if (matchedUserGmail.isNotBlank() && localGmail.isNotBlank()) {
            Log.e("ChatLifecycle", "CHAT OPENED → me: $localGmail  ↔  partner: $matchedUserGmail")
            WebSocketManager.sendChatOpen(matchedUserGmail)

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
                    // Screen became visible (app foreground + screen on top)
                    if (matchedUserGmail.isNotBlank() && localGmail.isNotBlank()) {
                        Log.e("ChatLifecycle", "CHAT VISIBLE → $matchedUserGmail")
                        WebSocketManager.sendChatOpen(matchedUserGmail)
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    // Screen no longer visible (either backgrounded or navigated away)
                    if (matchedUserGmail.isNotBlank() && localGmail.isNotBlank()) {
                        Log.e("ChatLifecycle", "CHAT HIDDEN → $matchedUserGmail")
                        //  WebSocketManager.sendChatClose(matchedUserGmail)
                    }
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            if (matchedUserGmail.isNotBlank() && localGmail.isNotBlank()) {
                Log.e(
                        "ChatLifecycle",
                        "CHAT CLOSED → me: $localGmail  ↔  partner: $matchedUserGmail"
                )
                // WebSocketManager.sendChatClose(matchedUserGmail)   // ← You should implement this
            }
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
    val emotion = remember {
        romanceRangeToEmotion(matchedUserPrefs.romanceMin, matchedUserPrefs.romanceMax)
    }
    val avatarResName =
            if (matchedUserPrefs.gender == "female") "female_exp$emotion" else "male_exp$emotion"
    val avatarResId = remember {
        context.resources.getIdentifier(avatarResName, "raw", context.packageName)
    }
    val avatarUri = remember {
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
                    if (event.message.from == matchedUserGmail && event.message.to == localGmail) {
                        messages.upsert(event.message.copy(status = MessageStatus.Delivered))
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
    LaunchedEffect(listState, messages.size, isMediaOverlayActive) {
        // Skip read acks while in fullscreen media mode
        if (isMediaOverlayActive) return@LaunchedEffect

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
                                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.ArrowBack, "Back", tint = headerContentColor)
                                }
                                Spacer(Modifier.width(8.dp))

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

                                Spacer(Modifier.width(12.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                            text = matchedUser.username,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 16.sp,
                                            color = headerContentColor
                                    )
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
                                                "Offline"
                                            }
                                    Text(
                                            text = presenceText,
                                            fontWeight = FontWeight.Normal,
                                            fontSize = 12.sp,
                                            color = headerContentColor.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                                                        Color(0x33000000)
                                                                .compositeOver(
                                                                        Color.Gray.copy(
                                                                                alpha = 0.1f
                                                                        )
                                                                ),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier =
                                                        Modifier.padding(
                                                                horizontal = 16.dp,
                                                                vertical = 4.dp
                                                        )
                                        ) {
                                            Text(
                                                    text = item.date,
                                                    color = messageTextColor.copy(alpha = 0.8f),
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
                                                Color(0xCC000000)
                                                        .compositeOver(
                                                                Color.Gray.copy(alpha = 0.2f)
                                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier =
                                                Modifier.padding(
                                                        horizontal = 16.dp,
                                                        vertical = 4.dp
                                                )
                                ) {
                                    Text(
                                            text = date,
                                            color = messageTextColor.copy(alpha = 0.9f),
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
        }
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
