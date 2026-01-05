package com.example.anonychat
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.example.anonychat.model.Preferences
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.anonychat.model.User
import com.example.anonychat.ui.ChatScreen
import com.example.anonychat.ui.BirdBubbleService
import com.example.anonychat.ui.LoginScreen
import com.example.anonychat.ui.ProfileScreen
import com.example.anonychat.ui.DirectChatScreen
import com.example.anonychat.ui.RatingsScreen
import com.example.anonychat.ui.theme.AnonychatTheme
import com.google.gson.Gson
import android.content.Context   // ✅ THIS IMPORT FIXES IT

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Chat : Screen("chat/{user}") {
        fun createRoute(user: User): String {
            val gson = Gson()
            val json = gson.toJson(user)
            val encodedJson = java.net.URLEncoder.encode(json, "UTF-8")
            return "chat/$encodedJson"
        }
    }
    object DirectChat : Screen("direct_chat/{user}/{prefs}") {
        fun createRoute(userJson: String, prefsJson: String): String {
            val u = java.net.URLEncoder.encode(userJson, "UTF-8")
            val p = java.net.URLEncoder.encode(prefsJson, "UTF-8")
            return "direct_chat/$u/$p"
        }
    }

    object Profile : Screen("profile/{username}") {
        fun createRoute(username: String): String {
            return "profile/$username"
        }
    }
    object Ratings : Screen("ratings")
}

@ExperimentalLayoutApi
class MainActivity : ComponentActivity() {

    private var isChatScreenActive = false

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screenshots
//        window.setFlags(
//            WindowManager.LayoutParams.FLAG_SECURE,
//            WindowManager.LayoutParams.FLAG_SECURE
//        )

        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        // Ask overlay permission only once
        checkOverlayPermission()

        setContent {
            AnonychatTheme {
                NavGraph(
                    onChatActive = { isChatScreenActive = true },
                    onChatInactive = { isChatScreenActive = false }
                )
            }
        }



    }

    // -------------------------------------------------------
    // APP ENTERING BACKGROUND → START BIRD OVERLAY
    // -------------------------------------------------------
//    override fun onPause() {
//        super.onPause()
//
//        // Avoid starting bubble when rotating screen or navigating inside app
//        if (isChangingConfigurations) return
//
//        // Start bubble ONLY when leaving app & chat screen is active
//        if (isChatScreenActive && !isAppInForeground()) {
//            startFloatingBubble()
//        }
//    }

    // -------------------------------------------------------
    // APP RESUMED → STOP BIRD OVERLAY
    override fun onResume() {
        super.onResume()
        stopService(Intent(this, BirdBubbleService::class.java))
    }

    fun getCurrentUser(context: Context): User {
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

        val username = prefs.getString("username", null)
            ?: error("Username missing in SharedPreferences")
        val email = prefs.getString("user_email", null)
            ?: error("Useremail missing in SharedPreferences")
        return User(
            username = username,
            profilePictureUrl = null,
            id = email
        )
    }


    // -------------------------------------------------------
    // Check permission to draw overlays
    // -------------------------------------------------------
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

    // -------------------------------------------------------
    // Start/Stop Overlay Service
    // -------------------------------------------------------


    // -------------------------------------------------------
    // Helper: Detect if app is in foreground
    // -------------------------------------------------------
    private fun isAppInForeground(): Boolean {
        return lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    }
    override fun onStop() {
        super.onStop()
        if (isChatScreenActive && !isChangingConfigurations) {
            startService(Intent(this, BirdBubbleService::class.java))
        }
    }


    // -------------------------------------------------------
    // NAVIGATION
    // -------------------------------------------------------


    @OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
    @Composable
    fun NavGraph(
        onChatActive: () -> Unit,
        onChatInactive: () -> Unit
    ) {
        val navController = rememberNavController()

        // --- Handle intent from the service to navigate to ChatScreen ---
        LaunchedEffect(intent) {
            if (intent?.getStringExtra("NAVIGATE_TO") == "CHAT") {
                // In a real app, you would retrieve the last logged-in user
                // from SharedPreferences or a database. For now, we'll create a default one.
                val lastUser = User("unknown", "Guest", "")

                navController.navigate(Screen.Chat.createRoute(lastUser)) {
                    // This clears the navigation stack up to the start destination (Login),
                    // making ChatScreen the new "home" screen for this session.
                    popUpTo(navController.graph.startDestinationId) {
                        inclusive = true
                    }
                    // This ensures we don't launch multiple copies of the chat screen
                    launchSingleTop = true
                }
                // Clear the extra so it doesn't trigger again on screen rotation
                intent.removeExtra("NAVIGATE_TO")
            }
        }
        // --- END ---

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
                    onNavigateToDirectChat = { matchedUser, matchedPrefs ->
                        val gson = Gson()
                        navController.navigate(
                            Screen.DirectChat.createRoute(
                                gson.toJson(matchedUser),
                                gson.toJson(matchedPrefs)
                            )
                        )
                    }
                )


            }


            composable(
                Screen.DirectChat.route,
                arguments = listOf(
                    navArgument("user") { type = NavType.StringType },
                    navArgument("prefs") { type = NavType.StringType }
                )
            ) { entry ->
                val userJson = java.net.URLDecoder.decode(
                    entry.arguments!!.getString("user")!!, "UTF-8"
                )
                val prefsJson = java.net.URLDecoder.decode(
                    entry.arguments!!.getString("prefs")!!, "UTF-8"
                )
                val context = LocalContext.current

                val matchedUser = Gson().fromJson(userJson, User::class.java)
                val matchedPrefs = Gson().fromJson(prefsJson, Preferences::class.java)
                val user = getCurrentUser(context)

                DirectChatScreen(
                    currentUser = user,
                    matchedUser = matchedUser,
                    matchedUserPrefs = matchedPrefs,
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
