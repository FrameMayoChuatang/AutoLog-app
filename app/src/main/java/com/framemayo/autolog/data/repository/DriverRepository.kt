package com.framemayo.autolog.data.repository

import com.framemayo.autolog.data.local.DriverDao
import com.framemayo.autolog.data.local.DriverEntity
import com.framemayo.autolog.data.local.DriverProfile
import com.framemayo.autolog.data.local.DriverProfileEntity
import com.framemayo.autolog.data.local.DriverStatus
import kotlinx.coroutines.flow.Flow

interface DriverRepository {
    val all: Flow<List<DriverEntity>>
    val profileSettings: Flow<List<DriverProfileEntity>>
    val profiles: Flow<List<DriverProfile>>
    suspend fun addProfile(name: String, color: Int, expression: Int = 0): Long
    suspend fun editProfile(id: Long, name: String, color: Int, expression: Int = 0)
    suspend fun hideProfile(id: Long): Boolean
    suspend fun add(name: String, department: String): Long

    suspend fun remoteIdOf(localId: Long): String?

    suspend fun find(localId: Long): DriverEntity?

    suspend fun consentOf(driverId: Long): Boolean?

    suspend fun setLocationConsent(driverId: Long, consent: Boolean)

    suspend fun upsertRemote(drivers: List<RemoteDriver>)

    suspend fun snapshot(): List<DriverEntity>

    suspend fun link(localId: Long, remote: RemoteDriver)

    suspend fun consentPending(): List<DriverEntity>

    suspend fun markConsentSent(driver: DriverEntity)

    suspend fun clearServerLinks()
}

data class RemoteDriver(val remoteId: String, val name: String, val department: String, val status: DriverStatus)

class DriverRepositoryImpl(
    private val dao: DriverDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPending: () -> Unit = {},
) : DriverRepository {
    override val profileSettings = dao.observeProfileSettings()
    override val profiles: Flow<List<DriverProfile>> = dao.observeProfiles()

    override suspend fun addProfile(name: String, color: Int, expression: Int): Long {
        require(name.isNotBlank() && name.trim().length <= 40 && color in 0..6 && expression in 0..6)
        val id = dao.addProfile(name.trim(), color, expression)
        onPending()
        return id
    }

    override suspend fun editProfile(id: Long, name: String, color: Int, expression: Int) {
        require(name.isNotBlank() && name.trim().length <= 40 && color in 0..6 && expression in 0..6)
        val previous = dao.profile(id)
        dao.saveProfile(DriverProfileEntity(id, name.trim(), color, previous?.hidden ?: false, expression))
    }

    override suspend fun hideProfile(id: Long): Boolean = dao.hideProfile(id)

    override val all: Flow<List<DriverEntity>> = dao.observeAll()

    override suspend fun add(name: String, department: String): Long {
        val id = dao.insert(DriverEntity(name = name.trim(), department = department.trim()))
        onPending()
        return id
    }

    override suspend fun remoteIdOf(localId: Long): String? = dao.remoteIdOf(localId)

    override suspend fun find(localId: Long): DriverEntity? = dao.byId(localId)

    override suspend fun consentOf(driverId: Long): Boolean? = dao.consentOf(driverId)

    override suspend fun setLocationConsent(driverId: Long, consent: Boolean) {
        dao.setConsent(driverId, consent, clock())
        onPending()
    }

    override suspend fun upsertRemote(drivers: List<RemoteDriver>) =
        dao.upsertRemote(drivers.map { DriverEntity(remoteId = it.remoteId, name = it.name, department = it.department, status = it.status) })

    override suspend fun snapshot(): List<DriverEntity> = dao.all()

    override suspend fun link(localId: Long, remote: RemoteDriver) = dao.link(localId, remote.remoteId, remote.status)

    override suspend fun consentPending(): List<DriverEntity> = dao.consentPending()

    override suspend fun clearServerLinks() = dao.clearServerLinks()

    override suspend fun markConsentSent(driver: DriverEntity) {
        driver.consentUpdatedAt?.let { dao.markConsentSent(driver.id, it) }
    }
}
