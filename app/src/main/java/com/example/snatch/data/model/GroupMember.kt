package com.example.snatch.data.model

data class GroupMember(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val role: String = "member",
    val joinedAt: Long = System.currentTimeMillis()
)
