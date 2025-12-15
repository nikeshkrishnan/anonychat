package com.example.anonychat.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.example.anonychat.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatingsScreen(navController: NavController) {
    val context = LocalContext.current
    val themePrefs = remember { context.getSharedPreferences("anonychat_theme", Context.MODE_PRIVATE) }
    val isDarkTheme by remember { mutableStateOf(themePrefs.getBoolean("is_dark_theme", false)) }
    val textColor = if (isDarkTheme) Color.White else Color.Black

    val exoPlayer = remember(isDarkTheme) {
        ExoPlayer.Builder(context).build().apply {
            val uri =
                if (isDarkTheme)
                    Uri.parse("android.resource://${context.packageName}/${R.raw.night}")
                else
                    Uri.parse("android.resource://${context.packageName}/${R.raw.cloud}")

            setMediaItem(MediaItem.fromUri(uri))
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

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Your Ratings") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = textColor)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = textColor
                    )
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Overall Rating", style = MaterialTheme.typography.headlineMedium, color = textColor)
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "4.6",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = Color(0xFFFFD700),
                        modifier = Modifier.size(48.dp)
                    )
                }
                Text("Based on 128 reviews", style = MaterialTheme.typography.bodyLarge, color = textColor.copy(alpha = 0.7f))
            }
        }
    }
}
