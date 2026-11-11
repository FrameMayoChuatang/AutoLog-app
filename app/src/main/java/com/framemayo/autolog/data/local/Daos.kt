package com.framemayo.autolog.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DriverDao {
    @Query("""
        SELECT d.*, COALESCE(p.displayName, d.name) AS displayName, COALESCE(p.color, 0) AS color,
               COALESCE(p.expression, 0) AS expression
        FROM drivers d LEFT JOIN driver_profiles p ON p.driverId = d.id
        WHERE COALESCE(p.hidden, 0) = 0
        ORDER BY (SELECT MAX(t.startedAt) FROM trips t WHERE t.driverId = d.id) DESC,
                 d.createdAt DESC, d.id DESC
    """)
    fun observeProfiles(): Flow<List<DriverProfile>>

    @Upsert
    suspend fun saveProfile(profile: DriverProfileEntity)

    @Transaction
    suspend fun addProfile(name: String, color: Int, expression: Int = 0): Long {
        val id = insert(DriverEntity(name = name, department = ""))
        saveProfile(DriverProfileEntity(id, null, color, expression = expression))
        return id
    }

    @Query("SELECT * FROM driver_profiles WHERE driverId = :id")
    suspend fun profile(id: Long): DriverProfileEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM trips WHERE driverId = :id AND status = 'ACTIVE')")
    suspend fun hasActiveTrip(id: Long): Boolean

    @Transaction
    suspend fun hideProfile(id: Long): Boolean {
        if (hasActiveTrip(id)) return false
        saveProfile((profile(id) ?: DriverProfileEntity(id, null)).copy(hidden = true))
        return true
    }

    @Insert
    suspend fun insert(driver: DriverEntity): Long

    @Query("SELECT * FROM drivers ORDER BY name")
    fun observeAll(): Flow<List<DriverEntity>>

    @Query("UPDATE drivers SET locationConsent = :consent, consentUpdatedAt = :at WHERE id = :id")
    suspend fun setConsent(id: Long, consent: Boolean, at: Long)

    @Query("SELECT locationConsent FROM drivers WHERE id = :id")
    suspend fun consentOf(id: Long): Boolean?

    @Query("SELECT remoteId FROM drivers WHERE id = :id")
    suspend fun remoteIdOf(id: Long): String?

    @Query("SELECT * FROM drivers WHERE remoteId = :remoteId")
    suspend fun byRemoteId(remoteId: String): DriverEntity?

    @Update
    suspend fun update(driver: DriverEntity)

    @Transaction
    suspend fun upsertRemote(drivers: List<DriverEntity>) {
        drivers.forEach { d ->
            val cur = d.remoteId?.let { byRemoteId(it) }
            if (cur == null) insert(d) else update(cur.copy(name = d.name, department = d.department))
        }
    }
}

@Dao
abstract class PurposeDao {
    @Upsert
    protected abstract suspend fun upsertAll(purposes: List<PurposeEntity>)

    @Query("UPDATE purposes SET active = 0")
    protected abstract suspend fun deactivateAll()

    @Query("SELECT * FROM purposes WHERE active = 1 ORDER BY name")
    abstract fun observeActive(): Flow<List<PurposeEntity>>

    @Transaction
    open suspend fun replaceAll(purposes: List<PurposeEntity>) {
        deactivateAll()
        upsertAll(purposes)
    }
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

    @Query("SELECT * FROM trips WHERE syncState = 'PENDING' ORDER BY startedAt")
    suspend fun pendingSync(): List<TripEntity>

    @Query("UPDATE trips SET ackedRevision = :revision, serverVersion = :serverVersion WHERE id = :id AND ackedRevision < :revision")
    suspend fun recordAck(id: Long, revision: Long, serverVersion: Long)

    @Query("UPDATE trips SET serverVersion = :serverVersion, sourceRevision = MAX(sourceRevision + 1, :serverRevision + 1) WHERE id = :id")
    suspend fun rebase(id: Long, serverVersion: Long, serverRevision: Long)

    @Query("DELETE FROM trips WHERE syncState = 'SYNCED' AND status != 'ACTIVE' AND syncedAt < :cutoff")
    suspend fun deleteSyncedBefore(cutoff: Long): Int

    @Query("UPDATE trips SET syncState = 'SYNCED', syncedAt = :at WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: Long, updatedAt: Long, at: Long)

    @Query("UPDATE trips SET syncState = :state WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun setSyncState(id: Long, updatedAt: Long, state: SyncState)
}
