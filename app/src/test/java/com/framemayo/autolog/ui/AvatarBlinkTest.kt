package com.framemayo.autolog.ui

import android.app.Application
import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import com.framemayo.autolog.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarBlinkTest {
    @Test fun closingEyesCompressesTheWholeEyeWithoutLeavingSpikes() {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources
        val body = BitmapFactory.decodeResource(resources, R.drawable.driver_cloud_body)
        val source = Class.forName("com.framemayo.autolog.ui.DriverAvatarKt")
            .getDeclaredField("AvatarShader").apply { isAccessible = true }.get(null) as String
        val sheet = Bitmap.createBitmap(1290, 966, Bitmap.Config.ARGB_8888)
        val sheetCanvas = Canvas(sheet)
        listOf(0, 2, 3).forEachIndexed { row, expression ->
            val eyes = extractAvatarEyes(BitmapFactory.decodeResource(resources, ProfileExpressions[expression].artwork))
            val shader = RuntimeShader(source).apply {
                setInputShader("artwork", BitmapShader(body, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
                setInputShader("eyes", BitmapShader(eyes, Shader.TileMode.DECAL, Shader.TileMode.DECAL))
                setFloatUniform("viewport", 860f, 645f)
                setFloatUniform("eyeScale", 1f, 1f)
                setFloatUniform("recolor", 0f)
                setFloatUniform("eyeTop", if (expression == 3) 565f else 495f)
                setFloatUniform("sourceHue", 197f / 360f)
                setFloatUniform("targetHue", 197f / 360f)
                setFloatUniform("saturationScale", 1f)
                setFloatUniform("leftEye", if (expression == 0) 566f else 556f, if (expression == 3) 625f else 605f)
                setFloatUniform("rightEye", if (expression == 2) -1000f else 714f, if (expression == 3) 630f else 616f)
            }
            var previousHeight = Int.MAX_VALUE
            var openFrame: Bitmap? = null
            listOf(0f, .5f, 1f).forEachIndexed { column, blink ->
                shader.setFloatUniform("blink", blink)
                val frame = Bitmap.createBitmap(860, 645, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(frame)
                // 테스트에서는 Native Skia로 실제 AGSL을 평가하되 Android의 소프트웨어 캔버스 제한을 해제한다.
                Class.forName("android.graphics.BaseCanvas").getMethod("setHwFeaturesInSwModeEnabled", Boolean::class.javaPrimitiveType)
                    .invoke(canvas, true)
                canvas.drawRect(0f, 0f, 860f, 645f, Paint().apply { this.shader = shader })
                val leftRows = mutableSetOf<Int>()
                val rightRows = mutableSetOf<Int>()
                for (y in (if (expression == 3) 255 else 185) until 415) for (x in 245 until 605) {
                    val pixel = frame.getPixel(x, y)
                    if (Color.red(pixel) < 190 && Color.green(pixel) < 120 && Color.blue(pixel) < 100) {
                        if (x < 435) leftRows.add(y) else rightRows.add(y)
                    }
                }
                assertTrue("eye must remain visible", leftRows.isNotEmpty())
                val height = leftRows.max() - leftRows.min() + 1
                assertTrue("eye height must decrease while closing", height < previousHeight)
                previousHeight = height
                if (blink == 1f) {
                    assertTrue("closed eye must not leave tall fragments: $height", height <= 15)
                    if (expression != 2) assertTrue(rightRows.max() - rightRows.min() + 1 <= 15)
                    else assertTrue("closed wink shape must remain unchanged", rightRows.size > 80)
                }
                if (blink == 0f) openFrame = frame
                if (expression == 3 && blink > 0f) {
                    for (y in 185 until 255) for (x in 245 until 605) {
                        assertEquals("eyebrows must remain fixed", openFrame!!.getPixel(x, y), frame.getPixel(x, y))
                    }
                }
                sheetCanvas.drawBitmap(frame, null, Rect(column * 430, row * 322, (column + 1) * 430, (row + 1) * 322), null)
            }
        }
        val output = File("build/reports/driver-selection/blink-phases.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
