package com.crosssend.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.crosssend.app.data.model.RenderMode
import kotlinx.coroutines.flow.Flow

@Dao
interface DomainPreferenceDao {
    @Query("SELECT renderMode FROM domain_preferences WHERE host = :host LIMIT 1")
    suspend fun getRenderModeForHost(host: String): RenderMode?

    @Query("SELECT * FROM domain_preferences ORDER BY host ASC")
    fun getAllFlow(): Flow<List<DomainPreferenceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preference: DomainPreferenceEntity)

    @Query("DELETE FROM domain_preferences WHERE host = :host")
    suspend fun deleteByHost(host: String)
}
