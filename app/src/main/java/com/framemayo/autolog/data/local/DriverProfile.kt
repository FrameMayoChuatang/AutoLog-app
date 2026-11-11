package com.framemayo.autolog.data.local

import androidx.room.Embedded
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** 서버 운전자 식별 정보와 분리한 기기 내 표시 설정. 삭제해도 기존 운행의 driverId는 보존한다. */
@Entity(
    tableName = "driver_profiles",
    foreignKeys = [ForeignKey(DriverEntity::class, ["id"], ["driverId"], onDelete = ForeignKey.CASCADE)],
)
data class DriverProfileEntity(
    @PrimaryKey val driverId: Long,
    val displayName: String?,
    val color: Int = 0,
    val hidden: Boolean = false,
    @ColumnInfo(defaultValue = "0") val expression: Int = 0,
)

data class DriverProfile(
    @Embedded val driver: DriverEntity,
    val displayName: String,
    val color: Int,
    val expression: Int = 0,
)
