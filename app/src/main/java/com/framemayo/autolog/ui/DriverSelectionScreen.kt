package com.framemayo.autolog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemayo.autolog.data.local.DriverStatus
import com.framemayo.autolog.data.local.DriverProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val SelectionColors = darkColorScheme(
    background = Color(0xFF0B0C0F), onBackground = Color(0xFFF4F5F7),
    surface = Color(0xFF17181D), onSurface = Color(0xFFF4F5F7),
    onSurfaceVariant = Color(0xFFB9BEC8), outline = Color(0xFF696B75),
)

/** 프로필 순서는 저장소가 실제 운행 시각으로 정렬한다. 검색은 모달 안의 결과에만 적용한다. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DriverSelectionScreen(
    profiles: List<DriverProfile>,
    parked: Boolean,
    linked: Boolean,
    odometerReady: Boolean,
    onStart: (Long) -> Unit,
    onSave: suspend (Long?, String, Int, Int, Boolean?) -> Unit,
    onDelete: suspend (Long) -> Boolean,
    onOpenVehicle: () -> Unit,
    onOpenRecords: () -> Unit = onOpenVehicle,
    footer: @Composable () -> Unit = {},
) {
    val listState = rememberLazyListState()
    var managing by rememberSaveable { mutableStateOf(false) }
    var showingMore by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var searchBounds by remember { mutableStateOf(Rect.Zero) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var blocked by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current
    val recent = profiles.take(5)
    val others = profiles.drop(5)
    LaunchedEffect(parked) {
        if (!parked) { searching = false; editing = false; deletingId = null; blocked = false; managing = false; query = ""; keyboard?.hide() }
    }
    BackHandler(managing && !searching && !editing && deletingId == null) { managing = false }
    val start: (Long) -> Unit = { driverId ->
        if (!linked || !odometerReady) blocked = true
        else {
            keyboard?.hide()
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onStart(driverId)
        }
    }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val select: (DriverProfile) -> Unit = { keyboard?.hide(); selectedId = it.driver.id }
    MaterialTheme(colorScheme = SelectionColors) {
        Surface(Modifier.fillMaxSize(), color = SelectionColors.background) {
            if (!parked) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("P", fontSize = 56.sp)
                    Text("주차 후 이용해 주세요", fontSize = 30.sp)
                    Text("차량 상태가 확인되면 선택할 수 있어요.", fontSize = 20.sp, color = SelectionColors.onSurfaceVariant)
                }
            } else BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 32.dp)) {
                val avatarSize = ((maxWidth - 20.dp * 5) / 6 - 16.dp).coerceIn(120.dp, 148.dp)
                val viewportHeight = maxHeight
                val columns = ((maxWidth + 20.dp) / (avatarSize + 36.dp)).toInt().coerceAtLeast(1)
                val openEditor: (Long?) -> Unit = { editingId = it; error = null; editing = true }
                val card: @Composable (DriverProfile) -> Unit = { profile ->
                    DriverProfileCard(profile, avatarSize, managing, { select(profile) }, { openEditor(profile.driver.id) }, { error = null; deletingId = profile.driver.id })
                }
                var topBarHeight by remember { mutableIntStateOf(0) }
                var footerHeight by remember { mutableIntStateOf(0) }
                val footerPx = if (managing) 0 else footerHeight
                val firstPageHeight = with(LocalDensity.current) {
                    (viewportHeight.roundToPx() - topBarHeight - footerPx).coerceAtLeast(0).toDp()
                }
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().onSizeChanged { topBarHeight = it.height }.padding(top = 24.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { managing = !managing }, shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.widthIn(min = 180.dp).heightIn(min = 72.dp),
                            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
                            border = BorderStroke(1.dp, if (managing) Color.White else SelectionColors.outline),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (managing) Color.White else SelectionColors.surface,
                                contentColor = if (managing) SelectionColors.surface else SelectionColors.onSurface,
                            ),
                        ) { Text(if (managing) "완료" else "프로필 관리", fontSize = 24.sp, fontWeight = FontWeight.Medium) }
                        Spacer(Modifier.weight(1f))
                        if (!managing) OutlinedButton(
                            onClick = { keyboard?.hide(); onOpenRecords() }, shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.widthIn(min = 180.dp).heightIn(min = 72.dp),
                            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
                            border = BorderStroke(1.dp, SelectionColors.outline),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = SelectionColors.surface, contentColor = SelectionColors.onSurface),
                        ) { Text("최근 운행", fontSize = 24.sp, fontWeight = FontWeight.Medium) }
                    }
                    // 경계에서 화면이 늘어났다 복귀하는 효과만 끄고 목록의 드래그·플링은 유지한다.
                    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(), state = listState, horizontalAlignment = Alignment.CenterHorizontally,
                        contentPadding = PaddingValues(bottom = 40.dp),
                    ) {
                        item {
                            // 첫 화면 안에 더보기를 두고, 펼친 목록은 실제 스크롤 영역 아래에서 시작한다.
                            Column(Modifier.fillMaxWidth().heightIn(min = firstPageHeight),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween) {
                            Box(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.fillMaxWidth().layout { measurable, constraints ->
                                        val group = measurable.measure(constraints)
                                        // 탑바 높이를 제외해 기존 화면 중앙을 유지하고, 목록만 탑바 아래에서 스크롤한다.
                                        val centered = (viewportHeight.roundToPx() - group.height) / 2 - topBarHeight
                                        val buttonSpace = if (others.isNotEmpty()) 104.dp.roundToPx() else 0
                                        val available = viewportHeight.roundToPx() - topBarHeight - footerPx - group.height - buttonSpace
                                        val top = minOf(centered, available).coerceAtLeast(0)
                                        layout(group.width, top + group.height) { group.placeRelative(0, top) }
                                    },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    DriverSearchBar(
                                        query, { query = it }, { keyboard?.hide() },
                                        Modifier.padding(bottom = 32.dp)
                                            .onGloballyPositioned { searchBounds = it.boundsInWindow() }
                                            .graphicsLayer { alpha = if (searching) 0f else 1f },
                                        onOpen = { searching = true },
                                    )
                                    Text(if (managing) "프로필 관리" else "운전자의 프로필을 선택하세요.", Modifier.fillMaxWidth().padding(bottom = 36.dp), fontSize = 34.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                                    ProfileRow {
                                        recent.forEach { card(it) }
                                        if (!managing) {
                                            Column(Modifier.width(avatarSize + 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Surface(
                                                    onClick = { openEditor(null) }, modifier = Modifier.size(avatarSize).semantics { contentDescription = "운전자 추가" },
                                                    shape = RoundedCornerShape(18.dp), color = SelectionColors.surface, border = BorderStroke(1.dp, Color(0xFF555862)),
                                                ) { Box(contentAlignment = Alignment.Center) { Text("+", fontSize = 64.sp, fontWeight = FontWeight.Light, color = SelectionColors.onSurfaceVariant) } }
                                                Text("운전자 추가", Modifier.padding(top = 18.dp), fontSize = 20.sp, color = SelectionColors.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        if (others.isNotEmpty() && !showingMore) {
                            OutlinedButton(
                                onClick = { showingMore = true },
                                modifier = Modifier.padding(vertical = 24.dp).widthIn(min = 160.dp).heightIn(min = 56.dp),
                                shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, SelectionColors.outline),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = SelectionColors.surface, contentColor = SelectionColors.onSurface),
                            ) { Text("더보기", fontSize = 20.sp) }
                        }
                            }
                        }
                        if (showingMore && others.isNotEmpty()) item { Box(Modifier.fillMaxWidth().padding(top = 72.dp)) }
                        if (showingMore) items(others.chunked(columns), key = { row -> "others-${row.first().driver.id}" }) { row ->
                            Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Top) { row.forEach { card(it) } }
                        }
                        if (showingMore && others.isNotEmpty()) item(key = "collapse-profiles") {
                            OutlinedButton(
                                onClick = {
                                    showingMore = false
                                    scope.launch { listState.scrollToItem(0) }
                                },
                                modifier = Modifier.padding(top = 24.dp, bottom = 24.dp).widthIn(min = 160.dp).heightIn(min = 56.dp),
                                shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, SelectionColors.outline),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = SelectionColors.surface, contentColor = SelectionColors.onSurface),
                            ) { Text("접기", fontSize = 20.sp) }
                        }
                    }
                    }
                    if (!managing) Box(
                        Modifier.fillMaxWidth().onSizeChanged { footerHeight = it.height }.padding(top = 16.dp, bottom = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) { Box(Modifier.widthIn(max = 960.dp).fillMaxWidth()) { footer() } }
                }
            }
        }
        if (parked && searching) DriverSearchDialog(
            query = query, onQuery = { query = it }, profiles = profiles,
            sourceBounds = searchBounds, managing = managing,
            onDismiss = { searching = false; query = "" },
            onSelect = { profile ->
                searching = false
                query = ""
                if (managing) { editingId = profile.driver.id; error = null; editing = true }
                else select(profile)
            },
        )
        if (parked && editing) DriverProfileEditor(
            profiles.find { it.driver.id == editingId }, busy, error, { editing = false },
            onSave = { name, color, expression, consent ->
                if (!busy) scope.launch {
                    busy = true; error = null
                    try { onSave(editingId, name, color, expression, consent); editing = false }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "저장하지 못했습니다. 다시 시도해 주세요." }
                    finally { busy = false }
                }
            },
        )
        if (parked && deletingId != null) AlertDialog(
            onDismissRequest = { if (!busy) deletingId = null },
            title = { Text("프로필을 삭제할까요?", fontSize = 28.sp) },
            text = { Text(error ?: "${profiles.find { it.driver.id == deletingId }?.displayName.orEmpty()} 프로필을 목록에서 삭제합니다. 기존 운행 기록은 유지됩니다.", fontSize = 22.sp, lineHeight = 32.sp) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                val id = deletingId ?: return@TextButton
                scope.launch {
                    busy = true; error = null
                    try { if (onDelete(id)) deletingId = null else error = "운행 중인 프로필은 삭제할 수 없습니다." }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "삭제하지 못했습니다. 다시 시도해 주세요." }
                    finally { busy = false }
                }
            }, modifier = Modifier.heightIn(min = 56.dp)) { Text("삭제", fontSize = 20.sp) } },
            dismissButton = { TextButton(onClick = { deletingId = null }, enabled = !busy, modifier = Modifier.heightIn(min = 56.dp)) { Text("취소", fontSize = 20.sp) } },
        )
        val selected = profiles.find { it.driver.id == selectedId }
        if (parked && selected != null) StartTripDialog(
            selected,
            onStart = { selectedId = null; start(selected.driver.id) },
            onEdit = { selectedId = null; editingId = selected.driver.id; error = null; editing = true },
            onDismiss = { selectedId = null },
        )
        if (parked && blocked) AlertDialog(
            onDismissRequest = { blocked = false }, title = { Text(if (!linked) "차량 연결이 필요합니다" else "주행거리 수신 대기", fontSize = 28.sp) },
            text = { Text(if (!linked) "화면 아래 차량 연결에 관리자가 발급한 8자리 페어링 코드를 입력해 주세요. 연결 후 운행을 시작할 수 있습니다." else "차량의 유효한 주행거리 정보를 받은 후 다시 선택해 주세요.", fontSize = 22.sp, lineHeight = 32.sp) },
            confirmButton = { TextButton(onClick = {
                blocked = false
                if (linked) onOpenVehicle()
            }, modifier = Modifier.heightIn(min = 56.dp)) { Text(if (!linked) "차량 연결하기" else "차량 상태 확인", fontSize = 20.sp) } },
            dismissButton = { TextButton(onClick = { blocked = false }, modifier = Modifier.heightIn(min = 56.dp)) { Text("닫기", fontSize = 20.sp) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileRow(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(24.dp)) { content() }
}

@Composable
private fun DriverProfileCard(profile: DriverProfile, size: Dp, managing: Boolean, onSelect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.width(size + 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            if (managing) Surface(Modifier.size(size), shape = RoundedCornerShape(18.dp), color = Color.White) {
                Box(contentAlignment = Alignment.Center) { DriverAvatar(profile.color, Modifier.fillMaxWidth().aspectRatio(4f / 3f), expression = profile.expression) }
            } else Surface(onClick = onSelect, modifier = Modifier.size(size).semantics { contentDescription = "${profile.displayName} 프로필 선택" }, shape = RoundedCornerShape(18.dp), color = Color.White) {
                Box(contentAlignment = Alignment.Center) { DriverAvatar(profile.color, Modifier.fillMaxWidth().aspectRatio(4f / 3f), expression = profile.expression) }
            }
            if (managing) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileTool("${profile.displayName} 프로필 수정", "edit", onEdit)
                ProfileTool("${profile.displayName} 프로필 삭제", "delete", onDelete)
            }
        }
        Text(profile.displayName, Modifier.padding(top = 18.dp), fontSize = 22.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (profile.driver.status == DriverStatus.PENDING) {
            Text("승인 대기", Modifier.padding(top = 4.dp), fontSize = VehicleUi.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProfileTool(label: String, icon: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.size(48.dp).semantics { contentDescription = label }, shape = CircleShape, color = Color(0xEE171719), border = BorderStroke(1.dp, Color(0x80FFFFFF))) {
        Box(contentAlignment = Alignment.Center) { ProfileIcon(icon, Modifier.size(24.dp), Color.White) }
    }
}

@Composable
internal fun DriverSearchBar(query: String, onQuery: (String) -> Unit, onSearch: () -> Unit, modifier: Modifier, onOpen: (() -> Unit)? = null, maxWidth: Dp = 480.dp) {
    Box(modifier.widthIn(max = maxWidth).fillMaxWidth()) {
        if (onOpen != null) Surface(
            onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics { contentDescription = "운전자 검색 열기" },
            shape = CircleShape, color = Color.White,
        ) {
            Row(Modifier.padding(start = 32.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(query.ifBlank { "운전자 이름 검색..." }, Modifier.weight(1f), color = if (query.isBlank()) Color(0xFF808088) else Color(0xFF171719), fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Surface(Modifier.size(52.dp), shape = CircleShape, color = Color.Black) {
                    Box(contentAlignment = Alignment.Center) { ProfileIcon("search", Modifier.size(28.dp), Color.White) }
                }
            }
        } else Surface(Modifier.fillMaxWidth(), shape = CircleShape, color = Color.White) {
            OutlinedTextField(
                query, onQuery, modifier = Modifier.padding(start = 16.dp).fillMaxWidth().heightIn(min = 64.dp).semantics { contentDescription = "운전자 이름 검색" },
                shape = CircleShape, singleLine = true, placeholder = { Text("운전자 이름 검색...", color = Color(0xFF808088), fontSize = 20.sp) },
                textStyle = TextStyle(color = Color(0xFF171719), fontSize = 20.sp),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedBorderColor = Color.White, unfocusedBorderColor = Color.White, cursorColor = Color.Black),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                trailingIcon = {
                    Surface(onClick = onSearch, Modifier.padding(6.dp).size(52.dp).semantics { contentDescription = "운전자 검색" }, shape = CircleShape, color = Color.Black) {
                        Box(contentAlignment = Alignment.Center) { ProfileIcon("search", Modifier.size(28.dp), Color.White) }
                    }
                },
            )
        }
    }
}

@Composable
private fun ProfileIcon(kind: String, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val unit = size.width / 24f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, point(x1, y1), point(x2, y2), 2 * unit, StrokeCap.Round)
        when (kind) {
            "search" -> { drawCircle(color, 8 * unit, point(11f, 11f), style = Stroke(2 * unit)); line(17f, 17f, 21f, 21f) }
            "edit" -> { line(5f, 16f, 16f, 5f); line(8f, 19f, 19f, 8f); line(16f, 5f, 19f, 8f); line(5f, 16f, 4f, 20f); line(4f, 20f, 8f, 19f) }
            "delete" -> { line(4f, 6f, 20f, 6f); line(9f, 3f, 15f, 3f); line(6f, 6f, 7f, 21f); line(18f, 6f, 17f, 21f); line(7f, 21f, 17f, 21f); line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f) }
        }
    }
}

@Composable
private fun StartTripDialog(profile: DriverProfile, onStart: () -> Unit, onEdit: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = SelectionColors.surface) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(Modifier.size(180.dp), shape = RoundedCornerShape(24.dp), color = Color.White) {
                    Box(contentAlignment = Alignment.Center) { DriverAvatar(profile.color, Modifier.fillMaxWidth().aspectRatio(4f / 3f), blinking = true, expression = profile.expression) }
                }
                Text(profile.displayName, Modifier.padding(top = 20.dp), fontSize = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (profile.driver.status == DriverStatus.PENDING) {
                    Text("승인 대기 · 관리자 승인 후 운행 확인이 전송돼요.", Modifier.padding(top = 6.dp), fontSize = VehicleUi.Caption, color = SelectionColors.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                Button(
                    onClick = onStart, modifier = Modifier.padding(top = 28.dp).fillMaxWidth().heightIn(min = 64.dp), shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = SelectionColors.surface),
                ) { Text("주행 시작", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = onEdit, modifier = Modifier.padding(top = 12.dp).fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, SelectionColors.outline),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = SelectionColors.surface, contentColor = SelectionColors.onSurface),
                ) { Text("프로필 수정", fontSize = 20.sp) }
                TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 4.dp).heightIn(min = 56.dp)) {
                    Text("닫기", fontSize = 20.sp, color = SelectionColors.onSurfaceVariant)
                }
            }
        }
    }
}
