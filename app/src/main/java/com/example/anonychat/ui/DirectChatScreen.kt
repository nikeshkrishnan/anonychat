package com.example.anonychat.ui

import android.content.Context
import android.net.Uri
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.example.anonychat.R
import com.example.anonychat.model.User
import com.example.anonychat.model.Preferences
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.graphics.Color as AndroidColor2
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.WindowInsets

/* ---------------------------------------------------
   MODELS
--------------------------------------------------- */

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val from: String,
    val to: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

/* ---------------------------------------------------
   SCREEN
--------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectChatScreen(
    currentUser: User,
    matchedUser: User,
    matchedUserPrefs: Preferences,
    onBack: () -> Unit,
    initialMessages: List<ChatMessage> = emptyList(),
    forceDarkTheme: Boolean? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current

    var input by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>().apply { addAll(initialMessages) } }

    val themePrefs = remember { context.getSharedPreferences("anonychat_theme", Context.MODE_PRIVATE) }
    val isDarkTheme = forceDarkTheme ?: themePrefs.getBoolean("is_dark_theme", false)

    /* -------- Avatar selection -------- */
    val emotion = remember {
        romanceRangeToEmotion(
            matchedUserPrefs.romanceMin,
            matchedUserPrefs.romanceMax
        )
    }

    val incomingBubbleColor = if (isDarkTheme) Color(0xFF141024) else Color(0xFFFFFFFF)
    val outgoingBubbleColor = if (isDarkTheme) Color(0xFF1E2A6D) else Color(0xFF1E88E5)
    val messageTextColor = if (isDarkTheme) Color(0xFFEAEAF0) else Color.Black

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

    /* -------- Auto-scroll to bottom -------- */
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    /* -------- Background video -------- */
    val exoPlayer = remember(context, isDarkTheme) {
        ExoPlayer.Builder(context).build().apply {
            val videoUri = if (isDarkTheme)
                Uri.parse("android.resource://${context.packageName}/${R.raw.night}")
            else
                Uri.parse("android.resource://${context.packageName}/${R.raw.cloud}")

            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
            setPlaybackSpeed(0.5f)
            prepare()
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    val view = LocalView.current
    SideEffect {
        val window = (view.context as android.app.Activity).window
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = AndroidColor2.TRANSPARENT
        window.navigationBarColor = AndroidColor2.TRANSPARENT
        val insetsController = WindowCompat.getInsetsController(window, view)
        insetsController.isAppearanceLightStatusBars = !isDarkTheme
        insetsController.isAppearanceLightNavigationBars = !isDarkTheme
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = exoPlayer
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(AndroidColor2.TRANSPARENT)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.systemBars,  // 👈 Critical: Ignore IME for scaffold layout
            topBar = {
                val headerContentColor = if (isDarkTheme) Color(0xFFE3F2FD) else Color.Black
                val avatarRingColor = if (isDarkTheme) Color(0xFF2E3A46) else Color(0xFF87CEEB)

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding(),
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
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

                        Surface(shape = CircleShape, color = Color.White, modifier = Modifier.size(46.dp)) {
                            Surface(
                                shape = CircleShape,
                                color = avatarRingColor,
                                modifier = Modifier.padding(2.dp).fillMaxSize()
                            ) {
                                Image(
                                    painter = rememberAsyncImagePainter(avatarUri),
                                    contentDescription = null,
                                    modifier = Modifier.padding(2.dp).fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        Text(
                            text = matchedUser.username,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = headerContentColor,
                            maxLines = 1
                        )
                    }
                }
            },
            bottomBar = {
                val imeBottom = WindowInsets.ime.getBottom(density)
                val isKeyboardVisible = imeBottom > 0

                val sendMessage = {
                    val text = input.trim()
                    if (text.isNotBlank()) {
                        messages += ChatMessage(
                            from = currentUser.username,
                            to = matchedUser.username,
                            text = text
                        )
                        input = ""
                        focusManager.clearFocus()
                        scope.launch {
                            listState.animateScrollToItem(messages.lastIndex)
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = if (isKeyboardVisible) 8.dp else 0.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f).heightIn(min = 54.dp, max = 220.dp),
                            shape = RoundedCornerShape(24.dp),
                            color = if (isDarkTheme) Color(0xFF1C2430) else Color(0xFFF2F6FB),
                            border = BorderStroke(1.dp, if (isDarkTheme) Color(0x33FFFFFF) else Color(0x22000000))
                        ) {
                            BasicTextField(
                                value = input,
                                onValueChange = { input = it },
                                singleLine = false,
                                maxLines = 17,
                                textStyle = TextStyle(
                                    color = if (isDarkTheme) Color.White else Color.Black,
                                    fontSize = 16.sp,
                                    lineHeight = 20.sp
                                ),
                                cursorBrush = SolidColor(if (isDarkTheme) Color.White else Color.Black),
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (input.isEmpty()) {
                                            Text("Message", color = Color.Gray, fontSize = 16.sp, lineHeight = 20.sp)
                                        }
                                        innerTextField()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().wrapContentHeight()
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        Surface(
                            modifier = Modifier.size(44.dp),
                            shape = CircleShape,
                            color = if (isDarkTheme) Color(0xFF0A84FF) else Color(0xFF1E88E5)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                IconButton(onClick = sendMessage, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(
                        message = msg,
                        isMe = msg.from == currentUser.username,
                        incomingBubbleColor = incomingBubbleColor,
                        outgoingBubbleColor = outgoingBubbleColor,
                        messageTextColor = messageTextColor
                    )
                }
            }
        }
    }
}
/* ---------------------------------------------------
   MESSAGE BUBBLE (unchanged)
--------------------------------------------------- */

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isMe: Boolean,
    incomingBubbleColor: Color,
    outgoingBubbleColor: Color,
    messageTextColor: Color
) {
    val time = remember(message.timestamp) {
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(message.timestamp))
    }

    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.75).dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = if (isMe) 12.dp else 4.dp,
                topEnd = if (isMe) 4.dp else 12.dp,
                bottomEnd = 12.dp,
                bottomStart = 12.dp
            ),
            color = if (isMe) outgoingBubbleColor else incomingBubbleColor,
            tonalElevation = 2.dp,
            modifier = Modifier
                .widthIn(max = maxWidth)
                .padding(horizontal = 4.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = message.text,
                    color = messageTextColor
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = time,
            fontSize = 11.sp,
            color = Color.Gray,
            modifier = Modifier.padding(start = if (isMe) 0.dp else 6.dp, end = if (isMe) 6.dp else 0.dp)
        )
    }
}

/* ---------------------------------------------------
   PREVIEW
--------------------------------------------------- */

@Preview(
    device = Devices.PIXEL_6,
    showSystemUi = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_NO
)
@Composable
fun DirectChatScreenPreview() {
    val user = User("ws_user1", "ws_user1", "1")
    val prefs = Preferences(2f, 8f, "female")

    val sampleMessages = listOf(
        ChatMessage(from = "ws_user1", to = "me", text = "Hey, how are you?"),
        ChatMessage(from = "me", to = "ws_user1", text = "Hi! Doing good — you?"),
        ChatMessage(from = "ws_user1", to = "me", text = "Just testing the new UI — looks great!")
    )

    DirectChatScreen(
        currentUser = user,
        matchedUser = user,
        matchedUserPrefs = prefs,
        onBack = {},
        initialMessages = sampleMessages,
        forceDarkTheme = false
    )
}