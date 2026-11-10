package com.framemayo.autolog.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "drivers")
data class DriverEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val department: String,
    val createdAt: Long = System.currentTimeMillis(),
)

enum class TripStatus {
    ACTIVE,
    COMPLETED,
    INTERRUPTED,
}
enum class TripPurpose { UNCONFIRMED, BUSINESS_COMMUTE, BUSINESS_GENERAL, PERSONAL }

@Entity(tableName = "trips", indices = [Index("status"), Index("startedAt")])
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    val synced: Boolean = false,
)

fun TripEntity.distanceKm(): Float = (endOdometerKm ?: lastOdometerKm) - startOdometerKm

fun TripEntity.durationMs(): Long = (endedAt ?: lastSavedAt) - startedAt
