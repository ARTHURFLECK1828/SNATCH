package com.example.snatch.data.model

data class Group(
    val groupId: String = "main",
    val name: String = "Snitch Shared Group",
    val inviteCode: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
