package com.project.scanmeow.data

data class UserCloudDocument(
    val docId: String,
    val fileName: String,
    val storagePath: String,
    val createdAtMillis: Long,
)
