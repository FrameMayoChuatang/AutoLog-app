package com.framemayo.autolog.data.repository

import com.framemayo.autolog.data.local.DriverDao
import com.framemayo.autolog.data.local.DriverEntity
import com.framemayo.autolog.data.local.DriverProfile
import com.framemayo.autolog.data.local.DriverProfileEntity
import kotlinx.coroutines.flow.Flow

interface DriverRepository {
    val all: Flow<List<DriverEntity>>
    val profiles: Flow<List<DriverProfile>>
    suspend fun addProfile(name: String, color: Int): Long
    suspend fun editProfile(id: Long, name: String, color: Int)
    suspend fun hideProfile(id: Long): Boolean
    suspend fun add(name: String, department: String): Long

    suspend fun remoteIdOf(localId: Long): String?

    suspend fun consentOf(driverId: Long): Boolean?

    suspend fun setLocationConsent(driverId: Long, consent: Boolean)

    suspend fun upsertRemote(drivers: List<RemoteDriver>)
}

data class RemoteDriver(val remoteId: String, val name: String, val department: String)

class DriverRepositoryImpl(
    private val dao: DriverDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : DriverRepository {
    override val profiles: Flow<List<DriverProfile>> = dao.observeProfiles()

    override suspend fun addProfile(name: String, color: Int): Long {
        require(name.isNotBlank() && name.trim().length <= 40 && color in 0..4)
        return dao.addProfile(name.trim(), color)
    }

    override suspend fun editProfile(id: Long, name: String, color: Int) {
        require(name.isNotBlank() && name.trim().length <= 40 && color in 0..4)
        val previous = dao.profile(id)
        dao.saveProfile(DriverProfileEntity(id, name.trim(), color, previous?.hidden ?: false))
    }

    override suspend fun hideProfile(id: Long): Boolean = dao.hideProfile(id)

    override val all: Flow<List<DriverEntity>> = dao.observeAll()

    override suspend fun add(name: String, department: String): Long =
        dao.insert(DriverEntity(name = name.trim(), department = department.trim()))

    override suspend fun remoteIdOf(localId: Long): String? = dao.remoteIdOf(localId)

    override suspend fun consentOf(driverId: Long): Boolean? = dao.consentOf(driverId)

    override suspend fun setLocationConsent(driverId: Long, consent: Boolean) =
        dao.setConsent(driverId, consent, clock())

    override suspend fun upsertRemote(drivers: List<RemoteDriver>) =
        dao.upsertRemote(drivers.map { DriverEntity(remoteId = it.remoteId, name = it.name, department = it.department) })
}
