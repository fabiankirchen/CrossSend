package com.crosssend.app.network

data class RemoteDavItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0,
    val lastModified: String? = null
)
