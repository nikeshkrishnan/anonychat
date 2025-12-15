package com.example.anonychat.ui

import android.content.Context
import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.Build
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
                    var animatable by remember { mutableStateOf<Animatable?>(null) }

                    Image(
                        painter = rememberAsyncImagePainter(
                            ImageRequest.Builder(context)
                                .data("android.resource://${context.packageName}/${R.raw.profilepic_once}")
                                .build(),
                            imageLoader = imageLoader,
                            onSuccess = { animatable = it.result.drawable as? Animatable }
                        ),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable {
                                animatable?.let {
                                    scope.launch {
                                        it.stop()
                                        it.start()
                                    }
                                }
                            },
                        contentScale = ContentScale.Crop
                    )
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
                            saveUserPrefs(
                                userPrefs,
                                username,
                                gender,
                                age,
                                preferredGender,
                                preferredAgeRange,
                                romanceRange
                            )
                            navController.popBackStack()
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

private fun saveUserPrefs(
    prefs: android.content.SharedPreferences,
    username: String,
    gender: String,
    age: Int,
    preferredGender: String,
    preferredAgeRange: ClosedFloatingPointRange<Float>,
    romanceRange: ClosedFloatingPointRange<Float>
) {
    with(prefs.edit()) {
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
}

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
