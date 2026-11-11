package com.framemayo.autolog.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 앱에 포함된 nodpi 이미지에만 사용한다. Bitmap은 공유하므로 캐시에서 제거할 때 recycle하지 않는다. */
internal class AvatarArtworkLoader(
    maxBytes: Int = 24 * 1024 * 1024,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val decode: (Resources, Int, Int) -> Bitmap = { resources, resourceId, sampleSize ->
        requireNotNull(BitmapFactory.decodeResource(resources, resourceId, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }))
    },
) {
    private data class Key(val resourceId: Int, val sampleSize: Int)
    private val cache = object : LruCache<Key, Bitmap>(maxBytes) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount
    }
    private val decodeLock = Mutex()

    fun cached(resourceId: Int, sampleSize: Int): Bitmap? = cache.get(Key(resourceId, sampleSize))

    suspend fun load(resources: Resources, resourceId: Int, sampleSize: Int): Bitmap = withContext(dispatcher) {
        // 목록 진입 시 여러 카드가 같은 이미지를 동시에 요청해도 디코딩은 한 번만 수행한다.
        decodeLock.withLock {
            val key = Key(resourceId, sampleSize)
            cache.get(key) ?: decode(resources, resourceId, sampleSize).also { cache.put(key, it) }
        }
    }
}
