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
import com.example.anonychat.ui.ChatMessage
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
        val timestamp: Long
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
}

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

@Volatile private var readyState: ReadyState = ReadyState.NOT_READY

// Media endpoints
//    private const val MEDIA_UPLOAD = "${NetworkConfig.BASE_URL}media/upload"
//    private const val MEDIA_DOWNLOAD_ACK = "${NetworkConfig.BASE_URL}media/download/ack"

private lateinit var appContext: Context

/* ---------------------------------------------------
   WEBSOCKET MANAGER
--------------------------------------------------- */
object WebSocketManager {

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
                    .pingInterval(
                            30,
                            java.util.concurrent.TimeUnit.SECONDS
                    ) // Keep connection alive
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
    private const val HEARTBEAT_INTERVAL_MS = 100L
    private const val READY_RETRY_INTERVAL_MS = 3_000L // increased to reduce spam
    private const val OUTGOING_SEND_DELAY_MS = 40L // throttle to avoid kernel buffer overflow

    // ack timeout — how long to wait for delivery_ack before retrying
    private const val ACK_TIMEOUT_MS = 20_000L

    private var heartbeatJob: Job? = null
    private var readyJob: Job? = null
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

    private const val SERVER_LIVENESS_TIMEOUT_MS = 45_000L
    @Volatile private var lastServerMessageAt: Long = 0
    private var livenessJob: Job? = null
    private lateinit var historyDao: ChatHistoryDao

    private fun startLivenessMonitor() {
        livenessJob?.cancel()
        livenessJob =
                scope.launch {
                    while (isActive) {
                        delay(10_000)
                        val now = System.currentTimeMillis()
                        if (now - lastServerMessageAt > SERVER_LIVENESS_TIMEOUT_MS) {
                            Log.e("WebSocketManager", "Server silent → force reconnect")
                            forceCloseSocket()
                            scheduleReconnect(++reconnectAttempt)
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

        pendingQueue.addAll(
                dao.getAll().map {
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

        initialized = true
        Log.i("WebSocketManager", "Initialized with pending messages=${pendingQueue.size}")
    }

    private fun ensureInit() {
        check(initialized) { "WebSocketManager.init(context) must be called first" }
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

        val payload = JSONObject().put("type", "presence").put("state", state).toString()

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
        Log.e("WebSocketManager", "Connecting...")

        // 1) Acquire lock only to check & mutate quick state -> don't suspend while holding it
        connectMutex.withLock {
            Log.e("WebSocketManager", "connect $wsState")
            if (wsState != WsState.DISCONNECTED) return
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
                            "${NetworkConfig.WS_BASE_URL}?token=${Uri.encode(token)}&gmail=${Uri.encode(email)}"
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
                                            put("type", "media")
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
                            webSocket?.send(JSONObject().put("type", "is_ready").toString())
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
                                            pendingQueue.removeIf { it.messageId == msg.messageId }
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

                // reset liveness timestamp and start monitors
                lastServerMessageAt = System.currentTimeMillis()
                startReadyHandshake()
                // startLivenessMonitor() // if you want
                startHeartbeat()
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
                lastServerMessageAt = System.currentTimeMillis()
                try {
                    scope.launch { handleIncoming(ws, text) }
                } catch (e: Exception) {
                    Log.e("WebSocketManager", "Failed to launch message handler", e)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                Log.e("WebSocketManager", "WebSocket FAILURE", t)

                // If connect() is still waiting, resume it with exception (exactly once)
                try {
                    if (continuation != null && resumed?.compareAndSet(false, true) == true) {
                        continuation.resumeWith(Result.failure(t))
                    }
                } catch (e: Exception) {
                    Log.w("WebSocketManager", "continuation resume onFailure failed", e)
                }

                // Clean up and schedule reconnect as before
                forceCloseSocket()
                readyState = ReadyState.NOT_READY
                scheduleReconnect(++reconnectAttempt)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.w("WebSocketManager", "WebSocket CLOSED: $code $reason")

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
                scheduleReconnect(++reconnectAttempt)
            }
        }
    }

    private fun sendMessageReceivedAck(messageId: String, senderGmail: String) {
        if (readyState != ReadyState.READY) return

        val payload =
                JSONObject()
                        .put("type", "message_received_ack")
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
                            put("type", "message_read_ack")
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
                    val chatMessage =
                            ChatMessage(
                                    id = messageId,
                                    from = json.getString("from"),
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
                            pendingQueue.removeIf { it.messageId == pm.messageId }
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
                            webSocket?.send(JSONObject().put("type", "ping").toString())
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

        val payload = JSONObject().put("type", "chat_open").put("with", peerGmail).toString()

        val pm =
                PendingMessage(
                        "chat_open_${System.currentTimeMillis()}",
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
                Log.e("WebSocketManager", "Failed to persist chat_open pending message", e)
            }
        }

        flushPendingQueue()
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
                            put("type", "chat_message")
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
    @RequiresApi(Build.VERSION_CODES.N)
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
                    pendingQueue.removeIf { it.messageId == msg.messageId }
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
    @RequiresApi(Build.VERSION_CODES.N)
    private fun removeFromQueue(messageId: String) {
        Log.e(
                "WebSocketManager",
                "[TRACE] removeFromQueue() ENTRY → messageId=$messageId, pendingQueue.size=${pendingQueue.size}"
        )
        val removed = pendingQueue.removeIf { it.messageId == messageId }
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
    private fun scheduleReconnect(attempt: Int) {
        if (wsUrl == null || attempt > 10) return

        val delayMs = (1000L * 2.0.pow(attempt - 1)).toLong().coerceAtMost(MAX_RECONNECT_DELAY_MS)

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
                    init(context)
                }

                // already connected or connecting
                if (webSocket != null || wsState == WsState.CONNECTING) {
                    Log.d("WebSocketManager", "reconnectIfNeeded: already active")
                    return@launch
                }

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

    fun getChatHistory(
            user1: String,
            user2: String
    ): kotlinx.coroutines.flow.Flow<List<ChatMessage>> =
            historyDao.getConversation(user1, user2).map { entities ->
                entities.map { it.toModel() }
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
