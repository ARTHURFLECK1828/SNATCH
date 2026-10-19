package com.example.snatch.data.model

data class RequestVote(
    val uid: String = "",
    val voterName: String = "",
    val approved: Boolean = true,
    val votedAt: Long = System.currentTimeMillis()
)
