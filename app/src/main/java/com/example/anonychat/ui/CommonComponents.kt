package com.example.anonychat.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.example.anonychat.R
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LoadingHeartOverlay(isLoading: Boolean) {
    if (isLoading) {
        val context = LocalContext.current

        // Haptic Feedback (Vibration)
        DisposableEffect(Unit) {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            val job = GlobalScope.launch {
                while (true) {
                    if (vibrator.hasVibrator()) {
                        if (Build.VERSION.SDK_INT >= 26) {
                            vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(60)
                        }
                        delay(200)
                        if (Build.VERSION.SDK_INT >= 26) {
                            vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(40)
                        }
                        delay(1000)
                    } else {
                        break
                    }
                }
            }
            onDispose {
                job.cancel()
                vibrator.cancel()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center
        ) {
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

            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(R.drawable.heart)
                    .crossfade(true)
                    .build(),
                imageLoader = imageLoader,
                contentDescription = "Loading Heart",
                modifier = Modifier.size(150.dp)
            ) {
                val state = painter.state
                if (state is AsyncImagePainter.State.Error) {
                    CodeGenerated3DHeart()
                } else {
                    SubcomposeAsyncImageContent()
                }
            }
        }
    }
}

@Composable
fun CodeGenerated3DHeart() {
    val infiniteTransition = rememberInfiniteTransition(label = "heart")

    // Throbbing Effect (Scale)
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    // Spinning Effect (Rotation Y)
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    val density = LocalDensity.current
    // 3D Heart
    Box(
        modifier = Modifier
            .size(100.dp)
            .scale(scale)
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density.density
            }
    ) {
        // Base Red Heart
        Icon(
            imageVector = Icons.Default.Favorite,
            contentDescription = "Loading",
            tint = Color.Red,
            modifier = Modifier.fillMaxSize()
        )

        // Shine/Highlight
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 15.dp, top = 15.dp)
                .size(25.dp, 15.dp)
                .graphicsLayer { rotationZ = -45f }
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.8f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
    }
}
