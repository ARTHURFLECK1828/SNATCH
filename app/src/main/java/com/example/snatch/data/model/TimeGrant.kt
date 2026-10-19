package com.example.snatch.data.model

data class TimeGrant(
    val grantId: String = "",
    val requestId: String = "",
    val recipientUid: String = "",
    val packageName: String = "",
    val minutes: Int = 0,
    val issuedAt: Long = System.currentTimeMillis()
)
