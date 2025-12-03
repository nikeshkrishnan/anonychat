package com.example.anonychat.network

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import android.util.Log // Import Log
import retrofit2.http.Path

// 1. The Data Model
data class UserRegistrationRequest(
    val username: String,
    val password: String,
    @SerializedName("userId")
    val userId: String,
    val email: String,
    val googleId: String
)

data class UserLoginRequest(
    val username: String,
    val password: String
)
data class UserResetPasswordRequest(
    @SerializedName("userId")
    val userId: String,
    val username: String,
    val newPassword: String
)

// 2. The API Interface
interface AuthApiService {
    @Headers("Content-Type: application/json")
    @POST("auth/register")
    suspend fun registerUser(@Body request: UserRegistrationRequest): Response<Void>

    @Headers("Content-Type: application/json")
    @POST("auth/login")
    suspend fun loginUser(@Body request: UserLoginRequest): Response<Void>

    // Change the endpoint back to its original form
    @Headers("Content-Type: application/json")
    @POST("auth/reset-password") // The URL does not have a parameter anymore
    suspend fun resetPassword(@Body request: UserResetPasswordRequest): Response<Void>
}

// 3. The Network Client (Singleton)
object NetworkClient {
    private const val BASE_URL = "http://192.168.1.66:8080/"

    // 1. Standard Logging Interceptor (Logs everything to "OkHttp" tag)
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    // 2. Custom Tracing Interceptor (Optional: Adds a specific tag for easier filtering)
    private val responseTracingInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)

        Log.d("API_TRACE", "Response Code: ${response.code} for ${request.url}")

        // Note: We don't read response.body().string() here because it consumes the stream
        // and would crash the app. The HttpLoggingInterceptor handles that safely.

        response
    }

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor) // Standard detailed logs
        .addInterceptor(responseTracingInterceptor) // Custom simple trace
        .build()

    val api: AuthApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthApiService::class.java)
    }
}
