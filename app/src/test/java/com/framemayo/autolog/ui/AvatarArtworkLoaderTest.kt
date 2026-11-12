package com.framemayo.autolog.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarArtworkLoaderTest {
    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    @Test fun concurrentCardsDecodeOnceOffTheCallerThread() = runBlocking {
        val calls = AtomicInteger()
        val caller = Thread.currentThread()
        var decodeThread: Thread? = null
        val loader = AvatarArtworkLoader(decode = { _, _, _ ->
            calls.incrementAndGet()
            decodeThread = Thread.currentThread()
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        })
        val images = (1..100).map { async { loader.load(resources, 1, 4) } }.awaitAll()
        assertEquals(1, calls.get())
        assertNotSame(caller, decodeThread)
        images.forEach { assertSame(images.first(), it) }
    }

    @Test fun cacheSeparatesResolutionsAndEvictsWithoutRecyclingVisibleImages() = runBlocking {
        val loader = AvatarArtworkLoader(maxBytes = 1024, decode = { _, _, _ -> Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888) })
        val first = loader.load(resources, 1, 4)
        val second = loader.load(resources, 1, 1)
        assertNotSame(first, second)
        assertNull(loader.cached(1, 4))
        assertSame(second, loader.cached(1, 1))
        assertFalse(first.isRecycled)
        assertNotSame(first, loader.load(resources, 1, 4))
    }

    @Test fun thumbnailDecodeUsesAtMostOneFifteenthOfOriginalBitmapMemory() = runBlocking {
        val loader = AvatarArtworkLoader()
        ProfileExpressions.forEach { face ->
            val original = BitmapFactory.decodeResource(resources, face.artwork)
            val thumbnail = loader.load(resources, face.artwork, 4)
            assertTrue("${face.name}: thumbnail ${thumbnail.allocationByteCount} vs original ${original.allocationByteCount}", thumbnail.allocationByteCount * 15 < original.allocationByteCount)
            assertTrue(thumbnail.width in 313..314)
            original.recycle()
        }
    }
}
