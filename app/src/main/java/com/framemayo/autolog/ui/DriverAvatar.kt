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
    Color(0xFF59CAFF), Color(0xFFA794EF), Color(0xFFF197C9), Color(0xFFFFD65A), Color(0xFF9AD574),
)
internal val ProfileColorNames = listOf("하늘", "보라", "분홍", "노랑", "초록")
private val ProfileHues = listOf(0f, 65f, 125f, 205f, 275f)

/** 원본 캐릭터의 눈과 반사광을 함께 변형한다. 편집 모달이 보일 때만 깜박인다. */
@Composable
internal fun DriverAvatar(color: Int, modifier: Modifier = Modifier, blinking: Boolean = false) {
    val resources = LocalContext.current.resources
    val bitmap = remember(resources) { BitmapFactory.decodeResource(resources, R.drawable.driver_cloud) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val shader = remember(bitmap) {
        if (Build.VERSION.SDK_INT >= 33) RuntimeShader(AvatarShader).apply {
            setInputShader("artwork", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        } else null
    }
    val blink = remember { Animatable(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(blinking, lifecycle) {
        blink.snapTo(0f)
        if (blinking && shader != null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
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
        if (Build.VERSION.SDK_INT >= 33 && shader != null) {
            shader.setFloatUniform("viewport", size.width, size.height)
            shader.setFloatUniform("hue", ProfileHues.getOrElse(color) { 0f } / 360f)
            shader.setFloatUniform("blink", if (blinking) blink.value else 0f)
            paint.shader = shader
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
        } else {
            // API 28–32는 원본 정적 캐릭터를 사용한다. Pleos API 34에서는 색상과 깜박임을 지원한다.
            drawImage(image, IntOffset(200, 310), IntSize(860, 645), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        }
    }
}

private const val AvatarShader = """
uniform shader artwork;
uniform float2 viewport;
uniform float hue;
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
    float2 pixel=float2(200.,310.)+position/viewport*float2(860.,645.);
    if(blink>0.) { pixel=blinkEye(pixel,float2(566.,605.));pixel=blinkEye(pixel,float2(714.,616.)); }
    float3 color=artwork.eval(pixel).rgb;
    float blue=smoothstep(.08,.2,color.b-color.r)*smoothstep(.06,.16,color.g-color.r);
    if(hue!=0.) { float3 hsv=rgb2hsv(color);hsv.x=fract(hsv.x+hue);color=mix(color,hsv2rgb(hsv),blue); }
    return half4(color,1.);
}
"""
