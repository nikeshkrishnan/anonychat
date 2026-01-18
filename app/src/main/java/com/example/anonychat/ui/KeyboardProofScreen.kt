package com.example.anonychat.ui

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.view.ViewTreeObserver
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import coil.compose.rememberAsyncImagePainter
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import com.example.anonychat.model.Preferences
import com.example.anonychat.model.User
import com.example.anonychat.network.WebSocketManager
import com.example.anonychat.network.WebSocketEvent
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import com.example.anonychat.R
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
/* ---------------- MESSAGE STATUS ---------------- */

enum class MessageStatus {
    Pending, Sending, Delivered, Failed
}

/* ---------------- CHAT MESSAGE MODEL (with status) ---------------- */

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val from: String, // email/gmail
    val to: String,   // email/gmail
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.Delivered
)

/* ---------------- KEYBOARD VISIBILITY DETECTOR ---------------- */

@Composable
private fun rememberKeyboardVisible(): State<Boolean> {
    val view = LocalView.current
    val keyboardVisible = remember { mutableStateOf(false) }

    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = Rect()
            view.getWindowVisibleDisplayFrame(rect)
            val screenHeight = view.rootView.height
            val keypadHeight = screenHeight - rect.bottom
            keyboardVisible.value = keypadHeight > screenHeight * 0.15
        }

        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        listener.onGlobalLayout()

        onDispose {
            view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }

    return keyboardVisible
}

/* ---------------- SCREEN ---------------- */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val lifecycleOwner = LocalLifecycleOwner.current

    var input by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>().apply { addAll(initialMessages) } }
    Log.e("KeyboardProofScreen", "start → $matchedUserGmail")

    val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    val localGmail = prefs.getString("user_email", "") ?: ""
    LaunchedEffect(matchedUserGmail) {
        if (matchedUserGmail.isNotBlank() && localGmail.isNotBlank()) {
            Log.e("ChatLifecycle", "CHAT OPENED → me: $localGmail  ↔  partner: $matchedUserGmail")
            WebSocketManager.sendChatOpen(matchedUserGmail)
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
                Log.e("ChatLifecycle", "CHAT CLOSED → me: $localGmail  ↔  partner: $matchedUserGmail")
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
    val avatarResName = if (matchedUserPrefs.gender == "female") "female_exp$emotion" else "male_exp$emotion"
    val avatarResId = remember {
        context.resources.getIdentifier(avatarResName, "raw", context.packageName)
    }
    val avatarUri = remember {
        if (avatarResId != 0)
            Uri.parse("android.resource://${context.packageName}/$avatarResId")
        else
            Uri.parse("android.resource://${context.packageName}/${R.raw.male_exp1}")
    }

    /* ---------------- BACKGROUND VIDEO ---------------- */
    val exoPlayer = remember(isDarkTheme) {
        ExoPlayer.Builder(context).build().apply {
            val videoUri = if (isDarkTheme)
                Uri.parse("android.resource://${context.packageName}/${R.raw.night}")
            else
                Uri.parse("android.resource://${context.packageName}/${R.raw.cloud}")
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    /* ---------------- KEYBOARD VISIBILITY ---------------- */
    val isKeyboardVisible by rememberKeyboardVisible()

    /* ---------------- AUTO SCROLL: NEW MESSAGES ---------------- */
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE)
        }
    }

    /* ---------------- AUTO SCROLL: KEYBOARD OPEN ---------------- */
    LaunchedEffect(isKeyboardVisible) {
        if (isKeyboardVisible && messages.isNotEmpty()) {
            snapshotFlow { listState.layoutInfo.totalItemsCount }
                .filter { it > 0 }
                .first()

            var stableCount = 0
            var previousHeight = 0
            while (stableCount < 2) {
                delay(25)
                val currentHeight = listState.layoutInfo.viewportSize.height
                if (currentHeight == previousHeight && currentHeight > 0) stableCount++ else stableCount = 0
                previousHeight = currentHeight
            }
            listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE)
        }
    }

    /* ---------------- WEBSOCKET EVENTS ---------------- */
    LaunchedEffect(Unit) {
        WebSocketManager.events.collect { event ->
            when (event) {

                is WebSocketEvent.NewMessage -> {
                    if (event.message.from == matchedUserGmail &&
                        event.message.to == localGmail
                    ) {
                        messages.add(
                            event.message.copy(status = MessageStatus.Delivered)
                        )
                    }
                }
                is WebSocketEvent.MessageSentAck -> {
                    val index = messages.indexOfFirst { it.id == event.messageId }
                    if (index != -1) {
                        val current = messages[index].status
                        // DO NOT downgrade Delivered → Sending
                        if (current == MessageStatus.Pending) {
                            messages[index] =
                                messages[index].copy(status = MessageStatus.Sending)
                        }
                    }
                }


                is WebSocketEvent.DeliveryAck -> {
                    Log.e("ChatWebSocket", "-> Message delivered")
                    val index = messages.indexOfFirst { it.id == event.messageId }
                    Log.e("ChatWebSocket", "-> Message delivered $index")

                    if (index != -1) {
                        messages[index] =
                            messages[index].copy(status = MessageStatus.Delivered)
                    }
                }

                is WebSocketEvent.DeliveryFailed -> {
                    Log.e("ChatWebSocket", "-> Message failed")
                    val index = messages.indexOfFirst { it.id == event.messageId }
                    if (index != -1) {
                        messages[index] =
                            messages[index].copy(status = MessageStatus.Failed)
                    }
                }
            }
        }
    }


    /* ---------------- UI ---------------- */
    Box(Modifier.fillMaxSize()) {
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

        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            containerColor = Color.Transparent,
            topBar = {
                Surface(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
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
                                painter = rememberAsyncImagePainter(avatarUri),
                                contentDescription = null,
                                modifier = Modifier.padding(2.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        }

                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = matchedUser.username,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = headerContentColor
                        )
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        KeyboardMessageBubble(
                            message = msg,
                            isMe = msg.from == localGmail,
                            incomingBubbleColor = incomingBubbleColor,
                            outgoingBubbleColor = outgoingBubbleColor,
                            messageTextColor = messageTextColor
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
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
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 17,
                            textStyle = TextStyle(
                                color = messageTextColor,
                                fontSize = 16.sp,
                                lineHeight = 20.sp
                            ),
                            cursorBrush = SolidColor(messageTextColor),
                            decorationBox = { inner ->
                                Box(Modifier.padding(14.dp), contentAlignment = Alignment.CenterStart) {
                                    if (input.isEmpty()) Text("Message", color = Color.Gray)
                                    inner()
                                }
                            }
                        )
                    }

                    val sendMessage = {
                        val text = input.trim()
                        if (text.isNotBlank() && localGmail.isNotEmpty() && matchedUserGmail.isNotEmpty()) {
                            val localId = UUID.randomUUID().toString()
                            val newMsg = ChatMessage(
                                id = localId,
                                from = localGmail,
                                to = matchedUserGmail,
                                text = text,
                                status = MessageStatus.Pending
                            )
                            messages.add(newMsg)
                            input = ""
                            Log.e("from to!!!!!!!!!!!!","${localGmail} to ${matchedUserGmail}")

                            WebSocketManager.sendMessage(matchedUserGmail, text, localId)
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = sendButtonColor
                    ) {
                        IconButton(onClick = sendMessage) {
                            Icon(Icons.Default.Send, null, tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/* ---------------- MESSAGE BUBBLE WITH STATUS ICONS ---------------- */

@Composable
private fun KeyboardMessageBubble(
    message: ChatMessage,
    isMe: Boolean,
    incomingBubbleColor: Color,
    outgoingBubbleColor: Color,
    messageTextColor: Color
) {
    val time = remember(message.timestamp) {
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(message.timestamp))
    }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isMe) outgoingBubbleColor else incomingBubbleColor,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                message.text,
                Modifier.padding(12.dp),
                color = messageTextColor
            )
        }

        Row(
            modifier = Modifier.padding(top = 4.dp, start = if (isMe) 0.dp else 4.dp, end = if (isMe) 4.dp else 0.dp),
            horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(time, fontSize = 11.sp, color = Color.Gray)

            if (isMe) {
                Spacer(Modifier.width(4.dp))
                when (message.status) {
                    MessageStatus.Pending -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Color.Gray
                    )
                    MessageStatus.Sending -> Icon(Icons.Default.Done, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                    MessageStatus.Delivered -> Icon(Icons.Default.DoneAll , null, tint = Color.Green, modifier = Modifier.size(16.dp))
                    MessageStatus.Failed -> Icon(Icons.Default.Close, null, tint = Color.Red,  modifier = Modifier.size(14.dp).offset(x = (-5).dp))
                }
            }
        }
    }
}

/* ---------------- PREVIEW ---------------- */

@Preview(device = Devices.PIXEL_6, showSystemUi = true)
@Composable
fun KeyboardProofScreenPreview() {
    val user = User("me_user", "Me", "1")
    val prefs = Preferences(2f, 8f, "female")
    val sampleMessages = listOf(
        ChatMessage(from = "other@gmail.com", to = "me@gmail.com", text = "Hey!", status = MessageStatus.Delivered),
        ChatMessage(from = "me@gmail.com", to = "other@gmail.com", text = "Hi there!", status = MessageStatus.Delivered),
        ChatMessage(from = "me@gmail.com", to = "other@gmail.com", text = "This is a pending message", status = MessageStatus.Pending),
        ChatMessage(from = "me@gmail.com", to = "other@gmail.com", text = "This is sending...", status = MessageStatus.Sending),
        ChatMessage(from = "me@gmail.com", to = "other@gmail.com", text = "Delivered ✓✓", status = MessageStatus.Delivered),
        ChatMessage(from = "me@gmail.com", to = "other@gmail.com", text = "Failed", status = MessageStatus.Failed)
    )

    KeyboardProofScreen(
        currentUser = user,
        matchedUser = user,
        matchedUserPrefs = prefs,
        matchedUserGmail = "other@gmail.com",
        onBack = {},
        initialMessages = sampleMessages,
        forceDarkTheme = false
    )
}