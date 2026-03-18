package com.heideen.smsbackup.model

data class MmsMessage(
    val id: Long,
    val threadId: Long,
    val timestampMs: Long,
    val msgBox: Int,
    val read: Int,
    val subject: String?,
    val addresses: String,
    val attachments: List<MmsAttachment> = emptyList()
)
