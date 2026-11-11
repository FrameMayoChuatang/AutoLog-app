package com.framemayo.autolog.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    val linked = vm.linkState.collectAsStateWithLifecycle().value.link != null
    state.consentPrompt?.let { ConsentDialog(it.name, vm, requestLocationPermission) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
                        val driver = state.drivers.find { it.id == active.driverId }?.name ?: "미확인"
                        Text("운행 중 · $driver", style = MaterialTheme.typography.titleMedium)
                        Text("%.2f km · %d분".format(active.distanceKm(), active.durationMs() / 60_000))
                        Button(onClick = vm::endTrip, enabled = v.isParked && v.odometerKm != null) {
                            Text("운행 종료")
                        }
                    }
                }
            }
        } else {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("누가 운전하나요?", style = MaterialTheme.typography.titleMedium)
                        val canStart = linked && v.isParked && v.odometerKm != null
                        if (!linked) Text("차량을 먼저 연결해 주세요.", color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.drivers.filter { it.status != DriverStatus.INACTIVE }.forEach { d ->
                                Button(onClick = { vm.startTrip(d.id) }, enabled = canStart) {
                                    Text(if (d.status == DriverStatus.PENDING) "${d.name} (승인 대기)" else d.name)
                                }
                            }
                            OutlinedButton(onClick = { vm.startTrip(null) }, enabled = canStart) { Text("나중에") }
                        }
                        AddDriver(vm::addDriver)
                    }
                }
            }
        }

        item { LocationConsentCard(state, vm, requestLocationPermission) }

        item { Text("최근 운행", style = MaterialTheme.typography.titleMedium) }
        items(state.recentTrips, key = { it.id }) { trip -> TripRow(trip, state.drivers, vm) }
    }
}

@Composable
private fun AddDriver(onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var dept by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("이름") })
        OutlinedTextField(dept, { dept = it }, label = { Text("부서") })
        OutlinedButton(onClick = { onAdd(name, dept); name = ""; dept = "" }) { Text("운전자 추가") }
    }
}

private val KST = ZoneId.of("Asia/Seoul")
private val TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(KST)

@Composable
private fun TripRow(trip: TripEntity, drivers: List<DriverEntity>, vm: MainViewModel) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val driverName = drivers.find { it.id == trip.driverId }?.name ?: "운전자 없음"
            Text(
                "${TIME.format(Instant.ofEpochMilli(trip.startedAt))} · ${"%.2f".format(trip.distanceKm())} km · " +
                    "${trip.status} · ${trip.purpose} · $driverName",
            )
            if (trip.driverId == null && trip.endedAt != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("운전자 지정:")
                    drivers.filter { it.status != DriverStatus.INACTIVE }.forEach { d ->
                        PurposeButton(d.name) { vm.assignDriver(trip.id, d.id) }
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
