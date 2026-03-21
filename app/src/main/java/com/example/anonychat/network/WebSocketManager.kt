package com.example.anonychat.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.anonychat.AppVisibility
import com.example.anonychat.repository.UserRepository
import com.example.anonychat.ui.ChatMessage
import com.example.anonychat.utils.ActiveChatTracker
import com.example.anonychat.utils.NotificationHelper
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okio.BufferedSink
import okio.sink
import okio.source
import org.json.JSONObject

/* ---------------------------------------------------
   ROOM – ENTITY
   (added state and lastSentAt for ack-tracking)
--------------------------------------------------- */
@Entity(tableName = "pending_messages")
data class PendingMessageEntity(
        @PrimaryKey val messageId: String,
        val payload: String,
        val retries: Int,
        val state: String, // QUEUED | IN_FLIGHT | DELIVERED | FAILED
        val lastSentAt: Long, // epoch millis when sent (for IN_FLIGHT)
        val timestamp:

        Long
)

/* ---------------------------------------------------
   ROOM – DAO
--------------------------------------------------- */
@Dao
interface PendingMessageDao {

    @Query("SELECT * FROM pending_messages ORDER BY timestamp ASC")
    suspend fun getAll(): List<PendingMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(msg: PendingMessageEntity)

    @Query("DELETE FROM pending_messages WHERE messageId = :id") suspend fun delete(id: String)
    
    @Query("DELETE FROM pending_messages") suspend fun deleteAll()
}

@Entity(tableName = "chat_history")
data class ChatHistoryEntity(
        @PrimaryKey val id: String,
        val fromEmail: String,
        val toEmail: String,
        val text: String,
        val audioUri: String?,
        val amplitudes: String, // Comma separated
        val mediaUri: String?,
        val mediaType: String?,
        val mediaId: String?,
        val isDownloading: Boolean,
        val timestamp: Long,
        val status: String,
        val sequence: Long = 0 // Auto-increment field for ordering
)

@Dao
interface ChatHistoryDao {
    @Query(
            "SELECT * FROM chat_history WHERE (fromEmail = :user1 AND toEmail = :user2) OR (fromEmail = :user2 AND toEmail = :user1) ORDER BY sequence ASC"
    )
    fun getConversation(
            user1: String,
            user2: String
    ): kotlinx.coroutines.flow.Flow<List<ChatHistoryEntity>>

    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM chat_history")
    suspend fun getNextSequence(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(msg: ChatHistoryEntity)

    @Query("UPDATE chat_history SET status = :status WHERE id = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    @Query(
            "UPDATE chat_history SET mediaUri = :uri, isDownloading = :isDownloading WHERE id = :messageId"
    )
    suspend fun updateMediaDownload(messageId: String, uri: String, isDownloading: Boolean)

    @Query(
            "UPDATE chat_history SET audioUri = :uri, isDownloading = :isDownloading WHERE id = :messageId"
    )
    suspend fun updateAudioDownload(messageId: String, uri: String, isDownloading: Boolean)

    @Query(
            "UPDATE chat_history SET status = :status WHERE id = :messageId AND status != :skippedStatus"
    )
    suspend fun updateStatusIfNot(messageId: String, status: String, skippedStatus: String)

    @Query("SELECT status FROM chat_history WHERE id = :messageId")
    suspend fun getStatus(messageId: String): String?
    
    @Query("DELETE FROM chat_history WHERE (fromEmail = :user1 AND toEmail = :user2) OR (fromEmail = :user2 AND toEmail = :user1)")
    suspend fun deleteChatHistory(user1: String, user2: String)
    
    @Query("UPDATE chat_history SET fromEmail = :newEmail WHERE fromEmail = :oldEmail")
    suspend fun updateFromEmail(oldEmail: String, newEmail: String)
    
    @Query("UPDATE chat_history SET toEmail = :newEmail WHERE toEmail = :oldEmail")
    suspend fun updateToEmail(oldEmail: String, newEmail: String)
    
    @Query("DELETE FROM chat_history") suspend fun deleteAll()
}

class Converters {
    @TypeConverter
    fun fromFloatList(value: List<Float>): String {
        return value.joinToString(",")
    }

    @TypeConverter
    fun toFloatList(value: String): List<Float> {
        if (value.isEmpty()) return emptyList()
        return value.split(",").mapNotNull { it.toFloatOrNull() }
    }
}

/* ---------------------------------------------------
   ROOM – DATABASE (version bumped to 2)
   Includes migration 1 -> 2 to add new columns
--------------------------------------------------- */
@Database(
        entities = [PendingMessageEntity::class, ChatHistoryEntity::class],
        version = 4,
        exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingMessageDao(): PendingMessageDao
    abstract fun chatHistoryDao(): ChatHistoryDao
}

/* Migration from v1 -> v2: add state and lastSentAt columns with defaults */
val MIGRATION_1_2 =
        object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // add columns with defaults to avoid losing preexisting rows
                database.execSQL(
                        "ALTER TABLE pending_messages ADD COLUMN state TEXT NOT NULL DEFAULT 'QUEUED'"
                )
                database.execSQL(
                        "ALTER TABLE pending_messages ADD COLUMN lastSentAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

/* ---------------------------------------------------
   EVENTS
--------------------------------------------------- */
sealed class WebSocketEvent {
    data class NewMessage(val message: ChatMessage) : WebSocketEvent()
    data class DeliveryAck(val messageId: String) : WebSocketEvent()
    data class MessageReadAck(val messageId: String) : WebSocketEvent()
    data class DeliveryFailed(val messageId: String, val reason: String) : WebSocketEvent()
    data class MessageSentAck(val messageId: String) : WebSocketEvent()
    data class PeerPresence(val from: String, val status: String, val lastSeen: Long) :
            WebSocketEvent()
    data class ChatOpen(val from: String) : WebSocketEvent()
    data class ChatClose(val from: String) : WebSocketEvent()
    data class SparkLeft(val from: String, val senderUserId: String) : WebSocketEvent()
    data class SparkRight(val from: String, val senderUserId: String) : WebSocketEvent()
    data class RoseGifted(val from: String) : WebSocketEvent()
    data class RoseTakenBack(val from: String) : WebSocketEvent()
    data class SparkTrigger(val from: String) : WebSocketEvent()
    data class SparkStart(val from: String) : WebSocketEvent()
    data class SparkReceivedAck(val from: String) : WebSocketEvent()
    data class WarningNotification(val message: String) : WebSocketEvent()
    data class SuspensionNotification(val message: String, val remainingMinutes: Int) : WebSocketEvent()
    data class BanNotification(val message: String) : WebSocketEvent()
    
    // Matchmaking events
    data class MatchFound(val match: com.example.anonychat.network.MatchResponse) : WebSocketEvent()
    data class MatchError(val error: String, val rawMessage: String? = null, val rawError: String? = null) : WebSocketEvent()
    data class SkipUserSuccess(val message: String) : WebSocketEvent()
    data class SkipUserError(val error: String) : WebSocketEvent()
    data class AcceptUserSuccess(val message: String) : WebSocketEvent()
    data class AcceptUserError(val error: String) : WebSocketEvent()
    data class DeleteAllMatchesSuccess(val message: String, val deleted: Boolean) : WebSocketEvent()
    data class DeleteAllMatchesError(val error: String) : WebSocketEvent()
    
    // Preference events
    data class PreferencesData(val preferences: com.example.anonychat.network.GetPreferencesResponse, val userEmail: String? = null) : WebSocketEvent()
    data class PreferencesError(val error: String, val email: String? = null) : WebSocketEvent()
    data class UpdatePreferencesSuccess(val message: String) : WebSocketEvent()
    data class UpdatePreferencesError(val error: String) : WebSocketEvent()
    data class TogglePickBestSuccess(val enabled: Boolean) : WebSocketEvent()
    data class TogglePickBestError(val error: String) : WebSocketEvent()
    
    // Rating events
    data class AddRatingSuccess(val message: String) : WebSocketEvent()
    data class AddRatingError(val error: String) : WebSocketEvent()
    data class AverageRatingData(val avgRating: Float?, val count: Int?, val userEmail: String?) : WebSocketEvent()
     data class AverageRatingError(val error: String, val userEmail: String? = null) : WebSocketEvent()
    data class RatingsData(val ratings: List<com.example.anonychat.network.Rating>) : WebSocketEvent()
    data class RatingsError(val error: String) : WebSocketEvent()
    data class SpecificRatingData(val rating: com.example.anonychat.network.Rating?) : WebSocketEvent()
    data class SpecificRatingError(val error: String) : WebSocketEvent()
    
    // Username update event for chat list
    data class UsernameUpdated(val email: String, val username: String) : WebSocketEvent()
    
    // Token expiration event - triggers redirect to login
    object TokenExpiredNeedLogin : WebSocketEvent()
    
    data class AppUpdateRequired(val message: String) : WebSocketEvent()
    
    // Blocked users events
    data class BlockedListData(val blockedUsers: List<BlockedUser>) : WebSocketEvent()
    data class BlockedListError(val error: String) : WebSocketEvent()
    data class UnblockUserSuccess(val message: String, val unblockedEmail: String) : WebSocketEvent()
    data class UnblockUserError(val error: String) : WebSocketEvent()
}

data class BlockedUser(
    val email: String,
    val username: String,
    val profilePicture: String?,
    val gender: String = "male",
    val romanceMin: Float = 1f,
    val romanceMax: Float = 5f
)

/* ---------------------------------------------------
   WEBSOCKET MANAGER
--------------------------------------------------- */
object WebSocketManager {
    
    /* ---------------------------------------------------
       INTERNAL MODEL
    --------------------------------------------------- */
    private enum class MessageState {
        QUEUED,
        IN_FLIGHT,
        DELIVERED,
        FAILED
    }

    private data class PendingMessage(
            val messageId: String,
            val payload: String,
            var retries: Int,
            var state: MessageState,
            var lastSentAt: Long,
            val timestamp: Long
    )

    private enum class WsState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    private enum class ReadyState {
        NOT_READY,
        WAITING_ACK,
        READY
    }

    private val connectMutex = Mutex()

    @Volatile private var wsState: WsState = WsState.DISCONNECTED
    
    // Lock to prevent connections during email updates
    @Volatile private var isUpdatingEmail = false

    @Volatile private var readyState: ReadyState = ReadyState.NOT_READY

    // Media endpoints
    //    private const val MEDIA_UPLOAD = "${NetworkConfig.BASE_URL}media/upload"
    //    private const val MEDIA_DOWNLOAD_ACK = "${NetworkConfig.BASE_URL}media/download/ack"

    private lateinit var appContext: Context

    // Track which users currently have their chat windows open with us
    private val activeChatSessions = mutableSetOf<String>()
    
    // Track pending rating requests to match responses with requests
    private val pendingRatingRequests = mutableListOf<String>()
    
    fun isUserInChatWithUs(userEmail: String): Boolean {
        return activeChatSessions.contains(userEmail)
    }
    
    /**
     * Check if we should show a notification for a message from a specific user
     */
    private fun shouldShowNotification(fromEmail: String): Boolean {
        // Don't show notification if app is in foreground AND user is viewing this chat
        val isInForeground = AppVisibility.isForeground
        val isViewingThisChat = ActiveChatTracker.isChatActive(fromEmail)
        
        val shouldShow = !isInForeground || !isViewingThisChat
        
        Log.e("WebSocketManager", "🔔 shouldShowNotification for $fromEmail: $shouldShow (foreground=$isInForeground, viewingChat=$isViewingThisChat)")
        
        return shouldShow
    }
    
    /**
     * Trigger notification for a new message
     */
    private suspend fun triggerNotification(message: ChatMessage) {
        try {
            // Fetch sender's username and preferences via WebSocket
            Log.i("WebSocketManager", "Sending get_preferences request for: ${message.from}")
            sendGetPreferences("", message.from) // Empty token since we're already connected
            
            // Wait for preferences response with timeout
            val prefsData = withTimeoutOrNull(5000L) {
                events.first { event: WebSocketEvent ->
                    event is WebSocketEvent.PreferencesData && event.userEmail == message.from
                } as? WebSocketEvent.PreferencesData
            }
            
            if (prefsData != null) {
                val prefs = prefsData.preferences
                // Use email as fallback for username display (not for API calls)
                val username = prefs.username ?: message.from.substringBefore("@")
                
                // Create Preferences object from WebSocket response
                val senderPreferences = com.example.anonychat.model.Preferences(
                    romanceMin = prefs.romanceRange?.min?.toFloat() ?: 1f,
                    romanceMax = prefs.romanceRange?.max?.toFloat() ?: 5f,
                    gender = prefs.gender ?: "male",
                    isOnline = prefs.isOnline ?: false,
                    lastSeen = prefs.lastOnline ?: 0L
                )
                
                // Get current user email from WebSocket request
                val localEmail = webSocket?.request()?.url?.queryParameter("gmail") ?: ""
                
                NotificationHelper.showMessageNotification(
                    appContext,
                    message,
                    username,
                    senderPreferences,
                    localEmail
                )
                
                // Emit username update event so ChatScreen can update the conversation list
                _events.tryEmit(WebSocketEvent.UsernameUpdated(message.from, username))
                
                Log.d("WebSocketManager", "Notification triggered for message from $username")
            } else {
                Log.e("WebSocketManager", "Failed to fetch user preferences via WebSocket (timeout)")
                // Fallback: show notification with email as username
                val localEmail = webSocket?.request()?.url?.queryParameter("gmail") ?: ""
                val fallbackPrefs = com.example.anonychat.model.Preferences(
                    romanceMin = 1f,
                    romanceMax = 5f,
                    gender = "male",
                    isOnline = false,
                    lastSeen = 0L
                )
                NotificationHelper.showMessageNotification(
                    appContext,
                    message,
                    message.from.substringBefore("@"),
                    fallbackPrefs,
                    localEmail
                )
            }
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Error triggering notification", e)
        }
    }

    private fun getEventListener(requestId: String): EventListener {
        return object : EventListener() {
            var start = 0L

            override fun callStart(call: Call) {
                start = System.currentTimeMillis()
                Log.e("WebSocketManager", "[TRACE-HTTP] ($requestId) Call started at $start")
            }

            override fun dnsStart(call: Call, domainName: String) {
                Log.e("WebSocketManager", "[TRACE-HTTP] ($requestId) DNS start: $domainName")
            }

            override fun dnsEnd(
                    call: Call,
                    domainName: String,
                    inetAddressList: List<java.net.InetAddress>
            ) {
                val duration = System.currentTimeMillis() - start
                Log.e("WebSocketManager", "[TRACE-HTTP] ($requestId) DNS end: ${duration}ms")
            }

            override fun connectStart(
                    call: Call,
                    inetSocketAddress: java.net.InetSocketAddress,
                    proxy: java.net.Proxy
            ) {
                Log.e("WebSocketManager", "[TRACE-HTTP] ($requestId) Connect start")
            }

            override fun connectEnd(
                    call: Call,
                    inetSocketAddress: java.net.InetSocketAddress,
                    proxy: java.net.Proxy,
                    protocol: Protocol?
            ) {
                val duration = System.currentTimeMillis() - start
                Log.e(
                        "WebSocketManager",
                        "[TRACE-HTTP] ($requestId) Connect end: ${duration}ms, Protocol: $protocol"
                )
            }

            override fun requestHeadersStart(call: Call) {
                Log.e("WebSocketManager", "[TRACE-HTTP] ($requestId) Request Headers start")
            }

            override fun requestHeadersEnd(call: Call, request: Request) {
                val duration = System.currentTimeMillis() - start
                Log.e(
                        "WebSocketManager",
                        "[TRACE-HTTP] ($requestId) Request Headers end: ${duration}ms"
                )
            }

            override fun requestBodyStart(call: Call) {
                Log.e(
                        "WebSocketManager",
                        "[TRACE-HTTP] ($requestId) Request Body start (Uploading...)"
                )
            }

            override fun requestBodyEnd(call: Call, byteCount: Long) {
                val duration = System.currentTimeMillis() - start
                Log.e(
                        "WebSocketManager",
                        "[TRACE-HTTP] ($requestId) Request Body end: ${duration}ms, bytes: $byteCount"
                )
            }

            override fun responseHeadersStart(call: Call) {
                Log.e(
                        "WebSocketManager",
                        "[TRACE-HTTP] ($requestId) Response Headers start (Waiting for server processing...)"
                )
            }

            override fun responseHeadersEnd(call: Call, response: Response) {
                val duration = System.currentTimeMillis() - start
                Log.e(
                        "WebSocketManager",
                        "[TRACE-HTTP] ($requestId) Response Headers end: ${duration}ms, code: ${response.code}"
                )
            }

            override fun responseBodyStart(call: Call) {
                Log.e(
                        "WebSocketManager",
                        "[TRACE-HTTP] ($requestId) Response Body start (Downloading response...)"
                )
            }

            override fun responseBodyEnd(call: Call, byteCount: Long) {
                val duration = System.currentTimeMillis() - start
                Log.e(
                        "WebSocketManager",
                        "[TRACE-HTTP] ($requestId) Response Body end: ${duration}ms, bytes: $byteCount"
                )
            }

            override fun callEnd(call: Call) {
                val duration = System.currentTimeMillis() - start
                Log.e("WebSocketManager", "[TRACE-HTTP] ($requestId) Call end: ${duration}ms")
            }

            override fun callFailed(call: Call, ioe: java.io.IOException) {
                Log.e("WebSocketManager", "[TRACE-HTTP] ($requestId) Call failed: $ioe")
            }
        }
    }

    /* ---------------------------------------------------
       OKHTTP (with ping)
    --------------------------------------------------- */
    private val client =
            OkHttpClient.Builder()
                    .retryOnConnectionFailure(true)
                    .addInterceptor { chain ->
                        val request = chain.request()
                        Log.e(
                                "WebSocketManager",
                                "[APP-INTERCEPTOR] Outgoing request to: ${request.url}"
                        )
                        chain.proceed(request)
                    }
                    .addNetworkInterceptor { chain ->
                        val request = chain.request()
                        Log.e(
                                "WebSocketManager",
                                "[NET-INTERCEPTOR] Handshake headers for: ${request.url}"
                        )
                        request.headers.forEach { (name, value) ->
                            Log.e("WebSocketManager", "  $name: $value")
                        }
                        chain.proceed(request)
                    }
                    .eventListenerFactory { call ->
                        object : EventListener() {
                            override fun requestHeadersEnd(call: Call, request: Request) {
                                Log.e(
                                        "WebSocketManager",
                                        "[EVENT-REQ] Headers sent for ${request.url}:"
                                )
                                request.headers.forEach { (name, value) ->
                                    Log.e("WebSocketManager", "  $name: $value")
                                }
                            }
                            override fun connectFailed(
                                    call: Call,
                                    inetSocketAddress: java.net.InetSocketAddress,
                                    proxy: java.net.Proxy,
                                    protocol: Protocol?,
                                    ioe: java.io.IOException
                            ) {
                                Log.e(
                                        "WebSocketManager",
                                        "[EVENT-FAIL] Connection failed: ${ioe.message}"
                                )
                            }
                        }
                    }
                    // Removed OkHttp's built-in pingInterval to avoid conflict with custom JSON ping/pong
                    .protocols(listOf(Protocol.HTTP_1_1)) // Force HTTP/1.1
                    .addInterceptor(
                            HttpLoggingInterceptor { message ->
                                Log.e("WebSocketManager", "[HTTP] $message")
                            }
                                    .apply { level = HttpLoggingInterceptor.Level.BODY }
                    )
                    .build()

    private var webSocket: WebSocket? = null

    // Use IO scope for network; we'll switch to Main when emitting events
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingQueue = ConcurrentLinkedQueue<PendingMessage>()

    private lateinit var dao: PendingMessageDao
    private var initialized = false
    private var curruser: String? = null
    private var wsUrl: String? = null
    private var reconnectAttempt = 0

    private const val MEDIA_BASE_URL = NetworkConfig.BASE_URL
    private const val MEDIA_UPLOAD = "${NetworkConfig.BASE_URL}media/upload"

    private const val MAX_RETRIES = 5
    private const val MAX_RECONNECT_DELAY_MS = 30_000L
    private const val HEARTBEAT_INTERVAL_MS = 5_000L // send ping every 5 seconds
    private const val PING_PONG_TIMEOUT_MS = 10_000L // reconnect if no pong within 10 seconds
    private const val READY_RETRY_INTERVAL_MS = 3_000L // increased to reduce spam
    private const val OUTGOING_SEND_DELAY_MS = 40L // throttle to avoid kernel buffer overflow

    // ack timeout — how long to wait for delivery_ack before retrying
    private const val ACK_TIMEOUT_MS = 20_000L

    private var heartbeatJob: Job? = null
    private var readyJob: Job? = null
    
    private fun getBuildVersion(): String {
        return NetworkConfig.BUILD_VERSION
    }
    
    /**
     * Get the current WebSocket instance for monitoring purposes.
     * Used by WebSocketMonitorService to check connection health.
     */
    fun getWebSocket(): WebSocket? = webSocket
    
    /**
     * Check if WebSocket is currently connected
     */
    fun isConnected(): Boolean {
        return wsState == WsState.CONNECTED && webSocket != null
    }
    
    /**
     * Check if WebSocket is ready to send messages (connected AND received ready_ack)
     */
    fun isReady(): Boolean {
        return wsState == WsState.CONNECTED && webSocket != null && readyState == ReadyState.READY
    }
    
    /**
     * Send a ping and return true if pong is received within timeout.
     * Used by WebSocketMonitorService for health checks.
     * Returns true if server responds, false if timeout or error.
     */
    suspend fun sendPingAndWaitForPong(timeoutMs: Long = 5000L): Boolean {
        return try {
            val ws = webSocket
            if (ws == null || wsState != WsState.CONNECTED) {
                return false
            }
            
            // Record current lastServerMessageAt
            val beforePing = lastServerMessageAt
            
            // Send ping
            val sent = ws.send(JSONObject().put("buildVersion", getBuildVersion()).put("type", "ping").toString())
            if (!sent) {
                return false
            }
            
            // Wait for pong (which updates lastServerMessageAt)
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (lastServerMessageAt > beforePing) {
                    // Server responded (pong or any message)
                    return true
                }
                delay(100) // Check every 100ms
            }
            
            // Timeout - no response
            false
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Error in sendPingAndWaitForPong", e)
            false
        }
    }
    
    /**
     * Connect to WebSocket with stored credentials (for reconnection)
     * This is a simpler version that doesn't require Context parameter
     */
    fun connect(token: String, email: String) {
        scope.launch {
            try {
                // Check if email update is in progress
                if (isUpdatingEmail) {
                    Log.w("WebSocketManager", "Email update in progress, blocking connection attempt")
                    return@launch
                }
                
                connectMutex.withLock {
                    if (wsState != WsState.DISCONNECTED) {
                        Log.w("WebSocketManager", "Already connecting or connected")
                        return@launch
                    }
                    wsState = WsState.CONNECTING
                }
                
                Log.e("WebSocketManager", "=== CONNECT CALLED ===")
                Log.e("WebSocketManager", "Email parameter received: $email")
                Log.e("WebSocketManager", "Token parameter received: ${token.take(20)}...")
                Log.e("WebSocketManager", "Previous curruser value: $curruser")
                
                curruser = email
                Log.e("WebSocketManager", "Updated curruser to: $curruser")
                
                val buildVersion = getBuildVersion()
                val url = "${NetworkConfig.WS_BASE_URL}?token=${Uri.encode(token)}&gmail=${Uri.encode(email)}&buildVersion=${Uri.encode(buildVersion)}"
                wsUrl = url
                
                Log.e("WebSocketManager", "WebSocket URL: $url")
                Log.i("WebSocketManager", "Reconnecting WebSocket to: $url")
                
                val request = Request.Builder().url(url).build()
                webSocket = client.newWebSocket(request, createListener())
                
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Failed to reconnect WebSocket", e)
                wsState = WsState.DISCONNECTED
            }
        }
    }
    private var ackMonitorJob: Job? = null

    private fun PendingMessage.toEntity(): PendingMessageEntity {
        Log.e(
                "WebSocketManager",
                "[TRACE] toEntity() ENTRY → messageId=$messageId, retries=$retries, state=${state.name}, lastSentAt=$lastSentAt"
        )
        val entity =
                PendingMessageEntity(messageId, payload, retries, state.name, lastSentAt, timestamp)
        Log.e("WebSocketManager", "[TRACE] toEntity() EXIT → entity created")
        return entity
    }

    // Backpressure-safe SharedFlow for UI-level events
    private val _events =
            MutableSharedFlow<WebSocketEvent>(
                    replay = 0,
                    extraBufferCapacity = 64,
                    onBufferOverflow = BufferOverflow.SUSPEND
            )
    val events = _events.asSharedFlow()

    /* ---------------------------------------------------
       NETWORK CALLBACK / LIVENESS
    --------------------------------------------------- */
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private const val SERVER_LIVENESS_TIMEOUT_MS = 30_000L // 30 seconds - fallback if server goes completely silent
    @Volatile private var lastServerMessageAt: Long = 0
    @Volatile private var lastPingSentAt: Long = 0
    private var livenessJob: Job? = null
    private lateinit var historyDao: ChatHistoryDao

    private fun startLivenessMonitor() {
        livenessJob?.cancel()
        livenessJob =
                scope.launch {
                    while (isActive) {
                        delay(2_000) // Check every 2 seconds for faster detection
                        val now = System.currentTimeMillis()
                        // Trigger reconnect if a ping was sent but no pong/message received within 10s
                        val pingSent = lastPingSentAt
                        val pingTimedOut = pingSent > 0 && (now - pingSent) > PING_PONG_TIMEOUT_MS
                        val serverSilent = lastServerMessageAt > 0 && (now - lastServerMessageAt) > SERVER_LIVENESS_TIMEOUT_MS
                        if (pingTimedOut || serverSilent) {
                            val reason = if (pingTimedOut) "PING NOT RECEIVED WITHIN 10s" else "SERVER SILENT"
                            Log.e("WebSocketManager", "!!! $reason → KILLING OLD CONNECTION AND STARTING FRESH !!!")

                            // Completely kill the old connection
                            forceCloseSocket()

                            // Reset ping tracking and reconnect attempt to start fresh
                            lastPingSentAt = 0
                            reconnectAttempt = 0

                            // Create a completely new WebSocket connection
                            if (wsUrl != null) {
                                Log.e("WebSocketManager", "!!! CREATING FRESH WEBSOCKET CONNECTION !!!")
                                try {
                                    webSocket = client.newWebSocket(
                                        Request.Builder().url(wsUrl!!).build(),
                                        createListener()
                                    )
                                    wsState = WsState.CONNECTING
                                } catch (e: Exception) {
                                    Log.e("WebSocketManager", "Failed to create fresh connection", e)
                                    scheduleReconnect(++reconnectAttempt)
                                }
                            }
                            return@launch
                        }
                    }
                }
    }

    private fun stopLivenessMonitor() {
        livenessJob?.cancel()
        livenessJob = null
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun registerNetworkCallback(context: Context) {
        try {
            connectivityManager =
                    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            networkCallback =
                    object : ConnectivityManager.NetworkCallback() {

                        override fun onAvailable(network: Network) {
                            Log.e("WebSocketManager", "Network AVAILABLE → reconnect")
                            reconnectIfNeeded(context)
                            if (!AppVisibility.isForeground) {

                                sendPresence("offline")
                            }
                        }

                        override fun onLost(network: Network) {
                            Log.e("WebSocketManager", "Network LOST → force close socket")
                            forceCloseSocket()
                        }
                    }

            connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Failed to register network callback", e)
        }
    }

    fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
            networkCallback = null
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Failed to unregister network callback", e)
        }
    }

    /* ---------------------------------------------------
       INIT
    --------------------------------------------------- */
    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext

        val db =
                Room.databaseBuilder(
                                context.applicationContext,
                                AppDatabase::class.java,
                                "anonychat.db"
                        )
                        .fallbackToDestructiveMigration()
                        .build()

        dao = db.pendingMessageDao()
        historyDao = db.chatHistoryDao()

        // Load pending messages from DB, but filter out chat_open/chat_close messages
        // These are presence signals and should not be persisted or retried
        pendingQueue.addAll(
                dao.getAll()
                    .filter { entity ->
                        // Filter out chat_open and chat_close messages
                        !entity.messageId.startsWith("chat_open_") &&
                        !entity.messageId.startsWith("chat_close_")
                    }
                    .map {
                        val st =
                                runCatching { MessageState.valueOf(it.state) }.getOrElse {
                                    MessageState.QUEUED
                                }
                        PendingMessage(
                                it.messageId,
                                it.payload,
                                it.retries,
                                st,
                                it.lastSentAt,
                                it.timestamp
                        )
                    }
        )
        
        // Clean up any chat_open/chat_close messages from DB
        scope.launch {
            try {
                val allMessages = dao.getAll()
                allMessages.forEach { entity ->
                    if (entity.messageId.startsWith("chat_open_") ||
                        entity.messageId.startsWith("chat_close_")) {
                        dao.delete(entity.messageId)
                        Log.d("WebSocketManager", "Cleaned up stale presence message: ${entity.messageId}")
                    }
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Failed to clean up stale presence messages", e)
            }
        }

        initialized = true
        Log.i("WebSocketManager", "Initialized with pending messages=${pendingQueue.size}")
    }

    private fun ensureInit() {
        check(initialized) { "WebSocketManager.init(context) must be called first" }
    }
    
    /**
     * Sets the email update lock to prevent connections during email changes
     */
    fun lockEmailUpdate() {
        Log.e("WebSocketManager", "🔒 EMAIL UPDATE LOCK ACQUIRED - Blocking all connection attempts")
        isUpdatingEmail = true
    }
    
    /**
     * Clears the email update lock to allow connections
     */
    fun unlockEmailUpdate() {
        Log.e("WebSocketManager", "🔓 EMAIL UPDATE LOCK RELEASED - Connections now allowed")
        isUpdatingEmail = false
    }
    
    /**
     * Update all occurrences of old email to new email in Room database
     * This is critical after account reset to ensure WebSocket connects with correct email
     * IMPORTANT: This function sets a lock to prevent WebSocket connections during the update
     */
    suspend fun updateAllEmailsInDatabase(oldEmail: String, newEmail: String) {
        ensureInit()
        try {
            // Set lock to prevent connections during update
            lockEmailUpdate()
            
            Log.i("WebSocketManager", "Updating all emails in database: $oldEmail -> $newEmail")
            
            // Update fromEmail field
            historyDao.updateFromEmail(oldEmail, newEmail)
            Log.d("WebSocketManager", "Updated fromEmail in chat_history")
            
            // Update toEmail field
            historyDao.updateToEmail(oldEmail, newEmail)
            Log.d("WebSocketManager", "Updated toEmail in chat_history")
            
            Log.i("WebSocketManager", "Successfully updated all emails in database")
            
            // Note: Lock will be released by the caller after SharedPreferences is also updated
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Error updating emails in database", e)
            // Release lock on error
            unlockEmailUpdate()
        }
    }

    private suspend fun downloadMediaAndAck(
            url: String,
            mediaId: String,
            chatMessage: ChatMessage? = null
    ) {
        try {
            val prefs = appContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val token = prefs.getString("access_token", null)

            val absoluteUrl = ensureAbsoluteUrl(url)
            val reqBuilder = Request.Builder().url(absoluteUrl)
            if (token != null) reqBuilder.header("Authorization", "Bearer $token")

            client.newCall(reqBuilder.build()).execute().use { res ->
                if (!res.isSuccessful) return

                // If a ChatMessage was provided, update it with "Downloading" state
                if (chatMessage != null) {
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(
                                WebSocketEvent.NewMessage(chatMessage.copy(isDownloading = true))
                        )
                    }
                }

                val extension = url.substringAfterLast('.', "bin").substringBefore('?')
                val filename = "media_${mediaId.replace(":", "_")}.${extension}"
                val file = File(appContext.filesDir, filename)

                res.body?.byteStream()?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }

                sendMediaDownloadAck(mediaId)

                // If a ChatMessage was provided, update it with the local path and notify UI
                if (chatMessage != null) {
                    val localUri = "file://${file.absolutePath}"
                    val updatedMsg =
                            when {
                                chatMessage.audioUri != null ->
                                        chatMessage.copy(audioUri = localUri, isDownloading = false)
                                chatMessage.mediaType == "IMAGE" ||
                                        chatMessage.mediaType == "GIF" ->
                                        chatMessage.copy(
                                                mediaUri = localUri,
                                                isDownloading = false,
                                                status = com.example.anonychat.ui.MessageStatus.Read
                                        )
                                chatMessage.mediaType != null ->
                                        chatMessage.copy(mediaUri = localUri, isDownloading = false)
                                else -> chatMessage.copy(isDownloading = false)
                            }
                    saveToHistory(updatedMsg)
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.NewMessage(updatedMsg))
                    }

                    // After download, for IMAGE, GIF, and VIDEO, we send MessageReadAck
                    if (updatedMsg.mediaType == "IMAGE" ||
                                    updatedMsg.mediaType == "GIF" ||
                                    updatedMsg.mediaType == "VIDEO"
                    ) {
                        sendMessageReadAck(updatedMsg.id, updatedMsg.from)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Media download failed", e)
        }
    }

    private fun sendMediaDownloadAck(mediaId: String) {
        try {
            val prefs = appContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val token = prefs.getString("access_token", null)

            val json = JSONObject().put("mediaId", mediaId).toString()
            val body = RequestBody.create("application/json".toMediaTypeOrNull(), json)

            val rb = Request.Builder().url("${NetworkConfig.BASE_URL}media/download/ack").post(body)

            if (token != null) rb.header("Authorization", "Bearer $token")

            client.newCall(rb.build()).execute()
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Media ACK failed", e)
        }
    }

    private fun sendOnlinePresenceIfReady() {
        if (readyState == ReadyState.READY) {
            if (!AppVisibility.isForeground) {

                sendPresence("offline")
            } else {

                sendPresence("online")
            }

            Log.i("WebSocketManager", "Presence auto-sent: online after reconnect")
        }
    }

    fun sendPresence(state: String) {
        // state = "foreground" | "background" | "online" | "offline"
        if (readyState != ReadyState.READY) return

        val payload = JSONObject().put("buildVersion", getBuildVersion()).put("type", "presence").put("state", state).toString()

        try {
            webSocket?.send(payload)
            Log.i("WebSocketManager", "Presence sent: $state")
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Presence send failed", e)
        }
    }

    /* ---------------------------------------------------
       CONNECT
    --------------------------------------------------- */
    suspend fun connect(context: Context) {
        ensureInit()
        Log.e("WebSocketManager", "connect() called - attempting to establish WebSocket connection")

        // Check if email update is in progress
        if (isUpdatingEmail) {
            Log.w("WebSocketManager", "Email update in progress, blocking connection attempt")
            return
        }

        // 1) Acquire lock only to check & mutate quick state -> don't suspend while holding it
        connectMutex.withLock {
            Log.e("WebSocketManager", "Current wsState: $wsState")
            if (wsState != WsState.DISCONNECTED) {
                Log.e("WebSocketManager", "Already connecting or connected, skipping connection attempt")
                return
            }
            Log.e("WebSocketManager", "State is DISCONNECTED, proceeding with connection")
            wsState = WsState.CONNECTING
        }

        // 2) Now do the long-running suspend outside the lock.
        // Optional: wrap in timeout to avoid waiting forever (recommended).
        try {
            withTimeout(15_000L) { // adjust timeout as needed
                suspendCancellableCoroutine<Unit> { cont ->
                    val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    val token = prefs.getString("access_token", null)
                    val email = prefs.getString("user_email", null)
                    curruser = email

                    Log.i(
                            "WebSocketManager",
                            "connect: Retrieved from prefs -> email: $email, token exists: ${token != null}"
                    )

                    if (token == null || email == null) {
                        // quick state reset under lock
                        scope.launch { connectMutex.withLock { wsState = WsState.DISCONNECTED } }
                        cont.resumeWith(Result.failure(IllegalStateException("Auth missing")))
                        return@suspendCancellableCoroutine
                    }

                    wsUrl =
                            "${NetworkConfig.WS_BASE_URL}?token=${Uri.encode(token)}&gmail=${Uri.encode(email)}&buildVersion=${Uri.encode(NetworkConfig.BUILD_VERSION)}"
                    Log.i("WebSocketManager", "connect: Constructed WS URL: $wsUrl")

                    // Atomic boolean to ensure we resume the continuation exactly once
                    val resumed = AtomicBoolean(false)

                    val listener = createListener(cont, resumed)

                    val request = Request.Builder().url(wsUrl!!).build()

                    Log.e(
                            "WebSocketManager",
                            "Initiating WebSocket connection for $email (headers logged by NETWORK/APP interceptors)"
                    )

                    // newWebSocket returns a WebSocket instance; keep reference for cancellation
                    val ws = client.newWebSocket(request, listener)

                    // If the coroutine is cancelled (timeout or external), close socket & reset
                    // state
                    cont.invokeOnCancellation {
                        if (resumed.compareAndSet(false, true)) {
                            try {
                                ws.close(1001, "Cancelled")
                            } catch (e: Exception) {
                                Log.e(
                                        "WebSocketManager",
                                        "Failed to close websocket on cancellation",
                                        e
                                )
                            }
                            scope.launch {
                                connectMutex.withLock { wsState = WsState.DISCONNECTED }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // On timeout or other failure, ensure state is DISCONNECTED so future connects can try
            // again.
            Log.e("WebSocketManager", "connect failed or timed out", e)
            connectMutex.withLock { wsState = WsState.DISCONNECTED }
            throw e
        }
    }

    fun downloadMediaManually(url: String, mediaId: String, chatMessage: ChatMessage? = null) {
        scope.launch { downloadMediaAndAck(url, mediaId, chatMessage) }
    }

    /**
     * Compresses an image if it exceeds 2MB. Returns the URI of the compressed image (or original
     * if no compression needed).
     */
    private suspend fun compressImageIfNeeded(fileUri: Uri, contentType: String): Uri {
        val startTime = System.currentTimeMillis()
        Log.e("WebSocketManager", "[TRACE] compressImageIfNeeded() START")

        // Only compress images
        if (!contentType.startsWith("image/") ||
                        contentType == "image/gif" ||
                        contentType == "image/webp"
        ) {
            return fileUri
        }

        return withContext(Dispatchers.IO) {
            try {
                // Check file size
                val inputStream = appContext.contentResolver.openInputStream(fileUri)
                val fileSize = inputStream?.available()?.toLong() ?: 0L
                inputStream?.close()

                val maxSizeBytes = 3 * 1024 * 1024L // 2MB

                Log.e(
                        "WebSocketManager",
                        "[COMPRESS] Image size: ${fileSize / 1024}KB, max: ${maxSizeBytes / 1024}KB"
                )

                if (fileSize <= maxSizeBytes) {
                    Log.e(
                            "WebSocketManager",
                            "[COMPRESS] Image is under 2MB, no compression needed"
                    )
                    return@withContext fileUri
                }

                Log.e("WebSocketManager", "[COMPRESS] Image exceeds 2MB, compressing...")

                // Read orientation before decoding
                val orientation =
                        try {
                            appContext.contentResolver.openInputStream(fileUri)?.use { input ->
                                ExifInterface(input)
                                        .getAttributeInt(
                                                ExifInterface.TAG_ORIENTATION,
                                                ExifInterface.ORIENTATION_NORMAL
                                        )
                            }
                                    ?: ExifInterface.ORIENTATION_NORMAL
                        } catch (e: Exception) {
                            Log.e("WebSocketManager", "[COMPRESS] Failed to read orientation", e)
                            ExifInterface.ORIENTATION_NORMAL
                        }

                // Decode the bitmap
                var originalBitmap =
                        appContext.contentResolver.openInputStream(fileUri)?.use { input ->
                            android.graphics.BitmapFactory.decodeStream(input)
                        }
                                ?: return@withContext fileUri

                // Rotate bitmap if needed
                if (orientation != ExifInterface.ORIENTATION_NORMAL) {
                    val matrix = Matrix()
                    when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> {
                            matrix.postRotate(90f)
                            matrix.postScale(-1f, 1f)
                        }
                        ExifInterface.ORIENTATION_TRANSVERSE -> {
                            matrix.postRotate(270f)
                            matrix.postScale(-1f, 1f)
                        }
                    }
                    try {
                        val rotatedBitmap =
                                Bitmap.createBitmap(
                                        originalBitmap,
                                        0,
                                        0,
                                        originalBitmap.width,
                                        originalBitmap.height,
                                        matrix,
                                        true
                                )
                        if (rotatedBitmap != originalBitmap) {
                            originalBitmap.recycle()
                            originalBitmap = rotatedBitmap
                        }
                    } catch (e: Exception) {
                        Log.e("WebSocketManager", "[COMPRESS] Failed to rotate bitmap", e)
                    }
                }

                // Create a temporary file for the compressed image
                val compressedFile =
                        File(appContext.cacheDir, "compressed_${System.currentTimeMillis()}.jpg")

                // Try different quality levels until we get under 3MB
                var quality = 90
                var compressedSize = Long.MAX_VALUE
                val format =
                        if (contentType == "image/png") android.graphics.Bitmap.CompressFormat.PNG
                        else android.graphics.Bitmap.CompressFormat.JPEG

                while (quality >= 10 && compressedSize > maxSizeBytes) {
                    compressedFile.outputStream().use { output ->
                        originalBitmap.compress(format, quality, output)
                    }
                    compressedSize = compressedFile.length()
                    Log.e(
                            "WebSocketManager",
                            "[COMPRESS] Quality: $quality%, Size: ${compressedSize / 1024}KB, Format: $format"
                    )

                    if (format == android.graphics.Bitmap.CompressFormat.PNG)
                            break // PNG doesn't support quality levels in the same way for size
                    // reduction usually
                    if (compressedSize > maxSizeBytes) {
                        quality -= 10
                    }
                }

                // If still too large, try scaling down the image
                if (compressedSize > maxSizeBytes) {
                    Log.e("WebSocketManager", "[COMPRESS] Still too large, scaling down image...")
                    var scaleFactor = 0.9f

                    while (scaleFactor >= 0.3f && compressedSize > maxSizeBytes) {
                        val scaledWidth = (originalBitmap.width * scaleFactor).toInt()
                        val scaledHeight = (originalBitmap.height * scaleFactor).toInt()

                        val scaledBitmap =
                                android.graphics.Bitmap.createScaledBitmap(
                                        originalBitmap,
                                        scaledWidth,
                                        scaledHeight,
                                        true
                                )

                        val format =
                                if (contentType == "image/png")
                                        android.graphics.Bitmap.CompressFormat.PNG
                                else android.graphics.Bitmap.CompressFormat.JPEG
                        compressedFile.outputStream().use { output ->
                            scaledBitmap.compress(format, 85, output)
                        }

                        scaledBitmap.recycle()
                        compressedSize = compressedFile.length()
                        Log.e(
                                "WebSocketManager",
                                "[COMPRESS] Scale: ${(scaleFactor * 100).toInt()}%, Size: ${compressedSize / 1024}KB"
                        )

                        if (compressedSize > maxSizeBytes) {
                            scaleFactor -= 0.1f
                        }
                    }
                }

                originalBitmap.recycle()

                Log.e(
                        "WebSocketManager",
                        "[COMPRESS] ✅ Compression complete! Original: ${fileSize / 1024}KB → Final: ${compressedSize / 1024}KB (saved ${(fileSize - compressedSize) / 1024}KB)"
                )

                val duration = System.currentTimeMillis() - startTime
                Log.e(
                        "WebSocketManager",
                        "[TRACE] compressImageIfNeeded() END → Duration: ${duration}ms"
                )
                // Return URI of compressed file
                Uri.fromFile(compressedFile)
            } catch (e: Exception) {
                Log.e("WebSocketManager", "[COMPRESS] Compression failed, using original", e)
                fileUri
            }
        }
    }
    @RequiresApi(Build.VERSION_CODES.N)
    fun sendMedia(
            toEmail: String,
            fileUri: Uri,
            contentType: String,
            localId: String,
            amplitudes: List<Float>? = null
    ) {
        Log.e(
                "WebSocketManager",
                "[TRACE] sendMedia() ENTRY → toEmail=$toEmail, fileUri=$fileUri, contentType=$contentType, localId=$localId"
        )
        ensureInit()
        Log.e("WebSocketManager", "[TRACE] sendMedia() ensureInit() completed")

        val chatMessage =
                ChatMessage(
                        id = localId,
                        from = curruser ?: "",
                        to = toEmail,
                        text =
                                if (contentType.startsWith("audio")) "[Audio Message]"
                                else "[Media Message]",
                        audioUri =
                                if (contentType.startsWith("audio")) fileUri.toString() else null,
                        mediaUri =
                                if (contentType.startsWith("audio")) null else fileUri.toString(),
                        mediaType =
                                if (contentType.startsWith("audio")) null
                                else if (contentType.startsWith("image")) "IMAGE"
                                else if (contentType.contains("gif")) "GIF" else "VIDEO",
                        amplitudes = amplitudes ?: emptyList(),
                        status = com.example.anonychat.ui.MessageStatus.Sending
                )
        saveToHistory(chatMessage)

        scope.launch {
            val totalStart = System.currentTimeMillis()
            Log.e("WebSocketManager", "[TRACE] sendMedia() coroutine launched at $totalStart")

            var retryCount = 0
            var uploadSuccessful = false

            // Compress image if needed (before retry loop to avoid re-compressing on each retry)
            val compressStart = System.currentTimeMillis()
            val actualFileUri = compressImageIfNeeded(fileUri, contentType)
            Log.e(
                    "WebSocketManager",
                    "[TRACE] Compression took ${System.currentTimeMillis() - compressStart}ms"
            )

            // Ensure permanent local storage for sender side persistence
            val extension = contentType.substringAfter('/', "bin").substringBefore('+')
            val localFileName = "sent_${localId.replace(":", "_")}.${extension}"
            val permanentFile = File(appContext.filesDir, localFileName)
            try {
                appContext.contentResolver.openInputStream(actualFileUri)?.use { input ->
                    permanentFile.outputStream().use { output -> input.copyTo(output) }
                }
                val permanentUri = "file://${permanentFile.absolutePath}"
                Log.e("WebSocketManager", "[PERSIST] Saved permanent local copy: $permanentUri")

                // Update history with permanent URI
                if (contentType.startsWith("audio")) {
                    historyDao.updateAudioDownload(localId, permanentUri, false)
                } else {
                    historyDao.updateMediaDownload(localId, permanentUri, false)
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "[PERSIST] Failed to save permanent copy", e)
            }

            while (!uploadSuccessful && retryCount <= MAX_RETRIES) {
                try {
                    if (retryCount > 0) {
                        val delayMs = (2.0.pow(retryCount - 1) * 1000).toLong().coerceAtMost(30000L)
                        Log.e(
                                "WebSocketManager",
                                "[RETRY] Attempt $retryCount after ${delayMs}ms delay"
                        )
                        delay(delayMs)
                    }

                    Log.e("WebSocketManager", "[TRACE] sendMedia() retrieving SharedPreferences")
                    val prefs = appContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    val token = prefs.getString("access_token", null)
                    Log.e(
                            "WebSocketManager",
                            "[TRACE] sendMedia() token retrieved, exists=${token != null}"
                    )

                    Log.e(
                            "WebSocketManager",
                            "[TRACE] sendMedia() opening input stream for fileUri=$actualFileUri"
                    )
                    val input = appContext.contentResolver.openInputStream(actualFileUri)
                    if (input == null) {
                        Log.e(
                                "WebSocketManager",
                                "[TRACE] sendMedia() input stream is NULL, returning early"
                        )
                        return@launch
                    }
                    Log.e(
                            "WebSocketManager",
                            "[TRACE] sendMedia() input stream opened successfully. Reading stream..."
                    )

                    Log.e("WebSocketManager", "[TRACE] sendMedia() creating RequestBody")
                    val bodyStart = System.currentTimeMillis()
                    val requestBody =
                            object : RequestBody() {
                                override fun contentType(): MediaType? {
                                    Log.e(
                                            "WebSocketManager",
                                            "[TRACE] sendMedia.RequestBody.contentType() called → $contentType"
                                    )
                                    return contentType.toMediaTypeOrNull()
                                }
                                override fun writeTo(sink: BufferedSink) {
                                    val writeStart = System.currentTimeMillis()
                                    Log.e(
                                            "WebSocketManager",
                                            "[TRACE] sendMedia.RequestBody.writeTo() ENTRY - starting actual bytes transfer"
                                    )
                                    appContext.contentResolver.openInputStream(actualFileUri)
                                            ?.use { input ->
                                                val inputStart = System.currentTimeMillis()
                                                Log.e(
                                                        "WebSocketManager",
                                                        "[TRACE] sendMedia.RequestBody.writeTo() openedInputStream in ${inputStart - writeStart}ms"
                                                )
                                                sink.writeAll(input.source())
                                                Log.e(
                                                        "WebSocketManager",
                                                        "[TRACE] sendMedia.RequestBody.writeTo() writeAll completed"
                                                )
                                            }
                                    val writeDuration = System.currentTimeMillis() - writeStart
                                    Log.e(
                                            "WebSocketManager",
                                            "[TRACE] sendMedia.RequestBody.writeTo() EXIT - Transfer took ${writeDuration}ms"
                                    )
                                }
                            }
                    Log.e(
                            "WebSocketManager",
                            "[TRACE] sendMedia() RequestBody created in ${System.currentTimeMillis() - bodyStart}ms"
                    )

                    val extension =
                            when {
                                contentType.contains("gif") -> "gif"
                                contentType.contains("webp") -> "webp"
                                contentType.contains("png") -> "png"
                                contentType.contains("video") -> "mp4"
                                else -> "jpg"
                            }
                    val fileName = "upload_${System.currentTimeMillis()}.$extension"
                    Log.e("WebSocketManager", "[TRACE] sendMedia() fileName=$fileName")

                    Log.e("WebSocketManager", "[TRACE] sendMedia() building multipart request")
                    val multipart =
                            MultipartBody.Builder()
                                    .setType(MultipartBody.FORM)
                                    .addFormDataPart("file", fileName, requestBody)
                                    .build()
                    Log.e("WebSocketManager", "[TRACE] sendMedia() multipart built")

                    Log.e(
                            "WebSocketManager",
                            "[TRACE] sendMedia() building HTTP request to $MEDIA_UPLOAD"
                    )
                    val reqBuilder = Request.Builder().url(MEDIA_UPLOAD).post(multipart)

                    if (token != null) {
                        Log.e("WebSocketManager", "[TRACE] sendMedia() adding Authorization header")
                        reqBuilder.header("Authorization", "Bearer $token")
                    }
                    Log.e("WebSocketManager", "[TRACE] sendMedia() HTTP request built")

                    val requestId = "req_${System.currentTimeMillis()}"
                    Log.e(
                            "WebSocketManager",
                            "[TRACE] sendMedia() executing HTTP request with requestId=$requestId"
                    )
                    val httpStart = System.currentTimeMillis()

                    // Use a custom client for this request to attach the event listener
                    val customClient =
                            client.newBuilder().eventListener(getEventListener(requestId)).build()

                    customClient.newCall(reqBuilder.build()).execute().use { res ->
                        val httpDuration = System.currentTimeMillis() - httpStart
                        Log.e(
                                "WebSocketManager",
                                "[TRACE] sendMedia() HTTP request took ${httpDuration}ms. Response received, isSuccessful=${res.isSuccessful}, code=${res.code}"
                        )
                        if (!res.isSuccessful) {
                            Log.e(
                                    "WebSocketManager",
                                    "[TRACE] sendMedia() HTTP request failed with code=${res.code}, will retry"
                            )
                            throw Exception("HTTP request failed with code ${res.code}")
                        }

                        Log.e("WebSocketManager", "[TRACE] sendMedia() parsing response JSON")
                        val responseJson = JSONObject(res.body!!.string())
                        val mediaId = responseJson.getString("mediaId")
                        val rawUrl = responseJson.getString("url")
                        val url = ensureAbsoluteUrl(rawUrl)
                        Log.e(
                                "WebSocketManager",
                                "[TRACE] sendMedia() response parsed → mediaId=$mediaId, url=$url (raw=$rawUrl)"
                        )

                        Log.e("WebSocketManager", "[TRACE] sendMedia() building WebSocket payload")
                        val payload =
                                JSONObject()
                                        .apply {
                                            put("buildVersion", getBuildVersion()).put("type", "media")
                                            put("to", toEmail)
                                            put("from", curruser)
                                            put("id", localId)
                                            put("messageId", localId)
                                            put("mediaId", mediaId)
                                            put("url", url)
                                            put("contentType", contentType)
                                            put("timestamp", chatMessage.timestamp)
                                            if (amplitudes != null) {
                                                val ampJson = org.json.JSONArray()
                                                amplitudes.forEach { ampJson.put(it.toDouble()) }
                                                put("amplitudes", ampJson)
                                            }
                                        }
                                        .toString()
                        Log.e("WebSocketManager", "[TRACE] sendMedia() payload created → $payload")

                        Log.e("WebSocketManager", "[TRACE] sendMedia() creating PendingMessage")
                        val pm =
                                PendingMessage(
                                        localId,
                                        payload,
                                        0,
                                        MessageState.QUEUED,
                                        0L,
                                        System.currentTimeMillis()
                                )
                        Log.e(
                                "WebSocketManager",
                                "[TRACE] sendMedia() PendingMessage created → messageId=${pm.messageId}, state=${pm.state}"
                        )

                        Log.e(
                                "WebSocketManager",
                                "[TRACE] sendMedia() adding to pendingQueue (current size=${pendingQueue.size})"
                        )
                        pendingQueue.add(pm)
                        Log.e(
                                "WebSocketManager",
                                "[TRACE] sendMedia() added to pendingQueue (new size=${pendingQueue.size})"
                        )

                        Log.e(
                                "WebSocketManager",
                                "[TRACE] sendMedia() calling dao.upsert() with entity"
                        )
                        dao.upsert(pm.toEntity())
                        Log.e("WebSocketManager", "[TRACE] sendMedia() dao.upsert() completed")

                        Log.e("WebSocketManager", "[TRACE] sendMedia() calling flushPendingQueue()")
                        flushPendingQueue()
                        Log.e(
                                "WebSocketManager",
                                "[TRACE] sendMedia() flushPendingQueue() returned"
                        )
                        Log.e(
                                "WebSocketManager",
                                "[TRACE] sendMedia() TOTAL SUCCESS DURATION: ${System.currentTimeMillis() - totalStart}ms"
                        )
                    }
                    Log.e(
                            "WebSocketManager",
                            "[TRACE] sendMedia() HTTP request completed successfully"
                    )
                    uploadSuccessful = true
                } catch (e: Exception) {
                    retryCount++
                    Log.e(
                            "WebSocketManager",
                            "[TRACE] sendMedia() EXCEPTION caught: ${e.message} (Attempt $retryCount/$MAX_RETRIES)",
                            e
                    )

                    if (retryCount > MAX_RETRIES) {
                        Log.e("WebSocketManager", "Media send failed after $MAX_RETRIES retries", e)
                        // Optionally emit a failure event to UI
                        withContext(Dispatchers.Main.immediate) {
                            _events.emit(
                                    WebSocketEvent.DeliveryFailed(
                                            localId,
                                            "upload_failed_after_retries"
                                    )
                            )
                        }
                    } else {
                        Log.e(
                                "WebSocketManager",
                                "Media send failed, will retry (${retryCount}/$MAX_RETRIES)"
                        )
                    }
                }
            }
            Log.e("WebSocketManager", "[TRACE] sendMedia() EXIT")
        }
    }

    /* ---------------------------------------------------
       READY HANDSHAKE
    --------------------------------------------------- */
    private fun startReadyHandshake() {
        readyJob?.cancel()
        readyState = ReadyState.WAITING_ACK

        readyJob =
                scope.launch {
                    while (isActive && readyState != ReadyState.READY) {
                        try {
                            val readyMsg = JSONObject().put("buildVersion", getBuildVersion()).put("type", "is_ready").toString()
                            val sent = webSocket?.send(readyMsg) ?: false
                            if (sent) {
                                Log.d("WebSocketManager", "is_ready sent: $readyMsg")
                            } else {
                                Log.w("WebSocketManager", "is_ready send returned false")
                            }
                        } catch (e: Exception) {
                            Log.e("WebSocketManager", "ready handshake send failed", e)
                        }
                        delay(READY_RETRY_INTERVAL_MS)
                    }
                }
    }

    private fun stopReadyHandshake() {
        readyJob?.cancel()
        readyJob = null
    }

    /* ---------------------------------------------------
       ACK MONITOR (retries on ack-timeout)
    --------------------------------------------------- */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun startAckMonitor() {
        ackMonitorJob?.cancel()
        ackMonitorJob =
                scope.launch {
                    while (isActive) {
                        try {
                            val now = System.currentTimeMillis()
                            for (msg in pendingQueue.toList()) {
                                if (msg.state == MessageState.IN_FLIGHT) {
                                    if (now - msg.lastSentAt > ACK_TIMEOUT_MS) {
                                        // timed out waiting for delivery_ack
                                        Log.w(
                                                "WebSocketManager",
                                                "Ack timeout for ${msg.messageId}"
                                        )
                                        msg.retries++
                                        if (msg.retries > MAX_RETRIES) {
                                            // permanently failed
                                            msg.state = MessageState.FAILED
                                            try {
                                                dao.delete(msg.messageId)
                                            } catch (e: Exception) {
                                                Log.e(
                                                        "WebSocketManager",
                                                        "Failed to delete failed message",
                                                        e
                                                )
                                            }
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                                pendingQueue.removeIf { it.messageId == msg.messageId }
                                            } else {
                                                val iter = pendingQueue.iterator()
                                                while (iter.hasNext()) { if (iter.next().messageId == msg.messageId) iter.remove() }
                                            }
                                            withContext(Dispatchers.Main.immediate) {
                                                _events.emit(
                                                        WebSocketEvent.DeliveryFailed(
                                                                msg.messageId,
                                                                "ack_timeout"
                                                        )
                                                )
                                            }
                                        } else {
                                            // requeue for send
                                            msg.state = MessageState.QUEUED
                                            msg.lastSentAt = 0
                                            try {
                                                dao.upsert(msg.toEntity())
                                            } catch (e: Exception) {
                                                Log.e(
                                                        "WebSocketManager",
                                                        "Failed to persist retry after ack timeout",
                                                        e
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("WebSocketManager", "Ack monitor error", e)
                        }
                        delay(1000)
                    }
                }
    }

    private fun stopAckMonitor() {
        ackMonitorJob?.cancel()
        ackMonitorJob = null
    }

    /* ---------------------------------------------------
       LISTENER
    --------------------------------------------------- */
    private fun createListener(
            continuation: CancellableContinuation<Unit>? = null,
            resumed: AtomicBoolean? = null
    ): WebSocketListener {

        return object : WebSocketListener() {

            @RequiresApi(Build.VERSION_CODES.N)
            override fun onOpen(ws: WebSocket, response: Response) {
                webSocket = ws
                wsState = WsState.CONNECTED
                readyState = ReadyState.NOT_READY
                reconnectAttempt = 0

                Log.i("WebSocketManager", "WebSocket CONNECTED (waiting for ready_ack)")

                // Log response headers
                Log.e("WebSocketManager", "WebSocket Response Headers:")
                response.headers.forEach { (name, value) ->
                    Log.e("WebSocketManager", "  $name: $value")
                }

                // reset liveness timestamps and start monitors
                lastServerMessageAt = System.currentTimeMillis()
                lastPingSentAt = 0 // clear stale ping state from any previous connection
                startReadyHandshake()
                startLivenessMonitor() // Monitor server liveness and reconnect if silent
                // Note: startHeartbeat() is called after ready_ack is received (line 1648)
                startAckMonitor()

                // resume connect() once (if provided)
                try {
                    if (continuation != null && resumed?.compareAndSet(false, true) == true) {
                        continuation.resumeWith(Result.success(Unit))
                    }
                } catch (e: Exception) {
                    Log.w("WebSocketManager", "continuation resume onOpen failed", e)
                }
            }

            @RequiresApi(Build.VERSION_CODES.N)
            override fun onMessage(ws: WebSocket, text: String) {
                Log.d("WebSocketManager", "<<< Received message: $text")
                lastServerMessageAt = System.currentTimeMillis()
                lastPingSentAt = 0 // pong or any server message resets ping timeout
                try {
                    scope.launch { handleIncoming(ws, text) }
                } catch (e: Exception) {
                    Log.e("WebSocketManager", "Failed to launch message handler", e)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                Log.e("WebSocketManager", "WebSocket FAILURE", t)
                
                // Check if failure is due to 401 (token expired/invalid)
                val is401 = r?.code == 401
                if (is401) {
                    Log.e("WebSocketManager", "WebSocket failed with 401 - token expired or invalid")
                    
                    // Try to parse response body
                    val responseBody = r?.peekBody(Long.MAX_VALUE)?.string()
                    Log.e("WebSocketManager", "401 Response body: $responseBody")
                    
                    // Attempt to regenerate token with saved credentials
                    scope.launch {
                        try {
                            val tokenRegenerated = regenerateTokenFromSavedCredentials()
                            if (tokenRegenerated) {
                                Log.i("WebSocketManager", "Token regenerated successfully, reconnecting...")
                                // Reset reconnect attempt counter since we have a fresh token
                                reconnectAttempt = 0
                                // Reconnect with new token
                                delay(1000) // Brief delay before reconnecting
                                reconnectIfNeeded(appContext)
                            } else {
                                Log.e("WebSocketManager", "Failed to regenerate token, scheduling normal reconnect")
                                scheduleReconnect(++reconnectAttempt)
                            }
                        } catch (e: Exception) {
                            Log.e("WebSocketManager", "Error during token regeneration", e)
                            scheduleReconnect(++reconnectAttempt)
                        }
                    }
                    
                    // Clean up current connection
                    forceCloseSocket()
                    readyState = ReadyState.NOT_READY
                    
                    // Resume continuation if waiting
                    try {
                        if (continuation != null && resumed?.compareAndSet(false, true) == true) {
                            continuation.resumeWith(Result.failure(t))
                        }
                    } catch (e: Exception) {
                        Log.w("WebSocketManager", "continuation resume onFailure failed", e)
                    }
                    
                    return // Don't schedule normal reconnect, token regeneration will handle it
                }
                
                // Check if failure is due to 403 (ban/suspension)
                val is403 = r?.code == 403
                if (is403) {
                    Log.e("WebSocketManager", "WebSocket failed with 403 - account may be restricted")
                    
                    // Try to parse response to check if it's a permanent ban
                    val responseBody = r?.peekBody(Long.MAX_VALUE)?.string()
                    val isPermanentBan = responseBody?.contains("permanently banned", ignoreCase = true) == true ||
                                        responseBody?.contains("permanent ban", ignoreCase = true) == true
                    
                    if (isPermanentBan) {
                        Log.e("WebSocketManager", "Permanent ban detected - clearing session and stopping reconnects")
                        // Clear session for permanent ban
                        val prefs = appContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                        prefs.edit().clear().apply()
                    } else {
                        Log.e("WebSocketManager", "Temporary restriction or suspension - will keep trying to reconnect")
                    }
                }

                // If connect() is still waiting, resume it with exception (exactly once)
                try {
                    if (continuation != null && resumed?.compareAndSet(false, true) == true) {
                        continuation.resumeWith(Result.failure(t))
                    }
                } catch (e: Exception) {
                    Log.w("WebSocketManager", "continuation resume onFailure failed", e)
                }

                // Clean up
                forceCloseSocket()
                readyState = ReadyState.NOT_READY
                
                // Always schedule reconnect - even for 403
                // This allows reconnection after temporary suspensions end
                // For permanent bans, session is cleared so reconnect will fail at auth check
                scheduleReconnect(++reconnectAttempt)
                Log.e("WebSocketManager", "Scheduled reconnect attempt #${reconnectAttempt}")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.w("WebSocketManager", "WebSocket CLOSED: $code $reason")
                
                // Check if closure is due to authentication/authorization
                val isAuthFailure = code == 1008 || reason.contains("403", ignoreCase = true) ||
                                   reason.contains("banned", ignoreCase = true) ||
                                   reason.contains("suspended", ignoreCase = true)
                
                if (isAuthFailure) {
                    Log.e("WebSocketManager", "WebSocket closed due to auth/ban/suspension")
                    
                    // Check if it's a permanent ban
                    val isPermanentBan = reason.contains("permanently banned", ignoreCase = true) ||
                                        reason.contains("permanent ban", ignoreCase = true)
                    
                    if (isPermanentBan) {
                        Log.e("WebSocketManager", "Permanent ban detected - clearing session")
                        val prefs = appContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                        prefs.edit().clear().apply()
                    } else {
                        Log.e("WebSocketManager", "Temporary restriction - will keep trying to reconnect")
                    }
                }

                // If connect() is still waiting, signal closed as failure
                try {
                    if (continuation != null && resumed?.compareAndSet(false, true) == true) {
                        continuation.resumeWith(
                                Result.failure(
                                        IllegalStateException("WebSocket closed: $code $reason")
                                )
                        )
                    }
                } catch (e: Exception) {
                    Log.w("WebSocketManager", "continuation resume onClosed failed", e)
                }

                forceCloseSocket()
                readyState = ReadyState.NOT_READY
                
                // Always schedule reconnect - even for auth failures
                // This allows reconnection after temporary suspensions end
                // For permanent bans, session is cleared so reconnect will fail at auth check
                scheduleReconnect(++reconnectAttempt)
                Log.e("WebSocketManager", "Scheduled reconnect attempt #${reconnectAttempt}")
            }
        }
    }

    private fun sendMessageReceivedAck(messageId: String, senderGmail: String) {
        if (readyState != ReadyState.READY) return

        val payload =
                JSONObject()
                        .put("buildVersion", getBuildVersion()).put("type", "message_received_ack")
                        .put("messageId", messageId)
                        .put("to", senderGmail) // 👈 original sender
                        .toString()

        try {
            webSocket?.send(payload)
            Log.i("WebSocketManager", "message_received_ack sent for $messageId to=$senderGmail")
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Failed to send message_received_ack", e)
        }
    }

    fun sendMessageReadAck(messageId: String, senderGmail: String) {
        if (readyState != ReadyState.READY) return

        val payload =
                JSONObject()
                        .apply {
                            put("buildVersion", getBuildVersion()).put("type", "message_read_ack")
                            put("messageId", messageId)
                            put("to", senderGmail)
                        }
                        .toString()

        try {
            webSocket?.send(payload)
            Log.i("WebSocketManager", "message_read_ack sent for $messageId to=$senderGmail")
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Read Ack send failed", e)
        }
    }

    /**
     * Handle incoming message in coroutine context (IO), parse and emit to UI on Main.immediate.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun handleIncoming(ws: WebSocket, text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            val messageId = json.optString("id", json.optString("messageId"))

            if (type == "error") {
                val message = json.optString("message", "")
                if (message.contains("version too old", ignoreCase = true) || message.contains("build version", ignoreCase = true) || message.contains("Minimum required", ignoreCase = true)) {
                    Log.e("WebSocketManager", "Received client build version error: $message")
                    
                    try {
                        appContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                        forceCloseSocket()
                    } catch (e: Exception) {
                        Log.e("WebSocketManager", "Error clearing session on version error", e)
                    }
                    
                    _events.emit(WebSocketEvent.AppUpdateRequired(message))
                    return
                }
            }

            when (type) {
                "ready_ack" -> {
                    Log.i("WebSocketManager", "ready_ack received → READY")
                    readyState = ReadyState.READY
                    // On ready, reset any IN_FLIGHT messages to QUEUED so they can be retried
                    startHeartbeat()
                    for (msg in pendingQueue.toList()) {
                        if (msg.state == MessageState.IN_FLIGHT) {
                            msg.state = MessageState.QUEUED
                            msg.lastSentAt = 0
                            try {
                                dao.upsert(msg.toEntity())
                            } catch (e: Exception) {
                                Log.e(
                                        "WebSocketManager",
                                        "Failed to persist reset of in-flight on ready",
                                        e
                                )
                            }
                        }
                    }
                    // send presence if needed
                    sendOnlinePresenceIfReady()
                    // flush pending outgoing
                    flushPendingQueue()
                }
                "media" -> {
                    val localEmail = ws.request().url.queryParameter("gmail") ?: ""
                    val mediaId = json.getString("mediaId")
                    val rawUrl = json.getString("url")
                    val url = ensureAbsoluteUrl(rawUrl)
                    val contentType = json.optString("contentType") // image/jpeg, audio/mpeg, etc.

                    val chatMessage =
                            when {
                                contentType.startsWith("audio") -> {
                                    ChatMessage(
                                            id = messageId,
                                            from = json.getString("from"),
                                            to = localEmail,
                                            text = "[Audio Message]",
                                            audioUri = url,
                                            amplitudes =
                                                    json.optJSONArray("amplitudes")?.let { arr ->
                                                        List(arr.length()) { i ->
                                                            arr.getDouble(i).toFloat()
                                                        }
                                                    }
                                                            ?: emptyList(),
                                            mediaId = mediaId,
                                            timestamp =
                                                    json.optLong(
                                                            "timestamp",
                                                            System.currentTimeMillis()
                                                    )
                                    )
                                }
                                contentType == "image/gif" -> {
                                    ChatMessage(
                                            id = messageId,
                                            from = json.getString("from"),
                                            to = localEmail,
                                            text = "[GIF]",
                                            mediaUri = url,
                                            mediaType = "GIF",
                                            mediaId = mediaId,
                                            timestamp =
                                                    json.optLong(
                                                            "timestamp",
                                                            System.currentTimeMillis()
                                                    )
                                    )
                                }
                                contentType.startsWith("image") -> {
                                    ChatMessage(
                                            id = messageId,
                                            from = json.getString("from"),
                                            to = localEmail,
                                            text = "[Image]",
                                            mediaUri = url,
                                            mediaType = "IMAGE",
                                            mediaId = mediaId,
                                            timestamp =
                                                    json.optLong(
                                                            "timestamp",
                                                            System.currentTimeMillis()
                                                    )
                                    )
                                }
                                contentType.startsWith("video") -> {
                                    ChatMessage(
                                            id = messageId,
                                            from = json.getString("from"),
                                            to = localEmail,
                                            text = "[Video]",
                                            mediaUri = url,
                                            mediaType = "VIDEO",
                                            mediaId = mediaId,
                                            timestamp =
                                                    json.optLong(
                                                            "timestamp",
                                                            System.currentTimeMillis()
                                                    )
                                    )
                                }
                                else -> {
                                    ChatMessage(
                                            id = messageId,
                                            from = json.getString("from"),
                                            to = localEmail,
                                            text = url,
                                            mediaId = mediaId,
                                            timestamp =
                                                    json.optLong(
                                                            "timestamp",
                                                            System.currentTimeMillis()
                                                    )
                                    )
                                }
                            }

                    saveToHistory(chatMessage)
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.NewMessage(chatMessage))
                    }
                    
                    // Trigger notification if appropriate
                    if (shouldShowNotification(chatMessage.from)) {
                        Log.e("WebSocketManager", "NOTIFICATION TRIGGER: media_message from ${chatMessage.from}, contentType=$contentType, text=${chatMessage.text}")
                        scope.launch {
                            triggerNotification(chatMessage)
                        }
                    } else {
                        Log.e("WebSocketManager", "NOTIFICATION SKIPPED: media_message from ${chatMessage.from} (shouldShowNotification=false)")
                    }

                    val shouldAutoDownload =
                            contentType.startsWith("audio") || contentType == "image/gif"

                    if (shouldAutoDownload) {
                        val downloadingMsg = chatMessage.copy(isDownloading = true)
                        saveToHistory(downloadingMsg)
                        withContext(Dispatchers.Main.immediate) {
                            _events.emit(WebSocketEvent.NewMessage(downloadingMsg))
                        }
                        scope.launch {
                            sendMessageReceivedAck(messageId, json.getString("from"))
                            downloadMediaAndAck(url, mediaId, downloadingMsg)
                        }
                    } else {
                        // Image/Video: Ack immediately, download will be manual via UI
                        sendMessageReceivedAck(messageId, json.getString("from"))
                    }
                }
                "chat_message" -> {
                    val localEmail = ws.request().url.queryParameter("gmail") ?: ""
                    Log.i("WebSocketManager", "chat_message received: $json")
                    
                    // Extract and store userId if present
                    val fromEmail = json.getString("from")
                    val fromUserId = json.optString("fromUserId")
                    if (fromUserId.isNotEmpty()) {
                        UserRepository.storeUserId(fromEmail, fromUserId)
                        Log.d("WebSocketManager", "Stored userId for $fromEmail: $fromUserId")
                    }
                    
                    val chatMessage =
                            ChatMessage(
                                    id = messageId,
                                    from = fromEmail,
                                    to = localEmail,
                                    text = json.getString("content"),
                                    timestamp =
                                            json.optLong("timestamp", System.currentTimeMillis())
                            )

                    // chat_message received:
                    // {"type":"chat_message","to":"b18559d0aa9a352c:6fb7ba6e-6936-ee64-b93b-9090b139838e@email.com","from":"ws_user1@example.com","messageId":"147575eb-9672-469a-a7de-a0960d74e963","id":"147575eb-9672-469a-a7de-a0960d74e963","fromUserId":"ik1a42oq87hhrsfi8hdz3q:atr7uticpcv","originInstance":"chat-8"}

                    // Emit on Main.immediate so UI receives it promptly
                    saveToHistory(chatMessage)
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.NewMessage(chatMessage))
                    }
                    
                    // Trigger notification if appropriate
                    if (shouldShowNotification(chatMessage.from)) {
                        Log.e("WebSocketManager", "NOTIFICATION TRIGGER: chat_message from ${chatMessage.from}, text=${chatMessage.text}")
                        scope.launch {
                            triggerNotification(chatMessage)
                        }
                    } else {
                        Log.e("WebSocketManager", "NOTIFICATION SKIPPED: chat_message from ${chatMessage.from} (shouldShowNotification=false)")
                    }
                    
                    sendMessageReceivedAck(messageId, json.getString("from"))
                }
                "message_delivered" -> {
                    Log.e("WebSocketManager", "message_delivered received for $messageId")

                    // This means: peer has RECEIVED the message
                    // UI should show delivered (double tick, etc.)
                    // Only update if not already Read (prevent downgrade)
                    scope.launch {
                        historyDao.updateStatusIfNot(
                                messageId,
                                com.example.anonychat.ui.MessageStatus.Delivered.name,
                                com.example.anonychat.ui.MessageStatus.Read.name
                        )
                    }
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.DeliveryAck(messageId))
                    }
                }
                "message_read_ack" -> {
                    Log.e("WebSocketManager", "message_read_ack received for $messageId")
                    scope.launch {
                        historyDao.updateStatus(
                                messageId,
                                com.example.anonychat.ui.MessageStatus.Read.name
                        )
                    }
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.MessageReadAck(messageId))
                    }
                }
                "delivery_ack" -> {
                    // Message successfully delivered: remove from queue & DB, emit ack event
                    Log.i("WebSocketManager", "delivery_ack for $messageId")
                    removeFromQueue(messageId)
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.MessageSentAck(messageId))
                    }
                }
                "pong" -> {
                    Log.d("WebSocketManager", "pong received")
                    lastServerMessageAt = System.currentTimeMillis()
                    lastPingSentAt = 0 // clear ping timeout — pong received within window
                    return
                }
                "delivery_failed" -> {
                    Log.w("WebSocketManager", "delivery_failed for $messageId")
                    // If server says delivery_failed, we retry (increment retries) or mark as
                    // permanently failed
                    val reason = json.optString("error", "server_failed")
                    val pm = pendingQueue.toList().find { it.messageId == messageId }
                    if (pm != null) {
                        pm.retries++
                        if (pm.retries > MAX_RETRIES) {
                            // give up
                            try {
                                dao.delete(pm.messageId)
                            } catch (e: Exception) {
                                Log.e("WebSocketManager", "Failed to delete failed message", e)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                pendingQueue.removeIf { it.messageId == pm.messageId }
                            } else {
                                val iter = pendingQueue.iterator()
                                while (iter.hasNext()) { if (iter.next().messageId == pm.messageId) iter.remove() }
                            }
                            withContext(Dispatchers.Main.immediate) {
                                _events.emit(WebSocketEvent.DeliveryFailed(pm.messageId, reason))
                            }
                        } else {
                            pm.state = MessageState.QUEUED
                            pm.lastSentAt = 0
                            try {
                                dao.upsert(pm.toEntity())
                            } catch (e: Exception) {
                                Log.e(
                                        "WebSocketManager",
                                        "Failed to persist retry after delivery_failed",
                                        e
                                )
                            }
                        }
                    } else {
                        Log.w(
                                "WebSocketManager",
                                "delivery_failed for unknown messageId: $messageId"
                        )
                    }
                }
                "peer_presence" -> {
                    val from = json.getString("from")
                    val status = json.getString("status")
                    val lastSeen = json.optLong("lastSeen", 0L)
                    Log.i(
                            "WebSocketManager",
                            "peer_presence: $from is $status (lastSeen: $lastSeen)"
                    )
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.PeerPresence(from, status, lastSeen))
                    }
                }
                "chat_open" -> {
                    val from = json.getString("from")
                    Log.e("WebSocketManager", "!!! RECEIVED chat_open from: $from !!!")
                    activeChatSessions.add(from)
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.ChatOpen(from))
                    }
                }
                "chat_close" -> {
                    val from = json.getString("from")
                    Log.e("WebSocketManager", "!!! RECEIVED chat_close from: $from !!!")
                    activeChatSessions.remove(from)
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.ChatClose(from))
                    }
                }
                "spark_left" -> {
                    val from = json.getString("from")
                    val senderUserId = json.getString("senderUserId")
                    
                    // Store userId mapping
                    UserRepository.storeUserId(from, senderUserId)
                    Log.d("WebSocketManager", "Stored userId for $from: $senderUserId (spark_left)")
                    
                    Log.e("WebSocketManager", "!!! RECEIVED spark_left from: $from (senderUserId: $senderUserId) !!!")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.SparkLeft(from, senderUserId))
                    }
                }
                "spark_right" -> {
                    val from = json.getString("from")
                    val senderUserId = json.getString("senderUserId")
                    
                    // Store userId mapping
                    UserRepository.storeUserId(from, senderUserId)
                    Log.d("WebSocketManager", "Stored userId for $from: $senderUserId (spark_right)")
                    
                    Log.e("WebSocketManager", "!!! RECEIVED spark_right from: $from (senderUserId: $senderUserId) !!!")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.SparkRight(from, senderUserId))
                    }
                }
                "spark_trigger" -> {
                    val from = json.getString("from")
                    Log.e("WebSocketManager", "!!! RECEIVED spark_trigger from: $from !!!")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.SparkTrigger(from))
                    }
                }
                "spark_start" -> {
                    val from = json.getString("from")
                    Log.e("WebSocketManager", "!!! RECEIVED spark_start from: $from !!!")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.SparkStart(from))
                    }
                }
                "spark_received_ack" -> {
                    val from = json.getString("from")
                    Log.e("WebSocketManager", "!!! RECEIVED spark_received_ack from: $from !!!")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.SparkReceivedAck(from))
                    }
                }
                "rose_gifted" -> {
                    val from = json.getString("from")
                    Log.e("WebSocketManager", "!!! RECEIVED rose_gifted from: $from !!!")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.RoseGifted(from))
                    }
                }
                "rose_taken_back" -> {
                    val from = json.getString("from")
                    Log.e("WebSocketManager", "!!! RECEIVED rose_taken_back from: $from !!!")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.RoseTakenBack(from))
                    }
                }
                "warning_notification" -> {
                    val message = json.getString("message")
                    Log.e("WebSocketManager", "!!! RECEIVED warning_notification: $message !!!")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.WarningNotification(message))
                    }
                }
                "suspension_notification" -> {
                    val message = json.getString("message")
                    val remainingMinutes = json.optInt("remainingMinutes", 0)
                    Log.e("WebSocketManager", "!!! RECEIVED suspension_notification: $message (remaining: $remainingMinutes min) !!!")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.SuspensionNotification(message, remainingMinutes))
                    }
                }
                "ban_notification" -> {
                    val message = json.getString("message")
                    Log.e("WebSocketManager", "!!! RECEIVED ban_notification: $message !!!")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.BanNotification(message))
                    }
                }
                "match_found" -> {
                    Log.i("WebSocketManager", "match_found received")
                    val matchJson = json.optJSONObject("match") ?: json
                    
                    // Extract and store userId if present
                    val matchEmail = matchJson.optString("gmail")
                    val matchUserId = matchJson.optString("userId")
                    
                    Log.d("WebSocketManager", "match_found - email: $matchEmail, userId from JSON: '$matchUserId'")
                    
                    if (matchEmail.isNotEmpty() && matchUserId.isNotEmpty()) {
                        UserRepository.storeUserId(matchEmail, matchUserId)
                        Log.d("WebSocketManager", "✅ Stored userId for $matchEmail: $matchUserId (match_found)")
                    } else {
                        Log.w("WebSocketManager", "⚠️ Cannot store userId - email: ${matchEmail.isNotEmpty()}, userId: ${matchUserId.isNotEmpty()}")
                    }
                    
                    val match = com.example.anonychat.network.MatchResponse(
                        username = matchJson.optString("username"),
                        gmail = matchEmail,
                        age = matchJson.optInt("age"),
                        gender = matchJson.optString("gender"),
                        preferredGender = matchJson.optString("preferredGender") ?: matchJson.optString("preferred_gender"),
                        romanceRange = matchJson.optJSONObject("romanceRange")?.let {
                            com.example.anonychat.network.RomanceRange(it.getInt("min"), it.getInt("max"))
                        },
                        random = matchJson.optBoolean("random"),
                        preferredAgeRange = matchJson.optJSONObject("preferredAgeRange")?.let {
                            com.example.anonychat.network.AgeRange(it.getInt("min"), it.getInt("max"))
                        },
                        pickBestflag = matchJson.optBoolean("pickBestflag"),
                        isOnline = matchJson.optBoolean("isOnline"),
                        lastOnline = matchJson.optLong("lastOnline"),
                        match = matchJson.optString("match"),
                        giftedMeARose = matchJson.optBoolean("giftedMeARose"),
                        hasTakenBackRose = matchJson.optBoolean("hasTakenBackRose")
                    )
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.MatchFound(match))
                    }
                }
                "match_error", "find_match_error" -> {
                    val rawError = json.optString("error", json.optString("message", "Match error"))
                    val rawMessage = json.optString("message", null) // Store raw message
                    Log.e("WebSocketManager", "match_error received: $rawError")
                    
                    val userFriendlyError = if (rawError == "User suspended") {
                        val durationParts = rawMessage?.split(", ") ?: emptyList()
                        val durationStr = if (durationParts.size > 1) {
                            durationParts[1].replace(" remaining", "").trim()
                        } else {
                            ""
                        }
                        val pauseText = if (durationStr.isNotEmpty()) "for $durationStr" else "temporarily"
                        "Matchmaking paused $pauseText. Your rating has been low for a long time. Chat with existing matches to improve it before finding new ones."
                    } else {
                        rawError
                    }
                    
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.MatchError(userFriendlyError, rawMessage, rawError))
                    }
                }
                "skip_user_success" -> {
                    val message = json.optString("message", "User skipped successfully")
                    Log.i("WebSocketManager", "skip_user_success received")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.SkipUserSuccess(message))
                    }
                }
                "skip_user_error" -> {
                    val error = json.optString("error", json.optString("message", "Skip user error"))
                    Log.e("WebSocketManager", "skip_user_error received: $error")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.SkipUserError(error))
                    }
                }
                "accept_user_success" -> {
                    val message = json.optString("message", "User accepted successfully")
                    Log.i("WebSocketManager", "accept_user_success received")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.AcceptUserSuccess(message))
                    }
                }
                "accept_user_error" -> {
                    val error = json.optString("error", json.optString("message", "Accept user error"))
                    Log.e("WebSocketManager", "accept_user_error received: $error")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.AcceptUserError(error))
                    }
                }
                "delete_all_matches_success" -> {
                    val message = json.optString("message", "All matches deleted successfully")
                    val deleted = json.optBoolean("deleted", false)
                    Log.i("WebSocketManager", "delete_all_matches_success received")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.DeleteAllMatchesSuccess(message, deleted))
                    }
                }
                "delete_all_matches_error" -> {
                    val error = json.optString("error", json.optString("message", "Delete all matches error"))
                    Log.e("WebSocketManager", "delete_all_matches_error received: $error")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.DeleteAllMatchesError(error))
                    }
                }
                "preferences_data" -> {
                    Log.i("WebSocketManager", "preferences_data received")
                    val prefsJson = json.optJSONObject("preferences") ?: json
                    val preferences = com.example.anonychat.network.GetPreferencesResponse(
                        username = prefsJson.optString("username"),
                        gmail = prefsJson.optString("gmail"),
                        age = prefsJson.optInt("age"),
                        gender = prefsJson.optString("gender"),
                        preferredGender = prefsJson.optString("preferredGender"),
                        romanceRange = prefsJson.optJSONObject("romanceRange")?.let {
                            com.example.anonychat.network.RomanceRange(it.getInt("min"), it.getInt("max"))
                        },
                        preferredAgeRange = prefsJson.optJSONObject("preferredAgeRange")?.let {
                            com.example.anonychat.network.AgeRange(it.getInt("min"), it.getInt("max"))
                        },
                        random = prefsJson.optBoolean("random"),
                        isOnline = prefsJson.optBoolean("isOnline"),
                        lastOnline = prefsJson.optLong("lastOnline"),
                        sparks = prefsJson.optInt("sparks"),
                        totalRosesReceived = prefsJson.optInt("totalRosesReceived"),
                        availableRoses = prefsJson.optInt("availableRoses")
                    )
                    
                    // If we successfully got preferences for a user, mark them as active (not deactivated)
                    val userEmail = preferences.gmail
                    if (!userEmail.isNullOrEmpty() && com.example.anonychat.utils.DeactivatedUsersManager.isDeactivated(userEmail)) {
                        com.example.anonychat.utils.DeactivatedUsersManager.markAsActive(userEmail)
                        Log.d("WebSocketManager", "User reactivated: $userEmail")
                    }
                    
                    // Clear the tracked email after successful response
                    lastRequestedPreferencesEmail = null
                    
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.PreferencesData(preferences, userEmail = preferences.gmail))
                    }
                }
                "preferences_error", "get_preferences_error" -> {
                    val error = json.optString("error", json.optString("message", "Preferences error"))
                    // Try to get email from response, fallback to last requested email
                    var targetEmail = json.optString("email", json.optString("targetGmail", json.optString("gmail")))
                    if (targetEmail.isEmpty()) {
                        targetEmail = lastRequestedPreferencesEmail ?: ""
                    }
                    
                    // Only log as error if it's not the expected "User preferences not found" message
                    if (error != "User preferences not found") {
                        Log.e("WebSocketManager", "preferences_error received: $error")
                    } else {
                        Log.d("WebSocketManager", "preferences_error received: $error (no preferences = show as deactivated)${if (targetEmail.isNotEmpty()) " - email: $targetEmail" else " - no email tracked"}")
                        
                        // Mark user as deactivated if we have their email
                        // This is intentional: without preferences, profile pic won't render, so show as deactivated
                        if (targetEmail.isNotEmpty() && error == "User preferences not found") {
                            com.example.anonychat.utils.DeactivatedUsersManager.markAsDeactivated(targetEmail)
                            Log.d("WebSocketManager", "Marked user as deactivated: $targetEmail")
                        } else if (targetEmail.isEmpty()) {
                            Log.w("WebSocketManager", "Cannot mark user as deactivated - no email available")
                        }
                    }
                    
                    // Clear the tracked email after handling
                    lastRequestedPreferencesEmail = null
                    
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.PreferencesError(error, targetEmail.takeIf { it.isNotEmpty() }))
                    }
                }
                "update_preferences_success" -> {
                    val message = json.optString("message", "Preferences updated successfully")
                    Log.i("WebSocketManager", "update_preferences_success received")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.UpdatePreferencesSuccess(message))
                    }
                }
                "update_preferences_error" -> {
                    val error = json.optString("error", json.optString("message", "Update preferences error"))
                    Log.e("WebSocketManager", "update_preferences_error received: $error")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.UpdatePreferencesError(error))
                    }
                }
                "toggle_pick_best_success" -> {
                    val enabled = json.optBoolean("enabled", false)
                    Log.i("WebSocketManager", "toggle_pick_best_success received: enabled=$enabled")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.TogglePickBestSuccess(enabled))
                    }
                }
                "toggle_pick_best_error" -> {
                    val error = json.optString("error", json.optString("message", "Toggle pick best error"))
                    Log.e("WebSocketManager", "toggle_pick_best_error received: $error")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.TogglePickBestError(error))
                    }
                }
                "add_rating_success" -> {
                    val message = json.optString("message", "Rating added successfully")
                    Log.i("WebSocketManager", "add_rating_success received")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.AddRatingSuccess(message))
                    }
                }
                "add_rating_error" -> {
                    val error = json.optString("error", json.optString("message", "Add rating error"))
                    Log.e("WebSocketManager", "add_rating_error received: $error")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.AddRatingError(error))
                    }
                }
                "average_rating_data" -> {
                    Log.i("WebSocketManager", "average_rating_data received")
                    val avgRating = if (json.has("avgRating") && !json.isNull("avgRating")) json.getDouble("avgRating").toFloat() else null
                    val count = if (json.has("count") && !json.isNull("count")) json.getInt("count") else null
                    // Server now provides userEmail in the response
                    val userEmail = json.optString("userEmail", json.optString("toParam", ""))
                    
                    Log.d("WebSocketManager", "Rating received for user: $userEmail, rating: $avgRating")
                    
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.AverageRatingData(avgRating, count, userEmail.ifBlank { null }))
                    }
                }
                "average_rating_error", "get_average_rating_error" -> {
                    val error = json.optString("error", json.optString("message", "Average rating error"))
                    val userEmail = json.optString("userEmail", json.optString("toParam", ""))
                    Log.e("WebSocketManager", "average_rating_error received for user: $userEmail, error: $error")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.AverageRatingError(error, userEmail.ifBlank { null }))
                    }
                }
                "ratings_data" -> {
                    Log.i("WebSocketManager", "ratings_data received")
                    val ratingsArray = json.optJSONArray("ratings") ?: org.json.JSONArray()
                    val ratings = mutableListOf<com.example.anonychat.network.Rating>()
                    for (i in 0 until ratingsArray.length()) {
                        val ratingJson = ratingsArray.getJSONObject(i)
                        ratings.add(com.example.anonychat.network.Rating(
                            fromusername = ratingJson.optString("fromusername"),
                            fromgender = ratingJson.optString("fromgender"),
                            rating = ratingJson.optInt("rating"),
                            comment = ratingJson.optString("comment"),
                            createdAt = ratingJson.optString("createdAt"),
                            romanceRange = ratingJson.optJSONObject("romanceRange")?.let {
                                com.example.anonychat.network.RomanceRange(it.getInt("min"), it.getInt("max"))
                            },
                            random = ratingJson.optBoolean("random")
                        ))
                    }
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.RatingsData(ratings))
                    }
                }
                "ratings_error", "get_ratings_for_user_error" -> {
                    val error = json.optString("error", json.optString("message", "Ratings error"))
                    Log.e("WebSocketManager", "ratings_error received: $error")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.RatingsError(error))
                    }
                }
                "specific_rating_data" -> {
                    Log.i("WebSocketManager", "specific_rating_data received")
                    val ratingJson = json.optJSONObject("rating")
                    val rating = ratingJson?.let {
                        com.example.anonychat.network.Rating(
                            fromusername = it.optString("fromusername"),
                            fromgender = it.optString("fromgender"),
                            rating = it.optInt("rating"),
                            comment = it.optString("comment"),
                            createdAt = it.optString("createdAt"),
                            romanceRange = it.optJSONObject("romanceRange")?.let { range ->
                                com.example.anonychat.network.RomanceRange(range.getInt("min"), range.getInt("max"))
                            },
                            random = it.optBoolean("random")
                        )
                    }
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.SpecificRatingData(rating))
                    }
                }
                "specific_rating_error", "get_specific_rating_error" -> {
                    val error = json.optString("error", json.optString("message", "Specific rating error"))
                    Log.e("WebSocketManager", "specific_rating_error received: $error")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.SpecificRatingError(error))
                    }
                }
                "get_blocked_list_success" -> {
                    Log.e("WebSocketManager", "=== GET BLOCKED LIST SUCCESS ===")
                    Log.e("WebSocketManager", "Raw response: $text")
                    val blockedUsersArray = json.optJSONArray("blocked_users") ?: org.json.JSONArray()
                    Log.e("WebSocketManager", "Blocked users array length: ${blockedUsersArray.length()}")
                    val blockedUsers = mutableListOf<BlockedUser>()
                    for (i in 0 until blockedUsersArray.length()) {
                        val userJson = blockedUsersArray.getJSONObject(i)
                        val romanceRange = userJson.optJSONObject("romance_range")
                        val romanceMin = romanceRange?.optInt("min", 1)?.toFloat() ?: 1f
                        val romanceMax = romanceRange?.optInt("max", 5)?.toFloat() ?: 5f
                        val user = BlockedUser(
                            email = userJson.optString("email", ""),
                            username = userJson.optString("username", ""),
                            profilePicture = userJson.optString("profilePicture").takeIf { it.isNotEmpty() },
                            gender = userJson.optString("gender", "male"),
                            romanceMin = romanceMin,
                            romanceMax = romanceMax
                        )
                        blockedUsers.add(user)
                        Log.e("WebSocketManager", "  Blocked user $i: ${user.username} (${user.email}), gender: ${user.gender}, romance: ${user.romanceMin}-${user.romanceMax}")
                    }
                    Log.e("WebSocketManager", "Emitting BlockedListData event with ${blockedUsers.size} users")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.BlockedListData(blockedUsers))
                    }
                }
                "get_blocked_list_error" -> {
                    val error = json.optString("error", json.optString("message", "Failed to get blocked list"))
                    Log.e("WebSocketManager", "=== GET BLOCKED LIST ERROR ===")
                    Log.e("WebSocketManager", "Raw response: $text")
                    Log.e("WebSocketManager", "Error message: $error")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.BlockedListError(error))
                    }
                }
                "unblock_user_success" -> {
                    val message = json.optString("message", "User unblocked successfully")
                    val unblockedEmail = json.optString("unblockedEmail", "")
                    Log.i("WebSocketManager", "unblock_user_success received: $message")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.UnblockUserSuccess(message, unblockedEmail))
                    }
                }
                "unblock_user_error" -> {
                    val error = json.optString("error", json.optString("message", "Failed to unblock user"))
                    Log.e("WebSocketManager", "unblock_user_error received: $error")
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.UnblockUserError(error))
                    }
                }
                else -> {
                    // Unknown type — ignore or log
                    Log.w("WebSocketManager", "Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Parse error in handleIncoming", e)
        }
    }

    /* ---------------------------------------------------
       HEARTBEAT
    --------------------------------------------------- */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob =
                scope.launch {
                    while (isActive) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        try {
                            val sent = webSocket?.send(JSONObject().put("buildVersion", getBuildVersion()).put("type", "ping").toString()) ?: false
                            if (sent) {
                                lastPingSentAt = System.currentTimeMillis()
                                Log.d("WebSocketManager", "ping sent, awaiting pong within ${PING_PONG_TIMEOUT_MS}ms")
                            }
                        } catch (e: Exception) {
                            Log.e("WebSocketManager", "heartbeat send failed", e)
                        }
                    }
                }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /* ---------------------------------------------------
       SEND MESSAGE API
    --------------------------------------------------- */
    @RequiresApi(Build.VERSION_CODES.N)
    fun sendChatOpen(peerGmail: String) {
        ensureInit()

        val messageId = "chat_open_${System.currentTimeMillis()}"
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "chat_open")
            .put("with", peerGmail)
            .put("messageId", messageId)
            .toString()
        
        Log.e("WebSocketManager", "Sending chat_open to: $peerGmail")

        // Send directly without queuing - chat_open is a presence signal, not a critical message
        // No need to persist or wait for ack
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payload) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "chat_open sent successfully to $peerGmail")
                    } else {
                        Log.w("WebSocketManager", "Failed to send chat_open to $peerGmail")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send chat_open - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending chat_open", e)
            }
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.N)
    fun sendChatClose(peerGmail: String) {
        ensureInit()

        val messageId = "chat_close_${System.currentTimeMillis()}"
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "chat_close")
            .put("with", peerGmail)
            .put("messageId", messageId)
            .toString()
        
        Log.e("WebSocketManager", "Sending chat_close to: $peerGmail")

        // Send directly without queuing - chat_close is a presence signal, not a critical message
        // No need to persist or wait for ack
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payload) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "chat_close sent successfully to $peerGmail")
                    } else {
                        Log.w("WebSocketManager", "Failed to send chat_close to $peerGmail")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send chat_close - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending chat_close", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun sendSparkLeft(peerGmail: String, senderUserId: String) {
        ensureInit()

        val messageId = "spark_left_${System.currentTimeMillis()}"
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "spark_left")
            .put("to", peerGmail)
            .put("senderUserId", senderUserId)
            .put("messageId", messageId)
            .toString()
        
        Log.e("WebSocketManager", "Sending spark_left to: $peerGmail (from: $senderUserId)")

        // Send directly without queuing - spark events are presence signals
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payload) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "spark_left sent successfully to $peerGmail")
                    } else {
                        Log.w("WebSocketManager", "Failed to send spark_left to $peerGmail")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send spark_left - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending spark_left", e)
            }
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.N)
    fun sendSparkRight(peerGmail: String, senderUserId: String) {
        ensureInit()

        val messageId = "spark_right_${System.currentTimeMillis()}"
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "spark_right")
            .put("to", peerGmail)
            .put("senderUserId", senderUserId)
            .put("messageId", messageId)
            .toString()
        
        Log.e("WebSocketManager", "Sending spark_right to: $peerGmail (from: $senderUserId)")

        // Send directly without queuing - spark events are presence signals
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payload) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "spark_right sent successfully to $peerGmail")
                    } else {
                        Log.w("WebSocketManager", "Failed to send spark_right to $peerGmail")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send spark_right - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending spark_right", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun sendSparkTrigger(peerGmail: String, senderGmail: String) {
        ensureInit()
        val messageId = "spark_trigger_${System.currentTimeMillis()}"
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "spark_trigger")
            .put("to", peerGmail)
            .put("from", senderGmail)
            .put("messageId", messageId)
            .toString()
        Log.e("WebSocketManager", "Sending spark_trigger to: $peerGmail (from: $senderGmail)")
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payload) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "spark_trigger sent successfully to $peerGmail")
                    } else {
                        Log.w("WebSocketManager", "Failed to send spark_trigger to $peerGmail")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send spark_trigger - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending spark_trigger", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun sendSparkStart(peerGmail: String, senderGmail: String) {
        ensureInit()
        val messageId = "spark_start_${System.currentTimeMillis()}"
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "spark_start")
            .put("to", peerGmail)
            .put("from", senderGmail)
            .put("messageId", messageId)
            .toString()
        Log.e("WebSocketManager", "Sending spark_start to: $peerGmail (from: $senderGmail)")
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payload) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "spark_start sent successfully to $peerGmail")
                    } else {
                        Log.w("WebSocketManager", "Failed to send spark_start to $peerGmail")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send spark_start - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending spark_start", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun sendSparkReceivedAck(peerGmail: String, senderGmail: String) {
        ensureInit()
        val messageId = "spark_received_ack_${System.currentTimeMillis()}"
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "spark_received_ack")
            .put("to", peerGmail)
            .put("from", senderGmail)
            .put("messageId", messageId)
            .toString()
        Log.e("WebSocketManager", "Sending spark_received_ack to: $peerGmail (from: $senderGmail)")
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payload) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "spark_received_ack sent successfully to $peerGmail")
                    } else {
                        Log.w("WebSocketManager", "Failed to send spark_received_ack to $peerGmail")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send spark_received_ack - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending spark_received_ack", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun sendRoseGifted(peerGmail: String, senderGmail: String) {
        ensureInit()
        val messageId = "rose_gifted_${System.currentTimeMillis()}"
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "rose_gifted")
            .put("to", peerGmail)
            .put("from", senderGmail)
            .put("messageId", messageId)
            .toString()
        Log.e("WebSocketManager", "Sending rose_gifted to: $peerGmail (from: $senderGmail)")
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payload) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "rose_gifted sent successfully to $peerGmail")
                    } else {
                        Log.w("WebSocketManager", "Failed to send rose_gifted to $peerGmail")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send rose_gifted - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending rose_gifted", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun sendRoseTakenBack(peerGmail: String, senderGmail: String) {
        ensureInit()
        val messageId = "rose_taken_back_${System.currentTimeMillis()}"
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "rose_taken_back")
            .put("to", peerGmail)
            .put("from", senderGmail)
            .put("messageId", messageId)
            .toString()
        Log.e("WebSocketManager", "Sending rose_taken_back to: $peerGmail (from: $senderGmail)")
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payload) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "rose_taken_back sent successfully to $peerGmail")
                    } else {
                        Log.w("WebSocketManager", "Failed to send rose_taken_back to $peerGmail")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send rose_taken_back - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending rose_taken_back", e)
            }
        }
    }

    /* ---------------------------------------------------
       MATCHMAKING WEBSOCKET METHODS
    --------------------------------------------------- */
    
    /**
     * Request to find a match via WebSocket
     */
    fun sendFindMatch(token: String) {
        ensureInit()
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "find_match")
            .put("authorization", "Bearer $token")
            .toString()
        
        Log.i("WebSocketManager", "Sending find_match request")
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payload) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "find_match sent successfully")
                    } else {
                        Log.w("WebSocketManager", "Failed to send find_match")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send find_match - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending find_match", e)
            }
        }
    }
    
    /**
     * Request to skip a user via WebSocket
     */
    fun sendSkipUser(candGmail: String, token: String) {
        ensureInit()
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "skip_user")
            .put("candGmail", candGmail)
            .put("authorization", "Bearer $token")
            .toString()
        
        Log.i("WebSocketManager", "Sending skip_user request for: $candGmail")
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payload) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "skip_user sent successfully")
                    } else {
                        Log.w("WebSocketManager", "Failed to send skip_user")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send skip_user - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending skip_user", e)
            }
        }
    }
    
    /**
     * Request to accept a user via WebSocket
     */
    fun sendAcceptUser(candGmail: String, token: String) {
        ensureInit()
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "accept_user")
            .put("candGmail", candGmail)
            .put("authorization", "Bearer $token")
            .toString()
        
        Log.i("WebSocketManager", "Sending accept_user request for: $candGmail")
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payload) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "accept_user sent successfully")
                    } else {
                        Log.w("WebSocketManager", "Failed to send accept_user")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send accept_user - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending accept_user", e)
            }
        }
    }
    
    /**
     * Request to delete all matches via WebSocket
     */
    fun sendDeleteAllMatches(token: String) {
        ensureInit()
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "delete_all_matches")
            .put("authorization", "Bearer $token")
            .toString()
        
        Log.i("WebSocketManager", "Sending delete_all_matches request")
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payload) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "delete_all_matches sent successfully")
                    } else {
                        Log.w("WebSocketManager", "Failed to send delete_all_matches")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send delete_all_matches - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending delete_all_matches", e)
            }
        }
    }
    
    /* ---------------------------------------------------
       PREFERENCE WEBSOCKET METHODS
    --------------------------------------------------- */
    
    // Track the last requested email for preferences to handle errors
    private var lastRequestedPreferencesEmail: String? = null
    
    /**
     * Request to get preferences via WebSocket
     */
    fun sendGetPreferences(token: String, targetGmail: String? = null) {
        ensureInit()
        
        // Track the requested email for error handling
        lastRequestedPreferencesEmail = targetGmail
        
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "get_preferences")
            .put("authorization", "Bearer $token")
        
        targetGmail?.let {
            payload.put("targetGmail", it)
        }
        
        val payloadStr = payload.toString()
        Log.i("WebSocketManager", "Sending get_preferences request${targetGmail?.let { " for: $it" } ?: ""}")
        
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payloadStr) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "get_preferences sent successfully")
                    } else {
                        Log.w("WebSocketManager", "Failed to send get_preferences")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send get_preferences - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending get_preferences", e)
            }
        }
    }
    /**
     * Request the list of blocked users
     */
    fun sendGetBlockedList(token: String) {
        ensureInit()
        
        Log.e("WebSocketManager", "=== SEND GET BLOCKED LIST ===")
        Log.e("WebSocketManager", "Token: ${if (token.isEmpty()) "EMPTY" else "Present (${token.length} chars)"}")
        Log.e("WebSocketManager", "WebSocket ready state: $readyState")
        Log.e("WebSocketManager", "WebSocket instance: ${if (webSocket != null) "Present" else "NULL"}")
        
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "get_blocked_list")
            .put("authorization", "Bearer $token")
        
        val payloadStr = payload.toString()
        Log.e("WebSocketManager", "Payload to send: $payloadStr")
        
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payloadStr) ?: false
                    if (sent) {
                        Log.e("WebSocketManager", "✓ get_blocked_list sent successfully")
                    } else {
                        Log.e("WebSocketManager", "✗ Failed to send get_blocked_list (send returned false)")
                    }
                } else {
                    Log.e("WebSocketManager", "✗ Cannot send get_blocked_list - WebSocket not ready (state: $readyState, ws: ${webSocket != null})")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "✗ Exception sending get_blocked_list: ${e.message}", e)
            }
        }
    }
    
    /**
     * Request to unblock a user
     */
    fun sendUnblockUser(token: String, userEmail: String) {
        ensureInit()
        
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "unblock_user")
            .put("authorization", "Bearer $token")
            .put("userEmail", userEmail)
        
        val payloadStr = payload.toString()
        Log.i("WebSocketManager", "Sending unblock_user request for: $userEmail")
        
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payloadStr) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "unblock_user sent successfully")
                    } else {
                        Log.w("WebSocketManager", "Failed to send unblock_user")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send unblock_user - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending unblock_user", e)
            }
        }
    }
    
    
    /**
     * Request to update preferences via WebSocket
     */
    fun sendUpdatePreferences(
        token: String,
        username: String? = null,
        age: Int? = null,
        gender: String? = null,
        preferredGender: String? = null,
        preferredAgeRange: com.example.anonychat.network.AgeRange? = null,
        romanceRange: com.example.anonychat.network.RomanceRange? = null,
        random: Boolean? = null
    ) {
        ensureInit()
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "update_preferences")
            .put("authorization", "Bearer $token")
        
        username?.let { payload.put("username", it) }
        age?.let { payload.put("age", it) }
        gender?.let { payload.put("gender", it) }
        preferredGender?.let { payload.put("preferredGender", it) }
        preferredAgeRange?.let {
            payload.put("preferredAgeRange", JSONObject()
                .put("min", it.min)
                .put("max", it.max))
        }
        romanceRange?.let {
            payload.put("romanceRange", JSONObject()
                .put("min", it.min)
                .put("max", it.max))
        }
        random?.let { payload.put("random", it) }
        
        val payloadStr = payload.toString()
        Log.i("WebSocketManager", "Sending update_preferences request")
        
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payloadStr) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "update_preferences sent successfully")
                    } else {
                        Log.w("WebSocketManager", "Failed to send update_preferences")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send update_preferences - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending update_preferences", e)
            }
        }
    }
    
    /**
     * Request to toggle pick best feature via WebSocket
     */
    fun sendTogglePickBest(token: String, enable: Boolean) {
        ensureInit()
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "toggle_pick_best")
            .put("enable", enable)
            .put("authorization", "Bearer $token")
            .toString()
        
        Log.i("WebSocketManager", "Sending toggle_pick_best request: enable=$enable")
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payload) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "toggle_pick_best sent successfully")
                    } else {
                        Log.w("WebSocketManager", "Failed to send toggle_pick_best")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send toggle_pick_best - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending toggle_pick_best", e)
            }
        }
    }
    
    /**
     * Request to add a rating via WebSocket
     */
    fun sendAddRating(
        token: String,
        toParam: String,
        rating: Int,
        comment: String? = null,
        romanceRange: com.example.anonychat.network.RomanceRange? = null,
        random: Boolean? = null
    ) {
        ensureInit()
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "add_rating")
            .put("toParam", toParam)
            .put("rating", rating)
            .put("authorization", "Bearer $token")
        
        if (comment != null) {
            payload.put("comment", comment)
        }
        if (romanceRange != null) {
            payload.put("romanceRange", JSONObject()
                .put("min", romanceRange.min)
                .put("max", romanceRange.max))
        }
        if (random != null) {
            payload.put("random", random)
        }
        
        Log.i("WebSocketManager", "Sending add_rating request: toParam=$toParam, rating=$rating")
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payload.toString()) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "add_rating sent successfully")
                    } else {
                        Log.w("WebSocketManager", "Failed to send add_rating")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send add_rating - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending add_rating", e)
            }
        }
    }
    
    /**
     * Request to get average rating via WebSocket
     */
    fun sendGetAverageRating(token: String, toParam: String) {
        ensureInit()
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "get_average_rating")
            .put("toParam", toParam)
            .put("authorization", "Bearer $token")
            .toString()
        
        Log.i("WebSocketManager", "Sending get_average_rating request: toParam=$toParam")
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payload) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "get_average_rating sent successfully")
                    } else {
                        Log.w("WebSocketManager", "Failed to send get_average_rating")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send get_average_rating - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending get_average_rating", e)
            }
        }
    }
    
    /**
     * Request to get all ratings for a user via WebSocket
     */
    fun sendGetRatingsForUser(token: String, toParam: String) {
        ensureInit()
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "get_ratings_for_user")
            .put("toParam", toParam)
            .put("authorization", "Bearer $token")
            .toString()
        
        Log.i("WebSocketManager", "Sending get_ratings_for_user request: toParam=$toParam")
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payload) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "get_ratings_for_user sent successfully")
                    } else {
                        Log.w("WebSocketManager", "Failed to send get_ratings_for_user")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send get_ratings_for_user - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending get_ratings_for_user", e)
            }
        }
    }
    
    /**
     * Request to get a specific rating via WebSocket
     */
    fun sendGetSpecificRating(token: String, fromParam: String, toParam: String) {
        ensureInit()
        val payload = JSONObject()
            .put("buildVersion", getBuildVersion()).put("type", "get_specific_rating")
            .put("fromParam", fromParam)
            .put("toParam", toParam)
            .put("authorization", "Bearer $token")
            .toString()
        
        Log.i("WebSocketManager", "Sending get_specific_rating request: from=$fromParam, to=$toParam")
        scope.launch {
            try {
                if (readyState == ReadyState.READY && webSocket != null) {
                    val sent = webSocket?.send(payload) ?: false
                    if (sent) {
                        Log.d("WebSocketManager", "get_specific_rating sent successfully")
                    } else {
                        Log.w("WebSocketManager", "Failed to send get_specific_rating")
                    }
                } else {
                    Log.w("WebSocketManager", "Cannot send get_specific_rating - WebSocket not ready")
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception sending get_specific_rating", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun sendMessage(toEmail: String, text: String, localId: String) {
        val chatMessage =
                ChatMessage(
                        id = localId,
                        from = curruser ?: "",
                        to = toEmail,
                        text = text,
                        status = com.example.anonychat.ui.MessageStatus.Sending
                )
        saveToHistory(chatMessage)

        ensureInit()

        val payload =
                JSONObject()
                        .apply {
                            put("buildVersion", getBuildVersion()).put("type", "chat_message")
                            put("to", toEmail)
                            put("from", curruser)
                            put("content", text)
                            put("id", localId)
                            put("messageId", localId)
                            put("timestamp", chatMessage.timestamp)
                        }
                        .toString()

        val pm =
                PendingMessage(
                        localId,
                        payload,
                        0,
                        MessageState.QUEUED,
                        0L,
                        System.currentTimeMillis()
                )

        pendingQueue.add(pm)
        scope.launch {
            try {
                dao.upsert(pm.toEntity())
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Failed to persist pending message", e)
            }
        }
        flushPendingQueue()
    }

    /* ---------------------------------------------------
       FLUSH QUEUE
       Only send QUEUED messages. After ws.send succeeds we mark IN_FLIGHT and set lastSentAt.
    --------------------------------------------------- */
    private fun flushPendingQueue() {
        Log.e(
                "WebSocketManager",
                "[TRACE] flushPendingQueue() ENTRY → initialized=$initialized, readyState=$readyState, pendingQueue.size=${pendingQueue.size}"
        )

        if (!initialized || readyState != ReadyState.READY) {
            Log.e(
                    "WebSocketManager",
                    "[TRACE] flushPendingQueue() not ready → initialized=$initialized, readyState=$readyState, returning early"
            )
            return
        }

        // If there is no websocket or it isn't marked CONNECTED, try reconnecting once and abort
        // flush.
        if (webSocket == null || wsState != WsState.CONNECTED) {
            Log.e(
                    "WebSocketManager",
                    "[TRACE] flushPendingQueue() websocket not connected → wsState=$wsState, webSocket=${webSocket != null}"
            )
            Log.w(
                    "WebSocketManager",
                    "flushPendingQueue: websocket not connected → scheduling reconnect"
            )
            scheduleReconnect(++reconnectAttempt)
            Log.e(
                    "WebSocketManager",
                    "[TRACE] flushPendingQueue() reconnect scheduled, returning early"
            )
            return
        }

        val ws = webSocket!!
        Log.e(
                "WebSocketManager",
                "[TRACE] flushPendingQueue() websocket available, launching coroutine"
        )

        scope.launch {
            Log.e(
                    "WebSocketManager",
                    "[TRACE] flushPendingQueue() coroutine started, iterating ${pendingQueue.size} messages"
            )
            var processedCount = 0
            for (msg in pendingQueue.toList()) {
                Log.e(
                        "WebSocketManager",
                        "[TRACE] flushPendingQueue() processing message #${++processedCount} → messageId=${msg.messageId}, state=${msg.state}, retries=${msg.retries}"
                )

                if (msg.state != MessageState.QUEUED) {
                    Log.e(
                            "WebSocketManager",
                            "[TRACE] flushPendingQueue() skipping message ${msg.messageId} → state=${msg.state} (not QUEUED)"
                    )
                    continue
                }

                if (msg.retries >= MAX_RETRIES) {
                    Log.e(
                            "WebSocketManager",
                            "[TRACE] flushPendingQueue() message ${msg.messageId} exceeded MAX_RETRIES ($MAX_RETRIES), dropping"
                    )
                    // drop if beyond retries (safeguard)
                    try {
                        Log.e(
                                "WebSocketManager",
                                "[TRACE] flushPendingQueue() deleting expired message ${msg.messageId} from DB"
                        )
                        dao.delete(msg.messageId)
                        Log.e("WebSocketManager", "[TRACE] flushPendingQueue() deleted from DB")
                    } catch (e: Exception) {
                        Log.e(
                                "WebSocketManager",
                                "[TRACE] flushPendingQueue() EXCEPTION deleting expired message: ${e.message}",
                                e
                        )
                        Log.e("WebSocketManager", "Failed to delete expired message", e)
                    }
                    Log.e(
                            "WebSocketManager",
                            "[TRACE] flushPendingQueue() removing ${msg.messageId} from pendingQueue"
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        pendingQueue.removeIf { it.messageId == msg.messageId }
                    } else {
                        val iter = pendingQueue.iterator()
                        while (iter.hasNext()) { if (iter.next().messageId == msg.messageId) iter.remove() }
                    }
                    Log.e(
                            "WebSocketManager",
                            "[TRACE] flushPendingQueue() emitting DeliveryFailed event"
                    )
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(
                                WebSocketEvent.DeliveryFailed(msg.messageId, "max_retries_exceeded")
                        )
                    }
                    Log.e(
                            "WebSocketManager",
                            "[TRACE] flushPendingQueue() DeliveryFailed event emitted"
                    )
                    continue
                }

                Log.e(
                        "WebSocketManager",
                        "[TRACE] flushPendingQueue() attempting to send message ${msg.messageId} via WebSocket"
                )
                val ok =
                        try {
                            val result = ws.send(msg.payload)
                            Log.e(
                                    "WebSocketManager",
                                    "[TRACE] flushPendingQueue() ws.send() returned $result for ${msg.messageId}"
                            )
                            result
                        } catch (e: Exception) {
                            Log.e(
                                    "WebSocketManager",
                                    "[TRACE] flushPendingQueue() EXCEPTION during ws.send(): ${e.message}",
                                    e
                            )
                            Log.e("WebSocketManager", "send failed", e)
                            false
                        }

                if (!ok) {
                    Log.e(
                            "WebSocketManager",
                            "[TRACE] flushPendingQueue() send failed for ${msg.messageId}, scheduling reconnect"
                    )
                    // if immediate send failed, schedule reconnect and keep msg QUEUED (do not
                    // increment retries yet)
                    Log.w(
                            "WebSocketManager",
                            "ws.send returned false for ${msg.messageId}, scheduling reconnect"
                    )
                    scheduleReconnect(++reconnectAttempt)
                    Log.e(
                            "WebSocketManager",
                            "[TRACE] flushPendingQueue() reconnect scheduled, exiting flush"
                    )
                    return@launch
                }

                Log.e(
                        "WebSocketManager",
                        "[TRACE] flushPendingQueue() message ${msg.messageId} sent successfully, marking IN_FLIGHT"
                )
                // On successful send over wire, mark IN_FLIGHT and set lastSentAt — do NOT mark
                // DELIVERED yet
                msg.state = MessageState.IN_FLIGHT
                msg.lastSentAt = System.currentTimeMillis()
                Log.e(
                        "WebSocketManager",
                        "[TRACE] flushPendingQueue() message ${msg.messageId} state updated → IN_FLIGHT, lastSentAt=${msg.lastSentAt}"
                )

                try {
                    Log.e(
                            "WebSocketManager",
                            "[TRACE] flushPendingQueue() persisting IN_FLIGHT state for ${msg.messageId}"
                    )
                    dao.upsert(msg.toEntity())
                    Log.e(
                            "WebSocketManager",
                            "[TRACE] flushPendingQueue() IN_FLIGHT state persisted"
                    )
                } catch (e: Exception) {
                    Log.e(
                            "WebSocketManager",
                            "[TRACE] flushPendingQueue() EXCEPTION persisting in-flight message: ${e.message}",
                            e
                    )
                    Log.e("WebSocketManager", "Failed to persist in-flight message", e)
                }

                // Notify UI that send was attempted (in-flight)
                // withContext(Dispatchers.Main.immediate) {
                // _events.emit(WebSocketEvent.MessageSentAttempted(msg.messageId)) }

                // Throttle next send slightly to avoid filling kernel buffers
                Log.e(
                        "WebSocketManager",
                        "[TRACE] flushPendingQueue() delaying ${OUTGOING_SEND_DELAY_MS}ms before next message"
                )
                delay(OUTGOING_SEND_DELAY_MS)
            }
            Log.e(
                    "WebSocketManager",
                    "[TRACE] flushPendingQueue() EXIT → processed $processedCount messages"
            )
        }
    }

    /* ---------------------------------------------------
       REMOVE MESSAGE
       Only remove on delivery_ack or when permanently failed
    --------------------------------------------------- */
    private fun removeFromQueue(messageId: String) {
        Log.e(
                "WebSocketManager",
                "[TRACE] removeFromQueue() ENTRY → messageId=$messageId, pendingQueue.size=${pendingQueue.size}"
        )
        val removed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            pendingQueue.removeIf { it.messageId == messageId }
        } else {
            var found = false
            val iter = pendingQueue.iterator()
            while (iter.hasNext()) { if (iter.next().messageId == messageId) { iter.remove(); found = true } }
            found
        }
        Log.e(
                "WebSocketManager",
                "[TRACE] removeFromQueue() removed from queue=$removed, new pendingQueue.size=${pendingQueue.size}"
        )

        scope.launch {
            try {
                Log.e("WebSocketManager", "[TRACE] removeFromQueue() deleting $messageId from DB")
                dao.delete(messageId)
                Log.e("WebSocketManager", "[TRACE] removeFromQueue() deleted from DB successfully")
            } catch (e: Exception) {
                Log.e(
                        "WebSocketManager",
                        "[TRACE] removeFromQueue() EXCEPTION deleting from DB: ${e.message}",
                        e
                )
                Log.e("WebSocketManager", "Failed to delete message from DB", e)
            }
        }
        Log.e("WebSocketManager", "[TRACE] removeFromQueue() EXIT")
    }

    /* ---------------------------------------------------
       RECONNECT
    --------------------------------------------------- */
    /**
     * Attempt to regenerate the access token using saved login credentials.
     * Returns true if token was successfully regenerated and saved, false otherwise.
     */
    private suspend fun regenerateTokenFromSavedCredentials(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = appContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val username = prefs.getString("username", null)
                val password = prefs.getString("password", null)
                val userId = prefs.getString("user_id", null)
                
                Log.i("WebSocketManager", "Attempting token regeneration with saved credentials")
                Log.d("WebSocketManager", "Username: $username, UserId: $userId, Password exists: ${password != null}")
                
                if (username == null || password == null || userId == null) {
                    Log.e("WebSocketManager", "Missing saved credentials - cannot regenerate token")
                    Log.e("WebSocketManager", "Clearing session and emitting TokenExpiredNeedLogin event")
                    
                    // Clear the session since we can't regenerate the token
                    prefs.edit().clear().apply()
                    
                    // Emit event to notify UI to redirect to login
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.TokenExpiredNeedLogin)
                    }
                    
                    return@withContext false
                }
                
                // Call login endpoint to get new token
                val loginRequest = UserLoginRequest(
                    username = username,
                    password = password,
                    userId = userId
                )
                
                val response = NetworkClient.api.loginUser(loginRequest)
                
                if (response.isSuccessful && response.body() != null) {
                    val loginResponse = response.body()!!
                    val newToken = loginResponse.accessToken
                    val userEmail = loginResponse.user.email
                    
                    Log.i("WebSocketManager", "Token regeneration successful")
                    Log.d("WebSocketManager", "New token obtained for email: $userEmail")
                    
                    // Save new token to SharedPreferences
                    prefs.edit().apply {
                        putString("access_token", newToken)
                        putString("refresh_token", loginResponse.refreshToken)
                        putString("user_email", userEmail)
                        apply()
                    }
                    
                    Log.i("WebSocketManager", "New token saved to SharedPreferences")
                    return@withContext true
                } else {
                    Log.e("WebSocketManager", "Token regeneration failed: ${response.code()} - ${response.message()}")
                    val errorBody = response.errorBody()?.string()
                    Log.e("WebSocketManager", "Error body: $errorBody")
                    
                    // If login fails (e.g., wrong credentials), clear session and redirect to login
                    if (response.code() == 401 || response.code() == 403) {
                        Log.e("WebSocketManager", "Login failed with ${response.code()}, clearing session")
                        prefs.edit().clear().apply()
                        
                        withContext(Dispatchers.Main.immediate) {
                            _events.emit(WebSocketEvent.TokenExpiredNeedLogin)
                        }
                    }
                    
                    return@withContext false
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Exception during token regeneration", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Reset the reconnect attempt counter.
     * Call this when app comes to foreground or when user explicitly triggers reconnection.
     */
    fun resetReconnectAttempts() {
        Log.i("WebSocketManager", "Resetting reconnect attempts (was: $reconnectAttempt)")
        reconnectAttempt = 0
    }
    
    private fun scheduleReconnect(attempt: Int) {
        // No limit on reconnect attempts - will keep trying indefinitely
        if (wsUrl == null) {
            Log.w("WebSocketManager", "scheduleReconnect: No wsUrl available, cannot reconnect")
            return
        }

        val delayMs = (1000L * 2.0.pow(attempt - 1)).toLong().coerceAtMost(MAX_RECONNECT_DELAY_MS)
        
        Log.i("WebSocketManager", "Scheduling reconnect attempt #$attempt in ${delayMs}ms (infinite retries)")

        scope.launch {
            delay(delayMs)
            if (webSocket == null) {
                client.newWebSocket(Request.Builder().url(wsUrl!!).build(), createListener())
            }
        }
    }

    private fun ensureAbsoluteUrl(url: String): String {
        return if (url.startsWith("/")) {
            "$MEDIA_BASE_URL$url"
        } else {
            url
        }
    }

    /** Ensure manager is initialized and connected. Safe to call repeatedly (idempotent). */
    fun reconnectIfNeeded(context: Context) {
        scope.launch {
            try {
                if (!initialized) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        init(context)
                    }
                }

                // already connected or connecting
                if (webSocket != null || wsState == WsState.CONNECTING) {
                    Log.d("WebSocketManager", "reconnectIfNeeded: already active")
                    return@launch
                }

                // Reset reconnect attempt counter when explicitly reconnecting
                // This ensures we don't give up after being in background for a long time
                Log.i("WebSocketManager", "reconnectIfNeeded: Resetting reconnect attempt counter (was: $reconnectAttempt)")
                reconnectAttempt = 0

                try {
                    connect(context)
                } catch (e: Exception) {
                    Log.e("WebSocketManager", "reconnectIfNeeded: connect failed", e)
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "reconnectIfNeeded failed", e)
            }
        }
    }

    /* ---------------------------------------------------
       FORCE CLOSE (CRITICAL)
    --------------------------------------------------- */
    private fun forceCloseSocket() {
        try {
            // cancel() is immediate and will more reliably kill the socket than close()
            webSocket?.cancel()
        } catch (e: Exception) {
            Log.e("WebSocketManager", "forceCloseSocket cancel failed", e)
        }

        webSocket = null
        wsState = WsState.DISCONNECTED
        readyState = ReadyState.NOT_READY

        // stop monitors/heartbeat
        stopHeartbeat()
        stopReadyHandshake()
        stopLivenessMonitor()
        stopAckMonitor()
    }

    /* ---------------------------------------------------
       DISCONNECT
    --------------------------------------------------- */
    fun disconnect(forceStop: Boolean = false) {
        try {
            webSocket?.close(1000, "disconnect")
        } catch (e: Exception) {
            Log.e("WebSocketManager", "close failed", e)
        }
        forceCloseSocket()
        if (forceStop) {
            wsUrl = null
            // Unregister network callback to avoid leaks when explicitly stopping
            unregisterNetworkCallback()
        }
    }

    fun updateMessageStatus(messageId: String, status: com.example.anonychat.ui.MessageStatus) {
        scope.launch { historyDao.updateStatus(messageId, status.name) }
    }
    
    fun deleteChatHistory(user1: String, user2: String) {
        scope.launch {
            try {
                historyDao.deleteChatHistory(user1, user2)
                Log.d("WebSocketManager", "Deleted chat history between $user1 and $user2")
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Failed to delete chat history", e)
            }
        }
    }
    
    /**
     * Clear all data from the Room database (chat history and pending messages)
     * Used during logout to ensure clean state
     */
    suspend fun clearAllDatabaseData() {
        try {
            ensureInit()
            historyDao.deleteAll()
            dao.deleteAll()
            Log.d("WebSocketManager", "Cleared all database data (chat history and pending messages)")
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Failed to clear database data", e)
        }
    }

    fun getChatHistory(
            user1: String,
            user2: String
    ): kotlinx.coroutines.flow.Flow<List<ChatMessage>> =
            historyDao.getConversation(user1, user2).map { entities ->
                entities.map { it.toModel() }
            }
    suspend fun clearChatHistory(user1: String, user2: String) {
        try {
            historyDao.deleteChatHistory(user1, user2)
            Log.d("WebSocketManager", "Chat history cleared between $user1 and $user2")
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Failed to clear chat history", e)
            throw e
        }
    }


    private fun saveToHistory(message: ChatMessage) {
        scope.launch {
            try {
                val nextSeq = historyDao.getNextSequence()
                historyDao.upsert(message.toHistoryEntity(nextSeq))
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Failed to save message to history", e)
            }
        }
    }

    private fun ChatMessage.toHistoryEntity(sequence: Long = 0): ChatHistoryEntity {
        return ChatHistoryEntity(
                id = id,
                fromEmail = from,
                toEmail = to,
                text = text,
                audioUri = audioUri,
                amplitudes = amplitudes.joinToString(","),
                mediaUri = mediaUri,
                mediaType = mediaType,
                mediaId = mediaId,
                isDownloading = isDownloading,
                timestamp = timestamp,
                status = status.name,
                sequence = sequence
        )
    }

    private fun ChatHistoryEntity.toModel(): ChatMessage {
        // Fix for legacy audio messages that were saved as VIDEO with [Audio Message] text
        val isLegacyAudio = mediaType == "VIDEO" && text == "[Audio Message]" && audioUri == null

        return ChatMessage(
                id = id,
                from = fromEmail,
                to = toEmail,
                text = text,
                audioUri = if (isLegacyAudio) mediaUri else audioUri,
                amplitudes =
                        if (amplitudes.isEmpty()) emptyList()
                        else amplitudes.split(",").map { it.toFloat() },
                mediaUri = if (isLegacyAudio) null else mediaUri,
                mediaType = if (isLegacyAudio) null else mediaType,
                mediaId = mediaId,
                isDownloading = isDownloading,
                timestamp = timestamp,
                status =
                        runCatching { com.example.anonychat.ui.MessageStatus.valueOf(status) }
                                .getOrElse { com.example.anonychat.ui.MessageStatus.Delivered }
        )
    }
}
