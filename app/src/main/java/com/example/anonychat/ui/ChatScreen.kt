package com.example.anonychat.ui

import android.content.Context
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.anonychat.model.User
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Animatable2
import android.graphics.drawable.Drawable
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.P)
private fun registerPlayOnceCallback(
    drawable: AnimatedImageDrawable,
    onEnd: () -> Unit
): Animatable2.AnimationCallback {
    val cb = object : Animatable2.AnimationCallback() {
        override fun onAnimationEnd(d: Drawable?) {
            android.util.Log.e("ProfileAnim", "onAnimationEnd CALLED for drawable: ${d?.javaClass?.simpleName}")
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

private fun romanceRangeToEmotion(rangeStart: Float, rangeEnd: Float): Int {
    // Explicit examples preserved: 1-2 -> 1, 9-10 -> 11
    if (rangeEnd <= 2f) return 1
    if (rangeStart >= 9f && rangeEnd == 10f) return 11

    val mid = (rangeStart + rangeEnd) / 2f

    return when {
        mid < 1.95f -> 1
        mid < 2.95f -> 2
        mid < 3.95f -> 3
        mid < 4.95f -> 4
        mid < 5.95f -> 5
        mid < 6.95f -> 6
        mid < 7.95f -> 7
        mid < 8.95f -> 8
        mid < 9.5f -> 9
        mid < 9.9f -> 10
        else -> 11
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun ChatScreen(
    user: User,
    onChatActive: () -> Unit,
    onChatInactive: () -> Unit,
    onNavigateToProfile: (String) -> Unit
) {
    // notify MainActivity that chat is now active
    LaunchedEffect(Unit) { onChatActive() }

    // when leaving ChatScreen
    DisposableEffect(Unit) {
        onDispose { onChatInactive() }
    }

    var searchQuery by remember { mutableStateOf("") }
    val otherUsers = emptyList<User>()
    var roses by remember { mutableStateOf(0) }
    var sparks by remember { mutableStateOf(0) }
    var flowerKey by remember { mutableStateOf(0) }
    var thunderKey by remember { mutableStateOf(0) }
    val context = LocalContext.current

    val themePrefs = remember { context.getSharedPreferences("anonychat_theme", Context.MODE_PRIVATE) }
    var isDarkTheme by remember { mutableStateOf(themePrefs.getBoolean("is_dark_theme", false)) }
    var showThemeDialog by remember { mutableStateOf(false) }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Choose Theme") },
            text = {
                Column {
                    TextButton(onClick = {
                        isDarkTheme = false
                        showThemeDialog = false
                        themePrefs.edit().putBoolean("is_dark_theme", false).apply()
                    }) { Text("Light Theme") }
                    TextButton(onClick = {
                        isDarkTheme = true
                        showThemeDialog = false
                        themePrefs.edit().putBoolean("is_dark_theme", true).apply()
                    }) { Text("Dark Theme") }
                }
            },
            confirmButton = { }
        )
    }

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
    // Register cleanup callback for PiP mode
    LaunchedEffect(exoPlayer) {
        ChatScreenPipController.onBeforeEnterPip = {
            exoPlayer.pause()
            exoPlayer.stop()
            exoPlayer.clearVideoSurface()
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 50.dp, end = 24.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(90.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .background(
                            if (isDarkTheme) Color(0xFF142235) else Color(0xFF87CEEB),
                            CircleShape
                        )
                        .clip(CircleShape)
                        .clickable { onNavigateToProfile(user.username) }
                ) {
                    val userPrefs = remember { context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE) }
                    val gender = userPrefs.getString("gender", "male") ?: "male"
                    val romanceStart = userPrefs.getFloat("romance_min", 2f)
                    val romanceEnd = userPrefs.getFloat("romance_max", 9f)

                    val emotion = romanceRangeToEmotion(romanceStart, romanceEnd)
                    val resName = if (gender == "female") "female_exp$emotion" else "male_exp$emotion"
                    val imageResId = context.resources.getIdentifier(resName, "raw", context.packageName)
                    val imageUri = if (imageResId != 0) Uri.parse("android.resource://${context.packageName}/$imageResId") else Uri.parse("android.resource://${context.packageName}/${R.raw.male_exp1}")

                    Crossfade(targetState = imageUri, animationSpec = tween(500)) { uri ->
                        Image(
                            painter = rememberAsyncImagePainter(
                                model = ImageRequest.Builder(context)
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
                            text = "$roses",
                            style = TextStyle(
                                color = Color(0xFFFF1053), // Vibrant Red-Pink
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                shadow = Shadow(
                                    color = Color.Black.copy(alpha = 0.5f),
                                    offset = Offset(3f, 3f), // Moves shadow down-right for 3D look
                                    blurRadius = 3f
                                )
                            )
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        val imageLoader = remember(context) {
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
                        Image(
                            painter = rememberAsyncImagePainter(
                                model = ImageRequest.Builder(context)
                                    .data(R.drawable.rose)
                                    .memoryCacheKey(flowerKey.toString())
                                    .build(),
                                imageLoader = imageLoader
                            ),
                            contentDescription = "Roses",
                            modifier = Modifier
                                .size(24.dp)
                                .clickable { flowerKey++ }
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        // 2. SPARK COUNT (3D & Bright)
                        Text(
                            text = "$sparks",
                            style = TextStyle(
                                color = Color(0xFFFFD700), // Bright Gold/Yellow
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                shadow = Shadow(
                                    color = Color.Black.copy(alpha = 0.5f),
                                    offset = Offset(3f, 3f), // Moves shadow down-right for 3D look
                                    blurRadius = 3f
                                )
                            )
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        Image(
                            painter = rememberAsyncImagePainter(
                                model = ImageRequest.Builder(context)
                                    .data(R.drawable.thunder)
                                    .memoryCacheKey(thunderKey.toString())
                                    .build(),
                                imageLoader = imageLoader
                            ),
                            contentDescription = "Sparks",
                            modifier = Modifier
                                .size(24.dp)
                                .clickable { thunderKey++ }
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




            // --- NEW: White Sheet Container for Search and List ---
            // --- UPDATED: Floating Glassy Card for Search and List ---
            // --- UPDATED: Floating Glassy Card for Search and List ---
            Surface(
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(32.dp),
                color = if (isDarkTheme) Color(0x4DFFFFFF) else Color(0x4DFFFFFF) // Transparent Glassy
            ) {

                // REMOVED padding(16.dp) from this Column so the Search Bar touches edges
                Column {

                    // --- SEARCH BAR (Top Rounded "Tab" Style) ---
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(),
                        // Top corners match the parent card (32.dp), bottom corners are slightly less rounded (8.dp)
                        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomStart = 8.dp, bottomEnd = 8.dp),
                        color = if (isDarkTheme) Color(0xFF3A4552) else Color.White, // Moon-glow night soft white vs Solid White Background
                        shadowElevation = 0.dp
                    ) {
                        // Inner Text Field
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp) // Inner padding for the text field itself
                                .height(50.dp),
                            shape = RoundedCornerShape(50),
                            placeholder = {
                                Text(
                                    text = "Search",
                                    color = if (isDarkTheme) Color(0xFF8FA4C0) else Color(0xFF5A6B88), // Muted night-blue vs muted gray
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
                                TextFieldDefaults.colors( //night theme
                                    focusedContainerColor = Color(0xFF121821),    // near-black navy
                                    unfocusedContainerColor = Color(0xFF121821),
                                    disabledContainerColor = Color(0xFF10151C),   // even darker

                                    cursorColor = Color(0xFFEFF3FA),              // moon-white cursor

                                    focusedTextColor = Color.White,               // text visible on black
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

                    // --- USER LIST ---
                    // Added padding here because we removed it from the parent Column
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        otherUsers.forEach { otherUser ->
                            UserListItem(user = otherUser)
                        }
                    }
                }
            }
        }
    }
    // ... (Your existing AndroidView and Column code) ...
    var isLoading by remember { mutableStateOf(false) }
    BackHandler(enabled = isLoading) {
        isLoading = false   // stop the heart overlay
    }
    // --- BIRD GIF (Bottom Right Corner) ---
    val birdImageLoader = remember(context) {
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
        modifier = Modifier
            .fillMaxSize()
            .padding(end = 2.dp, bottom = 50.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

        // 1. Detect if the user is pressing down
        val isPressed by interactionSource.collectIsPressedAsState()
        // 2. Animate scale: Shrink to 0.9f when pressed, back to 1f when released
        val scale by animateFloatAsState(
            targetValue = if (isPressed) 0.7f else 1f, // Changed 0.9f to 0.7f
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioHighBouncy, // Makes it wobble/bounce on release
                stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
            ),
            label = "bird_bounce"
        )

        Image(
            painter = rememberAsyncImagePainter(
                model = ImageRequest.Builder(context)
                    // Ensure this path matches your file (raw vs drawable)
                    .data(Uri.parse("android.resource://${context.packageName}/${if (isDarkTheme) R.drawable.owlt else R.drawable.bird}"))
                    .build(),
                imageLoader = birdImageLoader
            ),
            contentDescription = "Search Bird",
            modifier = Modifier
                .size(150.dp)
                // 3. Apply the bounce animation
                .scale(scale)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null // Keep this null to avoid the square shadow box
                ) {
                    isLoading = true                         // 4. Add your action here
                    // android.widget.Toast.makeText(context, "Bird Clicked!", android.widget.Toast.LENGTH_SHORT).show()
                }
        )
    }
    LoadingHeartOverlay(isLoading = isLoading)
}


@Composable
fun UserListItem(user: User) {
    // WRAP content in a Surface to create the "Thick Card" look with a boundary
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp), // Add spacing between chat messages
        shape = RoundedCornerShape(24.dp), // Rounded corners for the message card
        color = Color(0xF2FFFFFF), // High opacity (Thick) white background
        border = BorderStroke(3.dp, Color.White), // The white boundary
        shadowElevation = 2.dp // Subtle shadow for depth
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp), // Inner padding
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberAsyncImagePainter(model = user.profilePictureUrl ?: R.drawable.ic_launcher_background),
                contentDescription = "User Profile Picture",
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = user.username, fontWeight = FontWeight.Bold, color = Color.Black)
                Text(text = "Great, thanks!", color = Color.Gray, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(text = "23:23", color = Color.Gray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                )
            }
        }
    }
}
