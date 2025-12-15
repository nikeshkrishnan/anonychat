package com.example.anonychat.ui

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.example.anonychat.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PipBirdOnly() {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(false) }

    // This alpha is used to fade between the bird and the heart
    val alpha by animateFloatAsState(
        targetValue = if (isLoading) 0f else 1f,
        animationSpec = tween(500),
        label = "pipFade"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent), // Important for the floating bubble
        contentAlignment = Alignment.Center
    ) {
        // This is the 'loading' state that shows the heart
        if (isLoading) {
            // --- HEART BEAT ANIMATION LOGIC ---
            var heartState by remember { mutableStateOf(false) }
            val heartScale by animateFloatAsState(
                targetValue = if (heartState) 1.2f else 1f,
                animationSpec = tween(durationMillis = 600),
                label = "heartBeat"
            )

            // This LaunchedEffect runs as long as the heart is visible (isLoading = true)
            LaunchedEffect(isLoading) {
                // The timer to hide the heart after 30 seconds
                launch {
                    delay(30000)
                    isLoading = false
                }
                // The loop to make the heart beat
                launch {
                    while (true) {
                        heartState = !heartState
                        delay(600) // The duration of one beat (matches animation spec)
                    }
                }
            }

            Image(
                painter = rememberAsyncImagePainter(
                    model = R.drawable.heart, // Assuming you have a heart drawable
                ),
                contentDescription = "Loading",
                modifier = Modifier
                    .size(80.dp)
                    .scale(heartScale) // Apply the beating animation
            )
        }

        // This is the main content that shows the bird
        // It's wrapped in a Box to apply the fade animation and click handler
        Box(
            modifier = Modifier
                .size(150.dp) // The size of the clickable area
                .graphicsLayer {
                    // Apply the fade out/in animation
                    this.alpha = alpha
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null, // This removes the ripple effect
                    onClick = {
                        if (!isLoading) isLoading = true
                    }
                )
        ) {
            val imageLoader = remember(context) {
                ImageLoader.Builder(context)
                    .components {
                        if (Build.VERSION.SDK_INT >= 28) {
                            add(ImageDecoderDecoder.Factory()) // For modern Android (Animated WebP)
                        } else {
                            add(GifDecoder.Factory()) // Fallback for older Android if needed
                        }
                    }
                    .build()
            }
            // Use Coil's Image composable to load and display the animated bird.webp
            Image(
                painter = rememberAsyncImagePainter(
                    model = R.drawable.bird, // Make sure your bird.webp is in res/raw or res/drawable
                    imageLoader = imageLoader
                ),
                contentDescription = "Floating Bird",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
