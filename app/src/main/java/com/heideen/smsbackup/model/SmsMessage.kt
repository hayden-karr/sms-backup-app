package com.heideen.smsbackup.model

data class SmsMessage(
    val id: Long,
    val threadId: Long,
    val address: String?,
    val body: String?,
    val timestamp: Long,
    val type: Int,
    val read: Int,
    val status: Int,
    val serviceCenter: String?
)
