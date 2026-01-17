package com.example.anonychat.ui
import com.example.anonychat.network.NetworkClient

import com.example.anonychat.MainActivity
import com.example.anonychat.R
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.drawable.AnimatedImageDrawable
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import com.example.anonychat.model.Preferences
import com.example.anonychat.model.User


private const val TAG = "BirdBubbleService"
private const val NOTIF_CHANNEL_ID = "bird_bubble_channel"
private const val NOTIF_ID = 4231
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // You can have multiple
class BirdBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    companion object {
        var onNavigateToDirectChat: ((User, Preferences) -> Unit)? = null
    }

    private var bubbleView: ImageView? = null
    private var closeOverlay: ImageView? = null

    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())
    private val HEART_DURATION = 10_000L
    private var showingHeart = false

    private lateinit var vibrator: Vibrator
    private var vibrationJob: Job? = null

    // close zone state
    private var inCloseZone = false

    private val vibScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Service scope for API calls / UI updates
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Add a new state variable for the cupid phase
    private var cupidIsActive = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundCompat()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "No overlay permission")
            stopSelf()
            return START_NOT_STICKY
        }

        showBirdBubble()
        return START_STICKY
    }

    // Start foreground service
    private fun startForegroundCompat() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        NOTIF_CHANNEL_ID,
                        "Bird bubble",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "Floating bird bubble" }
                )
            }

            val notification: Notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setContentTitle("Bird bubble active")
                .setContentText("Tap bubble ♥")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            startForeground(NOTIF_ID, notification)

        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed: $t")
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // Main bubble setup
    private fun showBirdBubble() {
        if (bubbleView != null) return

        val imageView = ImageView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
        }

        val bubbleSize = dp(110)

        val params = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20)
            y = dp(200)
        }

        bubbleParams = params
        bubbleView = imageView

        val prefs = getSharedPreferences("anonychat_theme", Context.MODE_PRIVATE)
        val isDarkTheme = prefs.getBoolean("is_dark_theme", false)
        val birdDrawable = if (isDarkTheme) R.drawable.owlt else R.drawable.bird

        // Draw bird
        setImageSafely(imageView, birdDrawable)

        // --- Create CROSS overlay above bubble ---
        closeOverlay = ImageView(this).apply {
            setImageResource(R.drawable.ic_close_red)  // must exist in drawable
            visibility = View.GONE
        }

        overlayParams = WindowManager.LayoutParams(
            dp(50), dp(50),
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params.x + bubbleSize / 2 - dp(25)
            y = params.y + bubbleSize / 2 - dp(25)
        }

        attachDragHandler(imageView, params)

        try {
            windowManager.addView(imageView, params)
            windowManager.addView(closeOverlay, overlayParams)
        } catch (t: Throwable) {
            Log.e(TAG, "addView failed: $t")
            bubbleView = null
        }
    }


    // Load bird or heart safely
    private fun setImageSafely(view: ImageView, resId: Int) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val src = ImageDecoder.createSource(resources, resId)
                val drawable = ImageDecoder.decodeDrawable(src)
                view.setImageDrawable(drawable)
                if (drawable is AnimatedImageDrawable) drawable.start()
                return
            } catch (_: Exception) {}
        }
        view.setImageBitmap(BitmapFactory.decodeResource(resources, resId))
    }

    // Heart animation + vibration
    @RequiresApi(Build.VERSION_CODES.P)
    private fun showHeartTemporary() {
        if (showingHeart) return // Prevent re-triggering

        Log.d(TAG, "Heart sequence started.")
        showingHeart = true
        cupidIsActive = false // Reset cupid state at the start
        handler.removeCallbacksAndMessages(null)

        // --- Stage 1: Show HEART ---
        bubbleView?.let { iv ->
            (iv.drawable as? AnimatedImageDrawable)?.stop()
            setImageSafely(iv, R.drawable.heart)
            Log.d(TAG, "Image set to: heart.gif")
        }
        startVibration()

        // Launch matchmaking API call in serviceScope
        serviceScope.launch {
            val userPrefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val email = userPrefs.getString("user_email", null)

            if (email == null) {
                Log.w(TAG, "No user email saved. Cannot call matchmaking.")
                // show heart for short duration then revert
                delay(3000)
                stopVibration()
                revertToBird()
                return@launch
            }

            // Call the matchmaking API with a timeout. Adjust timeout as needed.
            val matchResponse = withContext(Dispatchers.IO) {
                try {
                    // keep the same timeout wrapper
                    withTimeoutOrNull(30_000) {
                        try {
                            NetworkClient.api.callMatch(email)
                        } catch (t: Throwable) {
                            Log.e(TAG, "callMatch failed: $t")
                            null
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "match request exception: $t")
                    null
                }
            }


            // --- Robust null check: API returns JSON like {"match":"..."} or {"match":null}
            val matchedEmail = matchResponse
                ?.takeIf { it.isSuccessful }
                ?.body()
                ?.match

            if (matchedEmail.isNullOrBlank()) {
                Log.w(TAG, "No match found in API response. Reverting to bird.")
                stopVibration() // Stop vibration since the search is over
                revertToBird()
                return@launch
            }

            Log.d(TAG, "Match found: $matchedEmail. Fetching preferences...")
            try {
                val myPrefsDeferred = async(Dispatchers.IO) { NetworkClient.api.getPreferences(email) }
                val matchedPrefsDeferred = async(Dispatchers.IO) { NetworkClient.api.getPreferences(matchedEmail) }

                val myPrefsResponse = myPrefsDeferred.await()
                val matchedPrefsResponse = matchedPrefsDeferred.await()

                // Stop vibration only after all network calls are complete
                stopVibration()

                if (!myPrefsResponse.isSuccessful || !matchedPrefsResponse.isSuccessful) {
                    Log.e(TAG, "Failed to fetch preferences for one or both users.")
                    revertToBird()
                    return@launch
                }

                // 4️⃣ Store all necessary data in SharedPreferences for the tap handler
                val gson = com.google.gson.Gson()
                with(userPrefs.edit()) {
                    putString("current_user_prefs_json", gson.toJson(myPrefsResponse.body()))
                    putString("matched_user_prefs_json", gson.toJson(matchedPrefsResponse.body()))
                    apply()
                }

                // 5️⃣ Switch to the cupid state to indicate a match is ready to be opened
                cupidIsActive = true
                showingHeart = false
                setImageSafely(bubbleView!!, R.drawable.heartcupid)
                Log.d(TAG, "Switched to heart-cupid state. Ready for tap.")

            } catch (e: Exception) {
                Log.e(TAG, "Exception while fetching preferences after match.", e)
                stopVibration()
                revertToBird()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun revertToBird() {
        try {
            val prefs = getSharedPreferences("anonychat_theme", Context.MODE_PRIVATE)
            val isDarkTheme = prefs.getBoolean("is_dark_theme", false)
            val birdDrawable = if (isDarkTheme) R.drawable.owlt else R.drawable.bird
            bubbleView?.let { iv ->
                (iv.drawable as? AnimatedImageDrawable)?.stop()
                setImageSafely(iv, birdDrawable)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "revertToBird failed: $t")
        } finally {
            showingHeart = false
            cupidIsActive = false
        }
    }

    private fun bounce(view: View) {
        view.animate()
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(80)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120)
                    .start()
            }
            .start()
    }

    // Touch handler

    private fun updateOverlayPosition() {
        try {
            val params = bubbleParams ?: return
            val overlay = overlayParams ?: return
            // Keep overlay centered on bubble
            overlay.x = params.x + params.width / 2 - dp(25)
            overlay.y = params.y + params.height / 2 - dp(25)
            // If overlay view is already added, update it
            closeOverlay?.let {
                try { windowManager.updateViewLayout(it, overlay) } catch (_: Exception) {}
            }
        } catch (t: Throwable) {
            Log.e(TAG, "updateOverlayPosition failed: $t")
        }
    }
    private fun animatePoofAndRemove(view: View) {

        // --- WHITE PUFF FLASH ---
        view.animate()
            .alpha(0.6f)
            .setDuration(40)
            .withEndAction {
                view.alpha = 1f
            }
            .start()

        // --- SOUND EFFECT ---
        try {
            view.playSoundEffect(SoundEffectConstants.CLICK)
        } catch (_: Exception) {}

        // --- TINY HAPTIC ---
        vibrateShort()

        // --- MAIN POOF ANIMATION ---
        view.animate()
            .scaleX(0.1f)
            .scaleY(0.1f)
            .alpha(0f)
            .translationYBy(-dp(24).toFloat())
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                try { windowManager.removeView(view) } catch (_: Exception) {}
                try { closeOverlay?.let { windowManager.removeView(it) } } catch (_: Exception) {}
                bubbleView = null
                closeOverlay = null
                stopSelf()
            }
            .start()
    }


    @OptIn(ExperimentalLayoutApi::class)
    private fun attachDragHandler(view: View, params: WindowManager.LayoutParams) {
        var longPressConsumed = false

        val gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                @RequiresApi(Build.VERSION_CODES.P)
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    bubbleView?.let { bounce(it) }

                    if (cupidIsActive) {
                        Log.d(TAG, "Heart-cupid clicked. Preparing to navigate to Direct Chat.")
                        serviceScope.launch {
                            try {
                                val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                                val myPrefsJson = prefs.getString("current_user_prefs_json", null)
                                val matchedPrefsJson = prefs.getString("matched_user_prefs_json", null)

                                if (myPrefsJson == null || matchedPrefsJson == null) {
                                    Log.e(TAG, "Cannot navigate, user preference data is missing.")
                                    revertToBird()
                                    return@launch
                                }

                                // --- NEW: Create a broadcast intent ---
                                val intent = Intent().apply {
                                    // Use a custom action to identify this broadcast
                                    action = "com.example.anonychat.ACTION_NAVIGATE_TO_DIRECT_CHAT"
                                    // Put the necessary data into the intent extras
                                    putExtra("my_prefs_json", myPrefsJson)
                                    putExtra("matched_prefs_json", matchedPrefsJson)
                                    // Add this flag to make sure the activity is brought to the foreground
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                // Send the broadcast
                                sendBroadcast(intent)

                                // Close the bubble after sending the broadcast
                                animatePoofAndRemove(bubbleView!!)

                            } catch (ex: Exception) {
                                Log.e(TAG, "Failed to prepare and start direct chat activity", ex)
                            }
                        }

                    }  else if (!showingHeart) {
                        Log.d(TAG, "Bird clicked. Starting heart sequence.")
                        showHeartTemporary()
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    Log.d(TAG, "Long press detected - smooth remove")
                    longPressConsumed = true

                    stopVibration()
                    handler.removeCallbacksAndMessages(null)

                    try {
                        // 🔴 IMPORTANT: stop any running animations to prevent flicker
                        view.animate().cancel()
                        view.clearAnimation()

                        // Optional feedback
                        try { view.playSoundEffect(SoundEffectConstants.CLICK) } catch (_: Exception) {}
                        vibrateShort()

                        // Single smooth vanish animation (NO flicker)
                        view.animate()
                            .alpha(0f)
                            .scaleX(0.2f)
                            .scaleY(0.2f)
                            .translationYBy(-dp(16).toFloat())
                            .setDuration(180)
                            .setInterpolator(DecelerateInterpolator())
                            .withEndAction {
                                try { windowManager.removeView(view) } catch (_: Exception) {}
                                try { closeOverlay?.let { windowManager.removeView(it) } } catch (_: Exception) {}
                                bubbleView = null
                                closeOverlay = null
                                stopSelf()
                            }
                            .start()

                    } catch (t: Throwable) {
                        Log.e(TAG, "error removing on long press: $t")
                        // fallback: immediate removal
                        try { windowManager.removeView(view) } catch (_: Exception) {}
                        try { closeOverlay?.let { windowManager.removeView(it) } } catch (_: Exception) {}
                        bubbleView = null
                        closeOverlay = null
                        stopSelf()
                    }
                }


            }
        )

        // local state to mark a long-press that already handled the gesture
         longPressConsumed = false

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        view.setOnTouchListener { _, event ->

            // If we already consumed a long-press, ignore all further events for this gesture
            if (longPressConsumed) return@setOnTouchListener true

            if (gestureDetector.onTouchEvent(event)) return@setOnTouchListener true

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // reset flag at the start of a new gesture
                    longPressConsumed = false
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()

                    windowManager.updateViewLayout(view, params)

                    // Update close overlay position always (keep it in sync)
                    overlayParams?.apply {
                        x = params.x + params.width / 2 - dp(25)
                        y = params.y + params.height / 2 - dp(25)
                        try { windowManager.updateViewLayout(closeOverlay, this) } catch (_: Exception) {}
                    }

                    // --- CLOSE ZONE LOGIC: compute screen dims here so they are always fresh ---
                    val screenW = resources.displayMetrics.widthPixels
                    val screenH = resources.displayMetrics.heightPixels
                    val closeThreshold = screenH - dp(120)

                    val centerY = params.y + params.height / 2
                    if (centerY >= closeThreshold) {
                        if (!inCloseZone) {
                            inCloseZone = true
                            view.animate().scaleX(0.82f).scaleY(0.82f).setDuration(80).start()
                            closeOverlay?.visibility = View.VISIBLE
                            vibrateShort()
                        }
                    } else {
                        if (inCloseZone) {
                            inCloseZone = false
                            view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                            closeOverlay?.visibility = View.GONE
                        }
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (inCloseZone) {
                        animateRemove(params, view)
                        return@setOnTouchListener true
                    }

                    // compute screen width dynamically so snapping also respects rotation
                    val screenW = resources.displayMetrics.widthPixels
                    val w = if (view.width > 0) view.width else params.width
                    val centerX = params.x + w / 2
                    val targetX = if (centerX < screenW / 2) 30 else screenW - w - 30
                    animateSnap(params, view, targetX)
                    true
                }
                else -> false
            }
        }
    }


    private fun animateRemove(params: WindowManager.LayoutParams, view: View) {

        val startY = params.y

        ValueAnimator.ofInt(startY, startY + dp(120)).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                params.y = anim.animatedValue as Int
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: Exception) {
                }
            }
            start()
        }

        view.animate().alpha(0f).setDuration(180).withEndAction {
            stopSelf()
        }.start()

    }

    private fun animateSnap(params: WindowManager.LayoutParams, view: View, targetX: Int) {

        val startX = params.x
        ValueAnimator.ofInt(startX, targetX).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: Exception) {
                }
            }
            start()
        }
    }

    // VIBRATION
    private fun startVibration() {
        if (vibrationJob?.isActive == true) return

        vibrationJob = vibScope.launch {
            while (isActive) {
                try {
                    if (vibrator.hasVibrator()) {

                        vibrator.vibrate(VibrationEffect.createOneShot(55, 180))
                        delay(200)
                        vibrator.vibrate(VibrationEffect.createOneShot(35, 120))
                        delay(1000)

                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun stopVibration() {
        vibrationJob?.cancel()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun vibrateShort() {
        if (!vibrator.hasVibrator()) return
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(40, 100))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopVibration()
        vibScope.cancel()
        serviceScope.cancel()
        try {
            bubbleView?.let { windowManager.removeView(it) }
            closeOverlay?.let { windowManager.removeView(it) }
        } catch (t: Throwable) {
            Log.e(TAG, "removeView failed: $t")
        }
        bubbleView = null
        closeOverlay = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
