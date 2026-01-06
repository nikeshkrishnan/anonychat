package com.example.anonychat

import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

import android.app.Activity
import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.net.Uri
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
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import com.example.anonychat.model.Preferences
import com.example.anonychat.model.User
import com.example.anonychat.ui.ChatMessage
import com.example.anonychat.ui.romanceRangeToEmotion
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.plusAssign

/* ---------------- MESSAGE MODEL ---------------- */

data class SimpleChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isMe: Boolean,
    val timestamp: Long = System.currentTimeMillis()
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

            // Threshold: keyboard usually takes >15% of screen height
            keyboardVisible.value = keypadHeight > screenHeight * 0.15
        }

        view.viewTreeObserver.addOnGlobalLayoutListener(listener)

        // Initial check
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
fun KeyboardProofScreen(  currentUser: User,
                          matchedUser: User,
                          matchedUserPrefs: Preferences,
                          onBack: () -> Unit,
                          initialMessages: List<ChatMessage> = emptyList(),
                          forceDarkTheme: Boolean? = null
) {

    val context = LocalContext.current
    val view = LocalView.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>().apply { addAll(initialMessages) } }

    /* ---------------- SYSTEM UI ---------------- */

    SideEffect {
        val window = (view.context as Activity).window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
    }

    /* ---------------- THEME ---------------- */
    val prefs = remember {
        context.getSharedPreferences("anonychat_theme", Context.MODE_PRIVATE)
    }
    val themePrefs = remember { context.getSharedPreferences("anonychat_theme", Context.MODE_PRIVATE) }
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
        romanceRangeToEmotion(
            matchedUserPrefs.romanceMin,
            matchedUserPrefs.romanceMax
        )
    }
    val avatarResName = if (matchedUserPrefs.gender == "female") "female_exp$emotion" else "male_exp$emotion"

    val avatarResId = remember {
        context.resources.getIdentifier(avatarResName, "raw", context.packageName)
    }
//    val avatarRes = remember {
//        context.resources.getIdentifier(
//            "female_exp$emotion",
//            "raw",
//            context.packageName
//        )
//    }

    val avatarUri = remember {
        if (avatarResId != 0)
            Uri.parse("android.resource://${context.packageName}/$avatarResId")
        else
            Uri.parse("android.resource://${context.packageName}/${R.raw.male_exp1}")
    }


    /* ---------------- BACKGROUND VIDEO ---------------- */

    val exoPlayer = remember {
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

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    /* ---------------- KEYBOARD VISIBILITY ---------------- */

    val isKeyboardVisible by rememberKeyboardVisible()

    /* ---------------- AUTO SCROLL: NEW MESSAGES (INSTANT, BOTTOM-ALIGNED) ---------------- */

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE)
        }
    }

    /* ---------------- AUTO SCROLL: KEYBOARD OPEN (WAIT FOR STABILIZATION, BOTTOM-ALIGNED) ---------------- */

    LaunchedEffect(isKeyboardVisible) {
        if (isKeyboardVisible && messages.isNotEmpty()) {
            // Wait for items to be laid out
            snapshotFlow { listState.layoutInfo.totalItemsCount }
                .filter { it > 0 }
                .first()

            // Wait until viewport height stabilizes (no change for ~100ms)
            var stableCount = 0
            var previousHeight = 0
            while (stableCount < 2) { // 2 consecutive stable checks (100ms stable)
                delay(25) // Check every 50ms
                val currentHeight = listState.layoutInfo.viewportSize.height
                if (currentHeight == previousHeight && currentHeight > 0) {
                    stableCount++
                } else {
                    stableCount = 0
                }
                previousHeight = currentHeight
            }

            // Instant scroll to bottom
            listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE)
        }
    }

    /* ---------------- UI ---------------- */

    Box(Modifier.fillMaxSize()) {

        /* VIDEO */
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

                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(40.dp).padding(start = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = headerContentColor
                            )
                        }
                        Spacer(Modifier.width(8.dp))

                        Surface(
                            shape = CircleShape,
                            color = avatarRingColor,
                            border = BorderStroke(2.dp, Color.White), // 👈 white border
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

            Box(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {

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
                            isMe = msg.from == currentUser.username,
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
                                Box(
                                    Modifier.padding(14.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (input.isEmpty()) {
                                        Text("Message", color = Color.Gray)
                                    }
                                    inner()
                                }
                            }
                        )

                    }
                    val sendMessage = {
                        val text = input.trim()
                        if (text.isNotBlank()) {
                            messages += ChatMessage(
                                from = currentUser.username,
                                to = matchedUser.username,
                                text = text
                            )
                            input = ""
                            scope.launch {
                                listState.animateScrollToItem(messages.lastIndex)
                            }
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = sendButtonColor
                    ) {
                        IconButton(
                            onClick = sendMessage
                        ) {
                            Icon(Icons.Default.Send, null, tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/* ---------------- MESSAGE BUBBLE ---------------- */

@Composable
private fun KeyboardMessageBubble(
    message: ChatMessage,
    isMe: Boolean,
    incomingBubbleColor: Color,
    outgoingBubbleColor: Color,
    messageTextColor: Color
) {
    val time = remember(message.timestamp) {
        SimpleDateFormat("hh:mm a", Locale.getDefault())
            .format(Date(message.timestamp))
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

        Text(
            time,
            fontSize = 11.sp,
            color = Color.Gray,
            modifier = Modifier.padding(4.dp)
        )
    }
}

