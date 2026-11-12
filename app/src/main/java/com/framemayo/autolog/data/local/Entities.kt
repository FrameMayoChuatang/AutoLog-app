package com.framemayo.autolog.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class DriverStatus { PENDING, ACTIVE, INACTIVE }

@Entity(tableName = "drivers", indices = [Index("remoteId", unique = true)])
data class DriverEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remoteId: String? = null, // 서버 운전자 UUID
    val name: String,
    val department: String,
    val createdAt: Long = System.currentTimeMillis(),
    val locationConsent: Boolean? = null,
    val consentUpdatedAt: Long? = null,
    val status: DriverStatus? = null,
    val consentSentAt: Long? = null,
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

/** 운행을 시작한 방식. AUTO는 앱 A가 이동을 감지해 운전자 없이 시작한 운행. */
enum class TripTrigger { MANUAL, AUTO }

/** 운행이 끝난 방식. RECOVERED는 강제 종료 후 실측 종료값을 확보해 완료한 운행. */
enum class TripEndReason { MANUAL, AUTO_PARKED, RECOVERED }

@Entity(tableName = "purposes")
data class PurposeEntity(
    @PrimaryKey val remoteId: String,
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
    val syncedAt: Long? = null,
    val sourceRevision: Long = 1,
    val ackedRevision: Long = 0,
    val serverVersion: Long = 0,
    val confirmSentAt: Long? = null,
    val purposeText: String? = null,
    @ColumnInfo(defaultValue = "MANUAL") val startedBy: TripTrigger = TripTrigger.MANUAL,
    val endedBy: TripEndReason? = null,
    val startFuelPercent: Float? = null,
    val endFuelPercent: Float? = null,
    val startBatteryPercent: Float? = null,
    val endBatteryPercent: Float? = null,
)

/** 운행 시작·종료 시점의 연료량·배터리 잔량(%). 지원하지 않는 차량은 null. */
data class EnergyLevels(val fuelPercent: Float? = null, val batteryPercent: Float? = null)

/** 운행 중 주차(P) 구간. 종료 버튼을 누르지 않은 중간 정차는 같은 운행 안에 기록한다. */
@Entity(
    tableName = "trip_stops",
    foreignKeys = [ForeignKey(TripEntity::class, ["id"], ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class TripStopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val startedAt: Long,
    val endedAt: Long? = null,
    val odometerKm: Float,
)

enum class EnergyEventKind { REFUEL, CHARGE }

/**
 * 감지한 주유·충전. 금액·양은 설정 단가와 용량으로 계산한 추정치다.
 * 운행이 보존 기간으로 삭제되어도 이벤트는 남도록 tripId는 SET NULL.
 */
@Entity(
    tableName = "energy_events",
    foreignKeys = [ForeignKey(TripEntity::class, ["id"], ["tripId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("tripId"), Index("detectedAt")],
)
data class EnergyEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: EnergyEventKind,
    val tripId: Long?,
    val detectedAt: Long,
    val odometerKm: Float?,
    val beforePercent: Float,
    val afterPercent: Float,
    val estimatedAmount: Float?,
    val estimatedCostWon: Long?,
)

fun TripEntity.distanceKm(): Float = (endOdometerKm ?: lastOdometerKm) - startOdometerKm

fun TripEntity.durationMs(): Long = (endedAt ?: lastSavedAt) - startedAt
