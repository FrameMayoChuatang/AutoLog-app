package com.framemayo.autolog.ui

import androidx.compose.runtime.getValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemayo.autolog.data.local.distanceKm
import com.framemayo.autolog.data.local.durationMs
import com.framemayo.autolog.service.CollectorStatus
import com.framemayo.autolog.service.VehicleSnapshot

internal fun canUseParkedControls(vehicle: VehicleSnapshot, ux: DrivingUxState): Boolean =
    vehicle.gear == VehicleGear.GEAR_PARK && vehicle.serviceRunning &&
        vehicle.collector == CollectorStatus.RUNNING && ux.allowsInput

/** 제한 전환 시 편집 화면 자체를 제거해 검색·프로필·동의 모달도 함께 닫는다. */
@Composable
internal fun DrivingRestrictionGate(
    state: MainUiState,
    ux: DrivingUxState,
    onRetryRestrictions: () -> Unit = {},
    parkedContent: @Composable () -> Unit,
) {
    val allowed = canUseParkedControls(state.vehicle, ux)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(allowed) {
        if (!allowed) {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    if (allowed) parkedContent() else RestrictedDrivingScreen(state, ux, onRetryRestrictions)
}

/** 주행 중에는 조작·스크롤·애니메이션 없이 운전자 프로필과 현재 기록의 핵심 수치를 보여준다. */
@Composable
internal fun RestrictedDrivingScreen(state: MainUiState, ux: DrivingUxState, onRetryRestrictions: () -> Unit = {}) {
    val trip = state.activeTrip
    val profile = state.profiles.find { it.driver.id == trip?.driverId }
    val receiving = state.vehicle.serviceRunning && state.vehicle.collector == CollectorStatus.RUNNING
    val status = when {
        !receiving -> "차량 정보 수신 대기"
        trip != null -> "기록 중"
        !state.linked -> "차량 연결 필요"
        state.vehicle.odometerKm?.let { it.isFinite() && it > 0f } != true -> "차량 정보 수신 대기"
        else -> "운행 시작 대기"
    }
    val nameLimit = ux.maxStringLength.coerceIn(1, 24)
    val restrictionMessage = if (ux.ready) null else when (ux.failure) {
        DrivingUxFailure.WAITING -> "주행 제한 정보 수신 대기 · 입력 잠금"
        DrivingUxFailure.DISCONNECTED -> "주행 제한 서비스 연결 끊김 · 입력 잠금"
        DrivingUxFailure.READ_FAILED -> "주행 제한 정보를 읽을 수 없음 · 입력 잠금"
        DrivingUxFailure.API_UNAVAILABLE -> "주행 제한 API 미지원 · 입력 잠금"
    }
    val canRetry = !ux.ready && state.vehicle.gear == VehicleGear.GEAR_PARK && receiving
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        val compact = maxHeight < 400.dp
        Column(Modifier.widthIn(max = 800.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("운행 현황", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = VehicleUi.Primary)
                if (canRetry) OutlinedButton(onClick = onRetryRestrictions) {
                    Text("제한 정보 다시 연결", fontSize = 20.sp, color = VehicleUi.Primary)
                }
            }
            if (restrictionMessage != null) Text(restrictionMessage, fontSize = 20.sp,
                lineHeight = 28.sp, color = VehicleUi.Secondary)
            Text(status, fontSize = 22.sp, color = if (trip != null && receiving) VehicleUi.Positive else VehicleUi.Secondary)
            if (trip != null) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp)) {
                Surface(Modifier.size(if (compact) 64.dp else 148.dp)
                    .semantics { contentDescription = if (profile != null) "현재 운전자 프로필" else "미지정 운전자 프로필" },
                    shape = RoundedCornerShape(if (compact) 16.dp else 24.dp), color = Color.White) {
                    Box(contentAlignment = Alignment.Center) {
                        DriverAvatar(profile?.color ?: 0, Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                            expression = profile?.expression ?: 0)
                    }
                }
                Text(state.driverName(trip.driverId).take(nameLimit), fontSize = 28.sp,
                    color = VehicleUi.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ReadOnlyTripValue("주행 거리", trip?.let { "%.1f km".format(it.distanceKm()) } ?: "—", Modifier.weight(1f), compact)
                val now by rememberNow()
                ReadOnlyTripValue("주행 시간", trip?.let { "${(it.durationMs(now) / 60_000).coerceAtLeast(0)}분" } ?: "—", Modifier.weight(1f), compact)
            }
        }
    }
}

@Composable
private fun ReadOnlyTripValue(label: String, value: String, modifier: Modifier, compact: Boolean) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = VehicleUi.Surface) {
        Column(Modifier.padding(if (compact) 16.dp else 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, fontSize = 20.sp, color = VehicleUi.Secondary)
            Text(value, fontSize = if (compact) 32.sp else 44.sp, fontWeight = FontWeight.Bold,
                color = VehicleUi.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
