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
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.example.anonychat.model.User
import com.example.anonychat.network.NetworkClient
import com.example.anonychat.network.UserLoginRequest
import com.example.anonychat.network.UserRegistrationRequest
import com.example.anonychat.network.UserResetPasswordRequest
import com.example.anonychat.ui.theme.AnonychatTheme
import com.google.android.gms.appset.AppSet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@ExperimentalFoundationApi
@ExperimentalLayoutApi
@OptIn(UnstableApi::class, ExperimentalLayoutApi::class, ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class
)
@Composable
fun LoginScreen(
    onLoginClick: (User) -> Unit = {}
) {
    val context = LocalContext.current

    // Detect IME (keyboard) visibility
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    // Allow scrolling UP only once per keyboard-open session
    var imeHandled by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    val imeHeight = WindowInsets.ime.getBottom(density)

    // track if keyboard is visible
    val keyboardVisible = imeHeight > 0

    // shift the card up only once when keyboard shows
    var cardOffset by remember { mutableStateOf(0.dp) }

    // animate the movement
    val animatedOffset by animateDpAsState(
        targetValue = cardOffset,
        label = "card offset"
    )

    // when keyboard becomes visible → move up once
    LaunchedEffect(keyboardVisible) {
        if (keyboardVisible) {
            cardOffset = (-300).dp
        } else {
            cardOffset = -(50).dp
        }
    }

    // BringIntoViewRequesters for the fields
    val usernameBring = remember { BringIntoViewRequester() }
    val passwordBring = remember { BringIntoViewRequester() }
    val confirmBring = remember { BringIntoViewRequester() }

    val coroutine = rememberCoroutineScope()
    val transitionColor = Color(0xFFDBF0F9)
    
    // Define standard colors for all text fields
    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.Black,
        unfocusedTextColor = Color.Black,
        focusedLabelColor = Color(0xFF4285F4),
        unfocusedLabelColor = Color.Gray,
        cursorColor = Color(0xFF4285F4),
        focusedBorderColor = Color(0xFF4285F4),
        unfocusedBorderColor = Color.Gray
    )

    // --- STATE MANAGEMENT ---
    var authMode by remember { mutableStateOf("Terms") }

    // Terms State
    var acceptedTerms by remember { mutableStateOf(false) }
    var isEighteenPlus by remember { mutableStateOf(false) }
    var noAbuse by remember { mutableStateOf(false) }

    // Input State
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var currentUsername by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmNewPassword by remember { mutableStateOf("") }

    // Loading State (Heartbeat)
    var isLoading by remember { mutableStateOf(false) }

    // --- BACK HANDLER ---
    BackHandler(enabled = isLoading || authMode != "Terms") {
        if (isLoading) {
            isLoading = false
        } else if (authMode != "Terms") {
            authMode = "Terms" 
            password = ""
            confirmPassword = ""
            newPassword = ""
            confirmNewPassword = ""
            currentUsername = ""
        }
    }

    // Logic to enable buttons
    val isTermsEnabled = acceptedTerms && isEighteenPlus && noAbuse
    val isSignUpComplete = username.isNotEmpty() && password.isNotEmpty() &&
            confirmPassword.isNotEmpty() && (password == confirmPassword)
    val isLoginComplete = username.isNotEmpty() && password.isNotEmpty()
    val isResetPasswordComplete = currentUsername.isNotEmpty() && newPassword.isNotEmpty() && confirmNewPassword.isNotEmpty() && (newPassword == confirmNewPassword)

    // ROOT BOX
    Box(modifier = Modifier.fillMaxSize()) {

        // LAYER 1: BACKGROUND (Video + Sky)
        Column(modifier = Modifier.fillMaxSize()) {
            // Video Section
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 50.dp)
            ) {
                VideoBackground(context = context)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, transitionColor)
                            )
                        )
                ) {}
            }

            // Sky Background Section
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.White)
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
                                colors = listOf(transitionColor, Color.Transparent)
                            )
                        )
                ) {}
            }
        }

        // LAYER 2: THE LOGIN CARD (Overlay)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 10.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Card(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .offset(y = animatedOffset)
                    .fillMaxWidth().heightIn(max = 400.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState())
                        .imeNestedScroll(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    Spacer(modifier = Modifier.height(10.dp))
                    // --- CONTENT SWITCHER ---

                    when (authMode) {
                        "Terms" -> {
                            Text(
                                text = "You will remain anonymous",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(16.dp))

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

                            // Login Button
                            Button(
                                onClick = { authMode = "Login" },
                                enabled = isTermsEnabled,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF4285F4),
                                    disabledContainerColor = Color.Gray
                                )
                            ) {
                                Text(text = "Login", color = Color.White)
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Sign Up Button
                            Button(
                                onClick = { authMode = "SignUp" },
                                enabled = isTermsEnabled,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color(0xFF4285F4),
                                    disabledContainerColor = Color.Gray.copy(alpha = 0.1f),
                                    disabledContentColor = Color.Gray
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if(isTermsEnabled) Color(0xFF4285F4) else Color.Gray)
                            ) {
                                Text(text = "Sign Up")
                            }
                        }

                        "Login" -> {
                            Text(
                                text = "Login",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            var usernameFocused by remember { mutableStateOf(false) }

                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text("Username") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .bringIntoViewRequester(usernameBring)
                                    .onFocusChanged { usernameFocused = it.isFocused },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                colors = textFieldColors
                            )

                            LaunchedEffect(usernameFocused, imeVisible) {
                                if (usernameFocused && imeVisible && !imeHandled) {
                                    imeHandled = true
                                    coroutine.launch { usernameBring.bringIntoView() }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            var passwordFocused by remember { mutableStateOf(false) }

                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .bringIntoViewRequester(passwordBring)
                                    .onFocusChanged { passwordFocused = it.isFocused },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next
                                ),
                                colors = textFieldColors
                            )

                            LaunchedEffect(passwordFocused, imeVisible) {
                                if (passwordFocused && imeVisible && !imeHandled) {
                                    imeHandled = true
                                    coroutine.launch { passwordBring.bringIntoView() }
                                }
                            }

                            // Reset Password Option
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Reset Password",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF4285F4),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .padding(top = 8.dp, bottom = 8.dp)
                                        .clickable {
                                            authMode = "ResetPassword"
                                        }
                                )
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            Button(
                                onClick = {
                                    isLoading = true
                                    coroutine.launch {
                                        try {
                                            val fakeUserDto = com.example.anonychat.network.UserDto(
                                                id = "debug_user_123",
                                                username = username,
                                                email = "debug@test.com",
                                                gender = "unknown"
                                            )
                                            val fakeBody = com.example.anonychat.network.LoginResponse(
                                                message = "Debug Login Success",
                                                accessToken = "fake_access_token",
                                                refreshToken = "fake_refresh_token",
                                                user = fakeUserDto
                                            )

// Create a successful Retrofit response manually
                                            val response = retrofit2.Response.success(fakeBody)
//val response = NetworkClient.api.loginUser(UserLoginRequest(username, password))
                                            if (response.isSuccessful && response.body() != null) {
                                                val loginResponse = response.body()!!
                                                // Map UserDto to User
                                                val dto = loginResponse.user
                                                val user = User(
                                                    id = dto.id,
                                                    username = dto.username,
                                                    profilePictureUrl = null // DTO doesn't provide this yet
                                                )
                                                onLoginClick(user)
                                            } else {
                                                //log full response

                                                isLoading = false
//                                                val errorBody = response.errorBody()?.string() ?: "No error body"
//// Log it to Logcat so you can copy-paste it if needed (tag: LOGIN_ERROR)
//                                                android.util.Log.e("LOGIN_ERROR!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!", "Response: $errorBody")

                                                Toast.makeText(context, "Login failed: ${response.errorBody()?.string()}", Toast.LENGTH_LONG).show()
                                            }
                                        } catch (e: Exception) {
                                            isLoading = false
                                            Toast.makeText(context, "Login error!!!!: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                enabled = isLoginComplete && !isLoading,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4))
                            ) {
                                Text(text = "Login", color = Color.White)
                            }
                        }

                        "SignUp" -> {
                            Text(
                                text = "Create Account",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text("Username") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                colors = textFieldColors
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Spacer(modifier = Modifier.height(12.dp))
                            
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next
                                ),
                                colors = textFieldColors
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = confirmPassword,
                                onValueChange = { confirmPassword = it },
                                label = { Text("Confirm Password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Done
                                ),
                                isError = confirmPassword.isNotEmpty() && password != confirmPassword,
                                modifier = Modifier.fillMaxWidth(),
                                colors = textFieldColors
                            )

                            if (confirmPassword.isNotEmpty() && password != confirmPassword) {
                                Text(
                                    text = "Passwords do not match",
                                    color = Color.Red,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.align(Alignment.Start)
                                )
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    isLoading = true
                                    
                                    // Retrieve AppSet ID and Register
                                    val client = AppSet.getClient(context)
                                    client.appSetIdInfo.addOnSuccessListener { appSetIdInfo ->
                                        val appSetId = appSetIdInfo.id
                                        val request = UserRegistrationRequest(
                                            username = username,
                                            password = password,
                                            userId = "androidid:$appSetId",
                                            email = "", 
                                            googleId = "" 
                                        )
                                        
                                        coroutine.launch {
                                            try {
                                                val response = NetworkClient.api.registerUser(request)
                                                isLoading = false
                                                if (response.isSuccessful) {
                                                    authMode = "Login" // Move to Login on success
                                                    Toast.makeText(context, "Sign up successful! Please login.", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "Sign up failed: ${response.errorBody()?.string()}", Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (e: Exception) {
                                                isLoading = false
                                                e.printStackTrace()
                                                Toast.makeText(context, "Sign up error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }.addOnFailureListener {
                                        isLoading = false
                                        Toast.makeText(context, "Failed to retrieve ID", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = isSignUpComplete && !isLoading,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4))
                            ) {
                                Text(text = "Sign Up", color = Color.White)
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        "ResetPassword" -> {
                            Text(
                                text = "Reset Password",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = currentUsername,
                                onValueChange = { currentUsername = it },
                                label = { Text("Username") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                colors = textFieldColors
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = newPassword,
                                onValueChange = { newPassword = it },
                                label = { Text("New Password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next
                                ),
                                colors = textFieldColors
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = confirmNewPassword,
                                onValueChange = { confirmNewPassword = it },
                                label = { Text("Confirm New Password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Done
                                ),
                                isError = confirmNewPassword.isNotEmpty() && newPassword != confirmNewPassword,
                                colors = textFieldColors
                            )

                            if (confirmNewPassword.isNotEmpty() && newPassword != confirmNewPassword) {
                                Text(
                                    text = "Passwords do not match",
                                    color = Color.Red,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.align(Alignment.Start)
                                )
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            Button(
                                onClick = {
                                    isLoading = true
                                    val client = AppSet.getClient(context)
                                    client.appSetIdInfo.addOnSuccessListener { appSetIdInfo ->
                                        val appSetId = appSetIdInfo.id
                                        val userId = "androidid:$appSetId"
                                        val request = UserResetPasswordRequest(
                                            userId = userId, 
                                            username = currentUsername, 
                                            newPassword = newPassword
                                        )

                                        coroutine.launch {
                                            try {
                                                val response = NetworkClient.api.resetPassword(request)
                                                isLoading = false
                                                if (response.isSuccessful) {
                                                    authMode = "Login" // Move to Login on success
                                                    Toast.makeText(context, "Password reset successful.", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "Reset failed: ${response.errorBody()?.string()}", Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (e: Exception) {
                                                isLoading = false
                                                Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }.addOnFailureListener {
                                        isLoading = false
                                        Toast.makeText(context, "Failed to get ID", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = isResetPasswordComplete && !isLoading,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4))
                            ) {
                                Text(text = "Reset Password", color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // LAYER 3: SHARED LOADING OVERLAY
        LoadingHeartOverlay(isLoading = isLoading)
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
    // 1. State to track if the video has actually produced a visual frame
    var isVideoReady by remember { mutableStateOf(false) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val videoUri = Uri.parse("android.resource://${context.packageName}/${R.raw.video_ending_suggestion}")
            setMediaItem(MediaItem.fromUri(videoUri))

            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            prepare()
            playWhenReady = true

            // 2. Add Listener to detect exactly when the first frame appears
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    super.onRenderedFirstFrame()
                    isVideoReady = true
                }
            })
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Layer 1: The Video Player (Initially black/invisible)
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM

                    // IMPORTANT: Set the shutter to Transparent so it doesn't paint black
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)

                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Layer 2: The Placeholder Image (Sits ON TOP of the video)
        // We fade this out once the video is ready.
        androidx.compose.animation.AnimatedVisibility(
            visible = !isVideoReady,
            exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(500)),
            modifier = Modifier.fillMaxSize()
        ) {
            Image(
                painter = painterResource(id = R.drawable.video_placeholder),
                contentDescription = "Video Placeholder",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@kotlin.OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Preview(showBackground = true)
@Composable
fun LoginScreenPreview() {
    AnonychatTheme {
        LoginScreen()
    }
}
