package com.example.snatch.data.model

data class TimeRequest(
    val requestId: String = "",
    val requesterUid: String = "",
    val requesterName: String = "",
    val packageName: String = "",
    val appName: String = "",
    val requestedMinutes: Int = 0,
    val reason: String = "",
    val status: String = "pending", // "pending", "approved", "rejected"
    val createdAt: Long = System.currentTimeMillis(),
    val approveVotesCount: Int = 0,
    val rejectVotesCount: Int = 0
)
