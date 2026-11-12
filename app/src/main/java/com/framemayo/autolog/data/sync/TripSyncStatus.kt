package com.framemayo.autolog.data.sync

import com.framemayo.autolog.data.local.DriverEntity
import com.framemayo.autolog.data.local.DriverStatus
import com.framemayo.autolog.data.local.SyncState
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.local.TripStatus

enum class HoldReason { NOT_LINKED, INTERRUPTED, NO_DRIVER, DRIVER_UNREGISTERED, DRIVER_PENDING, DRIVER_INACTIVE }

sealed interface TripSyncStatus {
    data object Synced : TripSyncStatus

    data object Waiting : TripSyncStatus

    data class Held(val reason: HoldReason) : TripSyncStatus

    data object Failed : TripSyncStatus
}

fun TripEntity.syncStatus(driver: DriverEntity?, linked: Boolean): TripSyncStatus = when {
    syncState == SyncState.SYNCED -> TripSyncStatus.Synced
    syncState == SyncState.FAILED -> TripSyncStatus.Failed
    !linked -> TripSyncStatus.Held(HoldReason.NOT_LINKED)
    sourceRevision > ackedRevision -> TripSyncStatus.Waiting
    purpose == TripPurpose.UNCONFIRMED || confirmSentAt == updatedAt -> TripSyncStatus.Waiting
    status == TripStatus.INTERRUPTED -> TripSyncStatus.Held(HoldReason.INTERRUPTED)
    status != TripStatus.COMPLETED -> TripSyncStatus.Waiting
    driverId == null || driver == null -> TripSyncStatus.Held(HoldReason.NO_DRIVER)
    driver.remoteId == null -> TripSyncStatus.Held(HoldReason.DRIVER_UNREGISTERED)
    driver.status == DriverStatus.INACTIVE -> TripSyncStatus.Held(HoldReason.DRIVER_INACTIVE)
    driver.status != DriverStatus.ACTIVE -> TripSyncStatus.Held(HoldReason.DRIVER_PENDING)
    else -> TripSyncStatus.Waiting
}
