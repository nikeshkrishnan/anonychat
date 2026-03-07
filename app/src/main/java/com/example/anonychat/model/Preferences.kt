package com.example.anonychat.model

data class Preferences(
        val romanceMin: Float = 1f,
        val romanceMax: Float = 5f,
        val gender: String = "male",
        val isOnline: Boolean = false,
        val lastSeen: Long = 0L,
        val giftedMeARose: Boolean = false,
        val hasTakenBackRose: Boolean = false
)
