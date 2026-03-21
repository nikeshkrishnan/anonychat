package com.example.anonychat.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response as OkResponse
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

// Sealed class for HTTP 403 events
sealed class Http403Event {
    data class AccountRestricted(val message: String) : Http403Event()
}

// Global flow for HTTP 403 events
object Http403EventBus {
    private val _events = MutableSharedFlow<Http403Event>(
        replay = 0,
        extraBufferCapacity = 10
    )
    val events = _events.asSharedFlow()
    
    suspend fun emit(event: Http403Event) {
        _events.emit(event)
    }
}

// Sealed class for HTTP 426 events (App Update Required)
sealed class Http426Event {
    data class AppUpdateRequired(val message: String) : Http426Event()
}

// Global flow for HTTP 426 events
object Http426EventBus {
    private val _events = MutableSharedFlow<Http426Event>(
        replay = 0,
        extraBufferCapacity = 10
    )
    val events = _events.asSharedFlow()
    
    suspend fun emit(event: Http426Event) {
        _events.emit(event)
    }
}

class CachingDns : Dns {

        private val cache = mutableMapOf<String, List<InetAddress>>()

        override fun lookup(hostname: String): List<InetAddress> {
                return try {
                        val result = Dns.SYSTEM.lookup(hostname)
                        cache[hostname] = result
                        result
                } catch (e: UnknownHostException) {
                        cache[hostname] ?: throw e
                }
        }
}

// This interceptor adds the auth token to requests
class AuthInterceptor(private val context: Context) : Interceptor {
        private val sharedPreferences: SharedPreferences =
                context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
                val token = sharedPreferences.getString("access_token", null)
                Log.e(
                        "AuthInterceptor!!!!!!!!!!!!!!!!",
                        "Retrieved token from SharedPreferences: $token"
                )
                val originalRequest = chain.request()

                // Use global hardcoded build version
                val buildVersion = NetworkConfig.BUILD_VERSION

                val requestBuilder = originalRequest.newBuilder()
                    .addHeader("x-build-version", buildVersion)

                token?.let {
                        // Skip adding token for login, register, and reset-password endpoints
                        val url = originalRequest.url.toString()
                        Log.d("AuthInterceptor", "Checking URL: $url")
                        Log.d("AuthInterceptor", "Contains auth/login: ${url.contains("auth/login")}")
                        Log.d("AuthInterceptor", "Contains auth/register: ${url.contains("auth/register")}")
                        Log.d("AuthInterceptor", "Contains auth/reset-password: ${url.contains("auth/reset-password")}")
                        
                        if (!url.contains("auth/login") &&
                            !url.contains("auth/register") &&
                            !url.contains("auth/reset-password")) {
                                requestBuilder.addHeader("Authorization", "Bearer $it")
                                Log.d("AuthInterceptor", "✅ Token added to header for URL: $url")
                        } else {
                                Log.d("AuthInterceptor", "⏭️ Skipping token for URL: $url")
                        }
                }

                val request = requestBuilder.build()
                val response = chain.proceed(request)
                
                // Handle 426 or 400 responses (App Update Required)
                val responseBodyStr = try { response.peekBody(Long.MAX_VALUE).string() } catch (e: Exception) { "" }
                if (response.code == 426 || (response.code == 400 && responseBodyStr.contains("version", ignoreCase = true))) {
                    val url = request.url.toString()
                    Log.e("AuthInterceptor", "Version error received for URL: $url")
                    Log.e("AuthInterceptor", "Response body: $responseBodyStr")
                    
                    try {
                        val json = org.json.JSONObject(responseBodyStr)
                        val message = json.optString("message", "App version is too old. Please update.")
                        
                        sharedPreferences.edit().clear().apply()
                        
                        kotlinx.coroutines.GlobalScope.launch {
                            Http426EventBus.emit(Http426Event.AppUpdateRequired(message))
                        }
                    } catch (e: Exception) {
                        Log.e("AuthInterceptor", "Failed to parse 426/400 response", e)
                        sharedPreferences.edit().clear().apply()
                        kotlinx.coroutines.GlobalScope.launch {
                            Http426EventBus.emit(Http426Event.AppUpdateRequired("App version is too old. Please update."))
                        }
                    }
                    return response
                }
                
                // Handle 403 responses (ban/suspension)
                if (response.code == 403) {
                        val responseBody = response.peekBody(Long.MAX_VALUE).string()
                        val url = request.url.toString()
                        Log.e("AuthInterceptor", "403 Forbidden received for URL: $url")
                        Log.e("AuthInterceptor", "Response body: $responseBody")
                        
                        // Skip event emission for login/register endpoints - they handle 403 themselves
                        val isAuthEndpoint = url.contains("auth/login") || url.contains("auth/register")
                        
                        if (isAuthEndpoint) {
                                Log.e("AuthInterceptor", "403 on auth endpoint - letting LoginScreen handle it")
                                // Don't emit event or clear session - LoginScreen will handle this
                                return response
                        }
                        
                        // For non-auth endpoints, handle 403 normally
                        try {
                                val json = org.json.JSONObject(responseBody)
                                val message = json.optString("message", "Your account has been restricted")
                                
                                // Clear user session
                                sharedPreferences.edit().clear().apply()
                                
                                // Emit event to show dialog in MainActivity
                                kotlinx.coroutines.GlobalScope.launch {
                                        Http403EventBus.emit(Http403Event.AccountRestricted(message))
                                }
                                
                                Log.e("AuthInterceptor", "403 event emitted: $message")
                        } catch (e: Exception) {
                                Log.e("AuthInterceptor", "Failed to parse 403 response", e)
                                
                                // Clear user session
                                sharedPreferences.edit().clear().apply()
                                
                                // Emit default message
                                kotlinx.coroutines.GlobalScope.launch {
                                        Http403EventBus.emit(Http403Event.AccountRestricted("Your account has been restricted"))
                                }
                        }
                }
                
                return response
        }
}

data class GetPreferencesResponse(
        val username: String?,
        val gmail: String?,
        val age: Int?,
        val gender: String?,
        val preferredGender: String?,
        val romanceRange: RomanceRange?,
        val preferredAgeRange: AgeRange?,
        val random: Boolean?,
        @SerializedName("isOnline") val isOnline: Boolean?,
        @SerializedName("lastOnline") val lastOnline: Long?,
        val sparks: Int?,
        val totalRosesReceived: Int?,
        val availableRoses: Int?
)

// --- DATA MODELS ---
data class UserRegistrationRequest(
        val username: String,
        val password: String,
        @SerializedName("userId") val userId: String,
        val email: String,
        val googleId: String,
        val integrityToken: String? = null
)

data class LoginResponse(
        val message: String,
        val accessToken: String,
        val refreshToken: String,
        val user: UserDto
)

data class UserDto(val id: String, val username: String, val email: String, val gender: String)

data class UserLoginRequest(val username: String, val password: String, val userId: String)

data class PreferencesRequest(
        val userId: String,
        val age: Int,
        val gender: String,
        val preferredGender: String,
        val preferredAgeRange: AgeRange,
        val romanceRange: RomanceRange,
        val random: Boolean
)

data class AgeRange(val min: Int, val max: Int)

data class RomanceRange(val min: Int, val max: Int)

data class UserResetPasswordRequest(
        @SerializedName("userId") val userId: String,
        val username: String,
        val newPassword: String,
        val integrityToken: String? = null
)

data class MatchResponse(
        val username: String?,
        val gmail: String?,
        val age: Int?,
        val gender: String?,
        val preferredGender: String?,
        val romanceRange: RomanceRange?,
        val random: Boolean?,
        val preferredAgeRange: AgeRange?,
        val pickBestflag: Boolean?,
        @SerializedName("isOnline") val isOnline: Boolean?,
        @SerializedName("lastOnline") val lastOnline: Long?,
        val match: String?, // Keep match for backward compatibility if it's still being sent as the primary identifier
        val giftedMeARose: Boolean? = null,
        val hasTakenBackRose: Boolean? = null
)

data class SparkRequest(
        val userA: String,
        val userB: String
)

data class SparkResponse(
        val sparked: Boolean,
        val alreadyExists: Boolean? = null
)

data class SparkRoseRequest(
        val toGmail: String
)

data class SparkRoseResponse(
        val message: String,
        val receiverTotalSparks: Int
)

data class BlockResponse(
        val message: String
)

data class ReportRequest(
        val reason: String
)

data class ReportResponse(
        val message: String
)

data class ReportErrorResponse(
        val isDuplicate: Boolean? = null,
        val message: String
)

data class DeleteMatchResponse(
        val message: String,
        val removed: Boolean
)

data class DeleteAllMatchesResponse(
        val message: String,
        val deleted: Boolean
)

// --- RATING DATA MODELS ---
data class Rating(
        val rating: Int,
        val comment: String?,
        val fromusername: String?,
        val fromgender: String?,
        val createdAt: String,  // ISO 8601 date string from backend
        val romanceRange: RomanceRange?,
        val random: Boolean?
)

data class AverageRatingResponse(
        val avgRating: Float?,
        val count: Int?
)

data class SubmitRatingRequest(
        val rating: Int,
        val comment: String?,
        val romanceRange: RomanceRange,
        val random: Boolean
)

data class SubmitRatingResponse(
	val message: String?
)

data class ResetAccountResponse(
	val message: String
)

data class UpdateUsernameRequest(
	val userId: String,
	val username: String,
	val integrityToken: String? = null
)

data class UpdateUsernameResponse(
	val message: String,
	val username: String,
	val email: String
)

// --- API INTERFACE ---
interface AuthApiService {
        @Headers("Content-Type: application/json")
        @POST("auth/register")
        suspend fun registerUser(@Body request: UserRegistrationRequest): Response<Void>

        @Headers("Content-Type: application/json")
        @POST("auth/login")
        suspend fun loginUser(@Body request: UserLoginRequest): Response<LoginResponse>

        @Headers("Content-Type: application/json")
        @POST("auth/reset-password")
        suspend fun resetPassword(@Body request: UserResetPasswordRequest): Response<Void>

        @Headers("Content-Type: application/json")
        @PUT("preferences/")
        suspend fun setPreferences(@Body request: PreferencesRequest): Response<Void>
        @GET("preferences/{email}")
        suspend fun getPreferences(@Path("email") email: String): Response<GetPreferencesResponse>

        @POST("/api/match/{gmail}")
        suspend fun callMatch(@Path("gmail") gmail: String): Response<MatchResponse>

        @POST("/api/match/skip/{gmail}/{candGmail}")
        suspend fun skipMatch(@Path("gmail") gmail: String, @Path("candGmail") candGmail: String): Response<Void>

        @POST("/api/match/accept/{gmail}/{candGmail}")
        suspend fun acceptMatch(@Path("gmail") gmail: String, @Path("candGmail") candGmail: String): Response<Void>

        @Headers("Content-Type: application/json")
        @POST("/api/roses/issparked")
        suspend fun isSparked(@Body request: SparkRequest): Response<SparkResponse>

        @Headers("Content-Type: application/json")
        @POST("/api/roses/hassparked")
        suspend fun hasSparked(@Body request: SparkRequest): Response<SparkResponse>

        @Headers("Content-Type: application/json")
        @POST("/api/roses/spark")
        suspend fun sparkUser(@Body request: SparkRoseRequest): Response<SparkRoseResponse>

        @Headers("Content-Type: application/json")
        @POST("/api/roses/give")
        suspend fun giveRose(@Body request: SparkRoseRequest): Response<SparkRoseResponse>

        @Headers("Content-Type: application/json")
        @POST("/api/roses/takeback")
        suspend fun takeBackRose(@Body request: SparkRoseRequest): Response<SparkRoseResponse>

        @POST("/api/match/block/{userId}/{candId}")
        suspend fun blockUser(@Path("userId") userId: String, @Path("candId") candId: String): Response<BlockResponse>

        @POST("/api/match/unblock/{userId}/{candId}")
        suspend fun unblockUser(@Path("userId") userId: String, @Path("candId") candId: String): Response<BlockResponse>

        @Headers("Content-Type: application/json")
        @POST("/report/{reporterId}/{targetId}")
        suspend fun reportUser(
                @Path("reporterId") reporterId: String,
                @Path("targetId") targetId: String,
                @Body request: ReportRequest
        ): Response<ReportResponse>

        @DELETE("/api/match/{gmail}/{candGmail}")
        suspend fun deleteMatch(
                @Path("gmail") gmail: String,
                @Path("candGmail") candGmail: String
        ): Response<DeleteMatchResponse>

        @DELETE("/api/match/all/{gmail}")
        suspend fun deleteAllMatches(@Path("gmail") gmail: String): Response<DeleteAllMatchesResponse>

        @GET("/rating/{email}")
        suspend fun getRatings(@Path("email") email: String): Response<List<Rating>>

        @GET("/rating/average/{email}")
        suspend fun getAverageRating(@Path("email") email: String): Response<AverageRatingResponse>
        
        @GET("/rating/from/{fromEmail}/to/{toEmail}")
        suspend fun getSpecificRating(
                @Path("fromEmail") fromEmail: String,
                @Path("toEmail") toEmail: String
        ): Response<Rating>
        
        @POST("/rating/{email}")
        suspend fun submitRating(
        	@Path("email") targetEmail: String,
        	@Body request: SubmitRatingRequest
        ): Response<SubmitRatingResponse>
        
        @POST("/reset/{userId}")
        suspend fun resetAccount(@Path("userId") userId: String): Response<ResetAccountResponse>
        
        @Headers("Content-Type: application/json")
        @PUT("/auth/update-username")
        suspend fun updateUsername(@Body request: UpdateUsernameRequest): Response<UpdateUsernameResponse>
        
        @POST("/auth/logout")
        suspend fun logout(): Response<Void>
       }

// --- NETWORK CLIENT (SINGLETON) ---
object NetworkClient {
        private const val BASE_URL = NetworkConfig.BASE_URL
        private lateinit var retrofit: Retrofit

        // Lazily initialize the api service after `initialize` has been called
        val api: AuthApiService by lazy {
                if (!::retrofit.isInitialized) {
                        throw UninitializedPropertyAccessException(
                                "NetworkClient must be initialized in Application class"
                        )
                }
                retrofit.create(AuthApiService::class.java)
        }

        private fun getTracingEventListener(): EventListener {
                return object : EventListener() {
                        var start = 0L
                        override fun callStart(call: Call) {
                                start = System.currentTimeMillis()
                                Log.e("NETWORK_TRACE", "Call started: ${call.request().url}")
                        }
                        override fun dnsStart(call: Call, domainName: String) {
                                Log.e("NETWORK_TRACE", "DNS start: $domainName")
                        }
                        override fun dnsEnd(
                                call: Call,
                                domainName: String,
                                inetAddressList: List<java.net.InetAddress>
                        ) {
                                Log.e(
                                        "NETWORK_TRACE",
                                        "DNS end: ${System.currentTimeMillis() - start}ms"
                                )
                        }
                        override fun connectStart(
                                call: Call,
                                inetSocketAddress: java.net.InetSocketAddress,
                                proxy: java.net.Proxy
                        ) {
                                Log.e("NETWORK_TRACE", "Connect start: $inetSocketAddress")
                        }
                        override fun connectEnd(
                                call: Call,
                                inetSocketAddress: java.net.InetSocketAddress,
                                proxy: java.net.Proxy,
                                protocol: Protocol?
                        ) {
                                Log.e(
                                        "NETWORK_TRACE",
                                        "Connect end: ${System.currentTimeMillis() - start}ms, Protocol: $protocol"
                                )
                        }
                        override fun responseHeadersStart(call: Call) {
                                Log.e(
                                        "NETWORK_TRACE",
                                        "Response Headers start (waiting for server...)"
                                )
                        }
                        override fun responseHeadersEnd(call: Call, response: OkResponse) {
                                Log.e(
                                        "NETWORK_TRACE",
                                        "Response Headers end: ${System.currentTimeMillis() - start}ms, Code: ${response.code}"
                                )
                        }
                        override fun callFailed(call: Call, ioe: java.io.IOException) {
                                Log.e(
                                        "NETWORK_TRACE",
                                        "Call failed after ${System.currentTimeMillis() - start}ms: $ioe"
                                )
                        }
                        override fun callEnd(call: Call) {
                                Log.e(
                                        "NETWORK_TRACE",
                                        "Call end: ${System.currentTimeMillis() - start}ms"
                                )
                        }
                }
        }

        // This function must be called from AnonychatApp.kt
        fun initialize(context: Context) {
                // FORCE IPv4 to avoid IPv6 timeouts/stale socket issues on some devices
                System.setProperty("java.net.preferIPv4Stack", "true")

                // If already initialized, try to clean up stale connections before returning
                if (::retrofit.isInitialized) {
                        try {
                                val client = retrofit.callFactory() as? OkHttpClient
                                client?.connectionPool?.evictAll()
                                client?.dispatcher?.executorService?.shutdown()
                        } catch (e: Exception) {
                                Log.e("NetworkClient", "Failed to cleanup stale connections", e)
                        }
                }

                val loggingInterceptor =
                        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }

                // Create an instance of our custom interceptor
                val authInterceptor = AuthInterceptor(context)

                // Use a custom ConnectionPool that we can explicitly clear if needed
                val connectionPool = okhttp3.ConnectionPool()

                val httpClient =
                        OkHttpClient.Builder()
                                .eventListenerFactory { getTracingEventListener() }
                                .protocols(listOf(Protocol.HTTP_1_1))
                                .connectionPool(connectionPool)
                                .dns(CachingDns())
                                .retryOnConnectionFailure(true)
                                .addInterceptor(authInterceptor)
                                .addInterceptor(loggingInterceptor)
                                .eventListener(
                                        object : EventListener() {

                                                override fun dnsStart(
                                                        call: Call,
                                                        domainName: String
                                                ) {
                                                        Log.e(
                                                                "NETWORK_TRACE",
                                                                "DNS start: $domainName"
                                                        )
                                                }

                                                override fun dnsEnd(
                                                        call: Call,
                                                        domainName: String,
                                                        inetAddressList: List<InetAddress>
                                                ) {
                                                        Log.e("NETWORK_TRACE", "DNS end")
                                                }

                                                override fun connectStart(
                                                        call: Call,
                                                        inetSocketAddress: InetSocketAddress,
                                                        proxy: Proxy
                                                ) {
                                                        Log.e("NETWORK_TRACE", "Connect start")
                                                }

                                                override fun secureConnectStart(call: Call) {
                                                        Log.e("NETWORK_TRACE", "TLS start")
                                                }

                                                override fun secureConnectEnd(
                                                        call: Call,
                                                        handshake: Handshake?
                                                ) {
                                                        Log.e("NETWORK_TRACE", "TLS end")
                                                }

                                                override fun connectionAcquired(
                                                        call: Call,
                                                        connection: Connection
                                                ) {
                                                        Log.e(
                                                                "NETWORK_TRACE",
                                                                "Connection acquired"
                                                        )
                                                }
                                        }
                                )
                                .addInterceptor { chain ->
                                        val request =
                                                chain.request()
                                                        .newBuilder()
                                                        .header("Connection", "close")
                                                        .build()
                                        chain.proceed(request)
                                }
                                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                .build()

                // Aggressively evict any potential stale connections from the pool on startup
                connectionPool.evictAll()

                retrofit =
                        Retrofit.Builder()
                                .baseUrl(BASE_URL)
                                .client(httpClient)
                                .addConverterFactory(GsonConverterFactory.create())
                                .build()
        }
}
