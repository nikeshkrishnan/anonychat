package com.example.anonychat.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.example.anonychat.R
import com.example.anonychat.ui.theme.AnonychatTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

@OptIn(UnstableApi::class) 
@Composable
fun LoginScreen(
    onLoginClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val transitionColor = Color(0xFFDBF0F9) 
    
    // State for checkboxes
    var acceptedTerms by remember { mutableStateOf(false) }
    var isEighteenPlus by remember { mutableStateOf(false) }
    var noAbuse by remember { mutableStateOf(false) }
    
    // State for loading
    var isLoading by remember { mutableStateOf(false) }
    
    // Handle Back Button Press to stop loading
    BackHandler(enabled = isLoading) {
        isLoading = false
    }
    
    val isLoginEnabled = acceptedTerms && isEighteenPlus && noAbuse && !isLoading

    // Root Box to allow overlaying the heart
    Box(modifier = Modifier.fillMaxSize()) {
        
        // Main Content Column
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Half: Video
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                VideoBackground(context = context)
                
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    transitionColor 
                                )
                            )
                        )
                ) {} 
            }
            
            // Bottom Half: Sky Background Image & Login
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.White), 
                contentAlignment = Alignment.Center
            ) {
                val imageBitmap = remember(context) {
                    BitmapFactory.decodeResource(context.resources, R.raw.cloud)?.asImageBitmap()
                }

                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "Sky Background",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    transitionColor,
                                    Color.Transparent
                                )
                            )
                        )
                ) {} 
                
                // Login Card
                Card(
                    modifier = Modifier
                        .padding(start = 24.dp, end = 24.dp, bottom = 40.dp) 
                        .fillMaxWidth()
                        .navigationBarsPadding() 
                        .imePadding(), 
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(20.dp) 
                            .verticalScroll(rememberScrollState()), 
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Welcome to AnonyChat",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp 
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp)) 
                        
                        Text(
                            text = "You will remain anonymous",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )

                        Spacer(modifier = Modifier.height(16.dp)) 
                        
                        // Checkboxes
                        TermsCheckbox(
                            checked = acceptedTerms,
                            onCheckedChange = { acceptedTerms = it },
                            text = "I accept Terms and Conditions and Privacy Policy"
                        )
                        
                        TermsCheckbox(
                            checked = isEighteenPlus,
                            onCheckedChange = { isEighteenPlus = it },
                            text = "I confirm I am 18 years or older"
                        )
                        
                        TermsCheckbox(
                            checked = noAbuse,
                            onCheckedChange = { noAbuse = it },
                            text = "Abuse and inappropriate content won't be tolerated"
                        )
                        
                        Spacer(modifier = Modifier.height(20.dp)) 
                        
                        Button(
                            onClick = {
                                isLoading = true
                                onLoginClick()
                            },
                            enabled = isLoginEnabled || isLoading, 
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4285F4),
                                disabledContainerColor = Color.Gray
                            )
                        ) {
                            Text(
                                text = "Proceed to setup profile", 
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // Loading Overlay with GIF Heart and 3D Fallback
        if (isLoading) {

                // ---------------------------------------------------------
                // VIBRATION LOGIC (Heartbeat Rhythm)
                //---------------------------------------------------------
                DisposableEffect(Unit) {
                    val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                        vibratorManager.defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    }

                    // Create a Coroutine scope to run the repeating pattern
                    val job = kotlinx.coroutines.GlobalScope.launch {
                        while (true) {
                            if (vibrator.hasVibrator()) {
                                // "Dub" (Stronger, longer)
                                if (Build.VERSION.SDK_INT >= 26) {
                                    vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
                                } else {
                                    @Suppress("DEPRECATION")
                                    vibrator.vibrate(60)
                                }

                                delay(200) // Short pause between Dub and Thub

                                // "Thub" (Softer, slightly shorter)
                                if (Build.VERSION.SDK_INT >= 26) {
                                    vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
                                } else {
                                    @Suppress("DEPRECATION")
                                    vibrator.vibrate(40)
                                }

                                // Wait for the next beat (Total cycle matches animation ~600ms)
                                delay(1000)
                            }
                        }
                    }

                    onDispose {
                        job.cancel() // Stop the vibration loop when loading finishes
                        vibrator.cancel() // Stop any currently running vibration
                    }
                }
                // ---------------------------------------------------------




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
                        // Fallback to Code-Generated 3D Spinning Heart if GIF fails
                        CodeGenerated3DHeart()
                    } else {
                        SubcomposeAsyncImageContent()
                    }
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

@Composable
fun TermsCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp), 
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF4285F4)),
            modifier = Modifier.size(36.dp) 
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color.Black,
            fontSize = 13.sp, 
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
@OptIn(UnstableApi::class)
fun VideoBackground(context: Context) {
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val videoUri = Uri.parse("android.resource://${context.packageName}/${R.raw.video_ending_suggestion}")
            setMediaItem(MediaItem.fromUri(videoUri))
            
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f // Mute the video
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false // Hide controls
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Preview(showBackground = true)
@Composable
fun LoginScreenPreview() {
    AnonychatTheme {
        LoginScreen()
    }
}
