package com.example.anonychat.ui // This package line is crucial
import android.annotation.SuppressLint
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.anonychat.network.NetworkClient
import com.example.anonychat.network.UserRegistrationRequest
import com.example.anonychat.network.UserLoginRequest
import com.example.anonychat.network.UserResetPasswordRequest
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.core.content.ContextCompat
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
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.gms.appset.AppSet
import com.google.android.gms.tasks.Tasks

@SuppressLint("HardwareIds")
@ExperimentalFoundationApi
@ExperimentalLayoutApi
@OptIn(UnstableApi::class, ExperimentalLayoutApi::class, ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class
)
@Composable
fun LoginScreen(
    onLoginClick: () -> Unit = {}
) {
    val context = LocalContext.current

// Detect IME (keyboard) visibility — MUST be inside a @Composable
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
            // Move the card UP to keep all fields visible

            cardOffset = (-300).dp   // adjust as needed
        } else {
            // Reset when keyboard hides
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
        focusedLabelColor = Color(0xFF4285F4), // Blue when active
        unfocusedLabelColor = Color.Gray,
        cursorColor = Color(0xFF4285F4),
        focusedBorderColor = Color(0xFF4285F4),
        unfocusedBorderColor = Color.Gray
    )

    // --- STATE MANAGEMENT ---

    // Modes: "Terms" (Initial), "Login", "SignUp"
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
    // If we are not in "Terms" mode (meaning we are in Login or SignUp), Back goes to Terms
    BackHandler(enabled = isLoading || authMode != "Terms") {
        if (isLoading) {
            isLoading = false
        } else if (authMode != "Terms") {
            authMode = "Terms" // Go back to start
            // Optional: Clear fields when going back
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
                    .offset(y = animatedOffset)   // ⭐ ADD THIS ONE LINE
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),   // ⭐ ADD THI
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState())
                        .imeNestedScroll(),   // <-- smooth stable keyboard handling
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    Spacer(modifier = Modifier.height(10.dp))
                    // --- CONTENT SWITCHER ---

                    when (authMode) {
                        "Terms" -> {
                            // ==========================================
                            // VIEW 1: Terms & Selection
                            // ==========================================
//                            Text(
//                                text = "Welcome to AnonyChat",
//                                style = MaterialTheme.typography.headlineSmall,
//                                color = Color.Black,
//                                fontWeight = FontWeight.Bold,
//                                fontSize = 22.sp
//                            )
//                            Spacer(modifier = Modifier.height(8.dp))
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
                                onClick = {
                                    // FIX: Don't make network call here.
                                    // Just switch the view to the Login form.
                                    authMode = "Login"
                                },
                                // FIX: Enable this based on checkboxes, not password fields
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

                            // Sign Up Button (Outlined style for visual distinction)
                            Button(
                                onClick = { authMode = "SignUp" },
                                enabled = isTermsEnabled,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White, // White background
                                    contentColor = Color(0xFF4285F4), // Blue text
                                    disabledContainerColor = Color.Gray.copy(alpha = 0.1f),
                                    disabledContentColor = Color.Gray
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if(isTermsEnabled) Color(0xFF4285F4) else Color.Gray)
                            ) {
                                Text(text = "Sign Up")
                            }
                        }

                        "Login" -> {
                            // ==========================================
                            // VIEW 2: LOGIN (User + Pass + Forgot)
                            // ==========================================
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
                                    if (!isLoginComplete) return@Button
                                    isLoading = true // Start Heartbeat

                                    // Launch Network Request
                                    coroutine.launch(Dispatchers.IO) {
                                        try {
                                            // Create the login request object
                                            val request = UserLoginRequest(
                                                username = username,
                                                password = password
                                            )

                                            // Make the API Call
                                            val response = NetworkClient.api.loginUser(request)

                                            withContext(Dispatchers.Main) {
                                                if (response.isSuccessful) {
                                                    Toast.makeText(context, "Login Successful!", Toast.LENGTH_SHORT).show()

                                                    // TODO: Handle successful login (e.g., navigate to Home Screen)
                                                    // onLoginClick() // Call the navigation callback provided to the Composable

                                                } else {
                                                    // Parse the error message from the server response
                                                    val errorMsg = try {
                                                        val rawError = response.errorBody()?.string()
                                                        if (rawError != null) {
                                                            org.json.JSONObject(rawError).getString("message")
                                                        } else {
                                                            "Unknown Error"
                                                        }
                                                    } catch (e: Exception) {
                                                        "Login Failed"
                                                    }
                                                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                            }
                                        } finally {
                                            withContext(Dispatchers.Main) {
                                                isLoading = false // Stop Heartbeat
                                            }
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
                            // ==========================================
                            // VIEW 3: SIGN UP (User + Pass + Confirm)
                            // ==========================================
                            Text(
                                text = "Create Account",
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
                                    .fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                colors = textFieldColors
                            )
                            Spacer(modifier = Modifier.height(8.dp))
//                            LaunchedEffect(usernameFocused, imeVisible) {
//                                if (usernameFocused && imeVisible && !imeHandled) {
//                                    imeHandled = true
//                                    coroutine.launch { usernameBring.bringIntoView() }
//                                }
//                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            var passwordFocused by remember { mutableStateOf(false) }

                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier
                                    .fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next
                                ),
                                colors = textFieldColors
                            )
                            Spacer(modifier = Modifier.height(8.dp))
//                            LaunchedEffect(passwordFocused, imeVisible) {
//                                if (passwordFocused && imeVisible && !imeHandled) {
//                                    imeHandled = true
//                                    coroutine.launch { passwordBring.bringIntoView() }
//                                }
//                            }

//                            Spacer(modifier = Modifier.height(12.dp))

                            var confirmFocused by remember { mutableStateOf(false) }

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


//                            LaunchedEffect(confirmFocused, imeVisible) {
//                                if (confirmFocused && imeVisible && !imeHandled) {
//                                    imeHandled = true
//                                    coroutine.launch { confirmBring.bringIntoView() }
//                                }
//                            }


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
                                    if (!isSignUpComplete) return@Button
                                    isLoading = true // Start Heartbeat

                                    // 1. Get the Android Device ID (Synchronous)
                                    val androidId = Settings.Secure.getString(
                                        context.contentResolver,
                                        Settings.Secure.ANDROID_ID
                                    )

                                    // 2. Launch Network Request
                                    coroutine.launch(Dispatchers.IO) {
                                        try {
                                            // --- NEW LOGIC: Get App Set ID ---
                                            // We use Tasks.await() to block this background thread until the ID is ready
                                            val client = com.google.android.gms.appset.AppSet.getClient(context)
                                            val task = client.appSetIdInfo

                                            // Default to "unknown" if it fails, or catch the exception
                                            val appSetId = try {
                                                val info = Tasks.await(task)
                                                info.id
                                            } catch (e: Exception) {
                                                "unknown_appset"
                                            }

                                            // Combine them as requested: "androidId:appSetId"
                                            val finalUserId = "$androidId:$appSetId"
                                            // ----------------------------------

                                            val generatedEmail = "$finalUserId@email.com"

// 2. Create GoogleId: same as userid
                                            val generatedGoogleId = finalUserId
// ----------------------

// Create the request object
                                            val request = UserRegistrationRequest(
                                                username = username,
                                                password = password,
                                                userId = finalUserId,
                                                // Pass the new fields
                                                email = generatedEmail,
                                                googleId = generatedGoogleId
                                            )


                                            // Make the API Call
                                            val response = NetworkClient.api.registerUser(request)

                                            withContext(Dispatchers.Main) {
                                                if (response.isSuccessful) {
                                                    Toast.makeText(context, "Sign Up Successful!", Toast.LENGTH_SHORT).show()
                                                    // Switch to Login mode
                                                    authMode = "Login"
                                                    password = ""
                                                    confirmPassword = ""
                                                } else {
                                                    // Parse the error message from the server response
                                                    val errorMsg = try {
                                                        val rawError = response.errorBody()?.string()
                                                        // Parse the JSON string and extract the "message" field
                                                        if (rawError != null) {
                                                            org.json.JSONObject(rawError).getString("message")
                                                        } else {
                                                            "Unknown Error"
                                                        }
                                                    } catch (e: Exception) {
                                                        // Fallback if response isn't JSON or key is missing
                                                        "Error parsing response"
                                                    }
                                                    // Display the actual message (e.g., "Username already taken")
                                                    Toast.makeText(context, "Sign Up Failed: $errorMsg", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                            }
                                        } finally {
                                            withContext(Dispatchers.Main) {
                                                isLoading = false // Stop Heartbeat
                                            }
                                        }
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
                    // ==========================================
                    // VIEW 4: RESET PASSWORD
                    // ==========================================
                    Text(
                        text = "Reset Password",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = username, // Reuse username state variable
                        onValueChange = { username = it },
                        label = { Text("Current Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        colors = textFieldColors
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = password, // Reuse password state variable for new password
                        onValueChange = { password = it },
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
                        value = confirmPassword, // Reuse confirmPassword state variable
                        onValueChange = { confirmPassword = it },
                        label = { Text("Confirm New Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done
                        ),
                        isError = confirmPassword.isNotEmpty() && password != confirmPassword,
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
                                if (!username.isNotEmpty() || !password.isNotEmpty()) return@Button

                                isLoading = true // Start Heartbeat

                                // Launch Network Request
                                coroutine.launch(Dispatchers.IO) {
                                    try {
                                        // 1. We need to generate the userId again to verify the device
                                        val androidId = Settings.Secure.getString(
                                            context.contentResolver,
                                            Settings.Secure.ANDROID_ID
                                        )

                                        val client = AppSet.getClient(context)
                                        val appSetId = try {
                                            Tasks.await(client.appSetIdInfo).id
                                        } catch (e: Exception) {
                                            "unknown_appset"
                                        }
                                        val finalUserId = "$androidId:$appSetId"

                                        // 2. Create the Reset Password Request
                                        val request = UserResetPasswordRequest(
                                            userId = finalUserId, // userId is now part of the body
                                            username = username,
                                            newPassword = password
                                        )

                                        // 3. Make the API Call
                                        val response  = NetworkClient.api.resetPassword(
                                            request = request
                                        )

                                        withContext(Dispatchers.Main) {
                                            if (response.isSuccessful) {
                                                Toast.makeText(context, "Password Reset Successful!", Toast.LENGTH_SHORT).show()

                                                // Navigate back to Login
                                                authMode = "Login"
                                                password = ""
                                                confirmPassword = ""
                                            } else {
                                                // Parse Error Message
                                                val errorMsg = try {
                                                    val rawError = response.errorBody()?.string()
                                                    if (rawError != null) {
                                                        org.json.JSONObject(rawError).getString("message")
                                                    } else {
                                                        "Unknown Error"
                                                    }
                                                } catch (e: Exception) {
                                                    "Reset Failed"
                                                }
                                                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    } finally {
                                        withContext(Dispatchers.Main) {
                                            isLoading = false // Stop Heartbeat
                                        }
                                    }
                                }
                            },
                            // Enable only if fields are valid and not loading
                            enabled = username.isNotEmpty() &&
                                    password.isNotEmpty() &&
                                    confirmPassword.isNotEmpty() &&
                                    (password == confirmPassword) &&
                                    !isLoading,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4))
                        ) {
                            Text(text = "Reset Password", color = Color.White)
                        }


                        // Add a "Back to Login" button for better UX
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Back to Login",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .clickable {
                                    authMode = "Login"
                                    password = ""
                                    confirmPassword = ""
                                }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
// END OF NEW CODE

// This creates the closing brace for the 'when' block
                }

            }
                }
            }
        }

        // LAYER 3: LOADING OVERLAY (Existing code)
        if (isLoading) {
            DisposableEffect(Unit) {
                val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vibratorManager.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }

                val job = kotlinx.coroutines.GlobalScope.launch {
                    while (true) {
                        if (vibrator.hasVibrator()) {
                            if (Build.VERSION.SDK_INT >= 26) {
                                vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
                            } else {
                                @Suppress("DEPRECATION")
                                vibrator.vibrate(60)
                            }
                            delay(100)
                            if (Build.VERSION.SDK_INT >= 26) {
                                vibrator.vibrate(VibrationEffect.createOneShot(60, 60))                             } else {
                                @Suppress("DEPRECATION")
                                vibrator.vibrate(40)
                            }
                            delay(1000)
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
        AnimatedVisibility(
            visible = !isVideoReady,
            exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(500)),
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
