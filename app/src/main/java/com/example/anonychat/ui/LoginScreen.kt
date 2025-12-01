package com.example.anonychat.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.example.anonychat.R
import com.example.anonychat.ui.theme.AnonychatTheme

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
    
    val isLoginEnabled = acceptedTerms && isEighteenPlus && noAbuse

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
            )
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
            )
            
            // Login Card
            Card(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Welcome to AnonyChat",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
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
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = onLoginClick,
                        enabled = isLoginEnabled,
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
            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF4285F4))
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color.Black,
            fontSize = 12.sp,
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
        factory = {
            PlayerView(context).apply {
                player = exoPlayer
                useController = false // Hide controls
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
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
