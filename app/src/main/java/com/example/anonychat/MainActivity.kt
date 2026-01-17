package com.example.anonychat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.anonychat.model.Preferences
import com.example.anonychat.model.User
import com.example.anonychat.ui.BirdBubbleService
import com.example.anonychat.ui.ChatScreen
import com.example.anonychat.ui.LoginScreen
import com.example.anonychat.ui.ProfileScreen
import com.example.anonychat.ui.RatingsScreen
import com.example.anonychat.ui.KeyboardProofScreen
import com.example.anonychat.ui.theme.AnonychatTheme
import com.google.gson.Gson
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
    object Ratings : Screen("ratings")

    object DirectChat : Screen("directChat/{currentUser}/{myPrefs}/{matchedUser}/{matchedPrefs}") {
        fun createRoute(
            currentUser: User,
            myPrefs: Preferences,
            matchedUser: User,
            matchedPrefs: Preferences
        ): String {
            val gson = Gson()
            val u1 = URLEncoder.encode(gson.toJson(currentUser), "UTF-8")
            val p1 = URLEncoder.encode(gson.toJson(myPrefs), "UTF-8")
            val u2 = URLEncoder.encode(gson.toJson(matchedUser), "UTF-8")
            val p2 = URLEncoder.encode(gson.toJson(matchedPrefs), "UTF-8")
            return "directChat/$u1/$p1/$u2/$p2"
        }
    }
}

@ExperimentalLayoutApi
class MainActivity : ComponentActivity() {

    private var isChatScreenActive = false
    private var navControllerHolder: androidx.navigation.NavController? = null

    // --- BROADCAST RECEIVER TO HANDLE NAVIGATION FROM SERVICE ---
    private val navigationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.anonychat.ACTION_NAVIGATE_TO_DIRECT_CHAT") {
                Log.d("MainActivity", "Received navigation broadcast from service.")
                val myPrefsJson = intent.getStringExtra("my_prefs_json")
                val matchedPrefsJson = intent.getStringExtra("matched_prefs_json")
                val myEmail = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    .getString("user_email", null)

                if (myPrefsJson != null && matchedPrefsJson != null && myEmail != null) {
                    val gson = Gson()
                    try {
                        val myPrefsBody = gson.fromJson(myPrefsJson, com.example.anonychat.network.GetPreferencesResponse::class.java)
                        val matchedPrefsBody = gson.fromJson(matchedPrefsJson, com.example.anonychat.network.GetPreferencesResponse::class.java)

                        val currentUser = User(id = myEmail, username = myPrefsBody.username ?: myEmail.substringBefore('@'), profilePictureUrl = null)
                        val matchedUser = User(id = matchedPrefsBody.gmail!!, username = matchedPrefsBody.username ?: matchedPrefsBody.gmail.substringBefore('@'), profilePictureUrl = null)

                        val myPrefs = Preferences(romanceMin = myPrefsBody.romanceRange?.min?.toFloat() ?: 1f, romanceMax = myPrefsBody.romanceRange?.max?.toFloat() ?: 5f, gender = myPrefsBody.gender ?: "male")
                        val matchedPrefs = Preferences(romanceMin = matchedPrefsBody.romanceRange?.min?.toFloat() ?: 1f, romanceMax = matchedPrefsBody.romanceRange?.max?.toFloat() ?: 5f, gender = matchedPrefsBody.gender ?: "male")

                        val route = Screen.DirectChat.createRoute(currentUser, myPrefs, matchedUser, matchedPrefs)

                        // Relaunch the activity to bring it to the foreground
                        val launchIntent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
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

        // Register the broadcast receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(navigationReceiver, IntentFilter("com.example.anonychat.ACTION_NAVIGATE_TO_DIRECT_CHAT"), RECEIVER_EXPORTED)
        } else {
            registerReceiver(
                navigationReceiver,
                IntentFilter("com.example.anonychat.ACTION_NAVIGATE_TO_DIRECT_CHAT"),
                RECEIVER_NOT_EXPORTED // <-- ADD THIS FLAG
            )        }


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
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }
    }

    // New helper to centralize intent handling
    private fun handleIntentNavigation(navController: androidx.navigation.NavController, intent: Intent?) {
        if (intent?.action == "NAVIGATE_TO_ROUTE" && intent.data != null) {
            val route = intent.data.toString().substringAfter("anonychat://")
            if (route.isNotBlank()) {
                navController.navigate(route)
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

    @OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
    @Composable
    fun NavGraph(
        intent: Intent?,
        onChatActive: () -> Unit,
        onChatInactive: () -> Unit,
        onNavControllerReady: (androidx.navigation.NavController) -> Unit
    ) {
        val navController = rememberNavController()

        // Give the activity a reference to the NavController
        LaunchedEffect(navController) {
            onNavControllerReady(navController)
        }

        // --- Handle intent from the service to navigate to ChatScreen ---
        LaunchedEffect(intent) {
            handleIntentNavigation(navController, intent)
        }

        NavHost(navController = navController, startDestination = Screen.Login.route) {
            composable(Screen.Login.route) {
                LoginScreen(onLoginClick = { user ->
                    navController.navigate(Screen.Chat.createRoute(user))
                })
            }

            composable(
                Screen.Chat.route,
                arguments = listOf(navArgument("user") { type = NavType.StringType })
            ) { backStackEntry ->
                val userJson = backStackEntry.arguments?.getString("user")
                val user = try {
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
                    onNavigateToDirectChat = { currentUser, myPrefs, matchedUser, matchedPrefs ->
                        val route = Screen.DirectChat.createRoute(currentUser, myPrefs, matchedUser, matchedPrefs)
                        navController.navigate(route)
                    }
                )
            }

            composable(
                Screen.DirectChat.route,
                arguments = listOf(
                    navArgument("currentUser") { type = NavType.StringType },
                    navArgument("myPrefs") { type = NavType.StringType },
                    navArgument("matchedUser") { type = NavType.StringType },
                    navArgument("matchedPrefs") { type = NavType.StringType }
                )
            ) { entry ->
                val currentUserJson = java.net.URLDecoder.decode(entry.arguments!!.getString("currentUser")!!, "UTF-8")
                val myPrefsJson = java.net.URLDecoder.decode(entry.arguments!!.getString("myPrefs")!!, "UTF-8")
                val matchedUserJson = java.net.URLDecoder.decode(entry.arguments!!.getString("matchedUser")!!, "UTF-8")
                val matchedPrefsJson = java.net.URLDecoder.decode(entry.arguments!!.getString("matchedPrefs")!!, "UTF-8")

                val gson = Gson()
                val currentUser = gson.fromJson(currentUserJson, User::class.java)
                val myPrefs = gson.fromJson(myPrefsJson, Preferences::class.java)
                val matchedUser = gson.fromJson(matchedUserJson, User::class.java)
                val matchedPrefs = gson.fromJson(matchedPrefsJson, Preferences::class.java)

                KeyboardProofScreen(
                    currentUser = currentUser,
                    matchedUser = matchedUser,
                    matchedUserPrefs = matchedPrefs,
                    matchedUserGmail = matchedUser.id,
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

            composable(Screen.Ratings.route) {
                RatingsScreen(navController = navController)
            }
        }
    }
}
