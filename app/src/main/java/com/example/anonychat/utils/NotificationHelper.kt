@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.anonychat.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.example.anonychat.AppVisibility
import com.example.anonychat.MainActivity
import com.example.anonychat.R
import com.example.anonychat.model.Preferences
import com.example.anonychat.model.User
import com.example.anonychat.ui.ChatMessage
import com.google.gson.Gson
import me.leolin.shortcutbadger.ShortcutBadger
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Helper class to manage chat notifications with messaging style
 */
object NotificationHelper {
    private const val CHANNEL_ID = "chat_messages"
    private const val CHANNEL_NAME = "Chat Messages"
    private const val PREFS_NAME = "notification_prefs"
    private const val KEY_UNREAD_MESSAGES = "unread_messages"
    private const val KEY_NOTIFICATION_IDS = "notification_ids"
    private const val KEY_NEXT_ID = "next_notification_id"
    private const val KEY_USER_METADATA = "user_metadata"
    
    // Single notification ID for all messages
    private const val UNIFIED_NOTIFICATION_ID = 1999
    
    // Track notifications per user
    private val userNotifications = ConcurrentHashMap<String, MutableList<ChatMessage>>()
    private val notificationIds = ConcurrentHashMap<String, Int>()
    private var nextNotificationId = 2000 // Start from 2000 to avoid conflicts
    
    // Store user metadata for notifications
    private data class UserMetadata(
        val username: String,
        val preferences: Preferences
    )
    private val userMetadata = ConcurrentHashMap<String, UserMetadata>()
    
    // Job for delayed notification recreation
    private var recreateNotificationJob: Job? = null
    private val notificationScope = CoroutineScope(Dispatchers.Main)
    
    // Track profile picture cache
    private val profilePictureCache = ConcurrentHashMap<String, Bitmap>()
    
    private val gson = Gson()
    
    /**
     * Initialize notification channel (required for Android O+)
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new chat messages"
                enableVibration(true)
                setShowBadge(true) // Enable badge on app icon
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        
        // Load persisted unread messages
        loadUnreadMessages(context)
    }
    
    /**
     * Save unread messages to SharedPreferences
     */
    private fun saveUnreadMessages(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            
            // Save user notifications as JSON
            val notificationsJson = gson.toJson(userNotifications)
            editor.putString(KEY_UNREAD_MESSAGES, notificationsJson)
            
            // Save notification IDs
            val idsJson = gson.toJson(notificationIds)
            editor.putString(KEY_NOTIFICATION_IDS, idsJson)
            
            // Save user metadata
            val metadataJson = gson.toJson(userMetadata)
            editor.putString(KEY_USER_METADATA, metadataJson)
            
            // Save next notification ID
            editor.putInt(KEY_NEXT_ID, nextNotificationId)
            
            editor.apply()
            Log.d("NotificationHelper", "Saved unread messages and metadata to preferences")
        } catch (e: Exception) {
            Log.e("NotificationHelper", "Failed to save unread messages", e)
        }
    }
    
    /**
     * Load unread messages from SharedPreferences
     */
    private fun loadUnreadMessages(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            // Load user notifications
            val notificationsJson = prefs.getString(KEY_UNREAD_MESSAGES, null)
            if (notificationsJson != null) {
                val type = object : com.google.gson.reflect.TypeToken<ConcurrentHashMap<String, MutableList<ChatMessage>>>() {}.type
                val loaded: ConcurrentHashMap<String, MutableList<ChatMessage>> = gson.fromJson(notificationsJson, type)
                userNotifications.clear()
                userNotifications.putAll(loaded)
            }
            
            // Load notification IDs
            val idsJson = prefs.getString(KEY_NOTIFICATION_IDS, null)
            if (idsJson != null) {
                val type = object : com.google.gson.reflect.TypeToken<ConcurrentHashMap<String, Int>>() {}.type
                val loaded: ConcurrentHashMap<String, Int> = gson.fromJson(idsJson, type)
                notificationIds.clear()
                notificationIds.putAll(loaded)
            }
            
            // Load user metadata
            val metadataJson = prefs.getString(KEY_USER_METADATA, null)
            if (metadataJson != null) {
                val type = object : com.google.gson.reflect.TypeToken<ConcurrentHashMap<String, UserMetadata>>() {}.type
                val loaded: ConcurrentHashMap<String, UserMetadata> = gson.fromJson(metadataJson, type)
                userMetadata.clear()
                userMetadata.putAll(loaded)
            }
            
            // Load next notification ID
            nextNotificationId = prefs.getInt(KEY_NEXT_ID, 2000)
            
            Log.d("NotificationHelper", "Loaded ${userNotifications.size} users with unread messages and ${userMetadata.size} metadata entries")
        } catch (e: Exception) {
            Log.e("NotificationHelper", "Failed to load unread messages", e)
        }
    }
    
    /**
     * Map romance range to emotion number (1-5)
     */
    private fun romanceRangeToEmotion(rangeStart: Float, rangeEnd: Float): Int {
        val mid = (rangeStart + rangeEnd) / 2f
        return when {
            mid < 1.8f -> 1
            mid < 2.6f -> 2
            mid < 3.4f -> 3
            mid < 4.2f -> 4
            else -> 5
        }
    }
    
    /**
     * Create a circular bitmap with border (like in ChatScreen)
     * In light theme, adds a blue background behind the profile picture
     */
    private fun createCircularBitmap(bitmap: Bitmap, isDarkTheme: Boolean): Bitmap {
        val size = 200 // Size for notification icon
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        
        // Start with transparent background
        canvas.drawARGB(0, 0, 0, 0)
        
        // Border color based on theme
        val borderColor = if (isDarkTheme) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val borderWidth = 4f
        val imageRadius = (size / 2f) - borderWidth
        
        // Step 1: In light theme, draw blue circular background first
        if (!isDarkTheme) {
            val backgroundPaint = Paint().apply {
                isAntiAlias = true
                color = 0xFF87CEEB.toInt() // Sky Blue
                style = Paint.Style.FILL
            }
            canvas.drawCircle(size / 2f, size / 2f, imageRadius, backgroundPaint)
        }
        
        // Step 2: Create a temporary bitmap for the circular image
        val tempBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val tempCanvas = Canvas(tempBitmap)
        
        // Draw white circle mask on temp canvas
        val maskPaint = Paint().apply {
            isAntiAlias = true
            color = 0xFFFFFFFF.toInt()
        }
        tempCanvas.drawCircle(size / 2f, size / 2f, imageRadius, maskPaint)
        
        // Apply source image with SRC_IN mode
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val rect = Rect(0, 0, bitmap.width, bitmap.height)
        val rectF = RectF(borderWidth, borderWidth, size - borderWidth, size - borderWidth)
        tempCanvas.drawBitmap(bitmap, rect, rectF, maskPaint)
        
        // Step 3: Draw the circular image on top of the background
        val imagePaint = Paint().apply {
            isAntiAlias = true
        }
        canvas.drawBitmap(tempBitmap, 0f, 0f, imagePaint)
        
        // Step 4: Draw border on top
        val borderPaint = Paint().apply {
            isAntiAlias = true
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
        }
        
        val borderRadius = (size / 2f) - (borderWidth / 2f)
        canvas.drawCircle(size / 2f, size / 2f, borderRadius, borderPaint)
        
        // Clean up temp bitmap
        tempBitmap.recycle()
        
        return output
    }
    
    /**
     * Get avatar bitmap from raw resources based on gender and romance range
     */
    private fun getAvatarBitmap(context: Context, gender: String, romanceMin: Float, romanceMax: Float): Bitmap? {
        return try {
            val emotion = romanceRangeToEmotion(romanceMin, romanceMax)
            val avatarResName = if (gender == "female") "female_exp$emotion" else "male_exp$emotion"
            val avatarResId = context.resources.getIdentifier(avatarResName, "raw", context.packageName)
            
            val rawBitmap = if (avatarResId != 0) {
                // Decode the webp image directly
                val inputStream = context.resources.openRawResource(avatarResId)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                bitmap
            } else {
                // Fallback to male_exp1
                val inputStream = context.resources.openRawResource(R.raw.male_exp1)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                bitmap
            }
            
            // Check if system is in dark mode
            val isDarkTheme = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            
            // Create circular bitmap with border
            rawBitmap?.let { createCircularBitmap(it, isDarkTheme) }
        } catch (e: Exception) {
            Log.e("NotificationHelper", "Failed to load avatar bitmap", e)
            null
        }
    }
    
    /**
     * Show a notification for a new message
     * Note: This should only be called for messages that are actually unread
     * (shouldShowNotification already filters out messages from active chats)
     */
    @Suppress("DEPRECATION")
    suspend fun showMessageNotification(
        context: Context,
        message: ChatMessage,
        senderUsername: String,
        senderPreferences: Preferences,
        currentUserEmail: String
    ) {
        val senderEmail = message.from
        
        Log.e("NotificationHelper", "showMessageNotification called: from=$senderEmail, username=$senderUsername, text=${message.text}, messageId=${message.id}")
        
        // Add message to user's notification list
        val messages = userNotifications.getOrPut(senderEmail) { mutableListOf() }
        messages.add(message)
        
        Log.e("NotificationHelper", "Total messages from $senderEmail: ${messages.size}, Total users with notifications: ${userNotifications.size}")
        
        // Store user metadata
        userMetadata[senderEmail] = UserMetadata(senderUsername, senderPreferences)
        
        // Show unified notification with all messages
        showUnifiedNotification(context, currentUserEmail)
        
        // Save unread messages to persist across app restarts
        saveUnreadMessages(context)
        
        Log.d("NotificationHelper", "Showed notification for $senderUsername (${messages.size} messages)")
    }
    
    /**
     * Show unified notification with all messages from all users
     */
    private fun showUnifiedNotification(context: Context, currentUserEmail: String) {
        val totalMessages = userNotifications.values.sumOf { it.size }
        val userCount = userNotifications.size
        
        Log.e("NotificationHelper", "showUnifiedNotification: totalMessages=$totalMessages, userCount=$userCount")
        
        if (totalMessages == 0) {
            Log.e("NotificationHelper", "No messages to show, skipping notification")
            return
        }
        
        // Create messaging style for unified notification
        val messagingStyle = NotificationCompat.MessagingStyle(
            Person.Builder().setName("You").build()
        )
        
        // Set conversation title based on number of users
        val conversationTitle = if (userCount == 1) {
            userMetadata.values.firstOrNull()?.username ?: "Chat"
        } else {
            "$userCount people"
        }
        messagingStyle.conversationTitle = conversationTitle
        
        // Mark as group conversation if multiple users - this ensures all messages are shown
        if (userCount > 1) {
            messagingStyle.isGroupConversation = true
        }
        
        Log.e("NotificationHelper", "Conversation title set to: '$conversationTitle' (userCount=$userCount)")
        
        // Collect all messages from all users and sort by timestamp
        val allMessages = mutableListOf<Triple<ChatMessage, String, UserMetadata>>()
        userNotifications.forEach { (email, messages) ->
            val metadata = userMetadata[email]
            Log.e("NotificationHelper", "Processing user $email: ${messages.size} messages, metadata=${metadata?.username}")
            if (metadata != null) {
                messages.forEach { msg ->
                    allMessages.add(Triple(msg, email, metadata))
                    Log.e("NotificationHelper", "  - Added message from ${metadata.username}: ${msg.text}")
                }
            }
        }
        allMessages.sortBy { it.first.timestamp }
        
        Log.e("NotificationHelper", "Total collected: ${allMessages.size} messages from ${userNotifications.size} users")
        Log.e("NotificationHelper", "About to add ${allMessages.size} messages to MessagingStyle")
        
        // Add all messages to the notification
        var addedCount = 0
        allMessages.forEach { (msg, email, metadata) ->
            addedCount++
            Log.e("NotificationHelper", "Adding message #$addedCount from ${metadata.username}: ${msg.text}")
            val profilePicBitmap = getAvatarBitmap(
                context,
                metadata.preferences.gender,
                metadata.preferences.romanceMin,
                metadata.preferences.romanceMax
            )
            
            val sender = Person.Builder()
                .setName(metadata.username)
                .apply {
                    if (profilePicBitmap != null) {
                        setIcon(IconCompat.createWithBitmap(profilePicBitmap))
                    }
                }
                .build()
            
            val messageText = when {
                msg.text == "[Audio Message]" -> "🎵 Audio message"
                msg.text == "[Image]" -> "🖼️ Image"
                msg.text == "[Video]" -> "🎥 Video"
                msg.text == "[GIF]" -> "🎞️ GIF"
                else -> msg.text
            }
            
            messagingStyle.addMessage(
                NotificationCompat.MessagingStyle.Message(
                    messageText,
                    msg.timestamp,
                    sender
                )
            )
        }
        
        Log.e("NotificationHelper", "✅ Added $addedCount messages to notification")
        
        // Create intent to open main activity
        val intent = Intent(context, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            UNIFIED_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build unified notification
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(messagingStyle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setNumber(totalMessages) // Shows badge count on app icon
            .setBadgeIconType(NotificationCompat.BADGE_ICON_LARGE) // Use large icon to show number
            .build()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(UNIFIED_NOTIFICATION_ID, notification)
        
        // Update app icon badge with ShortcutBadger for better cross-launcher support
        try {
            ShortcutBadger.applyCount(context, totalMessages)
            Log.d("NotificationHelper", "Badge count set to $totalMessages using ShortcutBadger")
        } catch (e: Exception) {
            Log.e("NotificationHelper", "Failed to set badge count with ShortcutBadger", e)
        }
        
        // Save unread messages to persist across app restarts
        saveUnreadMessages(context)
        
        Log.e("NotificationHelper", "✅ NOTIFICATION SHOWN: $totalMessages messages from $userCount users, title='$conversationTitle'")
    }
    
    /**
     * Clear notifications for a specific user
     */
    fun clearNotificationsForUser(context: Context, userEmail: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        Log.e("NotificationHelper", "clearNotificationsForUser called for: $userEmail")
        
        // Cancel any pending notification recreation
        recreateNotificationJob?.cancel()
        
        // First, cancel the existing notification to prevent empty notification flash
        notificationManager.cancel(UNIFIED_NOTIFICATION_ID)
        Log.e("NotificationHelper", "Cancelled notification ID: $UNIFIED_NOTIFICATION_ID")
        
        // Clear stored messages
        userNotifications.remove(userEmail)
        userMetadata.remove(userEmail)
        
        Log.e("NotificationHelper", "Remaining users with notifications: ${userNotifications.size}")
        
        // Update badge count
        val remainingMessages = userNotifications.values.sumOf { it.size }
        try {
            ShortcutBadger.applyCount(context, remainingMessages)
            Log.d("NotificationHelper", "Badge count updated to $remainingMessages after clearing $userEmail")
        } catch (e: Exception) {
            Log.e("NotificationHelper", "Failed to update badge count", e)
        }
        
        // Save updated state
        saveUnreadMessages(context)
        
        // Only recreate notification if there are remaining messages AND app is in background
        if (userNotifications.isNotEmpty()) {
            Log.e("NotificationHelper", "Scheduling notification recreation after 100ms delay")
            recreateNotificationJob = notificationScope.launch {
                // Small delay to prevent empty notification flash
                delay(100)
                
                // Check if app is still in foreground - don't show notification if user is actively using the app
                val isInForeground = AppVisibility.isForeground
                if (isInForeground) {
                    Log.e("NotificationHelper", "App is in foreground, skipping notification recreation")
                    return@launch
                }
                
                // Get current user email from preferences
                val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val currentUserEmail = prefs.getString("email", "") ?: ""
                showUnifiedNotification(context, currentUserEmail)
            }
        } else {
            Log.e("NotificationHelper", "No remaining notifications, not recreating")
        }
        
        Log.e("NotificationHelper", "✅ Cleared notifications for $userEmail")
    }
    
    /**
     * Clear all chat notifications
     */
    fun clearAllNotifications(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Cancel unified notification
        notificationManager.cancel(UNIFIED_NOTIFICATION_ID)
        
        userNotifications.clear()
        notificationIds.clear()
        userMetadata.clear()
        
        // Save updated state
        saveUnreadMessages(context)
        
        Log.d("NotificationHelper", "Cleared all notifications")
    }
    
    /**
     * Get notification count for a specific user
     */
    fun getNotificationCount(userEmail: String): Int {
        return userNotifications[userEmail]?.size ?: 0
    }
    
    /**
     * Get total notification count across all users
     */
    fun getTotalNotificationCount(): Int {
        return userNotifications.values.sumOf { it.size }
    }
    
    /**
     * Clear all stored notifications (useful for debugging or when app starts)
     */
    fun clearAllStoredNotifications(context: Context) {
        Log.e("NotificationHelper", "Clearing all stored notifications")
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(UNIFIED_NOTIFICATION_ID)
        
        userNotifications.clear()
        notificationIds.clear()
        userMetadata.clear()
        
        saveUnreadMessages(context)
        Log.e("NotificationHelper", "✅ All stored notifications cleared")
    }
    
}

// Made with Bob
