package com.b1nd.autolog.data.repository

import com.b1nd.autolog.data.local.DriverDao
import com.b1nd.autolog.data.local.DriverEntity
import kotlinx.coroutines.flow.Flow

class DriverRepository(private val dao: DriverDao) {
    val all: Flow<List<DriverEntity>> = dao.observeAll()

    suspend fun add(name: String, department: String): Long =
        dao.insert(DriverEntity(name = name.trim(), department = department.trim()))
}
