package com.framemayo.autolog.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.framemayo.autolog.data.local.DriverProfile

/** 상단 검색창의 위치에서 입력창이 이동하며, 검색어는 선택 화면과 같은 상태를 사용한다. */
@Composable
internal fun DriverSearchDialog(
    query: String,
    onQuery: (String) -> Unit,
    profiles: List<DriverProfile>,
    sourceBounds: Rect,
    managing: Boolean,
    onDismiss: () -> Unit,
    onSelect: (DriverProfile) -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var inputFocused by remember { mutableStateOf(false) }
    var targetBounds by remember { mutableStateOf(Rect.Zero) }
    val results = profiles.filter { it.displayName.contains(query.trim(), ignoreCase = true) }
    val dismiss = { keyboard?.hide(); onDismiss() }

    LaunchedEffect(targetBounds != Rect.Zero) {
        if (targetBounds != Rect.Zero) {
            progress.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
            focus.requestFocus()
            keyboard?.show()
        }
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val focusManager = LocalFocusManager.current
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .semantics { contentDescription = "검색 모달 배경" }
                .pointerInput(Unit) {
                    detectTapGestures {
                        if (inputFocused) {
                            focusManager.clearFocus()
                            keyboard?.hide()
                        } else dismiss()
                    }
                }
                .safeDrawingPadding().imePadding().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 바깥 탭은 입력 종료 후 다음 탭에 모달을 닫고, 내부 빈 영역은 포커스만 해제한다.
            // 입력창·결과 버튼이 소비한 터치는 부모의 닫기 동작으로 전달되지 않는다.
            Box(Modifier.widthIn(max = 840.dp).fillMaxWidth().height(minOf(640.dp, maxHeight))
                .pointerInput(Unit) {
                    detectTapGestures {
                        focusManager.clearFocus()
                        keyboard?.hide()
                    }
                }) {
                Surface(
                    Modifier.fillMaxSize().graphicsLayer { alpha = progress.value },
                    shape = RoundedCornerShape(28.dp), color = Color(0xFF17181D),
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("운전자 검색", fontSize = 26.sp, fontWeight = FontWeight.Medium)
                            TextButton(onClick = dismiss, modifier = Modifier.size(56.dp).semantics { contentDescription = "검색 닫기" }) {
                                Text("×", fontSize = 30.sp, color = Color(0xFFB9BEC8))
                            }
                        }
                        Spacer(Modifier.height(88.dp))
                        Text(if (query.isBlank()) "전체 운전자" else "검색 결과 ${results.size}명", fontSize = 18.sp, color = Color(0xFFB9BEC8))
                        Spacer(Modifier.height(12.dp))
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (results.isEmpty()) item { Text(if (query.isBlank()) "검색할 프로필이 없습니다." else "검색 결과가 없습니다.", Modifier.padding(vertical = 20.dp), fontSize = 20.sp) }
                            items(results, key = { it.driver.id }) { profile ->
                                Surface(
                                    onClick = { keyboard?.hide(); onSelect(profile) },
                                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${profile.displayName} 검색 결과 선택" },
                                    shape = RoundedCornerShape(16.dp), color = Color(0xFF25262D),
                                ) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Surface(Modifier.size(64.dp), shape = RoundedCornerShape(12.dp), color = Color.White) {
                                            Box(contentAlignment = Alignment.Center) { DriverAvatar(profile.color, Modifier.fillMaxWidth().aspectRatio(4f / 3f)) }
                                        }
                                        Text(profile.displayName, Modifier.weight(1f), fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(if (managing) "수정" else "선택", fontSize = 18.sp, color = Color(0xFFB9BEC8))
                                    }
                                }
                            }
                        }
                    }
                }
                // 이동 중에도 카드 경계에 잘리지 않도록 입력창을 모달 표면과 별도로 배치한다.
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = 80.dp, start = 24.dp, end = 24.dp)
                        .widthIn(max = 640.dp).fillMaxWidth().onGloballyPositioned { targetBounds = it.boundsInWindow() },
                ) {
                    DriverSearchBar(query, onQuery, { focusManager.clearFocus(); keyboard?.hide() }, Modifier.focusRequester(focus).onFocusChanged { inputFocused = it.hasFocus }.graphicsLayer {
                        if (sourceBounds != Rect.Zero && targetBounds != Rect.Zero) {
                            val remaining = 1f - progress.value
                            translationX = (sourceBounds.center.x - targetBounds.center.x) * remaining
                            translationY = (sourceBounds.center.y - targetBounds.center.y) * remaining
                            scaleX = 1f + (sourceBounds.width / targetBounds.width - 1f) * remaining
                        }
                    }, maxWidth = 640.dp)
                }
            }
        }
    }
}
