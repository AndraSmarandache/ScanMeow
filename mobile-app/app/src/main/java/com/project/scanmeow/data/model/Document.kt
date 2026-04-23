package com.project.scanmeow.data.model

data class Document(
    val id: Long = 0L,
    val name: String,
    val imagePath: String,
    val pdfPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val sizeBytes: Long = 0L
)
