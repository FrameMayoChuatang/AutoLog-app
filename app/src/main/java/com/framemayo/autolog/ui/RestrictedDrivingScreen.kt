package com.framemayo.autolog.ui

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
internal fun DrivingRestrictionGate(state: MainUiState, ux: DrivingUxState, parkedContent: @Composable () -> Unit) {
    val allowed = canUseParkedControls(state.vehicle, ux)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(allowed) {
        if (!allowed) {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    if (allowed) parkedContent() else RestrictedDrivingScreen(state, ux)
}

/** 주행 중에는 조작·스크롤·캐릭터 애니메이션 없이 현재 기록의 핵심 수치만 보여준다. */
@Composable
internal fun RestrictedDrivingScreen(state: MainUiState, ux: DrivingUxState) {
    val trip = state.activeTrip
    val receiving = state.vehicle.serviceRunning && state.vehicle.collector == CollectorStatus.RUNNING
    val status = when {
        !receiving -> "차량 정보 수신 대기"
        trip != null -> "기록 중"
        !state.linked -> "차량 연결 필요"
        state.vehicle.odometerKm?.let { it.isFinite() && it > 0f } != true -> "차량 정보 수신 대기"
        else -> "운행 시작 대기"
    }
    val nameLimit = ux.maxStringLength.coerceIn(1, 24)
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        val compact = maxHeight < 400.dp
        Column(Modifier.widthIn(max = 800.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 24.dp)) {
            Text("운행 현황", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = VehicleUi.Primary)
            Text(status, fontSize = 22.sp, color = if (trip != null && receiving) VehicleUi.Positive else VehicleUi.Secondary)
            if (trip != null) Text(state.driverName(trip.driverId).take(nameLimit), fontSize = 28.sp,
                color = VehicleUi.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ReadOnlyTripValue("주행 거리", trip?.let { "%.1f km".format(it.distanceKm()) } ?: "—", Modifier.weight(1f), compact)
                ReadOnlyTripValue("주행 시간", trip?.let { "${(it.durationMs() / 60_000).coerceAtLeast(0)}분" } ?: "—", Modifier.weight(1f), compact)
            }
            Text("검색과 편집은 주차 후 이용해 주세요.", fontSize = 20.sp, color = VehicleUi.Secondary)
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
