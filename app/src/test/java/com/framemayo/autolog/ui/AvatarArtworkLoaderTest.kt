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
    @Test fun expressionLayersKeepHighlightsAndRemoveTheColoredBody() = runBlocking {
        val loader = AvatarArtworkLoader()
        val sheet = Bitmap.createBitmap(360 * 7, 230, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(sheet)
        canvas.drawColor(android.graphics.Color.LTGRAY)
        for (sample in listOf(1, 2, 4)) {
            ProfileExpressions.forEachIndexed { index, face ->
                val eyes = loader.load(resources, face.artwork, sample, eyesOnly = true)
                assertSame(eyes, loader.load(resources, face.artwork, sample, eyesOnly = true))
                var opaque = 0
                var highlights = 0
                for (y in 0 until eyes.height) for (x in 0 until eyes.width) {
                    val pixel = eyes.getPixel(x, y)
                    val alpha = android.graphics.Color.alpha(pixel)
                    if (x == 0 || y == 0 || x == eyes.width - 1 || y == eyes.height - 1) {
                        assertEquals("${face.name}: crop boundary must be transparent", 0, alpha)
                    }
                    if (alpha > 0) opaque++
                    if (alpha > 0 && android.graphics.Color.red(pixel) > 220 && android.graphics.Color.green(pixel) > 220 && android.graphics.Color.blue(pixel) > 220) highlights++
                }
                assertTrue("${face.name}: eyes must remain", opaque > eyes.width * eyes.height / 10)
                assertTrue("${face.name}: body must be removed", opaque < eyes.width * eyes.height * 0.65)
                if (index in listOf(0, 1, 2, 3)) assertTrue("${face.name}: white highlight must remain", highlights > 0)
                if (sample == 1) canvas.drawBitmap(eyes, index * 360f, 0f, null)
            }
        }
        val output = java.io.File("build/reports/driver-selection/expression-layers.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Unit
    }

}
