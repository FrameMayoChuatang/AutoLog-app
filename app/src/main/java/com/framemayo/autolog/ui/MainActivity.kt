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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                        val canStart = v.isParked && v.odometerKm != null
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.drivers.forEach { d ->
                                Button(onClick = { vm.startTrip(d.id) }, enabled = canStart) { Text(d.name) }
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
        items(state.recentTrips, key = { it.id }) { trip -> TripRow(trip, vm) }
    }
}

@Composable
private fun ConsentDialog(name: String, vm: MainViewModel, requestLocationPermission: () -> Unit) {
    AlertDialog(
        onDismissRequest = vm::dismissConsent,
        title = { Text("위치정보 수집 동의") },
        text = {
            Text(
                "${name}님의 업무 운행에 한해 도착 위치를 저장합니다. 개인 운행은 저장하지 않으며, " +
                    "동의하지 않아도 운행 기록은 정상 작성됩니다. 동의는 언제든 철회할 수 있습니다.",
            )
        },
        confirmButton = { Button(onClick = { vm.answerConsent(true); requestLocationPermission() }) { Text("동의") } },
        dismissButton = { OutlinedButton(onClick = { vm.answerConsent(false) }) { Text("동의 안 함") } },
    )
}

@Composable
private fun LocationConsentCard(state: MainUiState, vm: MainViewModel, requestLocationPermission: () -> Unit) {
    if (state.drivers.isEmpty()) return
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("위치정보 수집 설정", style = MaterialTheme.typography.titleMedium)
            state.drivers.forEach { d ->
                val agreed = d.locationConsent == true
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${d.name}: ${when (d.locationConsent) { true -> "동의"; false -> "동의 안 함"; null -> "미확인" }}")
                    OutlinedButton(onClick = { vm.setLocationConsent(d.id, !agreed); if (!agreed) requestLocationPermission() }) { Text(if (agreed) "철회" else "동의") }
                }
            }
        }
    }
}

@Composable
private fun VehicleLinkCard(vm: MainViewModel) {
    val s by vm.linkState.collectAsStateWithLifecycle()
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val link = s.link
            if (link != null) {
                Text("차량 연결됨 · ${link.companyName}", style = MaterialTheme.typography.titleMedium)
                Text("${link.vehicleNumber} · VIN ${link.vin ?: "미확인"}")
                OutlinedButton(onClick = vm::disconnectVehicle) { Text("연결 해제") }
                return@Column
            }
            LaunchedEffect(Unit) { vm.detectVin() }
            var number by remember { mutableStateOf("") }
            var code by remember { mutableStateOf("") }
            Text("차량 연결", style = MaterialTheme.typography.titleMedium)
            Text("VIN: ${s.detectedVin ?: "자동 확인 불가"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(number, { number = it }, label = { Text("차량번호") }, singleLine = true)
                OutlinedTextField(code, { code = it }, label = { Text("인증 코드") }, singleLine = true)
                Button(onClick = { vm.connectVehicle(code, number) }, enabled = !s.busy) { Text("연결") }
            }
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
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
private fun TripRow(trip: TripEntity, vm: MainViewModel) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${TIME.format(Instant.ofEpochMilli(trip.startedAt))} · %.2f km · ${trip.status} · ${trip.purpose}"
                    .format(trip.distanceKm())
            )
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
