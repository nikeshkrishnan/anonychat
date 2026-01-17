package com.example.anonychat.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.room.*
import com.example.anonychat.ui.ChatMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.pow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/* ---------------------------------------------------
   ROOM – ENTITY
--------------------------------------------------- */
@Entity(tableName = "pending_messages")
data class PendingMessageEntity(
    @PrimaryKey val messageId: String,
    val payload: String,
    val retries: Int,
    val sent: Boolean,
    val timestamp: Long
)

/* ---------------------------------------------------
   ROOM – DAO
--------------------------------------------------- */
@Dao
interface PendingMessageDao {

    @Query("SELECT * FROM pending_messages ORDER BY timestamp ASC")
    suspend fun getAll(): List<PendingMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(msg: PendingMessageEntity)

    @Query("DELETE FROM pending_messages WHERE messageId = :id")
    suspend fun delete(id: String)
}

/* ---------------------------------------------------
   ROOM – DATABASE
--------------------------------------------------- */
@Database(
    entities = [PendingMessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingMessageDao(): PendingMessageDao
}

/* ---------------------------------------------------
   EVENTS
--------------------------------------------------- */
sealed class WebSocketEvent {
    data class NewMessage(val message: ChatMessage) : WebSocketEvent()
    data class DeliveryAck(val messageId: String) : WebSocketEvent()
    data class DeliveryFailed(val messageId: String, val reason: String) : WebSocketEvent()
    data class MessageSent(val messageId: String) : WebSocketEvent()
}

/* ---------------------------------------------------
   INTERNAL MODEL
--------------------------------------------------- */
private data class PendingMessage(
    val messageId: String,
    val payload: String,
    var retries: Int,
    var sent: Boolean
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

@Volatile
private var wsState: WsState = WsState.DISCONNECTED

@Volatile
private var readyState: ReadyState = ReadyState.NOT_READY

/* ---------------------------------------------------
   WEBSOCKET MANAGER
--------------------------------------------------- */
object WebSocketManager {

    /* ---------------------------------------------------
       OKHTTP (with ping)
    --------------------------------------------------- */
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
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

    private const val MAX_RETRIES = 5
    private const val MAX_RECONNECT_DELAY_MS = 30_000L
    private const val HEARTBEAT_INTERVAL_MS = 30_000L
    private const val READY_RETRY_INTERVAL_MS = 3_000L // increased to reduce spam
    private const val OUTGOING_SEND_DELAY_MS = 40L // throttle to avoid kernel buffer overflow

    private var heartbeatJob: Job? = null
    private var readyJob: Job? = null

    private fun PendingMessage.toEntity() =
        PendingMessageEntity(messageId, payload, retries, sent, System.currentTimeMillis())

    // Backpressure-safe SharedFlow for UI-level events
    private val _events = MutableSharedFlow<WebSocketEvent>(
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
    @Volatile
    private var lastServerMessageAt: Long = 0
    private var livenessJob: Job? = null

    private fun startLivenessMonitor() {
        livenessJob?.cancel()
        livenessJob = scope.launch {
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

    fun registerNetworkCallback(context: Context) {
        try {
            connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            networkCallback = object : ConnectivityManager.NetworkCallback() {

                override fun onAvailable(network: Network) {
                    Log.e("WebSocketManager", "Network AVAILABLE → reconnect")
                    reconnectIfNeeded(context)
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
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
            }
            networkCallback = null
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Failed to unregister network callback", e)
        }
    }

    /* ---------------------------------------------------
       INIT
    --------------------------------------------------- */
    suspend fun init(context: Context) {
        if (initialized) return

        val db = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "anonychat.db"
        ).build()

        dao = db.pendingMessageDao()

        pendingQueue.addAll(
            dao.getAll().map {
                PendingMessage(it.messageId, it.payload, it.retries, it.sent)
            }
        )

        initialized = true
        Log.i("WebSocketManager", "Initialized with pending messages=${pendingQueue.size}")

        // Register network callback automatically so manager is proactive.
        // You can still call registerNetworkCallback explicitly if you prefer.
        registerNetworkCallback(context)
    }

    private fun ensureInit() {
        check(initialized) { "WebSocketManager.init(context) must be called first" }
    }

    private fun sendOnlinePresenceIfReady() {
        if (readyState == ReadyState.READY) {
            sendPresence("online")
            Log.i("WebSocketManager", "Presence auto-sent: online after reconnect")
        }
    }

    fun sendPresence(state: String) {
        // state = "foreground" | "background" | "online" | "offline"
        if (readyState != ReadyState.READY) return

        val payload = JSONObject()
            .put("type", "presence")
            .put("state", state)
            .toString()

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
        Log.i("WebSocketManager", "Connecting...")
        connectMutex.withLock {
            if (wsState != WsState.DISCONNECTED) return
            wsState = WsState.CONNECTING

            suspendCancellableCoroutine<Unit> { cont ->
                Log.i("WebSocketManager", "connect: starting coroutine to establish connection.")
                val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val token = prefs.getString("access_token", null)
                val email = prefs.getString("user_email", null)
                curruser = email

                Log.i("WebSocketManager", "connect: Retrieved from prefs -> email: $email, token exists: ${token != null}")

                if (token == null || email == null) {
                    wsState = WsState.DISCONNECTED
                    cont.resumeWith(Result.failure(IllegalStateException("Auth missing")))
                    return@suspendCancellableCoroutine
                }

                wsUrl =
                    "ws://192.168.1.66:8080/?token=${Uri.encode(token)}&gmail=${Uri.encode(email)}"
                Log.i("WebSocketManager", "connect: Constructed WS URL: $wsUrl")

                client.newWebSocket(
                    Request.Builder().url(wsUrl!!).build(),
                    createListener(cont)
                )
            }
        }
    }

    /* ---------------------------------------------------
       READY HANDSHAKE
    --------------------------------------------------- */
    private fun startReadyHandshake() {
        readyJob?.cancel()
        readyState = ReadyState.WAITING_ACK

        readyJob = scope.launch {
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
       LISTENER
    --------------------------------------------------- */
    private fun createListener(
        continuation: CancellableContinuation<Unit>? = null
    ): WebSocketListener {

        return object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                webSocket = ws
                wsState = WsState.CONNECTED
                readyState = ReadyState.NOT_READY
                reconnectAttempt = 0

                Log.i("WebSocketManager", "WebSocket CONNECTED (waiting for ready_ack)")

                // reset liveness timestamp and start monitors
                lastServerMessageAt = System.currentTimeMillis()
                startReadyHandshake()
                startLivenessMonitor()

                continuation?.resumeWith(Result.success(Unit))
            }

            @RequiresApi(Build.VERSION_CODES.N)
            override fun onMessage(ws: WebSocket, text: String) {
                // Offload processing immediately to avoid blocking OkHttp callback thread
                lastServerMessageAt = System.currentTimeMillis()
                try {
                    scope.launch {
                        handleIncoming(ws, text)
                    }
                } catch (e: Exception) {
                    Log.e("WebSocketManager", "Failed to launch message handler", e)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                Log.e("WebSocketManager", "WebSocket FAILURE", t)
                forceCloseSocket()
                readyState = ReadyState.NOT_READY
                scheduleReconnect(++reconnectAttempt)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.w("WebSocketManager", "WebSocket CLOSED: $code $reason")
                forceCloseSocket()
                readyState = ReadyState.NOT_READY
                scheduleReconnect(++reconnectAttempt)
            }
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
                    // send presence if needed
                    sendOnlinePresenceIfReady()
                    // flush pending outgoing
                    flushPendingQueue()
                }

                "chat_message" -> {
                    val localEmail = ws.request().url.queryParameter("gmail") ?: ""
                    Log.i("WebSocketManager", "chat_message received: $json")
                    val chatMessage = ChatMessage(
                        id = messageId,
                        from = json.getString("from"),
                        to = localEmail,
                        text = json.getString("content"),
                        timestamp = json.optLong("timestamp")
                    )

                    // Emit on Main.immediate so UI receives it promptly
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.NewMessage(chatMessage))
                    }
                }

                "delivery_ack" -> {
                    removeFromQueue(messageId)
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(WebSocketEvent.DeliveryAck(messageId))
                    }
                }

                "delivery_failed" -> {
                    removeFromQueue(messageId)
                    withContext(Dispatchers.Main.immediate) {
                        _events.emit(
                            WebSocketEvent.DeliveryFailed(
                                messageId,
                                json.optString("error", "unknown")
                            )
                        )
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
        heartbeatJob = scope.launch {
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
       SEND MESSAGE
    --------------------------------------------------- */
    @RequiresApi(Build.VERSION_CODES.N)
    fun sendChatOpen(peerGmail: String) {
        ensureInit()

        val payload = JSONObject()
            .put("type", "chat_open")
            .put("with", peerGmail)
            .toString()

        pendingQueue.add(
            PendingMessage(
                "chat_open_${System.currentTimeMillis()}",
                payload,
                0,
                false
            )
        )

        flushPendingQueue()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun sendMessage(toEmail: String, text: String, localId: String) {
        ensureInit()

        val payload = JSONObject().apply {
            put("type", "chat_message")
            put("to", toEmail)
            put("from", curruser)
            put("content", text)
            put("id", localId)
            put("messageId", localId)
        }.toString()

        pendingQueue.add(PendingMessage(localId, payload, 0, false))
        scope.launch {
            try {
                dao.upsert(PendingMessageEntity(localId, payload, 0, false, System.currentTimeMillis()))
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Failed to persist pending message", e)
            }
        }
        flushPendingQueue()
    }

    /* ---------------------------------------------------
       FLUSH QUEUE
       Throttle outgoing sends to avoid OEM/kernel buffer overflow and bursts.
    --------------------------------------------------- */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun flushPendingQueue() {
        if (!initialized || readyState != ReadyState.READY) return
        val ws = webSocket ?: return

        scope.launch {
            for (msg in pendingQueue.toList()) {
                if (msg.sent) continue
                if (msg.retries >= MAX_RETRIES) {
                    removeFromQueue(msg.messageId)
                    continue
                }

                val ok = try {
                    ws.send(msg.payload)
                } catch (e: Exception) {
                    Log.e("WebSocketManager", "send failed", e)
                    false
                }

                if (!ok) {
                    msg.retries++
                    try {
                        dao.upsert(msg.toEntity())
                    } catch (e: Exception) {
                        Log.e("WebSocketManager", "Failed to persist retry", e)
                    }
                    scheduleReconnect(++reconnectAttempt)
                    return@launch
                }

                // Mark sent and persist
                msg.sent = true
                try {
                    dao.upsert(msg.toEntity())
                } catch (e: Exception) {
                    Log.e("WebSocketManager", "Failed to persist sent message", e)
                }

                // Notify UI on Main immediately
                withContext(Dispatchers.Main.immediate) {
                    _events.emit(WebSocketEvent.MessageSent(msg.messageId))
                }

                // Throttle next send slightly to avoid filling kernel buffers
                delay(OUTGOING_SEND_DELAY_MS)
            }
        }
    }

    /* ---------------------------------------------------
       REMOVE MESSAGE
    --------------------------------------------------- */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun removeFromQueue(messageId: String) {
        pendingQueue.removeIf { it.messageId == messageId }
        scope.launch {
            try {
                dao.delete(messageId)
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Failed to delete message from DB", e)
            }
        }
    }

    /* ---------------------------------------------------
       RECONNECT
    --------------------------------------------------- */
    private fun scheduleReconnect(attempt: Int) {
        if (wsUrl == null || attempt > 10) return

        val delayMs =
            (1000L * 2.0.pow(attempt - 1)).toLong().coerceAtMost(MAX_RECONNECT_DELAY_MS)

        scope.launch {
            delay(delayMs)
            if (webSocket == null) {
                client.newWebSocket(
                    Request.Builder().url(wsUrl!!).build(),
                    createListener()
                )
            }
        }
    }

    /**
     * Ensure manager is initialized and connected.
     * Safe to call repeatedly (idempotent).
     */
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
}
