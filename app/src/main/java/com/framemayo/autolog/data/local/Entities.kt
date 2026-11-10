package com.framemayo.autolog.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "drivers", indices = [Index("remoteId", unique = true)])
data class DriverEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remoteId: Long? = null,
    val name: String,
    val department: String,
    val createdAt: Long = System.currentTimeMillis(),
    val locationConsent: Boolean? = null,
    val consentUpdatedAt: Long? = null,
)

@Entity(
    tableName = "trip_locations",
    foreignKeys = [ForeignKey(TripEntity::class, ["id"], ["tripId"], onDelete = ForeignKey.CASCADE)],
)
data class TripLocationEntity(
    @PrimaryKey val tripId: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float?,
    val capturedAt: Long,
)

enum class TripStatus {
    ACTIVE,
    COMPLETED,
    INTERRUPTED,
}
enum class SyncState { PENDING, SYNCED, FAILED }

@Entity(tableName = "purposes")
data class PurposeEntity(
    @PrimaryKey val remoteId: Long,
    val name: String,
    val active: Boolean = true,
)

enum class TripPurpose { UNCONFIRMED, BUSINESS_COMMUTE, BUSINESS_GENERAL, PERSONAL }

@Entity(tableName = "trips", indices = [Index("status"), Index("startedAt"), Index("uuid", unique = true)])
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val driverId: Long?,
    val startedAt: Long,
    val endedAt: Long? = null,
    val startOdometerKm: Float,
    val endOdometerKm: Float? = null,
    val lastOdometerKm: Float,
    val lastSavedAt: Long,
    val status: TripStatus = TripStatus.ACTIVE,
    val purpose: TripPurpose = TripPurpose.UNCONFIRMED,
    val memo: String? = null,
    val syncState: SyncState = SyncState.PENDING,
    val updatedAt: Long = System.currentTimeMillis(),
)

fun TripEntity.distanceKm(): Float = (endOdometerKm ?: lastOdometerKm) - startOdometerKm

fun TripEntity.durationMs(): Long = (endedAt ?: lastSavedAt) - startedAt
