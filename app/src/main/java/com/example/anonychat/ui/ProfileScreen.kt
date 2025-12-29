package com.example.anonychat.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.Build
import com.example.anonychat.network.NetworkClient
import com.example.anonychat.network.PreferencesRequest
import com.example.anonychat.network.AgeRange
import com.example.anonychat.network.RomanceRange
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.example.anonychat.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    navController: NavController,
    initialUsername: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var roses by remember { mutableStateOf(12) }
    var sparks by remember { mutableStateOf(5) }
    var giftsLeft by remember { mutableStateOf(5) } // This is now a variable
    var rating by remember { mutableStateOf(4.6f) }
    var ratingCount by remember { mutableStateOf(128) }

    /* ---------- THEME ---------- */
    val themePrefs = remember {
        context.getSharedPreferences("anonychat_theme", Context.MODE_PRIVATE)
    }
    val isDarkTheme = themePrefs.getBoolean("is_dark_theme", false)

    val primaryColor = if (isDarkTheme) Color(0xFFC0C0C0) else Color(0xFF64B5F6)
    val panelColor =
        if (isDarkTheme) Color.Black.copy(alpha = 0.35f)
        else Color.White.copy(alpha = 0.65f)
    val textColor =
        if (isDarkTheme) Color(0xFFF5F5F5)
        else Color(0xFF1E1E1E)

    val inactiveSliderColor = if (isDarkTheme) Color.DarkGray else Color(0xFFB3E5FC).copy(alpha = 0.5f)

    /* ---------- PREFS ---------- */
    val userPrefs = remember {
        context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    }

    var username by remember { mutableStateOf(initialUsername) }
    var gender by remember { mutableStateOf(userPrefs.getString("gender", "male") ?: "male") }
    var age by remember { mutableStateOf(userPrefs.getInt("age", 30)) }
    var preferredGender by remember {
        mutableStateOf(userPrefs.getString("preferred_gender", "female") ?: "female")
    }
    var preferredAgeRange by remember {
        mutableStateOf(
            userPrefs.getFloat("preferred_age_min", 27f)..
                    userPrefs.getFloat("preferred_age_max", 31f)
        )
    }
    var romanceRange by remember {
        mutableStateOf(
            userPrefs.getFloat("romance_min", 2f)..
                    userPrefs.getFloat("romance_max", 9f)
        )
    }

    /* ---------- VIDEO ---------- */
    val exoPlayer = remember(isDarkTheme) {
        ExoPlayer.Builder(context).build().apply {
            val uri =
                if (isDarkTheme)
                    Uri.parse("android.resource://${context.packageName}/${R.raw.night}")
                else
                    Uri.parse("android.resource://${context.packageName}/${R.raw.cloud}")

            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    /* ---------- IMAGE LOADER ---------- */
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28)
                    add(ImageDecoderDecoder.Factory())
                else
                    add(GifDecoder.Factory())
            }
            .build()
    }

    /* ================= UI ================= */
    var isLoading by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {

        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            /* ---------- TOP BAR ---------- */
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 24.dp)
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Filled.ArrowBack, null, tint = textColor)
                }
            }

            /* ---------- PROFILE HEADER ---------- */
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .border(2.5.dp, Color.White, CircleShape)
                        .background(
                            if (isDarkTheme) Color(0xFF142235) else Color(0xFF87CEEB),
                            CircleShape
                        )
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    // -----------------------------
                    // Dynamic animated avatar:
                    // - maps romanceRange -> emotion (1..11)
                    // - picks raw resource: male_exp{N} / female_exp{N}
                    // - crossfades between images
                    // - preserves animatable start/stop on tap (like your previous code)
                    // -----------------------------

                    // place inside your avatar Box (replaces the Crossfade block + animatable handling)
                    var animatable by remember { mutableStateOf<Animatable?>(null) }
                    var animDurationMs by remember { mutableStateOf(3000L) } // default fallback
                    var isPlaying by remember { mutableStateOf(false) }

// Map of resource base name -> duration in ms (converted from your values)
                    val animationDurations = remember {
                        mapOf(
                            "male_exp9" to 3580L,
                            "female_exp8" to 8500L,
                            "female_exp9" to 8500L,
                            "female_exp10" to 5700L,
                            "female_exp11" to 5020L,
                            "male_exp10" to 8380L,
                            "male_exp11" to 11600L,
                            "male_exp1" to 5400L,
                            "male_exp2" to 4420L,
                            "female_exp1" to 2740L,
                            "male_exp3" to 7640L,
                            "female_exp2" to 3100L,
                            "male_exp4" to 2020L,
                            "female_exp3" to 1540L,
                            "male_exp5" to 2380L,
                            "female_exp4" to 1360L,
                            "male_exp6" to 1840L,
                            "female_exp5" to 1720L,
                            "male_exp7" to 3760L,
                            "female_exp6" to 1480L,
                            "male_exp8" to 2800L,
                            "female_exp7" to 5700L
                        )
                    }

// compute emotion and resource name like you did earlier
                    val emotion = remember(romanceRange.start, romanceRange.endInclusive) {
                        romanceRangeToEmotion(romanceRange.start, romanceRange.endInclusive)
                    }
                    val resBaseName = remember(gender, emotion) {
                        if (gender.lowercase() == "female") "female_exp$emotion" else "male_exp$emotion"
                    }
                    val resUri = remember(gender, emotion) {
                        "android.resource://${context.packageName}/raw/$resBaseName"
                    }
                    var playToken by remember { mutableIntStateOf(0) }

                    Crossfade(
                        targetState = resUri,
                        animationSpec = tween(durationMillis = 1500)
                    ) { uri ->
                        val painter = rememberAsyncImagePainter(
                            ImageRequest.Builder(context)
                                .data(uri)
                                .setParameter("playToken", playToken) // 👈 forces reload
                                .build(),
                            imageLoader = imageLoader,
                            onSuccess = { result ->
                                val drawable = result.result.drawable
                                if (drawable is Animatable) {
                                    animatable = drawable
                                    // set duration from map (fallback to default)
                                    animDurationMs = animationDurations[resBaseName] ?: 3000L
                                    // ensure it is stopped initially (no autoplay)
                                    scope.launch {
                                        try { drawable.stop() } catch (_: Exception) {}
                                    }
                                } else {
                                    animatable = null
                                }
                            }
                        )

                        Image(
                            painter = painter,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
                                    if (isPlaying) return@clickable

                                    animatable?.let { anim ->
                                        scope.launch {
                                            try {
                                                isPlaying = true

                                                // always start clean
                                                try {
                                                    anim.stop()
                                                } catch (_: Exception) {
                                                }
                                                try {
                                                    anim.start()
                                                } catch (_: Exception) {
                                                }

                                                // play exactly once
                                                kotlinx.coroutines.delay(animDurationMs)

                                                // stop at end
                                                try {
                                                    anim.stop()
                                                } catch (_: Exception) {
                                                }

                                                // 🔥 FORCE RESET TO FIRST FRAME
                                                playToken++   // <-- this reloads drawable → frame 0
                                            } finally {
                                                isPlaying = false
                                            }
                                        }
                                    }
                                },
                            contentScale = ContentScale.Crop
                        )
                    }


// ...

//...

                }

                Spacer(Modifier.height(8.dp))

                RatingRow(
                    rating = rating,
                    ratingCount = ratingCount,
                    isDarkTheme = isDarkTheme
                ) {
                    navController.navigate("ratings") // 👈 your ratings screen
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GiftCounter(roses, R.drawable.rose, imageLoader)
                    GiftCounter(sparks, R.drawable.thunder, imageLoader)
                }

            }

            Spacer(Modifier.height(12.dp))

            /* ---------- SCROLLABLE CARD ---------- */
            Surface(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .offset(y = (-8).dp)     // ⬆ move card up
                    .fillMaxWidth()
                    .heightIn(max = 500.dp),
                shape = RoundedCornerShape(22.dp),
                color = panelColor
            ) {

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = 14.dp,
                            end = 14.dp,
                            top = 12.dp,
                            bottom = 20.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {

                    SectionTitle("Username", textColor)

                    TextField(
                        value = username,
                        onValueChange = { username = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = textColor,
                            unfocusedTextColor = textColor,
                            cursorColor = primaryColor,
                            focusedContainerColor =
                                if (isDarkTheme) Color(0xFF2A2F3A) else Color(0xFFF2F6FB),
                            unfocusedContainerColor =
                                if (isDarkTheme) Color(0xFF2A2F3A) else Color(0xFFF2F6FB),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )


                    val pillBackgroundBrush =
                        if (isDarkTheme) {
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFFB71C1C).copy(alpha = 0.35f), // deep rose
                                    Color.Black.copy(alpha = 0.35f)
                                )
                            )
                        } else {
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFFFFDCE1), // soft rose blush
                                    Color(0xFFE3F2FD)  // sky blue
                                )
                            )
                        }

                    val pillBorderColor =
                        if (isDarkTheme)
                            Color(0xFFFFCDD2).copy(alpha = 0.6f)
                        else
                            Color(0xFFF8BBD0).copy(alpha = 0.8f) // rose-tinted border

                    val pillTextColor =
                        if (isDarkTheme)
                            Color.White
                        else
                            Color(0xFF37474F) // soft neutral, not harsh blue
                    Surface(
                        modifier = Modifier
                            .padding(start = 4.dp, top = 8.dp),
                        shape = RoundedCornerShape(50),
                        color = Color.Transparent
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    brush = pillBackgroundBrush,
                                    shape = RoundedCornerShape(50)
                                )
                                .border(
                                    width = 1.dp,
                                    color = pillBorderColor,
                                    shape = RoundedCornerShape(50)
                                )
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$giftsLeft ",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = pillTextColor
                                )

                                Spacer(Modifier.width(8.dp))

                                Image(
                                    painter = rememberAsyncImagePainter(
                                        model = ImageRequest.Builder(context)
                                            .data(R.drawable.rose) // 🌹 GIF
                                            .build(),
                                        imageLoader = imageLoader
                                    ),
                                    contentDescription = "Rose gift",
                                    modifier = Modifier.size(18.dp)
                                )

                                Spacer(Modifier.width(8.dp))

                                Text(
                                    text = "left to gift today",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = pillTextColor.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }


                    SectionTitle("Your Gender", textColor)
                    CompactGenderSelector(gender, { gender = it }, isDarkTheme, primaryColor)

                    CompactSlider("Your Age", age.toString(), textColor, primaryColor) {
                        Slider(
                            value = age.toFloat(),
                            onValueChange = { age = it.toInt() },
                            valueRange = 18f..100f,
                            colors = SliderDefaults.colors(
                                activeTrackColor = primaryColor,
                                inactiveTrackColor = inactiveSliderColor,
                                thumbColor = Color.White
                            ),
                            thumb = { CircularThumb(color = Color.White) }
                        )
                    }

                    SectionTitle("Preferred Gender", textColor)
                    CompactGenderSelector(preferredGender, { preferredGender = it }, isDarkTheme, primaryColor)

                    CompactSlider(
                        "Preferred Age Range",
                        "${preferredAgeRange.start.toInt()} - ${preferredAgeRange.endInclusive.toInt()}",
                        textColor,
                        primaryColor
                    ) {
                        RangeSlider(
                            value = preferredAgeRange,
                            onValueChange = { preferredAgeRange = it },
                            valueRange = 18f..100f,
                            colors = SliderDefaults.colors(
                                activeTrackColor = primaryColor,
                                inactiveTrackColor = inactiveSliderColor,
                                thumbColor = Color.White
                            ),
                            startThumb = { CircularThumb(color = Color.White) },
                            endThumb = { CircularThumb(color = Color.White) }
                        )
                    }

                    CompactSlider(
                        "Romance Range",
                        "${romanceRange.start.toInt()} - ${romanceRange.endInclusive.toInt()}",
                        textColor,
                        primaryColor
                    ) {
                        RangeSlider(
                            value = romanceRange,
                            onValueChange = { romanceRange = it },
                            valueRange = 1f..10f,
                            colors = SliderDefaults.colors(
                                activeTrackColor = primaryColor,
                                inactiveTrackColor = inactiveSliderColor,
                                thumbColor = Color.White
                            ),
                            startThumb = { CircularThumb(color = Color.White) },
                            endThumb = { CircularThumb(color = Color.White) }
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    Button(
                        onClick = {
                            isLoading = true
                            saveUserPrefs(
                                context = context,
                                scope = scope,
                                userPrefs = userPrefs,
                                username = username,
                                gender = gender,
                                age = age,
                                preferredGender = preferredGender,
                                preferredAgeRange = preferredAgeRange,
                                romanceRange = romanceRange,
                                onSuccess = {
                                    isLoading = false
                                    Toast.makeText(context, "Preferences saved!", Toast.LENGTH_SHORT).show()
                                },
                                onFailure = {
                                    isLoading = false
                                    Toast.makeText(context, "Failed to save. Please try again.", Toast.LENGTH_LONG).show()
                                }
                            )
                         },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor,
                            contentColor = if (isDarkTheme) Color.Black else Color.White
                        )
                    ) {
                        Text("Save")
                    }
                }

            }
        }
        LoadingHeartOverlay(isLoading = isLoading)

    }
}

/* ================= HELPERS ================= */

@Composable
private fun CircularThumb(color: Color) {
    Box(
        Modifier
            .size(20.dp)
            .background(color, CircleShape)
            .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
    )
}

@Composable
private fun SectionTitle(text: String, color: Color) {
    // --- You can modify the style here to change all titles at once ---
    Text(
        text,
        style = MaterialTheme.typography.titleSmall, // Changed from labelMedium to titleSmall for a slightly larger size
        fontWeight = FontWeight.Bold, // Make it bold
        color = color
    )
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactGenderSelector(
    value: String,
    onChange: (String) -> Unit,
    isDarkTheme: Boolean,
    activeColor: Color
) {
    val buttonColors = SegmentedButtonDefaults.colors(
        activeContainerColor = activeColor,
        activeContentColor = if (isDarkTheme) Color.Black else Color.White,
        inactiveContainerColor = Color.Transparent,
        inactiveContentColor = activeColor,
        activeBorderColor = activeColor,
        inactiveBorderColor = activeColor.copy(alpha = 0.5f)
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.scale(0.9f)) {
        SegmentedButton(
            selected = value == "male",
            onClick = { onChange("male") },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
            label = { Text("Male") },
            colors = buttonColors,
            border = SegmentedButtonDefaults.borderStroke(color = activeColor, width = 1.dp)
        )
        SegmentedButton(
            selected = value == "female",
            onClick = { onChange("female") },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
            label = { Text("Female") },
            colors = buttonColors,
            border = SegmentedButtonDefaults.borderStroke(color = activeColor, width = 1.dp)
        )
    }
}

//... (at the bottom of ProfileScreen.kt, inside the HELPERS section)

@Composable
private fun CompactSlider(
    label: String,
    valueLabel: String,
    labelColor: Color,
    valueColor: Color,
    content: @Composable () -> Unit
) {
    Column {
        // --- THIS IS THE FIX ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Use the existing SectionTitle composable for the label
            SectionTitle(text = label, color = labelColor)

            // 2. Keep the value label as it was
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
        }
        // --- END FIX ---

        content() // The Slider or RangeSlider goes here
    }
}


@Composable
private fun RowScope.GiftCounter(
    count: Int,
    iconResId: Int,
    imageLoader: ImageLoader
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = rememberAsyncImagePainter(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(iconResId)
                    .build(),
                imageLoader = imageLoader
            ),
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            count.toString(),
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

// ... inside ProfileScreen.kt, at the bottom of the file

// --- THIS IS THE NEW IMPLEMENTATION ---
private fun saveUserPrefs(
    context: Context,
    scope: CoroutineScope,
    userPrefs: SharedPreferences,
    username: String,
    gender: String,
    age: Int,
    preferredGender: String,
    preferredAgeRange: ClosedFloatingPointRange<Float>,
    romanceRange: ClosedFloatingPointRange<Float>,
    onSuccess: () -> Unit,
    onFailure: () -> Unit
) {
    scope.launch {
        try {
            // 1. Build the request body
            val request = PreferencesRequest(
                userId = userPrefs.getString("user_id", "") ?: "", // Get userId from prefs
                age = age,
                gender = gender,
                preferredGender = preferredGender,
                preferredAgeRange = AgeRange(
                    min = preferredAgeRange.start.toInt(),
                    max = preferredAgeRange.endInclusive.toInt()
                ),
                romanceRange = RomanceRange(
                    min = romanceRange.start.toInt(),
                    max = romanceRange.endInclusive.toInt()
                )
            )

            // 2. Make the network call
            val response = NetworkClient.api.setPreferences(request)

            // 3. Handle the response
            if (response.isSuccessful) {
                // On success, save the preferences locally
                with(userPrefs.edit()) {
                    putString("username", username)
                    putString("gender", gender)
                    putInt("age", age)
                    putString("preferred_gender", preferredGender)
                    putFloat("preferred_age_min", preferredAgeRange.start)
                    putFloat("preferred_age_max", preferredAgeRange.endInclusive)
                    putFloat("romance_min", romanceRange.start)
                    putFloat("romance_max", romanceRange.endInclusive)
                    apply()
                }
                onSuccess() // Trigger success callback (e.g., show toast and navigate)
            } else {
                // On failure, log the error and show a toast
                val errorBody = response.errorBody()?.string() ?: "Unknown error"
                android.util.Log.e("PREFS_SAVE_ERROR", "Failed to save preferences: $errorBody")
                onFailure() // Trigger failure callback (e.g., show error toast)
            }
        } catch (e: Exception) {
            // Handle network exceptions (e.g., no internet)
            android.util.Log.e("PREFS_SAVE_ERROR", "Exception while saving preferences", e)
            onFailure() // Trigger failure callback
        }
    }
}
// --- END ---


@Composable
private fun RatingRow(
    rating: Float,
    ratingCount: Int,
    isDarkTheme: Boolean,
    onClick: () -> Unit
) {
    val starColor =
        if (isDarkTheme) Color(0xFFFFD54F) else Color(0xFFFFB300)

    val textColor =
        if (isDarkTheme) Color.White else Color(0xFF37474F)

    Surface(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // ⭐ Stars
            repeat(5) { index ->
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = if (rating >= index + 1) starColor else starColor.copy(alpha = 0.3f),
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            Text(
                text = String.format("%.1f", rating),
                fontWeight = FontWeight.Bold,
                color = textColor,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.width(4.dp))

            Text(
                text = "($ratingCount)",
                color = textColor.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.width(6.dp))

            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = "View ratings",
                tint = textColor.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/* ---------------------------
   Utility: romance range -> emotion (1..11)
   Mirrors the mapping we discussed earlier.
   --------------------------- */
private fun romanceRangeToEmotion(rangeStart: Float, rangeEnd: Float): Int {
    // Explicit examples preserved: 1-2 -> 1, 9-10 -> 11
    if (rangeEnd <= 2f) return 1
    if (rangeStart >= 9f && rangeEnd == 10f) return 11

    val mid = (rangeStart + rangeEnd) / 2f

    return when {
        mid < 1.95f -> 1
        mid < 2.95f -> 2
        mid < 3.95f -> 3
        mid < 4.95f -> 4
        mid < 5.95f -> 5
        mid < 6.95f -> 6
        mid < 7.95f -> 7
        mid < 8.95f -> 8
        mid < 9.5f -> 9
        mid < 9.9f -> 10
        else -> 11
    }
}
