package com.example.anonychat.ui

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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

private const val TAG = "BirdBubbleService"
private const val NOTIF_CHANNEL_ID = "bird_bubble_channel"
private const val NOTIF_ID = 4231
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // You can have multiple
class BirdBubbleService : Service() {

    private lateinit var windowManager: WindowManager

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
    // Heart animation + vibration
    // Heart animation + vibration
    // ... inside BirdBubbleService.kt class

    // Add a new state variable for the cupid phase
    private var cupidIsActive = false

    // ...

    // Heart animation + vibration
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

        // --- Stage 2: Show HEART-CUPID ---
        handler.postDelayed({
            stopVibration()
            cupidIsActive = true // Cupid is now active

            bubbleView?.let { iv ->
                (iv.drawable as? AnimatedImageDrawable)?.stop()
                setImageSafely(iv, R.drawable.heartcupid)
                Log.d(TAG, "Image set to: heart-cupid.gif. This is now the permanent state.")
            }
        }, HEART_DURATION)
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
    @OptIn(ExperimentalLayoutApi::class)
    private fun attachDragHandler(view: View, params: WindowManager.LayoutParams) {

        val gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    bubbleView?.let { bounce(it) }   // 🔥 ADD THIS LINE

                    // --- THIS IS THE MAIN LOGIC CHANGE ---
                    if (cupidIsActive) {
                        Log.d(TAG, "Heart-cupid clicked. Opening app to ChatScreen.")

                        // Create an Intent to launch MainActivity
                        val intent = Intent(this@BirdBubbleService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            // Add an extra to tell MainActivity where to go
                            putExtra("NAVIGATE_TO", "CHAT")
                        }
                        startActivity(intent)

                    } else if (!showingHeart) {
                        Log.d(TAG, "Bird clicked. Starting heart sequence.")
                        showHeartTemporary()
                    }
                    // --- END OF CHANGE ---

                    return true
                }
            }
        )

        // ... the rest of the attachDragHandler function remains exactly the same
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val closeThreshold = screenH - dp(120)

        view.setOnTouchListener { _, event ->

            if (gestureDetector.onTouchEvent(event)) return@setOnTouchListener true

            when (event.action) {
                // ... (ACTION_DOWN, ACTION_MOVE, ACTION_UP logic is unchanged)
                MotionEvent.ACTION_DOWN -> {
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

                    // Update close overlay position always
                    overlayParams?.apply {
                        x = params.x + params.width / 2 - dp(25)
                        y = params.y + params.height / 2 - dp(25)
                        windowManager.updateViewLayout(closeOverlay, this)
                    }

                    // --- CLOSE ZONE LOGIC ---
                    val centerY = params.y + params.height / 2
                    if (centerY >= closeThreshold) {

                        if (!inCloseZone) {
                            inCloseZone = true

                            // shrink bubble
                            view.animate().scaleX(0.82f).scaleY(0.82f).setDuration(80).start()

                            // show cross
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
