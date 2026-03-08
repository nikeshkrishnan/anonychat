package com.example.anonychat.ui

import android.content.Context
import android.graphics.drawable.Animatable
import android.net.Uri
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat.getSystemService
import androidx.compose.foundation.clickable
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.anonychat.network.NetworkClient
import com.example.anonychat.network.Rating
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatingsScreen(
    navController: NavController,
    targetUserEmail: String? = null,  // If null, show current user's ratings
    targetUsername: String? = null,
    targetGender: String? = null,
    targetRomanceMin: Float? = null,
    targetRomanceMax: Float? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val themePrefs = remember { context.getSharedPreferences("anonychat_theme", Context.MODE_PRIVATE) }
    val userPrefs = remember { context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE) }
    val isDarkTheme by remember { mutableStateOf(themePrefs.getBoolean("is_dark_theme", false)) }
    val textColor = if (isDarkTheme) Color.White else Color.Black
    
    // Hide keyboard when screen loads using InputMethodManager
    DisposableEffect(Unit) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(
            (context as? android.app.Activity)?.currentFocus?.windowToken,
            0
        )
        onDispose { }
    }
    val panelColor = if (isDarkTheme) Color.Black.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.65f)
    
    // Determine if we're viewing another user's ratings or our own
    val isViewingOtherUser = targetUserEmail != null
    val displayEmail = targetUserEmail ?: userPrefs.getString("user_email", null)
    val displayUsername = targetUsername ?: userPrefs.getString("username", "You")
    
    // Shared animated ImageLoader for all rating cards
    val animatedImageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28)
                    add(ImageDecoderDecoder.Factory())
                else
                    add(GifDecoder.Factory())
            }
            .build()
    }
    
    var ratings by remember { mutableStateOf<List<Rating>?>(null) }
    var averageRating by remember { mutableStateOf(0f) }
    var ratingCount by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var sortOption by remember { mutableStateOf("Most relevant") }
    var showSortMenu by remember { mutableStateOf(false) }
    
    // Filter states
    var genderFilter by remember { mutableStateOf("All Genders") }
    var showGenderMenu by remember { mutableStateOf(false) }
    var romanceFilter by remember { mutableStateOf("All Ranges") }
    var showRomanceMenu by remember { mutableStateOf(false) }
    
    // Write review dialog states
    var showWriteReviewDialog by remember { mutableStateOf(false) }
    var reviewRating by remember { mutableIntStateOf(0) }
    var reviewComment by remember { mutableStateOf("") }
    var isLoadingExistingReview by remember { mutableStateOf(false) }
    var isEditMode by remember { mutableStateOf(false) }
    var isSubmittingReview by remember { mutableStateOf(false) }
    
    // Get current user's romance range for "Match my romance range" filter
    val myRomanceMin = remember { userPrefs.getFloat("romance_min", 1f) }
    val myRomanceMax = remember { userPrefs.getFloat("romance_max", 5f) }
    
    // Filtered and sorted ratings
    val filteredAndSortedRatings = remember(ratings, sortOption, genderFilter, romanceFilter, myRomanceMin, myRomanceMax) {
        ratings?.let { list ->
            // First apply filters
            var filtered = list
            
            // Gender filter
            if (genderFilter != "All Genders") {
                filtered = filtered.filter { it.fromgender?.lowercase() == genderFilter.lowercase() }
            }
            
            // Romance range filter - EXACT match only
            if (romanceFilter == "Match my romance range") {
                filtered = filtered.filter { rating ->
                    rating.romanceRange?.let { range ->
                        // Only show ratings with EXACT same romance range
                        range.min == myRomanceMin.toInt() && range.max == myRomanceMax.toInt()
                    } ?: false
                }
            }
            
            // Then apply sorting
            when (sortOption) {
                "Newest" -> filtered.sortedByDescending {
                    try {
                        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).apply {
                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }.parse(it.createdAt)?.time ?: 0L
                    } catch (e: Exception) {
                        0L
                    }
                }
                "Highest" -> filtered.sortedByDescending { it.rating }
                "Lowest" -> filtered.sortedBy { it.rating }
                else -> filtered // "Most relevant" - default order from server
            }
        }
    }
    
    // Recalculate average rating and count based on filtered results
    val displayAverageRating = remember(filteredAndSortedRatings) {
        filteredAndSortedRatings?.let { filtered ->
            if (filtered.isEmpty()) {
                0f
            } else {
                filtered.map { it.rating }.average().toFloat()
            }
        } ?: averageRating
    }
    
    val displayRatingCount = remember(filteredAndSortedRatings) {
        filteredAndSortedRatings?.size ?: ratingCount
    }
    
    // Fetch ratings on load
    LaunchedEffect(displayEmail) {
        if (displayEmail != null) {
            android.util.Log.d("RatingsScreen", "Fetching ratings for $displayEmail")
            
            try {
                // Fetch average rating
                val avgResponse = NetworkClient.api.getAverageRating(displayEmail)
                if (avgResponse.isSuccessful && avgResponse.body() != null) {
                    val avgData = avgResponse.body()!!
                    avgData.avgRating?.let { averageRating = it }
                    avgData.count?.let { ratingCount = it }
                    android.util.Log.d("RatingsScreen", "Average: $averageRating, Count: $ratingCount")
                }
                
                // Fetch all ratings
                val ratingsResponse = NetworkClient.api.getRatings(displayEmail)
                if (ratingsResponse.isSuccessful && ratingsResponse.body() != null) {
                    ratings = ratingsResponse.body()!!
                    android.util.Log.d("RatingsScreen", "Fetched ${ratings?.size ?: 0} ratings: $ratings")
                } else {
                    android.util.Log.e("RatingsScreen", "Failed to fetch ratings: ${ratingsResponse.code()}, ${ratingsResponse.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                android.util.Log.e("RatingsScreen", "Error fetching ratings: ${e.message}")
                ratings = emptyList() // Set to empty list on error
            } finally {
                isLoading = false
            }
        } else {
            android.util.Log.w("RatingsScreen", "User email not found")
            ratings = emptyList()
            isLoading = false
        }
    }

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
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        if (isViewingOtherUser && targetUsername != null) {
                            // Show target user's profile info
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Avatar
                                val emotion = remember(targetRomanceMin, targetRomanceMax) {
                                    if (targetRomanceMin != null && targetRomanceMax != null) {
                                        romanceRangeToEmotion(targetRomanceMin, targetRomanceMax)
                                    } else 3
                                }
                                val avatarResName = if (targetGender == "female") "female_exp$emotion" else "male_exp$emotion"
                                val avatarResId = remember(avatarResName) {
                                    context.resources.getIdentifier(avatarResName, "raw", context.packageName)
                                }
                                val avatarUri = remember(avatarResId) {
                                    if (avatarResId != 0)
                                        Uri.parse("android.resource://${context.packageName}/$avatarResId")
                                    else
                                        Uri.parse("android.resource://${context.packageName}/${R.raw.male_exp1}")
                                }
                                val avatarBgColor = if (isDarkTheme) Color(0xFF3A4552) else Color(0xFF87CEEB)
                                
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(avatarBgColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = rememberAsyncImagePainter(
                                            model = ImageRequest.Builder(context)
                                                .data(avatarUri)
                                                .build(),
                                            imageLoader = animatedImageLoader
                                        ),
                                        contentDescription = "Profile",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                
                                Text("$targetUsername's Ratings")
                            }
                        } else {
                            Text("Your Ratings")
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = textColor)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = textColor
                    )
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Overall Rating Summary (Always visible at top) - Reduced padding
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Overall Rating", style = MaterialTheme.typography.headlineMedium, color = textColor)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (displayAverageRating > 0) String.format("%.1f", displayAverageRating) else "N/A",
                            style = if (displayAverageRating > 0) MaterialTheme.typography.displayLarge else MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    Text(
                        "Based on $displayRatingCount review${if (displayRatingCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor.copy(alpha = 0.7f)
                    )
                    
                    // Write a review button (only show when viewing another user's ratings)
                    if (isViewingOtherUser) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                // Fetch existing rating before showing dialog
                                scope.launch {
                                    isLoadingExistingReview = true
                                    try {
                                        val myEmail = userPrefs.getString("user_email", null)
                                        if (myEmail != null && displayEmail != null) {
                                            android.util.Log.d("RatingsScreen", "Fetching existing rating from $myEmail to $displayEmail")
                                            val response = NetworkClient.api.getSpecificRating(myEmail, displayEmail)
                                            
                                            if (response.isSuccessful && response.body() != null) {
                                                // Found existing rating - pre-fill dialog (edit mode)
                                                val existingRating = response.body()!!
                                                reviewRating = existingRating.rating
                                                reviewComment = existingRating.comment ?: ""
                                                isEditMode = true
                                                android.util.Log.d("RatingsScreen", "Found existing rating: ${existingRating.rating} stars")
                                            } else if (response.code() == 404) {
                                                // No existing rating - show empty dialog (create mode)
                                                reviewRating = 0
                                                reviewComment = ""
                                                isEditMode = false
                                                android.util.Log.d("RatingsScreen", "No existing rating found (404)")
                                            } else {
                                                android.util.Log.e("RatingsScreen", "Error fetching rating: ${response.code()}")
                                                // Show empty dialog on error
                                                reviewRating = 0
                                                reviewComment = ""
                                                isEditMode = false
                                            }
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("RatingsScreen", "Exception fetching existing rating: ${e.message}")
                                        // Show empty dialog on exception
                                        reviewRating = 0
                                        reviewComment = ""
                                        isEditMode = false
                                    } finally {
                                        isLoadingExistingReview = false
                                        showWriteReviewDialog = true
                                    }
                                }
                            },
                            enabled = !isLoadingExistingReview,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDarkTheme) Color(0xFF121821) else Color(0xFF64B5F6),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isLoadingExistingReview) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Write a review")
                            }
                        }
                    }
                }
                
                // Glassy Card Container (matching ChatScreen style) - Maximum size
                val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Surface(
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 0.dp)
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(32.dp),
                    color = if (isDarkTheme) Color(0x0DFFFFFF) else Color(0x4DFFFFFF),
                    border = if (isDarkTheme) BorderStroke(1.dp, Color(0x08FFFFFF)) else null
                ) {
                    if (isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = textColor)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = navigationBarPadding + 16.dp),
                            contentPadding = PaddingValues(bottom = 0.dp)
                        ) {
                            // Show section header based on whether ratings exist
                            item {
                                android.util.Log.d("RatingsScreen", "Rendering header. Ratings: ${ratings?.size}, Count: $ratingCount")
                                when {
                                    ratings == null -> {
                                        // Still loading or error
                                        Text(
                                            "Loading ratings...",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = textColor.copy(alpha = 0.6f),
                                            modifier = Modifier.padding(vertical = 32.dp)
                                        )
                                    }
                                    ratings!!.isEmpty() && ratingCount == 0 -> {
                                        // No ratings exist
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 32.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                "No ratings yet",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = textColor.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                    ratings!!.isNotEmpty() -> {
                                        // Filter and Sort controls when ratings exist
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 16.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            // Sort dropdown
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                // Sort
                                                Box {
                                                    Surface(
                                                        onClick = { showSortMenu = true },
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = if (isDarkTheme) Color(0xFF121821) else Color(0xFFF2F6FB)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                        ) {
                                                            Text(
                                                                text = sortOption,
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                fontWeight = FontWeight.Medium,
                                                                color = textColor
                                                            )
                                                            Icon(
                                                                imageVector = Icons.Default.ArrowDropDown,
                                                                contentDescription = "Sort",
                                                                tint = textColor,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }
                                                    }
                                                    
                                                    DropdownMenu(
                                                        expanded = showSortMenu,
                                                        onDismissRequest = { showSortMenu = false },
                                                        modifier = Modifier.background(
                                                            if (isDarkTheme) Color(0xFF2A2F3A) else Color.White
                                                        )
                                                    ) {
                                                        listOf("Most relevant", "Newest", "Highest", "Lowest").forEach { option ->
                                                            DropdownMenuItem(
                                                                text = {
                                                                    Text(
                                                                        text = option,
                                                                        color = textColor,
                                                                        fontWeight = if (option == sortOption) FontWeight.Bold else FontWeight.Normal
                                                                    )
                                                                },
                                                                onClick = {
                                                                    sortOption = option
                                                                    showSortMenu = false
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                                
                                                // Gender Filter
                                                Box {
                                                    Surface(
                                                        onClick = { showGenderMenu = true },
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = if (isDarkTheme) Color(0xFF121821) else Color(0xFFF2F6FB)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                        ) {
                                                            Text(
                                                                text = genderFilter,
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                fontWeight = FontWeight.Medium,
                                                                color = textColor
                                                            )
                                                            Icon(
                                                                imageVector = Icons.Default.ArrowDropDown,
                                                                contentDescription = "Gender Filter",
                                                                tint = textColor,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }
                                                    }
                                                    
                                                    DropdownMenu(
                                                        expanded = showGenderMenu,
                                                        onDismissRequest = { showGenderMenu = false },
                                                        modifier = Modifier.background(
                                                            if (isDarkTheme) Color(0xFF2A2F3A) else Color.White
                                                        )
                                                    ) {
                                                        listOf("All Genders", "Male", "Female").forEach { option ->
                                                            DropdownMenuItem(
                                                                text = {
                                                                    Text(
                                                                        text = option,
                                                                        color = textColor,
                                                                        fontWeight = if (option == genderFilter) FontWeight.Bold else FontWeight.Normal
                                                                    )
                                                                },
                                                                onClick = {
                                                                    genderFilter = option
                                                                    showGenderMenu = false
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            
                                            // Romance Range Filter
                                            Box {
                                                Surface(
                                                    onClick = { showRomanceMenu = true },
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = if (isDarkTheme) Color(0xFF121821) else Color(0xFFF2F6FB)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Text(
                                                            text = romanceFilter,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.Medium,
                                                            color = textColor
                                                        )
                                                        Icon(
                                                            imageVector = Icons.Default.ArrowDropDown,
                                                            contentDescription = "Romance Filter",
                                                            tint = textColor,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }
                                                
                                                DropdownMenu(
                                                    expanded = showRomanceMenu,
                                                    onDismissRequest = { showRomanceMenu = false },
                                                    modifier = Modifier.background(
                                                        if (isDarkTheme) Color(0xFF2A2F3A) else Color.White
                                                    )
                                                ) {
                                                    listOf("All Ranges", "Match my romance range").forEach { option ->
                                                        DropdownMenuItem(
                                                            text = {
                                                                Text(
                                                                    text = option,
                                                                    color = textColor,
                                                                    fontWeight = if (option == romanceFilter) FontWeight.Bold else FontWeight.Normal
                                                                )
                                                            },
                                                            onClick = {
                                                                romanceFilter = option
                                                                showRomanceMenu = false
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // Display rating cards
                            if (filteredAndSortedRatings != null && filteredAndSortedRatings!!.isNotEmpty()) {
                                android.util.Log.d("RatingsScreen", "Rendering ${filteredAndSortedRatings!!.size} rating cards")
                                items(
                                    items = filteredAndSortedRatings!!,
                                    key = { rating -> "${rating.createdAt}_${rating.fromusername}" }
                                ) { rating ->
                                    RatingCard(
                                        rating = rating,
                                        isDarkTheme = isDarkTheme,
                                        textColor = textColor,
                                        imageLoader = animatedImageLoader
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Write Review Dialog
        if (showWriteReviewDialog) {
            AlertDialog(
                onDismissRequest = {
                    showWriteReviewDialog = false
                    reviewRating = 0
                    reviewComment = ""
                },
                title = {
                    Text(
                        if (isEditMode) "Edit Review" else "Write a Review",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isDarkTheme) Color.White else Color.Black
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Star Rating Bar
                        Text(
                            "Rating",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (isDarkTheme) Color.White else Color.Black
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            repeat(5) { index ->
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "Star ${index + 1}",
                                    tint = if (reviewRating >= index + 1) Color(0xFFFFD700) else Color.Gray,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clickable { reviewRating = index + 1 }
                                )
                            }
                        }
                        
                        // Comment Text Field
                        Text(
                            "Comment (optional)",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (isDarkTheme) Color.White else Color.Black
                        )
                        OutlinedTextField(
                            value = reviewComment,
                            onValueChange = { reviewComment = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            placeholder = { Text("Share your experience...") },
                            maxLines = 5,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = if (isDarkTheme) Color.White else Color.Black,
                                unfocusedTextColor = if (isDarkTheme) Color.White else Color.Black,
                                focusedBorderColor = if (isDarkTheme) Color(0xFF64B5F6) else Color(0xFF64B5F6),
                                unfocusedBorderColor = if (isDarkTheme) Color.Gray else Color.Gray
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                isSubmittingReview = true
                                showWriteReviewDialog = false
                                try {
                                    android.util.Log.d("RatingsScreen", "Submitting review: rating=$reviewRating, comment=$reviewComment")
                                    
                                    // Get user's romance range and random flag from SharedPreferences
                                    val romanceMin = userPrefs.getFloat("romance_min", 1f).toInt()
                                    val romanceMax = userPrefs.getFloat("romance_max", 5f).toInt()
                                    val random = userPrefs.getBoolean("random_match", true)
                                    
                                    // Build the request
                                    val request = com.example.anonychat.network.SubmitRatingRequest(
                                        rating = reviewRating,
                                        comment = reviewComment.ifBlank { null },
                                        romanceRange = com.example.anonychat.network.RomanceRange(
                                            min = romanceMin,
                                            max = romanceMax
                                        ),
                                        random = random
                                    )
                                    
                                    // Submit to backend
                                    if (displayEmail != null) {
                                        val response = NetworkClient.api.submitRating(displayEmail, request)
                                        
                                        if (response.isSuccessful) {
                                            android.util.Log.d("RatingsScreen", "Rating submitted successfully")
//                                            android.widget.Toast.makeText(
//                                                context,
//                                                if (isEditMode) "Review updated!" else "Review posted!",
//                                                android.widget.Toast.LENGTH_SHORT
//                                            ).show()
//
                                            // Refresh ratings list
                                            isLoading = true
                                            try {
                                                val ratingsResponse = NetworkClient.api.getRatings(displayEmail)
                                                val avgResponse = NetworkClient.api.getAverageRating(displayEmail)
                                                
                                                if (ratingsResponse.isSuccessful && avgResponse.isSuccessful) {
                                                    ratings = ratingsResponse.body()
                                                    avgResponse.body()?.let {
                                                        averageRating = it.avgRating ?: 0f
                                                        ratingCount = it.count ?: 0
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                android.util.Log.e("RatingsScreen", "Error refreshing ratings: ${e.message}")
                                            } finally {
                                                isLoading = false
                                            }
                                        } else {
                                            android.util.Log.e("RatingsScreen", "Failed to submit rating: ${response.code()}")
                                            android.widget.Toast.makeText(
                                                context,
                                                "Failed to submit review. Please try again.",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("RatingsScreen", "Exception submitting rating: ${e.message}")
                                    android.widget.Toast.makeText(
                                        context,
                                        "Error: ${e.message}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } finally {
                                    isSubmittingReview = false
                                    reviewRating = 0
                                    reviewComment = ""
                                }
                            }
                        },
                        enabled = reviewRating > 0 && reviewComment.isNotBlank(), // Enable only if rating is selected AND comment has text
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDarkTheme) Color(0xFF64B5F6) else Color(0xFF64B5F6), // Blue in both themes
                            contentColor = Color.White,
                            disabledContainerColor = if (isDarkTheme) Color(0xFF4A5562) else Color.Gray.copy(alpha = 0.3f),
                            disabledContentColor = Color.White.copy(alpha = 0.5f)
                        )
                    ) {
                        Text("Post")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showWriteReviewDialog = false
                            reviewRating = 0
                            reviewComment = ""
                        }
                    ) {
                        Text(
                            "Cancel",
                            color = if (isDarkTheme) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.7f)
                        )
                    }
                },
                containerColor = if (isDarkTheme) Color(0xFF2A2F3A) else Color.White
            )
        }
        
        LoadingHeartOverlay(isLoading = isSubmittingReview)
    }
}

@Composable
private fun RatingCard(
    rating: Rating,
    isDarkTheme: Boolean,
    textColor: Color,
    imageLoader: ImageLoader
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val starColor = if (isDarkTheme) Color(0xFFFFD54F) else Color(0xFFFFB300)
    val subTextColor = if (isDarkTheme) Color(0xFFB0BEC5) else Color.Gray
    val cardColor = if (isDarkTheme) Color(0xFF121821) else Color(0xF2FFFFFF)
    val avatarBgColor = if (isDarkTheme) Color(0xFF3A4552) else Color(0xFF87CEEB)
    val borderColor = if (isDarkTheme) Color(0xFF4A5562) else Color.White.copy(alpha = 0.6f)
    
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault()) }
    val isoFormat = remember { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }}
    
    // Parse the ISO date string
    val formattedDate = remember(rating.createdAt) {
        try {
            val date = isoFormat.parse(rating.createdAt)
            if (date != null) dateFormat.format(date) else rating.createdAt
        } catch (e: Exception) {
            rating.createdAt
        }
    }
    
    // Avatar logic with animated WebP support
    val emotion = remember(rating.romanceRange) {
        if (rating.romanceRange != null) {
            romanceRangeToEmotion(rating.romanceRange.min.toFloat(), rating.romanceRange.max.toFloat())
        } else {
            3 // default emotion
        }
    }
    val avatarResName = remember(rating.fromgender, emotion) {
        if (rating.fromgender == "female") "female_exp$emotion" else "male_exp$emotion"
    }
    val avatarResId = remember(avatarResName) {
        context.resources.getIdentifier(avatarResName, "raw", context.packageName)
    }
    val avatarUri = remember(avatarResId) {
        if (avatarResId != 0)
            "android.resource://${context.packageName}/$avatarResId"
        else
            "android.resource://${context.packageName}/${R.raw.male_exp1}"
    }
    
    // Animation state
    var animatable by remember(rating.createdAt, rating.fromusername) {
        mutableStateOf<Animatable?>(null)
    }
    var isPlaying by remember(rating.createdAt, rating.fromusername) {
        mutableStateOf(false)
    }
    var playToken by remember(rating.createdAt, rating.fromusername) {
        mutableIntStateOf(0)
    }
    
    val animationDurations = remember {
        mapOf(
            "female_exp4" to 8500L, "female_exp5" to 5020L,
            "male_exp4" to 8380L, "male_exp5" to 11600L,
            "male_exp1" to 5400L, "female_exp1" to 2740L,
            "male_exp2" to 2020L, "male_exp3" to 2380L,
            "female_exp2" to 1360L, "female_exp3" to 1720L
        )
    }
    val animDurationMs = animationDurations[avatarResName] ?: 3000L
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = cardColor,
        border = if (isDarkTheme) null else BorderStroke(1.dp, borderColor),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Profile Avatar - isolated in its own Box with graphicsLayer for stability
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer() // Isolate from parent recomposition
                    .clip(CircleShape)
                    .background(avatarBgColor)
                    .clickable {
                        if (isPlaying) return@clickable
                        animatable?.let { anim ->
                            scope.launch {
                                try {
                                    isPlaying = true
                                    try { anim.stop() } catch (_: Exception) {}
                                    try { anim.start() } catch (_: Exception) {}
                                    delay(animDurationMs)
                                    try { anim.stop() } catch (_: Exception) {}
                                    playToken++
                                } finally {
                                    isPlaying = false
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Crossfade(
                    targetState = avatarUri,
                    animationSpec = tween(durationMillis = 1500)
                ) { uri ->
                    Image(
                        painter = rememberAsyncImagePainter(
                            model = ImageRequest.Builder(context)
                                .data(uri)
                                .setParameter("playToken", playToken)
                                .build(),
                            imageLoader = imageLoader,
                            onSuccess = { result ->
                                val drawable = result.result.drawable
                                if (drawable is Animatable) {
                                    animatable = drawable
                                    scope.launch {
                                        try { drawable.stop() } catch (_: Exception) {}
                                    }
                                } else {
                                    animatable = null
                                }
                            }
                        ),
                        contentDescription = "Profile",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Content Column
            Column(modifier = Modifier.weight(1f)) {
                // Username
                Text(
                    text = rating.fromusername ?: "Anonymous",
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    fontSize = 15.sp,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                
                // Gender and Romance Range Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = rating.fromgender?.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                        } ?: "Unknown",
                        color = subTextColor,
                        fontSize = 13.sp
                    )
                    
                    rating.romanceRange?.let {
                        Text(
                            text = "•",
                            color = subTextColor.copy(alpha = 0.5f),
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Romance Range ${it.min}-${it.max}",
                            color = subTextColor,
                            fontSize = 13.sp
                        )
                    }
                }
                
                // Stars Row (5-star bar)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    repeat(5) { index ->
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = if (rating.rating >= index + 1) starColor else starColor.copy(alpha = 0.3f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                
                // Comment
                if (!rating.comment.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "\"${rating.comment}\"",
                        color = textColor,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
                
                // Date only (romance range moved to top)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = formattedDate,
                    color = subTextColor,
                    fontSize = 11.sp
                )
            }
        }
    }
}
