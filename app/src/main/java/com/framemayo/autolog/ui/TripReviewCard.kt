package com.framemayo.autolog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.framemayo.autolog.data.local.*
import com.framemayo.autolog.data.sync.syncStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TripTime = DateTimeFormatter.ofPattern("MM월 dd일 HH:mm").withZone(ZoneId.of("Asia/Seoul"))

internal fun TripPurpose.label(): String = when (this) {
    TripPurpose.UNCONFIRMED -> "목적 미확인"
    TripPurpose.BUSINESS_COMMUTE -> "업무 · 출퇴근"
    TripPurpose.BUSINESS_GENERAL -> "업무 · 일반"
    TripPurpose.PERSONAL -> "개인"
}

internal fun TripStatus.label(): String = when (this) {
    TripStatus.ACTIVE -> "운행 중"
    TripStatus.COMPLETED -> "완료"
    TripStatus.INTERRUPTED -> "중단됨"
}

/** 목록과 확인창은 같은 운행 상태를 읽고, 저장은 기존 ViewModel 콜백에 위임한다. */
@Composable
internal fun TripReviewCard(
    trip: TripEntity,
    state: MainUiState,
    parked: Boolean,
    onAssign: (Long) -> Unit,
    onPurpose: (TripPurpose) -> Unit,
) {
    var reviewing by rememberSaveable(trip.id) { mutableStateOf(false) }
    var choosingDriver by rememberSaveable(trip.id) { mutableStateOf(false) }
    var candidateId by rememberSaveable(trip.id) { mutableStateOf<Long?>(null) }
    var query by rememberSaveable(trip.id) { mutableStateOf("") }
    val status = state.syncStatus[trip.id] ?: trip.syncStatus(state.drivers.find { it.id == trip.driverId }, state.linked)
    LaunchedEffect(parked) {
        if (!parked) { reviewing = false; choosingDriver = false; candidateId = null; query = "" }
    }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = Color(0xFF17181D)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(state.driverName(trip.driverId), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Text(TripTime.format(Instant.ofEpochMilli(trip.startedAt)), fontSize = 18.sp, color = Color(0xFFB9BEC8))
                }
                Text(trip.status.label(), fontSize = 20.sp)
            }
            Text("%.2f km · %d분".format(trip.distanceKm(), trip.durationMs() / 60_000), fontSize = 30.sp)
            Text(trip.purpose.label(), fontSize = 22.sp)
            if (trip.driverId == null) Text("운전자를 지정해 주세요", fontSize = 20.sp, color = Color(0xFFF0CF8A))
            TripSyncStatusNotice(status, trip.purpose)
            if (trip.endedAt != null) Button(
                onClick = { reviewing = true }, enabled = parked,
                modifier = Modifier.align(Alignment.End).heightIn(min = 56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF17181D)),
            ) { Text(if (trip.purpose == TripPurpose.UNCONFIRMED) "운행 확인" else "확인 및 수정", fontSize = 20.sp) }
        }
    }
    if (reviewing && parked) {
        var selectedPurpose by rememberSaveable(trip.id, trip.purpose) { mutableStateOf(trip.purpose) }
        Dialog(onDismissRequest = { reviewing = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                Modifier.padding(24.dp).widthIn(max = 960.dp).fillMaxWidth().heightIn(max = 720.dp),
                shape = RoundedCornerShape(28.dp), color = Color(0xFF17181D),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("운행 확인", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Text("${TripTime.format(Instant.ofEpochMilli(trip.startedAt))} · ${trip.status.label()}", fontSize = 20.sp)
                    Text("운전자 · ${state.driverName(trip.driverId)}", fontSize = 24.sp)
                    if (trip.driverId == null) {
                        OutlinedButton(onClick = { choosingDriver = true }, enabled = state.profiles.isNotEmpty(), modifier = Modifier.heightIn(min = 56.dp)) {
                            Text("운전자 지정", fontSize = 20.sp)
                        }
                        if (state.profiles.isEmpty()) Text("선택할 프로필이 없어요. 운전자 선택 화면에서 프로필을 추가해 주세요.", fontSize = 18.sp)
                        Text("운전자를 나중에 지정해도 선택한 목적은 보관됩니다.", fontSize = 18.sp, color = Color(0xFFB9BEC8))
                    }
                    Text("운행 목적", fontSize = 22.sp)
                    PurposeChoices(selectedPurpose) { selectedPurpose = it }
                    TripSyncStatusNotice(status, trip.purpose)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedButton(onClick = { reviewing = false }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("닫기", fontSize = 20.sp) }
                        Button(
                            onClick = { onPurpose(selectedPurpose); reviewing = false },
                            enabled = selectedPurpose != TripPurpose.UNCONFIRMED,
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF17181D)),
                        ) { Text("목적 저장", fontSize = 20.sp) }
                    }
                }
            }
        }
    }
    if (choosingDriver && parked) DriverSearchDialog(
        query, { query = it }, state.profiles, Rect.Zero, false,
        onDismiss = { choosingDriver = false; query = "" },
        onSelect = { candidateId = it.driver.id; choosingDriver = false; query = "" },
    )
    val candidate = state.profiles.find { it.driver.id == candidateId }
    if (candidate != null && trip.driverId == null && parked) AlertDialog(
        onDismissRequest = { candidateId = null },
        title = { Text("운전자를 지정할까요?", fontSize = 26.sp) },
        text = { Text("${candidate.displayName}님으로 지정합니다. 지정한 운전자는 차량에서 다시 변경할 수 없습니다.", fontSize = 22.sp) },
        confirmButton = { TextButton(onClick = { onAssign(candidate.driver.id); candidateId = null }, modifier = Modifier.heightIn(min = 56.dp)) { Text("지정", fontSize = 20.sp) } },
        dismissButton = { TextButton(onClick = { candidateId = null }, modifier = Modifier.heightIn(min = 56.dp)) { Text("취소", fontSize = 20.sp) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PurposeChoices(selected: TripPurpose, onSelect: (TripPurpose) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(TripPurpose.BUSINESS_COMMUTE, TripPurpose.BUSINESS_GENERAL, TripPurpose.PERSONAL).forEach { purpose ->
            FilterChip(
                selected = selected == purpose, onClick = { onSelect(purpose) },
                modifier = Modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(14.dp),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color.White, selectedLabelColor = Color(0xFF17181D)),
                label = { Text(purpose.label(), fontSize = 22.sp) },
            )
        }
    }
}
