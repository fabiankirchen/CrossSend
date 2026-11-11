package com.crosssend.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "feeds")
data class FeedEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val url: String,
    val description: String? = null,
    val iconUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastFetchedAt: Long = 0L
)
