package com.example.anonychat

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.anonychat.model.Preferences
import com.example.anonychat.model.User
import com.example.anonychat.network.WebSocketManager
import com.example.anonychat.service.WebSocketMonitorService
import com.example.anonychat.ui.BirdBubbleService
import com.example.anonychat.ui.ChatScreen
import com.example.anonychat.ui.KeyboardProofScreen
import com.example.anonychat.ui.LoginScreen
import com.example.anonychat.ui.ProfileScreen
import com.example.anonychat.ui.RatingsScreen
import com.example.anonychat.ui.theme.AnonychatTheme
import com.google.gson.Gson
import java.io.File
import java.net.URLEncoder
import java.util.UUID

// Define your data models if they aren't in a separate file
// data class User(...) - Assuming this is in model/User.kt
// data class Preferences(...) - Assuming this is in model/Preferences.kt

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Chat : Screen("chat/{user}") {
        fun createRoute(user: User): String {
            val gson = Gson()
            val json = gson.toJson(user)
            val encodedJson = URLEncoder.encode(json, "UTF-8")
            return "chat/$encodedJson"
        }
    }
    object Profile : Screen("profile/{username}") {
        fun createRoute(username: String): String {
            return "profile/$username"
        }
    }
    object Ratings : Screen("ratings?email={email}&username={username}&gender={gender}&romanceMin={romanceMin}&romanceMax={romanceMax}") {
        fun createRoute(
            email: String? = null,
            username: String? = null,
            gender: String? = null,
            romanceMin: Float? = null,
            romanceMax: Float? = null
        ): String {
            return if (email != null && username != null) {
                "ratings?email=$email&username=$username&gender=${gender ?: "male"}&romanceMin=${romanceMin ?: 1f}&romanceMax=${romanceMax ?: 5f}"
            } else {
                "ratings"
            }
        }
    }

    object DirectChat : Screen("directChat/{currentUser}/{myPrefs}/{matchedUser}/{matchedPrefs}/{isNewMatch}") {
        fun createRoute(
                currentUser: User,
                myPrefs: Preferences,
                matchedUser: User,
                matchedPrefs: Preferences,
                isNewMatch: Boolean
        ): String {
            val gson = Gson()
            val u1 = URLEncoder.encode(gson.toJson(currentUser), "UTF-8")
            val p1 = URLEncoder.encode(gson.toJson(myPrefs), "UTF-8")
            val u2 = URLEncoder.encode(gson.toJson(matchedUser), "UTF-8")
            val p2 = URLEncoder.encode(gson.toJson(matchedPrefs), "UTF-8")
            return "directChat/$u1/$p1/$u2/$p2/$isNewMatch"
        }
    }
}

private lateinit var takePictureLauncher: ActivityResultLauncher<Uri>
private lateinit var pickImageLauncher: ActivityResultLauncher<String>
private lateinit var requestCameraPermission: ActivityResultLauncher<String>
private lateinit var requestGalleryPermission: ActivityResultLauncher<Array<String>>

private var tempCameraUri: Uri? = null
private var currentChatPeer: String? = null
lateinit var requestAudioPermission: ActivityResultLauncher<String>
var pendingAudioStart: (() -> Unit)? = null
var pendingCameraAction: (() -> Unit)? = null
var pendingGalleryAction: (() -> Unit)? = null

@ExperimentalLayoutApi
class MainActivity : ComponentActivity() {

    private var isChatScreenActive = false
    private var navControllerHolder: androidx.navigation.NavController? = null
    fun openCamera(peerEmail: String) {
        currentChatPeer = peerEmail
        requestCameraPermission.launch(Manifest.permission.CAMERA)

        val file = File.createTempFile("IMG_${System.currentTimeMillis()}", ".jpg", cacheDir)
        tempCameraUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        takePictureLauncher.launch(tempCameraUri)
    }
    fun requestMicPermissionAndRun(action: () -> Unit) {
        pendingAudioStart = action
        requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun requestCameraPermissionAndRun(action: () -> Unit) {
        pendingCameraAction = action
        requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    fun requestGalleryPermissionAndRun(action: () -> Unit) {
        pendingGalleryAction = action
        val permissions =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (Build.VERSION.SDK_INT >= 34) {
                        arrayOf(
                                Manifest.permission.READ_MEDIA_IMAGES,
                                Manifest.permission.READ_MEDIA_VIDEO,
                                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                        )
                    } else {
                        arrayOf(
                                Manifest.permission.READ_MEDIA_IMAGES,
                                Manifest.permission.READ_MEDIA_VIDEO
                        )
                    }
                } else {
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
        requestGalleryPermission.launch(permissions)
    }

    fun openGallery(peerEmail: String) {
        currentChatPeer = peerEmail
        pickImageLauncher.launch("image/*")
    }

    // --- BROADCAST RECEIVER TO HANDLE NAVIGATION FROM SERVICE ---
    private val navigationReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == "com.example.anonychat.ACTION_NAVIGATE_TO_DIRECT_CHAT") {
                        Log.d("MainActivity", "Received navigation broadcast from service.")
                        val myPrefsJson = intent.getStringExtra("my_prefs_json")
                        val matchedPrefsJson = intent.getStringExtra("matched_prefs_json")
                        Log.e("MainActivity", "DEBUG: Received matchedPrefsJson: $matchedPrefsJson")
                        val myEmail =
                                getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                                        .getString("user_email", null)

                        if (myPrefsJson != null && matchedPrefsJson != null && myEmail != null) {
                            val gson = Gson()
                            try {
                                val myPrefsBody =
                                        gson.fromJson(
                                                myPrefsJson,
                                                com.example.anonychat.network
                                                                .GetPreferencesResponse::class
                                                        .java
                                        )
                                val matchedPrefsBody =
                                        gson.fromJson(
                                                matchedPrefsJson,
                                                com.example.anonychat.network
                                                                .GetPreferencesResponse::class
                                                        .java
                                        )
                                Log.e(
                                        "MainActivity",
                                        "DEBUG: deserialized body -> isOnline=${matchedPrefsBody?.isOnline}, lastOnline=${matchedPrefsBody?.lastOnline}"
                                )

                                val currentUser =
                                        User(
                                                id = myEmail,
                                                username = myPrefsBody.username
                                                                ?: myEmail.substringBefore('@'),
                                                profilePictureUrl = null
                                        )
                                val matchedUser =
                                        User(
                                                id = matchedPrefsBody.gmail!!,
                                                username = matchedPrefsBody.username
                                                                ?: matchedPrefsBody.gmail
                                                                        .substringBefore('@'),
                                                profilePictureUrl = null
                                        )

                                val myPrefs =
                                        Preferences(
                                                romanceMin =
                                                        myPrefsBody.romanceRange?.min?.toFloat()
                                                                ?: 1f,
                                                romanceMax =
                                                        myPrefsBody.romanceRange?.max?.toFloat()
                                                                ?: 5f,
                                                gender = myPrefsBody.gender ?: "male"
                                        )
                                val matchedPrefs =
                                        Preferences(
                                                romanceMin =
                                                        matchedPrefsBody.romanceRange?.min
                                                                ?.toFloat()
                                                                ?: 1f,
                                                romanceMax =
                                                        matchedPrefsBody.romanceRange?.max
                                                                ?.toFloat()
                                                                ?: 5f,
                                                gender = matchedPrefsBody.gender ?: "male",
                                                isOnline = matchedPrefsBody.isOnline ?: false,
                                                lastSeen = matchedPrefsBody.lastOnline ?: 0L
                                        )

                                val route =
                                        Screen.DirectChat.createRoute(
                                                currentUser,
                                                myPrefs,
                                                matchedUser,
                                                matchedPrefs,
                                                isNewMatch = true // From notification/service
                                        )

                                // Relaunch the activity to bring it to the foreground
                                val launchIntent =
                                        Intent(context, MainActivity::class.java).apply {
                                            addFlags(
                                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                            )
                                            action = "NAVIGATE_TO_ROUTE"
                                            data = Uri.parse("anonychat://$route")
                                        }
                                startActivity(launchIntent)
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Error processing navigation broadcast", e)
                            }
                        }
                    }
                }
            }

    @RequiresApi(Build.VERSION_CODES.O)
    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        checkOverlayPermission()
        requestCameraPermission =
                registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) {
                        pendingCameraAction?.invoke()
                        pendingCameraAction = null
                    }
                }
        requestAudioPermission =
                registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) {
                        pendingAudioStart?.invoke()
                        pendingAudioStart = null
                    }
                }

        requestGalleryPermission =
                registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                        results ->
                    if (results.values.any { it }) {
                        pendingGalleryAction?.invoke()
                        pendingGalleryAction = null
                    }
                }

        takePictureLauncher =
                registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                    if (success && tempCameraUri != null && currentChatPeer != null) {
                        val localId = "media-${UUID.randomUUID()}"
                        WebSocketManager.sendMedia(
                                currentChatPeer!!,
                                tempCameraUri!!,
                                "image/jpeg",
                                localId
                        )
                    }
                }

        pickImageLauncher =
                registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                    if (uri != null && currentChatPeer != null) {
                        val localId = "media-${UUID.randomUUID()}"
                        WebSocketManager.sendMedia(currentChatPeer!!, uri, "image/jpeg", localId)
                    }
                }

        // Register the broadcast receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                    navigationReceiver,
                    IntentFilter("com.example.anonychat.ACTION_NAVIGATE_TO_DIRECT_CHAT"),
                    RECEIVER_EXPORTED
            )
        } else {
            registerReceiver(
                    navigationReceiver,
                    IntentFilter("com.example.anonychat.ACTION_NAVIGATE_TO_DIRECT_CHAT"),
                    RECEIVER_NOT_EXPORTED
            )
        }

        // Start WebSocket Monitor Service
        startWebSocketMonitorService()

        setContent {
            AnonychatTheme {
                NavGraph(
                        intent = intent,
                        onChatActive = { isChatScreenActive = true },
                        onChatInactive = { isChatScreenActive = false },
                        onNavControllerReady = { navControllerHolder = it }
                )
            }
        }
    }

    private fun startWebSocketMonitorService() {
        // Check if user is logged in by checking for stored credentials
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("token", null)
        val email = prefs.getString("email", null)
        
        // Only start the monitor service if user has valid credentials
        if (!token.isNullOrEmpty() && !email.isNullOrEmpty()) {
            Log.d("MainActivity", "Starting WebSocketMonitorService")
            val intent = Intent(this, WebSocketMonitorService::class.java)
            startService(intent)
        } else {
            Log.d("MainActivity", "Skipping WebSocketMonitorService - no credentials found")
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Handle intents received while the activity is alive
        navControllerHolder?.let { handleIntentNavigation(it, intent) }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister to prevent memory leaks
        unregisterReceiver(navigationReceiver)
    }

    override fun onResume() {
        super.onResume()
        stopService(Intent(this, BirdBubbleService::class.java))
    }

    override fun onStop() {
        super.onStop()
        if (isChatScreenActive && !isChangingConfigurations) {
            // Check for permission before starting service
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                startService(Intent(this, BirdBubbleService::class.java))
            }
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent =
                        Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                        )
                startActivity(intent)
            }
        }
    }

    // New helper to centralize intent handling
    private fun handleIntentNavigation(
            navController: androidx.navigation.NavController,
            intent: Intent?
    ) {
        if (intent?.action == "NAVIGATE_TO_ROUTE" && intent.data != null) {
            val route = intent.data.toString().substringAfter("anonychat://")
            if (route.isNotBlank()) {
                navController.navigate(route) {
                    launchSingleTop = true
                    restoreState = true
                }
            }
            intent.action = null // Clear action to prevent re-triggering
        } else if (intent?.getStringExtra("NAVIGATE_TO") == "CHAT") {
            val lastUser = User("unknown", "Guest", "")
            navController.navigate(Screen.Chat.createRoute(lastUser)) {
                popUpTo(navController.graph.startDestinationId) { inclusive = true }
                launchSingleTop = true
            }
            intent.removeExtra("NAVIGATE_TO")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    @OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
    @Composable
    fun NavGraph(
            intent: Intent?,
            onChatActive: () -> Unit,
            onChatInactive: () -> Unit,
            onNavControllerReady: (androidx.navigation.NavController) -> Unit
    ) {
        val navController = rememberNavController()
        var warningMessage by remember { mutableStateOf<String?>(null) }
        var suspensionMessage by remember { mutableStateOf<String?>(null) }
        var banMessage by remember { mutableStateOf<String?>(null) }
        var http403Message by remember { mutableStateOf<String?>(null) }

        // Listen for warning, suspension, and ban notifications from WebSocket
        LaunchedEffect(Unit) {
            WebSocketManager.events.collect { event ->
                when (event) {
                    is com.example.anonychat.network.WebSocketEvent.WarningNotification -> {
                        warningMessage = event.message
                    }
                    is com.example.anonychat.network.WebSocketEvent.SuspensionNotification -> {
                        suspensionMessage = event.message
                        // Clear session and navigate to login
                        getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                    is com.example.anonychat.network.WebSocketEvent.BanNotification -> {
                        banMessage = event.message
                        // Clear session and navigate to login
                        getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                    is com.example.anonychat.network.WebSocketEvent.TokenExpiredNeedLogin -> {
                        // Token expired and couldn't be regenerated - redirect to login
                        Log.e("MainActivity", "Token expired, redirecting to login screen")
                        val currentRoute = navController.currentBackStackEntry?.destination?.route
                        if (currentRoute != Screen.Login.route) {
                            navController.navigate(Screen.Login.route) {
                                popUpTo(0) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
        
        // Listen for HTTP 403 events
        LaunchedEffect(Unit) {
            com.example.anonychat.network.Http403EventBus.events.collect { event ->
                when (event) {
                    is com.example.anonychat.network.Http403Event.AccountRestricted -> {
                        http403Message = event.message
                        // Only navigate to login if not already there
                        val currentRoute = navController.currentBackStackEntry?.destination?.route
                        if (currentRoute != Screen.Login.route) {
                            navController.navigate(Screen.Login.route) {
                                popUpTo(0) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                }
            }
        }

        // Show warning dialog when warningMessage is not null
        if (warningMessage != null) {
            AlertDialog(
                onDismissRequest = { warningMessage = null },
                title = { Text("⚠️ Warning") },
                text = { Text(warningMessage ?: "") },
                confirmButton = {
                    TextButton(onClick = { warningMessage = null }) {
                        Text("OK")
                    }
                }
            )
        }
        
        // Show suspension dialog
        if (suspensionMessage != null) {
            AlertDialog(
                onDismissRequest = { suspensionMessage = null },
                title = { Text("⛔ Account Suspended") },
                text = { Text(suspensionMessage ?: "") },
                confirmButton = {
                    TextButton(onClick = { suspensionMessage = null }) {
                        Text("OK")
                    }
                }
            )
        }
        
        // Show ban dialog
        if (banMessage != null) {
            AlertDialog(
                onDismissRequest = { banMessage = null },
                title = { Text("🚫 Account Banned") },
                text = { Text(banMessage ?: "") },
                confirmButton = {
                    TextButton(onClick = { banMessage = null }) {
                        Text("OK")
                    }
                }
            )
        }
        
        // Show HTTP 403 dialog (account restricted)
        if (http403Message != null) {
            AlertDialog(
                onDismissRequest = { http403Message = null },
                title = { Text("🚫 Account Restricted") },
                text = { Text(http403Message ?: "") },
                confirmButton = {
                    TextButton(onClick = { http403Message = null }) {
                        Text("OK")
                    }
                }
            )
        }

        // Give the activity a reference to the NavController
        LaunchedEffect(navController) { onNavControllerReady(navController) }

        // --- Handle intent from the service to navigate to ChatScreen ---
        LaunchedEffect(intent) { handleIntentNavigation(navController, intent) }

        // Determine start destination based on login status
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val isLoggedIn = prefs.getString("access_token", null) != null
        val userEmail = prefs.getString("user_email", null)
        val username = prefs.getString("username", null)
        
        val startDestination = if (isLoggedIn && userEmail != null && username != null) {
            // User is logged in, start at ChatScreen
            val user = User(userEmail, username, null)
            Screen.Chat.createRoute(user)
        } else {
            // User not logged in, start at LoginScreen
            Screen.Login.route
        }

        NavHost(navController = navController, startDestination = startDestination) {
            composable(Screen.Login.route) {
                LoginScreen(
                        onLoginClick = { user ->
                            navController.navigate(Screen.Chat.createRoute(user)) {
                                // Clear the back stack so user can't go back to login
                                popUpTo(Screen.Login.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                )
            }

            composable(
                    Screen.Chat.route,
                    arguments = listOf(navArgument("user") { type = NavType.StringType })
            ) { backStackEntry ->
                val userJson = backStackEntry.arguments?.getString("user")
                val user =
                        try {
                            if (userJson != null) Gson().fromJson(userJson, User::class.java)
                            else User("unknown", "Guest", "")
                        } catch (e: Exception) {
                            User("unknown", "Guest", "")
                        }
                ChatScreen(
                        user = user,
                        onChatActive = onChatActive,
                        onChatInactive = onChatInactive,
                        onNavigateToProfile = { username ->
                            navController.navigate(Screen.Profile.createRoute(username))
                        },
                        onNavigateToDirectChat = { currentUser, myPrefs, matchedUser, matchedPrefs, isNewMatch
                            ->
                            val route =
                                    Screen.DirectChat.createRoute(
                                            currentUser,
                                            myPrefs,
                                            matchedUser,
                                            matchedPrefs,
                                            isNewMatch
                                    )
                            navController.navigate(route)
                        }
                )
            }

            composable(
                    Screen.DirectChat.route,
                    arguments =
                            listOf(
                                    navArgument("currentUser") { type = NavType.StringType },
                                    navArgument("myPrefs") { type = NavType.StringType },
                                    navArgument("matchedUser") { type = NavType.StringType },
                                    navArgument("matchedPrefs") { type = NavType.StringType },
                                    navArgument("isNewMatch") { type = NavType.BoolType }
                            )
            ) { entry ->
                val currentUserJson =
                        java.net.URLDecoder.decode(
                                entry.arguments!!.getString("currentUser")!!,
                                "UTF-8"
                        )
                val myPrefsJson =
                        java.net.URLDecoder.decode(
                                entry.arguments!!.getString("myPrefs")!!,
                                "UTF-8"
                        )
                val matchedUserJson =
                        java.net.URLDecoder.decode(
                                entry.arguments!!.getString("matchedUser")!!,
                                "UTF-8"
                        )
                val matchedPrefsJson =
                        java.net.URLDecoder.decode(
                                entry.arguments!!.getString("matchedPrefs")!!,
                                "UTF-8"
                        )
                val isNewMatch = entry.arguments?.getBoolean("isNewMatch") ?: false

                val gson = Gson()
                val currentUser = gson.fromJson(currentUserJson, User::class.java)
                val matchedUser = gson.fromJson(matchedUserJson, User::class.java)
                val matchedPrefs = gson.fromJson(matchedPrefsJson, Preferences::class.java)

                KeyboardProofScreen(
                        currentUser = currentUser,
                        matchedUser = matchedUser,
                        matchedUserPrefs = matchedPrefs,
                        matchedUserGmail = matchedUser.id,
                        navController = navController,
                        isNewMatch = isNewMatch,
                        onBack = { navController.popBackStack() }
                )
            }

            composable(
                    Screen.Profile.route,
                    arguments = listOf(navArgument("username") { type = NavType.StringType })
            ) { backStackEntry ->
                val username = backStackEntry.arguments?.getString("username") ?: ""
                ProfileScreen(navController = navController, initialUsername = username)
            }

            composable(
                Screen.Ratings.route,
                arguments = listOf(
                    navArgument("email") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("username") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("gender") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("romanceMin") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("romanceMax") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val email = backStackEntry.arguments?.getString("email")
                val username = backStackEntry.arguments?.getString("username")
                val gender = backStackEntry.arguments?.getString("gender")
                val romanceMin = backStackEntry.arguments?.getString("romanceMin")?.toFloatOrNull()
                val romanceMax = backStackEntry.arguments?.getString("romanceMax")?.toFloatOrNull()
                
                RatingsScreen(
                    navController = navController,
                    targetUserEmail = email,
                    targetUsername = username,
                    targetGender = gender,
                    targetRomanceMin = romanceMin,
                    targetRomanceMax = romanceMax
                )
            }
        }
    }
}
