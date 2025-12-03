package com.example.anonychat.ui

import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import androidx.compose.foundation.BorderStroke
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.example.anonychat.R
import com.example.anonychat.model.User
import androidx.compose.ui.graphics.Shadow
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.scale

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class) // Add this
@Composable
fun ChatScreen(user: User) {
    var searchQuery by remember { mutableStateOf("") }
    val otherUsers = emptyList<User>()
    var roses by remember { mutableStateOf(0) }
    var sparks by remember { mutableStateOf(0) }
    var flowerKey by remember { mutableStateOf(0) }
    var thunderKey by remember { mutableStateOf(0) }
    val context = LocalContext.current

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            val videoUri = Uri.parse("android.resource://${context.packageName}/${R.raw.cloud}")
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        AndroidView(
            factory = { ctx ->
                // PlayerView doesn't support changing surface type programmatically.
                // Using TextureView directly gives you the TEXTURE_VIEW behavior (transparency support)
                // and MATCH_PARENT gives you the RESIZE_MODE_FILL behavior (no black bars).
                android.view.TextureView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // Bind the player to this specific TextureView
                    exoPlayer.setVideoTextureView(this)
                }
            },
            // Ensure the view fills the screen
            modifier = Modifier.fillMaxSize()
        )



        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Profile Header

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
                        // 1. White Circle Outline (Border)
                        .border(4.dp, Color.White, CircleShape)
                        // 2. Sky Blue Background
                        .background(Color(0xFF87CEEB), CircleShape) // Sky Blue hex code
                        .clip(CircleShape)
                ) {
                    Image(
                        // 3. Load from res/raw/profilepic
                        // Note: Coil can load raw resources using the android.resource URI scheme
                        painter = rememberAsyncImagePainter(
                            model = Uri.parse("android.resource://${context.packageName}/${R.raw.profilepic}")
                        ),
                        contentDescription = "Profile Picture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
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
                // REMOVED: Spacer(modifier = Modifier.weight(1f)) and the IconButton
            }




            // --- NEW: White Sheet Container for Search and List ---
            // --- UPDATED: Floating Glassy Card for Search and List ---
            // --- UPDATED: Floating Glassy Card for Search and List ---
            Surface(
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(32.dp),
                color = Color(0x4DFFFFFF) // Transparent Glassy White
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
                        color = Color.White, // Solid White Background
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
                                    color = Color(0xFF5A6B88),
                                    fontSize = 16.sp
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search Icon",
                                    tint = Color(0xFF5A6B88),
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFFEFF3FA),
                                unfocusedContainerColor = Color(0xFFEFF3FA),
                                disabledContainerColor = Color(0xFFEFF3FA),
                                cursorColor = Color(0xFF5A6B88),
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                focusedTextColor = Color(0xFF2D3648),
                                unfocusedTextColor = Color(0xFF2D3648)
                            ),
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
        // ... (Your existing AndroidView and Column code) ...

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
                .padding(end = 2.dp,bottom = 50.dp),
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
                        .data(Uri.parse("android.resource://${context.packageName}/${R.drawable.bird}"))
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
                        // 4. Add your action here
                       // android.widget.Toast.makeText(context, "Bird Clicked!", android.widget.Toast.LENGTH_SHORT).show()
                    }
            )
        }

    }
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
                        .background(Color(0xFFFFD700), CircleShape), // Gold/Yellow badge
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "2", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
