package com.framemayo.autolog.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DriverDao {
    @Insert
    suspend fun insert(driver: DriverEntity): Long

    @Query("SELECT * FROM drivers ORDER BY name")
    fun observeAll(): Flow<List<DriverEntity>>
}

@Dao
interface TripDao {
    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun get(id: Long): TripEntity?

    @Query("SELECT * FROM trips WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1")
    suspend fun active(): TripEntity?

    @Query("SELECT * FROM trips WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1")
    fun observeActive(): Flow<TripEntity?>

    @Query("UPDATE trips SET lastOdometerKm = :km, lastSavedAt = :at WHERE id = :id")
    suspend fun heartbeat(id: Long, km: Float, at: Long)

    @Query("SELECT * FROM trips ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TripEntity>>
    @Query("SELECT * FROM trips WHERE status = 'INTERRUPTED' OR (status = 'COMPLETED' AND purpose = 'UNCONFIRMED') ORDER BY startedAt DESC")
    fun observeNeedsReview(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE synced = 0 AND status != 'ACTIVE'")
    suspend fun unsynced(): List<TripEntity>
}
