package com.framemayo.autolog.ui

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.framemayo.autolog.data.local.DriverEntity
import com.framemayo.autolog.data.local.DriverStatus
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.local.distanceKm
import com.framemayo.autolog.data.local.durationMs
import com.framemayo.autolog.data.location.FusedLocationSource
import com.framemayo.autolog.service.TripRecorderService
import com.framemayo.autolog.ui.theme.AutoLogTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* 거부돼도 위치만 수집 안 함 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        TripRecorderService.start(this)

        setContent {
            AutoLogTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    MainScreen(state, viewModel) {
                        locationPermission.launch(arrayOf(FusedLocationSource.PERMISSION, Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                }
            }
        }
    }
}

@Composable
private fun MainScreen(state: MainUiState, vm: MainViewModel, requestLocationPermission: () -> Unit) {
    val v = state.vehicle
    val linked = state.linked
    // TODO: android.car를 제공하는 이미지에서 CarUxRestrictions를 연동한다.
    // 현재 Pleos v2.1은 해당 API가 없어 OS 차단을 유지하고, P 확인 전에는 이 UI를 잠근다.
    val parked = v.gear == VehicleGear.GEAR_PARK
    var showVehicle by rememberSaveable { mutableStateOf(false) }
    // 운행을 마친 뒤에도 목적 선택과 기록 확인 화면에 머문다.
    LaunchedEffect(state.activeTrip?.id) {
        if (state.activeTrip != null) showVehicle = true
    }
    if (parked) {
        state.consentPrompt?.let { ConsentDialog(state.driverName(it.id), vm, requestLocationPermission) }
    }
    if (state.activeTrip == null && (!showVehicle || !parked)) {
        DriverSelectionScreen(
            profiles = state.profiles,
            parked = parked,
            linked = linked,
            odometerReady = v.odometerKm?.let { it.isFinite() && it > 0f } == true,
            onStart = vm::startTrip,
            onSave = vm::saveProfile,
            onDelete = vm::deleteProfile,
            onOpenVehicle = { showVehicle = true },
        )
        return
    }
    BackHandler(enabled = showVehicle && state.activeTrip == null) { showVehicle = false }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.activeTrip == null) {
            item { OutlinedButton(onClick = { showVehicle = false }) { Text("운전자 선택으로 돌아가기") } }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("차량 상태", style = MaterialTheme.typography.titleMedium)
                    Text("기록 서비스: ${if (v.serviceRunning) "실행 중" else "꺼짐"}")
                    Text("누적거리: ${v.odometerKm?.let { "%.1f km".format(it) } ?: "수신 대기"}")
                    Text("기어: ${v.gear?.name ?: "알 수 없음"}  /  정차 ${v.stationaryMs / 1000}초${if (v.stopCandidate) " (정차 후보)" else ""}")
                }
            }
        }

        item { VehicleLinkCard(vm) }

        val active = state.activeTrip
        if (active != null) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val driver = state.driverName(active.driverId)
                        Text("운행 중 · $driver", style = MaterialTheme.typography.titleMedium)
                        Text("%.2f km · %d분".format(active.distanceKm(), active.durationMs() / 60_000))
                        Button(onClick = vm::endTrip, enabled = v.isParked && v.odometerKm != null) {
                            Text("운행 종료")
                        }
                    }
                }
            }
        }

        item { LocationConsentCard(state, vm, requestLocationPermission) }

        item { Text("최근 운행", style = MaterialTheme.typography.titleMedium) }
        items(state.recentTrips, key = { it.id }) { trip -> TripRow(trip, state, vm) }
    }
}

private val KST = ZoneId.of("Asia/Seoul")
private val TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(KST)

@Composable
private fun TripRow(trip: TripEntity, state: MainUiState, vm: MainViewModel) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val driverName = state.driverName(trip.driverId)
            Text(
                "${TIME.format(Instant.ofEpochMilli(trip.startedAt))} · ${"%.2f".format(trip.distanceKm())} km · " +
                    "${trip.status} · ${trip.purpose} · $driverName",
            )
            if (trip.driverId == null && trip.endedAt != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("운전자 지정:")
                    state.profiles.forEach { profile ->
                        PurposeButton(profile.displayName) { vm.assignDriver(trip.id, profile.driver.id) }
                    }
                }
            }
            if (trip.purpose == TripPurpose.UNCONFIRMED && trip.endedAt != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PurposeButton("업무(출퇴근)") { vm.setPurpose(trip.id, TripPurpose.BUSINESS_COMMUTE) }
                    PurposeButton("업무(일반)") { vm.setPurpose(trip.id, TripPurpose.BUSINESS_GENERAL) }
                    PurposeButton("개인") { vm.setPurpose(trip.id, TripPurpose.PERSONAL) }
                }
            }
        }
    }
}

@Composable
private fun PurposeButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(label) }
}
