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
        WHERE COALESCE(p.hidden, 0) = 0 AND (d.status IS NULL OR d.status != 'INACTIVE')
        ORDER BY (SELECT MAX(t.startedAt) FROM trips t WHERE t.driverId = d.id) DESC,
                 d.createdAt DESC, d.id DESC
    """)
    fun observeProfiles(): Flow<List<DriverProfile>>

    @Query("SELECT * FROM driver_profiles")
    fun observeProfileSettings(): Flow<List<DriverProfileEntity>>

    @Upsert
    suspend fun saveProfile(profile: DriverProfileEntity)

    @Transaction
    suspend fun addProfile(name: String, color: Int, expression: Int = 0): Long {
        val id = insert(DriverEntity(name = name, department = ""))
        saveProfile(DriverProfileEntity(id, null, color, expression = expression))
        return id
    }

    /** 프로필과 선택한 동의 상태를 함께 저장하여 부분 저장을 막는다. [consent]가 null이면 동의는 바꾸지 않는다. */
    @Transaction
    suspend fun saveProfileWithConsent(id: Long?, name: String, color: Int, expression: Int, consent: Boolean?, at: Long): Long {
        if (id == null) {
            val created = addProfile(name, color, expression)
            if (consent != null) setConsent(created, consent, at)
            return created
        }
        val driver = requireNotNull(byId(id)) { "프로필을 찾을 수 없습니다." }
        val previous = profile(id)
        saveProfile(DriverProfileEntity(id, name, color, previous?.hidden ?: false, expression))
        if (consent != null && driver.locationConsent != consent) setConsent(id, consent, at)
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

    @Query("SELECT * FROM drivers WHERE id = :id")
    suspend fun byId(id: Long): DriverEntity?

    @Query("SELECT remoteId FROM drivers WHERE id = :id")
    suspend fun remoteIdOf(id: Long): String?

    @Query("SELECT * FROM drivers WHERE remoteId = :remoteId")
    suspend fun byRemoteId(remoteId: String): DriverEntity?

    @Update
    suspend fun update(driver: DriverEntity)

    @Query("SELECT * FROM drivers ORDER BY id")
    suspend fun all(): List<DriverEntity>

    @Query("UPDATE drivers SET remoteId = :remoteId, status = :status WHERE id = :id AND remoteId IS NULL")
    suspend fun link(id: Long, remoteId: String, status: DriverStatus)

    @Query("UPDATE drivers SET remoteId = NULL, status = NULL, consentSentAt = NULL")
    suspend fun clearServerLinks()

    @Query("UPDATE drivers SET status = 'INACTIVE' WHERE remoteId IS NOT NULL AND remoteId NOT IN (:remoteIds)")
    suspend fun markMissingInactive(remoteIds: List<String>)

    @Query("SELECT * FROM drivers WHERE remoteId IS NOT NULL AND locationConsent IS NOT NULL AND consentSentAt IS NOT consentUpdatedAt ORDER BY id")
    suspend fun consentPending(): List<DriverEntity>

    @Query("UPDATE drivers SET consentSentAt = :at WHERE id = :id AND consentUpdatedAt = :at")
    suspend fun markConsentSent(id: Long, at: Long)

    @Transaction
    suspend fun upsertRemote(drivers: List<DriverEntity>) {
        drivers.forEach { d ->
            val cur = d.remoteId?.let { byRemoteId(it) }
            if (cur == null) insert(d) else update(cur.copy(name = d.name, department = d.department, status = d.status))
        }
        markMissingInactive(drivers.mapNotNull { it.remoteId })
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

    // 위치 조회 중 철회/개인 목적 변경이 발생해도 오래된 동의로 위치가 다시 생기지 않게 한다.
    @Query("""
        INSERT OR REPLACE INTO trip_locations (tripId, latitude, longitude, accuracyM, capturedAt)
        SELECT :tripId, :latitude, :longitude, :accuracyM, :capturedAt
        WHERE EXISTS (
            SELECT 1 FROM trips t JOIN drivers d ON d.id = t.driverId
            WHERE t.id = :tripId AND t.driverId = :driverId
              AND d.locationConsent = 1 AND t.purpose != 'PERSONAL'
        )
    """)
    suspend fun upsertIfConsented(tripId: Long, driverId: Long, latitude: Double, longitude: Double, accuracyM: Float?, capturedAt: Long): Long

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

    @Query("SELECT * FROM trips WHERE status = 'INTERRUPTED' ORDER BY id DESC LIMIT 1")
    suspend fun lastInterrupted(): TripEntity?

    @Query("SELECT * FROM trips WHERE status != 'ACTIVE' ORDER BY id DESC LIMIT 1")
    suspend fun lastEnded(): TripEntity?

    @Query("SELECT * FROM trips WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1")
    fun observeActive(): Flow<TripEntity?>

    @Query("UPDATE trips SET purpose = :purpose, purposeText = :purposeText, memo = :memo, syncState = 'PENDING', updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePurpose(id: Long, purpose: TripPurpose, purposeText: String?, memo: String?, updatedAt: Long)

    @Query("UPDATE trips SET driverId = :driverId, syncState = 'PENDING', sourceRevision = sourceRevision + 1, updatedAt = :updatedAt WHERE id = :id AND driverId IS NULL")
    suspend fun assignDriver(id: Long, driverId: Long, updatedAt: Long): Int

    @Query("""
        UPDATE trips SET endedAt = :endedAt, endOdometerKm = :km, lastOdometerKm = :km, status = 'COMPLETED',
            syncState = 'PENDING', sourceRevision = sourceRevision + 1, updatedAt = :updatedAt, endedBy = :endedBy,
            endFuelPercent = COALESCE(:fuel, endFuelPercent), endBatteryPercent = COALESCE(:battery, endBatteryPercent)
        WHERE id = :id AND status = 'ACTIVE'
    """)
    suspend fun complete(id: Long, endedAt: Long, km: Float, updatedAt: Long, endedBy: TripEndReason, fuel: Float?, battery: Float?): Int

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

    @Query("UPDATE trips SET serverVersion = :serverVersion, confirmSentAt = CASE WHEN updatedAt = :updatedAt THEN :updatedAt ELSE confirmSentAt END WHERE id = :id")
    suspend fun recordConfirm(id: Long, serverVersion: Long, updatedAt: Long)

    @Query("UPDATE trips SET ackedRevision = 0, serverVersion = 0, confirmSentAt = NULL, syncState = 'PENDING' WHERE syncState != 'SYNCED'")
    suspend fun clearServerState()

    @Query("UPDATE trips SET serverVersion = :serverVersion WHERE id = :id")
    suspend fun setServerVersion(id: Long, serverVersion: Long)

    @Query("UPDATE trips SET serverVersion = :serverVersion, sourceRevision = MAX(sourceRevision + 1, :serverRevision + 1) WHERE id = :id")
    suspend fun rebase(id: Long, serverVersion: Long, serverRevision: Long)

    @Query("DELETE FROM trips WHERE syncState = 'SYNCED' AND status != 'ACTIVE' AND purpose != 'UNCONFIRMED' AND syncedAt < :cutoff")
    suspend fun deleteSyncedBefore(cutoff: Long): Int

    @Query("UPDATE trips SET syncState = 'SYNCED', syncedAt = :at WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: Long, updatedAt: Long, at: Long)

    @Query("UPDATE trips SET syncState = :state WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun setSyncState(id: Long, updatedAt: Long, state: SyncState)
}

@Dao
interface TripStopDao {
    @Insert
    suspend fun insert(stop: TripStopEntity): Long

    @Query("SELECT * FROM trip_stops WHERE tripId = :tripId AND endedAt IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun open(tripId: Long): TripStopEntity?

    @Query("UPDATE trip_stops SET endedAt = :endedAt WHERE tripId = :tripId AND endedAt IS NULL")
    suspend fun close(tripId: Long, endedAt: Long)

    @Query("SELECT * FROM trip_stops WHERE tripId = :tripId ORDER BY startedAt")
    suspend fun forTrip(tripId: Long): List<TripStopEntity>

    @Query("SELECT * FROM trip_stops WHERE tripId = :tripId ORDER BY startedAt")
    fun observeForTrip(tripId: Long): Flow<List<TripStopEntity>>
}

@Dao
interface EnergyEventDao {
    @Insert
    suspend fun insert(event: EnergyEventEntity): Long

    @Query("SELECT * FROM energy_events ORDER BY detectedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<EnergyEventEntity>>

    @Query("SELECT * FROM energy_events WHERE tripId = :tripId ORDER BY detectedAt")
    suspend fun forTrip(tripId: Long): List<EnergyEventEntity>
}
