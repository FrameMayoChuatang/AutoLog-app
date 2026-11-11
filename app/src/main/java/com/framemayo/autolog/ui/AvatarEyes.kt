package com.framemayo.autolog.ui

import android.graphics.Bitmap
import android.graphics.Color

/** 원본의 갈색 눈·눈썹만 분리한다. 눈 내부의 흰 반사광은 보존하고 몸체·볼은 투명하게 만든다. */
internal fun extractAvatarEyes(source: Bitmap): Bitmap {
    val scale = source.width / 1254f
    val left = (445 * scale).toInt()
    val top = (495 * scale).toInt()
    val width = (360 * scale).toInt()
    val height = (230 * scale).toInt()
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, left, top, width, height)
    val eye = BooleanArray(pixels.size) { i ->
        val pixel = pixels[i]
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        r < 190 && g <= r && b <= g + 10
    }
    // 외곽과 연결된 배경만 제거하므로 눈 안쪽의 흰 점은 남고 나선형 눈의 빈 곳은 뚫린다.
    val exterior = BooleanArray(pixels.size)
    val queue = IntArray(pixels.size)
    var head = 0
    var tail = 0
    fun visit(index: Int) {
        if (!eye[index] && !exterior[index]) {
            exterior[index] = true
            queue[tail++] = index
        }
    }
    for (x in 0 until width) { visit(x); visit((height - 1) * width + x) }
    for (y in 0 until height) { visit(y * width); visit(y * width + width - 1) }
    while (head < tail) {
        val index = queue[head++]
        val x = index % width
        val y = index / width
        if (x > 0) visit(index - 1)
        if (x < width - 1) visit(index + 1)
        if (y > 0) visit(index - width)
        if (y < height - 1) visit(index + width)
    }
    for (i in pixels.indices) if (exterior[i]) pixels[i] = Color.TRANSPARENT
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
