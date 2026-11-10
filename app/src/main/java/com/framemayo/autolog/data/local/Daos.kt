package com.framemayo.autolog.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DriverDao {
    @Insert
    suspend fun insert(driver: DriverEntity): Long

    @Query("SELECT * FROM drivers ORDER BY name")
    fun observeAll(): Flow<List<DriverEntity>>

    @Query("UPDATE drivers SET locationConsent = :consent, consentUpdatedAt = :at WHERE id = :id")
    suspend fun setConsent(id: Long, consent: Boolean, at: Long)

    @Query("SELECT remoteId FROM drivers WHERE id = :id")
    suspend fun remoteIdOf(id: Long): Long?

    @Query("SELECT * FROM drivers WHERE remoteId = :remoteId")
    suspend fun byRemoteId(remoteId: Long): DriverEntity?

    @Update
    suspend fun update(driver: DriverEntity)
}

@Dao
interface PurposeDao {
    @Upsert
    suspend fun upsertAll(purposes: List<PurposeEntity>)

    @Query("SELECT * FROM purposes WHERE active = 1 ORDER BY name")
    fun observeActive(): Flow<List<PurposeEntity>>
}

@Dao
interface TripLocationDao {
    @Upsert
    suspend fun upsert(location: TripLocationEntity)

    @Query("SELECT * FROM trip_locations WHERE tripId = :tripId")
    suspend fun get(tripId: Long): TripLocationEntity?

    @Query("DELETE FROM trip_locations WHERE tripId = :tripId")
    suspend fun delete(tripId: Long)

    @Query("DELETE FROM trip_locations WHERE tripId IN (SELECT id FROM trips WHERE driverId = :driverId)")
    suspend fun deleteByDriver(driverId: Long)
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

    @Query("SELECT * FROM trips WHERE syncState != 'SYNCED' AND status != 'ACTIVE' ORDER BY startedAt")
    suspend fun pendingSync(): List<TripEntity>

    @Query("DELETE FROM trips WHERE syncState = 'SYNCED' AND status != 'ACTIVE' AND updatedAt < :cutoff")
    suspend fun deleteSyncedBefore(cutoff: Long): Int

    @Query("UPDATE trips SET syncState = :state WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun setSyncState(id: Long, updatedAt: Long, state: SyncState)
}
