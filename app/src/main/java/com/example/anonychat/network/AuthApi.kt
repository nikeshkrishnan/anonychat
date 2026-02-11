package com.example.anonychat.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response as OkResponse
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

class RetryInterceptor(private val maxRetries: Int = 3) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
                var request = chain.request()
                var response: okhttp3.Response? = null
                var exception: IOException? = null
                var tryCount = 0

                while (tryCount < maxRetries && response == null) {
                        try {
                                if (tryCount > 0) {
                                        // Simple exponential backoff: 200ms, 400ms, 800ms
                                        val sleepTime = 200L * (1 shl (tryCount - 1))
                                        Thread.sleep(sleepTime)
                                        Log.d(
                                                "RetryInterceptor",
                                                "Retrying request ($tryCount) for: ${request.url}"
                                        )
                                }
                                response = chain.proceed(request)
                                if (!response.isSuccessful && shouldRetry(response.code)) {
                                        response.close()
                                        response = null
                                }
                        } catch (e: IOException) {
                                exception = e
                                Log.e(
                                        "RetryInterceptor",
                                        "Request failed ($tryCount): ${e.message}"
                                )
                        }
                        tryCount++
                }

                // If we have a response, return it
                if (response != null) {
                        return response
                }

                // Otherwise throw the last exception
                throw exception ?: IOException("Request failed after $maxRetries retries")
        }

        private fun shouldRetry(code: Int): Boolean {
                // Retry on server errors (5xx) or request timeout (408)
                return code >= 500 || code == 408
        }
}

// This interceptor adds the auth token to requests
class AuthInterceptor(context: Context) : Interceptor {
        private val sharedPreferences: SharedPreferences =
                context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
                val token = sharedPreferences.getString("access_token", null)
                Log.e(
                        "AuthInterceptor!!!!!!!!!!!!!!!!",
                        "Retrieved token from SharedPreferences: $token"
                )
                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()

                token?.let {
                        // Skip adding token for login and register endpoints
                        val url = originalRequest.url.toString()
                        if (!url.contains("auth/login") && !url.contains("auth/register")) {
                                requestBuilder.addHeader("Authorization", "Bearer $it")
                                Log.d("AuthInterceptor", "Token added to header for URL: $url")
                        }
                }

                val request = requestBuilder.build()
                return chain.proceed(request)
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
        @SerializedName("lastOnline") val lastOnline: Long?
)

// --- DATA MODELS ---
data class UserRegistrationRequest(
        val username: String,
        val password: String,
        @SerializedName("userId") val userId: String,
        val email: String,
        val googleId: String
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
        val newPassword: String
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
        val match: String? // Keep match for backward compatibility if it's still being sent as the
// primary identifier
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
                                .retryOnConnectionFailure(true)
                                .addInterceptor(RetryInterceptor()) // Add retry logic
                                .addInterceptor(authInterceptor)
                                .addInterceptor(loggingInterceptor)
                                .addInterceptor { chain ->
                                        val request =
                                                chain.request()
                                                        .newBuilder()
                                                        .header("Connection", "close")
                                                        .build()
                                        chain.proceed(request)
                                }
                                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                                .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
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
