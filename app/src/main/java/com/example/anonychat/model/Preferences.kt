package com.example.anonychat.model

data class Preferences(
    val romanceMin: Float = 1f,
    val romanceMax: Float = 5f,
    val gender: String = "male"
)
