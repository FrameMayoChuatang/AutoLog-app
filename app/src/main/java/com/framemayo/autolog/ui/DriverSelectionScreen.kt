package com.framemayo.autolog.ui

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemayo.autolog.data.local.DriverEntity

private val SelectionDarkColors = darkColorScheme(
    primary = Color(0xFFB9CEFF), onPrimary = Color(0xFF122647),
    background = Color(0xFF111314), onBackground = Color(0xFFF4F5F7),
    surface = Color(0xFF1E2228), onSurface = Color(0xFFF4F5F7),
    onSurfaceVariant = Color(0xFFB9BEC8), outline = Color(0xFF939BA9),
)
private val SelectionLightColors = lightColorScheme(
    primary = Color(0xFF214C9B), onPrimary = Color.White,
    background = Color(0xFFF5F6F8), onBackground = Color(0xFF181B20),
    surface = Color.White, onSurface = Color(0xFF181B20),
    onSurfaceVariant = Color(0xFF505866), outline = Color(0xFF626B79),
)

/** 운전자 선택은 화면 상태로만 보관하고, 시작 버튼에서 기존 운행 로직을 호출한다. */
@Composable
internal fun DriverSelectionScreen(
    drivers: List<DriverEntity>,
    recentDriverId: Long?,
    parked: Boolean,
    linked: Boolean,
    odometerReady: Boolean,
    onStart: (Long) -> Unit,
    onAdd: (String, String) -> Unit,
    onOpenVehicle: () -> Unit,
) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) SelectionDarkColors else SelectionLightColors) {
        var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
        var query by rememberSaveable { mutableStateOf("") }
        var searching by rememberSaveable { mutableStateOf(false) }
        var adding by rememberSaveable { mutableStateOf(false) }
        val selected = drivers.firstOrNull { it.id == selectedId }
        val canInteract = parked
        val haptic = LocalHapticFeedback.current
        val keyboard = LocalSoftwareKeyboardController.current
        val visibleDrivers = remember(drivers, query, recentDriverId) {
            drivers.filter { it.name.contains(query.trim(), ignoreCase = true) }
                .sortedByDescending { it.id == recentDriverId }
        }
        LaunchedEffect(canInteract) {
            if (!canInteract) {
                adding = false
                searching = false
                query = ""
                keyboard?.hide()
            }
        }
        val guidance = when {
            !canInteract -> "P에 주차한 후 운전자를 선택해 주세요."
            !linked -> "운행을 시작하려면 차량을 먼저 연결해 주세요."
            !odometerReady -> "차량의 주행거리 정보를 기다리고 있어요."
            selected == null -> "운전자를 선택하면 운행을 시작할 수 있어요."
            else -> "${selected.name}님으로 운행을 기록합니다."
        }
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                val compact = maxWidth < 600.dp
                val side = if (compact) 24.dp else 48.dp
                val availableWidth = maxWidth - side * 2
                val spacious = maxHeight > 900.dp
                Column(Modifier.fillMaxSize().padding(horizontal = side)) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("AUTOLOG", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                        OutlinedButton(
                            onClick = onOpenVehicle, enabled = canInteract,
                            modifier = Modifier.heightIn(min = 64.dp), shape = RoundedCornerShape(12.dp),
                        ) { Text(if (linked) "차량 · 기록" else "차량 연결", fontSize = 20.sp) }
                    }
                    if (!canInteract) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                Text("P", fontSize = 56.sp, fontWeight = FontWeight.Medium)
                                Text("주차 후 이용해 주세요", fontSize = 30.sp, textAlign = TextAlign.Center)
                                Text("차량 상태가 확인되면 선택할 수 있어요.", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                        }
                    } else {
                        val columns = minOf((availableWidth.value / 184).toInt().coerceIn(1, 4), visibleDrivers.size + 1)
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            modifier = Modifier.weight(1f).widthIn(max = 960.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                            contentPadding = PaddingValues(top = if (spacious) 64.dp else 16.dp, bottom = 32.dp),
                        ) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("누가 운전하시나요?", fontSize = if (compact) 32.sp else 40.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                                    Text("이번 운행을 기록할 운전자를 선택해 주세요.", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                    if (searching) {
                                        OutlinedTextField(
                                            value = query, onValueChange = { query = it },
                                            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                                            label = { Text("운전자 이름", fontSize = 20.sp) },
                                            textStyle = TextStyle(fontSize = 22.sp), singleLine = true,
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                                            trailingIcon = {
                                                TextButton(onClick = { query = ""; searching = false; keyboard?.hide() }, modifier = Modifier.sizeIn(minWidth = 64.dp, minHeight = 64.dp)) {
                                                    Text("닫기", fontSize = 18.sp)
                                                }
                                            },
                                        )
                                    } else {
                                        TextButton(onClick = { searching = true }, modifier = Modifier.heightIn(min = 64.dp)) {
                                            Text("운전자 검색", fontSize = 20.sp)
                                        }
                                    }
                                }
                            }
                            if (visibleDrivers.isEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        if (query.isBlank()) "등록된 운전자가 없어요. 운전자를 추가해 주세요." else "검색한 운전자가 없어요. 이름을 확인해 주세요.",
                                        fontSize = 20.sp, textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            items(visibleDrivers, key = { it.id }) { driver ->
                                DriverProfile(driver, driver.id == selectedId, driver.id == recentDriverId) {
                                    selectedId = driver.id
                                    keyboard?.hide()
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                            item(key = "add") {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Surface(
                                        onClick = { adding = true },
                                        modifier = Modifier.size(156.dp).semantics { contentDescription = "운전자 추가" }, shape = RoundedCornerShape(20.dp),
                                        color = Color.Transparent,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text("+", fontSize = 64.sp, fontWeight = FontWeight.Light)
                                        }
                                    }
                                    Spacer(Modifier.height(16.dp))
                                    Text("운전자 추가", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Column(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(guidance, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Button(
                            onClick = { selected?.let { onStart(it.id) } },
                            enabled = selected != null && canInteract && linked && odometerReady,
                            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().heightIn(min = 72.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("운행 시작", fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
        if (adding && canInteract) AddDriverDialog(onDismiss = { adding = false }) { name, department ->
            onAdd(name, department)
            adding = false
        }
    }
}

@Composable
private fun DriverProfile(driver: DriverEntity, selected: Boolean, recent: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val avatarColors = listOf(
        Color(0xFF1452A0), Color(0xFF9B3543), Color(0xFF256658), Color(0xFF65509B),
    )
    val avatarColor = avatarColors[Math.floorMod(driver.id - 1, avatarColors.size.toLong()).toInt()]
    Column(
        Modifier.clip(RoundedCornerShape(20.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(156.dp)
                .border(if (selected) 3.dp else 0.dp, if (selected) colors.primary else Color.Transparent, RoundedCornerShape(20.dp))
                .padding(6.dp).clip(RoundedCornerShape(15.dp))
                .background(Brush.linearGradient(listOf(avatarColor, avatarColor.copy(alpha = 0.92f)))),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().padding(34.dp)) {
                val ink = Color.White
                drawCircle(ink, radius = size.width * 0.065f, center = Offset(size.width * 0.18f, size.height * 0.30f))
                drawCircle(ink, radius = size.width * 0.065f, center = Offset(size.width * 0.82f, size.height * 0.30f))
                drawArc(ink, 20f, 140f, false, Offset(size.width * 0.20f, size.height * 0.35f), Size(size.width * 0.60f, size.height * 0.40f), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            }
            if (selected) {
                Text("✓", Modifier.align(Alignment.TopEnd).padding(8.dp).background(colors.primary, CircleShape).padding(horizontal = 6.dp), color = colors.onPrimary, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(driver.name, fontSize = 24.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(driver.department.ifBlank { "운전자" }, Modifier.padding(top = 6.dp), fontSize = 18.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(if (selected) "선택됨" else if (recent) "최근 운전자" else "", Modifier.padding(top = 8.dp), fontSize = 18.sp, color = colors.primary)
    }
}

@Composable
private fun AddDriverDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var department by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("운전자 추가", fontSize = 28.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(name, { name = it.take(50) }, label = { Text("이름", fontSize = 20.sp) }, textStyle = TextStyle(fontSize = 22.sp), singleLine = true)
                OutlinedTextField(department, { department = it.take(50) }, label = { Text("부서 (선택)", fontSize = 20.sp) }, textStyle = TextStyle(fontSize = 22.sp), singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(name.trim(), department.trim()) }, enabled = name.isNotBlank(), modifier = Modifier.heightIn(min = 64.dp)) { Text("추가", fontSize = 20.sp) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 64.dp)) { Text("취소", fontSize = 20.sp) } },
    )
}

@Preview(name = "Driver selection · night", widthDp = 1100, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Driver selection · split", widthDp = 540, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun DriverSelectionPreview() {
    DriverSelectionScreen(
        drivers = listOf(
            DriverEntity(id = 1, name = "김민수", department = "영업팀"),
            DriverEntity(id = 2, name = "이서연", department = "운영팀"),
            DriverEntity(id = 3, name = "박지훈", department = "경영지원팀"),
        ),
        recentDriverId = 1, parked = true, linked = true, odometerReady = true,
        onStart = {}, onAdd = { _, _ -> }, onOpenVehicle = {},
    )
}
