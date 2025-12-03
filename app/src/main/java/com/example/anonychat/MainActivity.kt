package com.example.anonychat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.anonychat.model.User
import com.example.anonychat.ui.ChatScreen
import com.example.anonychat.ui.LoginScreen
import com.example.anonychat.ui.theme.AnonychatTheme
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.google.gson.Gson

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Chat : Screen("chat/{user}") {
        fun createRoute(user: User): String {
            val userJson = Gson().toJson(user)
            return "chat/$userJson"
        }
    }
}

@ExperimentalLayoutApi
class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContent {
            AnonychatTheme {
                NavGraph()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun NavGraph() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Screen.Login.route) {
        composable(Screen.Login.route) {
            LoginScreen(onLoginClick = { user ->
                navController.navigate(Screen.Chat.createRoute(user))
            })
        }
        composable(
            Screen.Chat.route,
            arguments = listOf(navArgument("user") { type = NavType.StringType })
        ) {
            val userJson = it.arguments?.getString("user")
            val user = Gson().fromJson(userJson, User::class.java)
            ChatScreen(user)
        }
    }
}
