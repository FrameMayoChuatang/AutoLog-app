package com.framemayo.autolog.data.repository

import com.framemayo.autolog.data.local.DriverDao
import com.framemayo.autolog.data.local.DriverEntity
import kotlinx.coroutines.flow.Flow

interface DriverRepository {
    val all: Flow<List<DriverEntity>>
    suspend fun add(name: String, department: String): Long

    suspend fun remoteIdOf(localId: Long): Long?

    suspend fun consentOf(driverId: Long): Boolean?

    suspend fun setLocationConsent(driverId: Long, consent: Boolean)

    suspend fun upsertRemote(drivers: List<RemoteDriver>)
}

data class RemoteDriver(val remoteId: Long, val name: String, val department: String)

class DriverRepositoryImpl(
    private val dao: DriverDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : DriverRepository {
    override val all: Flow<List<DriverEntity>> = dao.observeAll()

    override suspend fun add(name: String, department: String): Long =
        dao.insert(DriverEntity(name = name.trim(), department = department.trim()))

    override suspend fun remoteIdOf(localId: Long): Long? = dao.remoteIdOf(localId)

    override suspend fun consentOf(driverId: Long): Boolean? = dao.consentOf(driverId)

    override suspend fun setLocationConsent(driverId: Long, consent: Boolean) =
        dao.setConsent(driverId, consent, clock())

    override suspend fun upsertRemote(drivers: List<RemoteDriver>) =
        dao.upsertRemote(drivers.map { DriverEntity(remoteId = it.remoteId, name = it.name, department = it.department) })
}
