package com.framemayo.autolog.ui

import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.framemayo.autolog.R
import kotlinx.coroutines.delay
import kotlin.random.Random

internal val ProfileColors = listOf(
    Color(0xFF59CAFF), Color(0xFFC3A1EE), Color(0xFFF8ACCF), Color(0xFFFFE573), Color(0xFF86EFB0),
    Color(0xFFFF5F68), Color(0xFFD2D2E5),
)
internal val ProfileColorNames = listOf("하늘", "보라", "분홍", "노랑", "초록", "빨강", "실버")
// 저장된 색상·표정 번호는 기존 프로필과 호환되도록 순서를 유지하고 끝에만 추가한다.
internal data class ProfileExpression(val name: String, val artwork: Int, val presetColor: Int)
internal val ProfileExpressions = listOf(
    ProfileExpression("기본", R.drawable.driver_cloud, 0),
    ProfileExpression("미소", R.drawable.driver_cloud_smile, 2),
    ProfileExpression("윙크", R.drawable.driver_cloud_wink, 1),
    ProfileExpression("버럭", R.drawable.driver_cloud_angry, 5),
    ProfileExpression("찡긋", R.drawable.driver_cloud_squeeze, 3),
    ProfileExpression("빙글", R.drawable.driver_cloud_dizzy, 6),
    ProfileExpression("느긋", R.drawable.driver_cloud_relaxed, 4),
)
private val ProfileHues = listOf(197f, 264f, 331f, 47f, 146f, 356f, 240f)
private val ProfileSaturations = listOf(.64f, .32f, .28f, .50f, .40f, .65f, .08f)

/** 원본 표정을 유지하며 몸통 색상만 변경한다. 눈을 뜬 기본·윙크·버럭 표정은 편집 중 깜박인다. */
@Composable
internal fun DriverAvatar(color: Int, modifier: Modifier = Modifier, blinking: Boolean = false, expression: Int = 0, faceOnly: Boolean = false) {
    val face = ProfileExpressions.getOrElse(expression) { ProfileExpressions.first() }
    val chosenColor = color.takeIf { it in ProfileColors.indices } ?: 0
    val canBlink = blinking && expression in listOf(0, 2, 3)
    val cropOrigin = if (faceOnly) IntOffset(445, 495) else IntOffset(200, 310)
    val cropSize = if (faceOnly) IntSize(360, 230) else IntSize(860, 645)
    val resources = LocalContext.current.resources
    val bitmap = remember(resources, face.artwork) { BitmapFactory.decodeResource(resources, face.artwork) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val shader = remember(bitmap) {
        if (Build.VERSION.SDK_INT >= 33) RuntimeShader(AvatarShader).apply {
            setInputShader("artwork", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        } else null
    }
    val blink = remember { Animatable(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(canBlink, expression, lifecycle) {
        blink.snapTo(0f)
        if (canBlink && shader != null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            blink.snapTo(0f)
            while (true) {
                delay(Random.nextLong(2800, 5000))
                blink.animateTo(1f, tween(90))
                delay(25)
                blink.animateTo(0f, tween(170))
            }
        }
    }
    Canvas(modifier) {
        var hardwareCanvas = false
        drawIntoCanvas { hardwareCanvas = it.nativeCanvas.isHardwareAccelerated }
        if (Build.VERSION.SDK_INT >= 33 && shader != null && hardwareCanvas) {
            shader.setFloatUniform("viewport", size.width, size.height)
            shader.setFloatUniform("origin", cropOrigin.x.toFloat(), cropOrigin.y.toFloat())
            shader.setFloatUniform("cropSize", cropSize.width.toFloat(), cropSize.height.toFloat())
            shader.setFloatUniform("recolor", if (chosenColor == face.presetColor) 0f else 1f)
            shader.setFloatUniform("sourceHue", ProfileHues[face.presetColor] / 360f)
            shader.setFloatUniform("targetHue", ProfileHues[chosenColor] / 360f)
            shader.setFloatUniform("saturationScale", ProfileSaturations[chosenColor] / ProfileSaturations[face.presetColor])
            shader.setFloatUniform("blink", if (canBlink) blink.value else 0f)
            shader.setFloatUniform("leftEye", if (expression == 0) 566f else 556f, if (expression == 3) 625f else 605f)
            shader.setFloatUniform("rightEye", if (expression == 2) -1000f else 714f, if (expression == 3) 630f else 616f)
            paint.shader = shader
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
        } else {
            // API 28–32 또는 소프트웨어 캔버스는 원본 정적 캐릭터로 표시한다. Pleos의 하드웨어 렌더링에서는 색상과 깜박임을 지원한다.
            drawImage(image, cropOrigin, cropSize, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        }
    }
}

private const val AvatarShader = """
uniform shader artwork;
uniform float2 viewport;
uniform float2 origin;
uniform float2 cropSize;
uniform float recolor;
uniform float sourceHue;
uniform float targetHue;
uniform float saturationScale;
uniform float2 leftEye;
uniform float2 rightEye;
uniform float blink;
float3 rgb2hsv(float3 c) {
    float4 K=float4(0.,-1./3.,2./3.,-1.);
    float4 p=mix(float4(c.bg,K.wz),float4(c.gb,K.xy),step(c.b,c.g));
    float4 q=mix(float4(p.xyw,c.r),float4(c.r,p.yzx),step(p.x,c.r));
    float d=q.x-min(q.w,q.y);
    return float3(abs(q.z+(q.w-q.y)/(6.*d+1.e-10)),d/(q.x+1.e-10),q.x);
}
float3 hsv2rgb(float3 c) {
    float3 p=abs(fract(c.xxx+float3(0.,2./3.,1./3.))*6.-3.);
    return c.z*mix(float3(1.),clamp(p-1.,0.,1.),c.y);
}
float2 blinkEye(float2 pixel,float2 center) {
    float2 d=pixel-center;
    float2 local=float2(.961*d.x+.276*d.y,-.276*d.x+.961*d.y);
    float weight=1.-smoothstep(48.,84.,abs(local.x));
    float y=abs(local.y),openness=1.-blink*.965,inner=84.*openness;
    if(y<130.) {
        float mapped=y<inner?y/openness:84.+(y-inner)*46./(130.-inner);
        local.y=mix(local.y,sign(local.y)*mapped,weight);
    }
    return center+float2(.961*local.x-.276*local.y,.276*local.x+.961*local.y);
}
half4 main(float2 position) {
    float2 pixel=origin+position/viewport*cropSize;
    if(blink>0.) { pixel=blinkEye(pixel,leftEye);pixel=blinkEye(pixel,rightEye); }
    float3 color=artwork.eval(pixel).rgb;
    if(recolor>0.) {
        float3 hsv=rgb2hsv(color);
        float distance=abs(hsv.x-sourceHue);distance=min(distance,1.-distance);
        // 원본의 밝은 몸통만 재색칠하고 갈색 눈과 흰 볼·배경은 보존한다.
        float body=(1.-smoothstep(.025,.05,distance))*smoothstep(.015,.065,hsv.y)*smoothstep(.32,.55,hsv.z);
        hsv.x=targetHue;
        hsv.y=clamp(hsv.y*saturationScale,0.,1.);
        color=mix(color,hsv2rgb(hsv),body);
    }
    return half4(color,1.);
}
"""
