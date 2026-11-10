package com.framemayo.autolog.data.repository

import com.framemayo.autolog.data.local.DriverDao
import com.framemayo.autolog.data.local.DriverEntity
import kotlinx.coroutines.flow.Flow

interface DriverRepository {
    val all: Flow<List<DriverEntity>>
    suspend fun add(name: String, department: String): Long

    suspend fun upsertRemote(drivers: List<RemoteDriver>)
}

data class RemoteDriver(val remoteId: Long, val name: String, val department: String)

class DriverRepositoryImpl(private val dao: DriverDao) : DriverRepository {
    override val all: Flow<List<DriverEntity>> = dao.observeAll()

    override suspend fun add(name: String, department: String): Long =
        dao.insert(DriverEntity(name = name.trim(), department = department.trim()))

    override suspend fun upsertRemote(drivers: List<RemoteDriver>) {
        drivers.forEach { d ->
            val cur = dao.byRemoteId(d.remoteId)
            if (cur == null) dao.insert(DriverEntity(remoteId = d.remoteId, name = d.name, department = d.department))
            else dao.update(cur.copy(name = d.name, department = d.department))
        }
    }
}
