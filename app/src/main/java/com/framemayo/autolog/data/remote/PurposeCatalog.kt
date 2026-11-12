package com.framemayo.autolog.data.remote

import com.framemayo.autolog.data.local.PurposeEntity

/** 회사의 활성 목적 목록을 조회한다. 실패 시 직접 입력을 계속 쓸 수 있도록 null로 구분한다. */
class PurposeCatalog(private val server: ServerApi) {
    suspend fun load(): List<PurposeEntity>? {
        val response = server.call("GET", "/api/device/catalog/purposes")
        if (response !is ApiResult.Http || !response.ok) return null
        val array = response.array ?: return null
        return runCatching {
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                PurposeEntity(item.getString("id"), item.getString("name"), item.getBoolean("active"))
            }.filter { it.active && it.name.isNotBlank() && it.name.length <= 200 }
        }.getOrNull()
    }
}
