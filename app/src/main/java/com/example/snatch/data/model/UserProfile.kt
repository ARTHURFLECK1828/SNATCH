package com.example.snatch.data.model

data class UserProfile(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val groupId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
