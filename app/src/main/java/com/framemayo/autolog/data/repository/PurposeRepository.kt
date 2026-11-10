package com.framemayo.autolog.data.repository

import com.framemayo.autolog.data.local.PurposeDao
import com.framemayo.autolog.data.local.PurposeEntity
import kotlinx.coroutines.flow.Flow

interface PurposeRepository {
    val active: Flow<List<PurposeEntity>>
    suspend fun upsertAll(purposes: List<PurposeEntity>)
}

class PurposeRepositoryImpl(private val dao: PurposeDao) : PurposeRepository {
    override val active: Flow<List<PurposeEntity>> = dao.observeActive()
    override suspend fun upsertAll(purposes: List<PurposeEntity>) = dao.upsertAll(purposes)
}
