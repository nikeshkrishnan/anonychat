package com.example.anonychat.network

import android.content.Context
import android.content.SharedPreferences
import com.example.anonychat.model.User
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.GET // Make sure to import this
import retrofit2.http.Path   // And this
import android.util.Log
import okhttp3.Interceptor

// This interceptor adds the auth token to requests
class AuthInterceptor(context: Context) : Interceptor {
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val token = sharedPreferences.getString("access_token", null)
        Log.e("AuthInterceptor!!!!!!!!!!!!!!!!", "Retrieved token from SharedPreferences: $token")
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()

        token?.let {
            requestBuilder.addHeader("Authorization", "Bearer $it")
            Log.d("AuthInterceptor", "Token added to header for URL: ${originalRequest.url}")
        }

        val request = requestBuilder.build()
        return chain.proceed(request)
    }
}

data class GetPreferencesResponse(
    val gmail: String?,
    val age: Int?,
    val gender: String?,
    val preferredGender: String?,
    val romanceRange: RomanceRange?,
    val preferredAgeRange: AgeRange?,
    val random: Boolean?
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

data class UserDto(
    val id: String,
    val username: String,
    val email: String,
    val gender: String
)

data class UserLoginRequest(
    val username: String,
    val password: String
)

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
    suspend fun callMatch(@Path("gmail") gmail: String): Response<Void>
}


// --- NETWORK CLIENT (SINGLETON) ---
object NetworkClient {
    private const val BASE_URL = "http://192.168.1.66:8080/"
    private lateinit var retrofit: Retrofit

    // Lazily initialize the api service after `initialize` has been called
    val api: AuthApiService by lazy {
        if (!::retrofit.isInitialized) {
            throw UninitializedPropertyAccessException("NetworkClient must be initialized in Application class")
        }
        retrofit.create(AuthApiService::class.java)
    }

    // This function must be called from AnonychatApp.kt
    fun initialize(context: Context) {
        if (::retrofit.isInitialized) return

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        // Create an instance of our custom interceptor
        val authInterceptor = AuthInterceptor(context)

        val httpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addInterceptor(authInterceptor) // Add the authentication interceptor here
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
}
