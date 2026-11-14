package com.framemayo.autolog.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import com.framemayo.autolog.R
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
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
    val density = LocalDensity.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var inputFocused by remember { mutableStateOf(false) }
    var targetBounds by remember { mutableStateOf(Rect.Zero) }
    var closing by remember { mutableStateOf(false) }
    var selectedProfile by remember { mutableStateOf<DriverProfile?>(null) }
    val results = remember(profiles, query) {
        val searchText = query.trim()
        profiles.filter { it.displayName.contains(searchText, ignoreCase = true) }
    }
    // 배경과 어울리는 단색 톤을 쓰되 눈과 플러그를 알아볼 수 있는 명암은 남긴다.
    val emptyArtworkFilter = remember {
        ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            0.053f, 0.179f, 0.018f, 0f, 45f,
            0.053f, 0.179f, 0.018f, 0f, 46f,
            0.053f, 0.179f, 0.018f, 0f, 51f,
            0f, 0f, 0f, 1f, 0f,
        )))
    }
    val dismiss = { keyboard?.hide(); closing = true }

    LaunchedEffect(targetBounds != Rect.Zero, closing) {
        if (closing) {
            progress.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
            selectedProfile?.let(onSelect) ?: onDismiss()
        } else if (targetBounds != Rect.Zero) {
            progress.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
            focus.requestFocus()
            keyboard?.show()
        }
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val focusManager = LocalFocusManager.current
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        var visibleOcclusionPx by remember { mutableIntStateOf(0) }
        // 차량용 IME가 Compose inset을 주지 않아도 실제 가용 창 영역으로 가림을 확인한다.
        DisposableEffect(view) {
            val root = view.rootView
            val frame = android.graphics.Rect()
            val location = IntArray(2)
            val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                root.getWindowVisibleDisplayFrame(frame)
                root.getLocationOnScreen(location)
                visibleOcclusionPx = (location[1] + root.height - frame.bottom).coerceIn(0, root.height)
            }
            root.viewTreeObserver.addOnGlobalLayoutListener(listener)
            onDispose {
                if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
        }
        // 키보드 옆에 드러난 앱 영역까지 같은 농도로 어둡게 하고 전환과 함께 복원한다.
        val dimAmount = 0.65f * progress.value
        SideEffect {
            window?.setDimAmount(dimAmount)
            // 시스템의 화면 밀기 대신 IME inset을 이용해 모달 자체를 이동한다.
            @Suppress("DEPRECATION") // Pleos IME의 가용 창 영역 전달에 필요하다.
            if (window?.attributes?.softInputMode != android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) {
                window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .semantics { contentDescription = "검색 모달 배경" }
                .pointerInput(closing) {
                    detectTapGestures {
                        if (closing) return@detectTapGestures
                        if (inputFocused) {
                            focusManager.clearFocus()
                            keyboard?.hide()
                        } else dismiss()
                    }
                }
                .systemBarsPadding().displayCutoutPadding().padding(horizontal = 24.dp, vertical = 8.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            val imeHeight = with(density) { maxOf(WindowInsets.ime.exclude(WindowInsets.systemBars).getBottom(this), visibleOcclusionPx).toDp() }
            val availableHeight = (maxHeight - imeHeight).coerceAtLeast(0.dp)
            val targetHeight = minOf(640.dp, availableHeight)
            val dialogHeight by animateDpAsState(targetHeight, tween(240, easing = FastOutSlowInEasing), label = "searchDialogHeight")
            val freeHeight = availableHeight - targetHeight
            val targetTop = (freeHeight / 2 + if (imeHeight > 0.dp) 12.dp else 0.dp).coerceAtMost(freeHeight)
            val topGap by animateDpAsState(targetTop, tween(240, easing = FastOutSlowInEasing), label = "searchDialogTop")
            val compactHeight = dialogHeight < 360.dp
            val verticalPadding = if (compactHeight) 12.dp else 32.dp
            val horizontalPadding = if (compactHeight) 24.dp else 40.dp
            val closeButton: @Composable () -> Unit = {
                OutlinedButton(
                    onClick = dismiss,
                    enabled = !closing,
                    modifier = Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = "검색 닫기" },
                    shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color(0xFF696B75)),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF25262D), contentColor = Color.White),
                ) { Text("닫기", fontSize = 20.sp) }
            }
            // 바깥 탭은 입력 종료 후 다음 탭에 모달을 닫고, 내부 빈 영역은 포커스만 해제한다.
            // 입력창·결과 버튼이 소비한 터치는 부모의 닫기 동작으로 전달되지 않는다.
            Box(Modifier.padding(top = topGap).widthIn(max = 840.dp).fillMaxWidth().height(dialogHeight)
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
                    Column(Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding)) {
                        Spacer(Modifier.height(if (compactHeight) 80.dp else 88.dp))
                        if (results.isEmpty()) {
                            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                                val artworkHeight = (maxHeight - 64.dp).coerceIn(0.dp, 240.dp)
                                Column(
                                    Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    if (artworkHeight > 0.dp) {
                                        Image(
                                            painterResource(R.drawable.driver_search_empty), contentDescription = null,
                                            modifier = Modifier.fillMaxWidth().height(artworkHeight),
                                            colorFilter = emptyArtworkFilter,
                                        )
                                        Spacer(Modifier.height(12.dp))
                                    }
                                    Text(
                                        if (query.isBlank()) "검색할 프로필이 없습니다." else "검색 결과가 없습니다.",
                                        Modifier.fillMaxWidth(), color = Color(0xFF92949C), fontSize = 22.sp,
                                        lineHeight = 28.sp, textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                Text(if (query.isBlank()) "전체 운전자" else "검색 결과 ${results.size}명", fontSize = 20.sp, color = Color(0xFFB9BEC8))
                            }
                            items(results, key = { it.driver.id }) { profile ->
                                Surface(
                                    onClick = { selectedProfile = profile; dismiss() },
                                    enabled = !closing,
                                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${profile.displayName} 검색 결과 선택" },
                                    shape = RoundedCornerShape(16.dp), color = Color(0xFF25262D),
                                ) {
                                    Row(Modifier.padding(if (compactHeight) 8.dp else 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Surface(Modifier.size(if (compactHeight) 40.dp else 64.dp), shape = RoundedCornerShape(12.dp), color = Color.White) {
                                            Box(contentAlignment = Alignment.Center) { DriverAvatar(profile.color, Modifier.fillMaxWidth().aspectRatio(4f / 3f), expression = profile.expression) }
                                        }
                                        Text(profile.displayName, Modifier.weight(1f), fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (managing) Text("수정", fontSize = 20.sp, color = Color(0xFFB9BEC8))
                                    }
                                }
                            }
                            if (compactHeight) item { closeButton() }
                        }
                        if (!compactHeight || results.isEmpty()) {
                            Spacer(Modifier.height(if (compactHeight) 8.dp else 24.dp))
                            closeButton()
                        }
                    }
                }
                // 이동 중에도 카드 경계에 잘리지 않도록 입력창을 모달 표면과 별도로 배치한다.
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = verticalPadding, start = horizontalPadding, end = horizontalPadding)
                        .fillMaxWidth().onGloballyPositioned { targetBounds = it.boundsInWindow() },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    // 내용 전체를 scaleX로 압축하면 글자와 원형 아이콘도 찌그러지므로 실제 너비만 보간한다.
                    val animatedWidth = with(density) {
                        val targetWidth = targetBounds.width.takeIf { it > 0f } ?: 760.dp.toPx()
                        val sourceWidth = sourceBounds.width.takeIf { it > 0f } ?: targetWidth
                        (sourceWidth + (targetWidth - sourceWidth) * progress.value).toDp()
                    }
                    DriverSearchBar(query, onQuery, { focusManager.clearFocus(); keyboard?.hide() }, Modifier.width(animatedWidth).focusRequester(focus).onFocusChanged { inputFocused = it.hasFocus }.graphicsLayer {
                        if (sourceBounds != Rect.Zero && targetBounds != Rect.Zero) {
                            val remaining = 1f - progress.value
                            translationX = (sourceBounds.center.x - targetBounds.center.x) * remaining
                            translationY = (sourceBounds.center.y - targetBounds.center.y) * remaining
                        }
                    }, maxWidth = 760.dp, enabled = !closing)
                }
            }
        }
    }
}
