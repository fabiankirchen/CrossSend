package com.crosssend.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.crosssend.app.data.model.RenderMode
import java.net.URI

@Entity(tableName = "domain_preferences")
data class DomainPreferenceEntity(
    @PrimaryKey
    val host: String,
    val renderMode: RenderMode,
    val updatedAt: Long = System.currentTimeMillis()
)

object DomainHostUtils {
    fun normalizeHost(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val host = URI(url.trim()).host ?: return null
            host.lowercase()
                .removePrefix("www.")
                .trim('.')
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
