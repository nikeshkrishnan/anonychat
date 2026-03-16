
package com.example.anonychat.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class User(
    val id: String,
    val username: String,
    val profilePictureUrl: String?,
    val userId: String? = null  // Actual userId from backend (not extracted from email)
) : Parcelable
