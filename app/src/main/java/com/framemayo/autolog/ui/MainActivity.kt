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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.framemayo.autolog.service.VehicleSnapshot
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.framemayo.autolog.data.local.DriverEntity
import com.framemayo.autolog.data.local.DriverStatus
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.local.distanceKm
import com.framemayo.autolog.data.local.durationMs
import com.framemayo.autolog.data.sync.syncStatus
import com.framemayo.autolog.data.location.FusedLocationSource
import com.framemayo.autolog.service.TripRecorderService
import com.framemayo.autolog.ui.theme.AutoLogTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var drivingUx by mutableStateOf(DrivingUxState())
    private val uxObserver by lazy { CarUxObserver(this) { drivingUx = it } }
    private var notificationPermissionRequested = false
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* 거부돼도 위치만 수집 안 함 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TripRecorderService.start(this)

        setContent {
            AutoLogTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    DrivingRestrictionGate(state, drivingUx, onRetryRestrictions = uxObserver::start,
                        waitingFooter = {
                            val linkState by viewModel.linkState.collectAsStateWithLifecycle()
                            VehicleLinkStatusCard(linkState.link)
                        }) {
                        LaunchedEffect(Unit) {
                            if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionRequested) {
                                notificationPermissionRequested = true
                                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
                            }
                        }
                        MainScreen(state, viewModel) {
                            if (canUseParkedControls(viewModel.state.value.vehicle, drivingUx)) {
                                locationPermission.launch(arrayOf(FusedLocationSource.PERMISSION, Manifest.permission.ACCESS_FINE_LOCATION))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        uxObserver.start()
    }

    override fun onStop() {
        uxObserver.stop()
        super.onStop()
    }
}

@Composable
private fun MainScreen(state: MainUiState, vm: MainViewModel, requestLocationPermission: () -> Unit) {
    val v = state.vehicle
    val linked = state.linked
    // DrivingRestrictionGate에서 플랫폼 제한과 P 기어를 모두 확인한 후 진입한다.
    val parked = v.gear == VehicleGear.GEAR_PARK
    var showVehicle by rememberSaveable { mutableStateOf(false) }
    var showTripDetail by rememberSaveable { mutableStateOf(false) }
    var selectedTripId by rememberSaveable { mutableStateOf<Long?>(null) }
    var historyPage by rememberSaveable { mutableIntStateOf(0) }
    var lastActiveId by rememberSaveable { mutableStateOf<Long?>(null) }
    // 운행을 마친 뒤에도 기록 화면에 머물고, 방금 끝난 운행의 상세에서 바로 목적을 확인한다.
    LaunchedEffect(state.activeTrip?.id) {
        val current = state.activeTrip?.id
        if (current != null) showVehicle = true
        else if (lastActiveId != null) selectedTripId = lastActiveId
        lastActiveId = current
        showTripDetail = false
    }
    if (parked) {
        state.consentPrompt?.let { ConsentDialog(it, state.driverName(it.id), vm, requestLocationPermission) }
    }
    if (state.activeTrip == null && (!showVehicle || !parked)) {
        DriverSelectionScreen(
            profiles = state.profiles,
            parked = parked,
            linked = linked,
            odometerReady = v.odometerKm?.let { it.isFinite() && it > 0f } == true,
            onStart = vm::startTrip,
            onSave = { id, name, color, expression, consent ->
                vm.saveProfile(id, name, color, expression, consent)
                if (consent == true) requestLocationPermission()
            },
            onDelete = vm::deleteProfile,
            onOpenVehicle = { showVehicle = true },
            footer = { VehicleLinkCard(vm) },
        )
        return
    }
    val active = state.activeTrip
    if (active != null && (!showTripDetail || !parked)) {
        DrivingScreen(
            active, state.driverName(active.driverId), state.profiles.find { it.driver.id == active.driverId },
            parked = parked, canEnd = v.isParked && v.odometerKm != null,
            onEnd = vm::endTrip, onOpenVehicle = { showTripDetail = true },
        )
        return
    }
    BackHandler(enabled = active != null) { showTripDetail = false }
    BackHandler(enabled = showVehicle && state.activeTrip == null) { showVehicle = false }
    val selected = state.recentTrips.find { it.id == selectedTripId }
    if (selected != null) {
        BackHandler { selectedTripId = null }
        val stops by remember(selected.id) { vm.tripStops(selected.id) }.collectAsStateWithLifecycle(emptyList())
        val energy by produceState(emptyList(), selected.id, selected.endedAt) {
            value = try { vm.tripEnergy(selected.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        }
        TripDetailScreen(
            selected, state, parked, stops, energy,
            onBack = { selectedTripId = null },
            onAssign = { vm.assignDriver(selected.id, it) },
            onPurpose = { purpose, text -> vm.setPurpose(selected.id, purpose, text) },
            loadPurposes = vm::loadPurposes,
        )
        return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
        modifier = Modifier.widthIn(max = 1100.dp).fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (state.activeTrip == null) {
            item { VehicleSecondaryButton("← 운전자 선택으로 돌아가기", { showVehicle = false }) }
        } else {
            item { VehicleSecondaryButton("← 주행 중 화면으로 돌아가기", { showTripDetail = false }) }
        }
        if (active != null) {
            item {
                VehicleSection("운행 중 · ${state.driverName(active.driverId)}") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val now by rememberNow()
                        Text(
                            "%.2f km · %d분".format(active.distanceKm(), active.durationMs(now) / 60_000),
                            Modifier.weight(1f), fontSize = VehicleUi.Value, fontWeight = FontWeight.Bold, color = VehicleUi.Primary,
                        )
                        VehiclePrimaryButton("운행 종료", vm::endTrip, enabled = v.isParked && v.odometerKm != null)
                    }
                    if (!v.isParked) Text("주차(P) 후 종료할 수 있어요.", fontSize = VehicleUi.Caption, color = VehicleUi.Caution)
                }
            }
        }
        item { VehicleStatusCard(v) }

        recentTrips(state.recentTrips, state, historyPage, { historyPage = it }, { selectedTripId = it.id })
    }
    }
}

@Composable
private fun VehicleStatusCard(v: VehicleSnapshot) {
    VehicleSection("차량 상태") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusTile("누적거리", v.odometerKm?.let { "%,.1f km".format(it) } ?: "수신 대기", Modifier.weight(1f), ok = v.odometerKm?.let { it > 0f } == true)
            StatusTile("기어", v.gear.label(), Modifier.weight(1f), ok = v.gear == VehicleGear.GEAR_PARK)
            StatusTile("기록 서비스", if (v.serviceRunning) "실행 중" else "꺼짐", Modifier.weight(1f), ok = v.serviceRunning)
        }
        if (v.gear == VehicleGear.GEAR_PARK && v.stationaryMs > 0) {
            Text("정차 ${v.stationaryMs / 1000}초${if (v.stopCandidate) " · 운행 종료 후보" else ""}", fontSize = VehicleUi.Caption, color = VehicleUi.Secondary)
        }
    }
}

@Composable
private fun StatusTile(label: String, value: String, modifier: Modifier, ok: Boolean) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = VehicleUi.Tile) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, fontSize = VehicleUi.Caption, color = VehicleUi.Secondary)
            Text(value, fontSize = VehicleUi.Value, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = if (ok) VehicleUi.Primary else VehicleUi.Caution)
        }
    }
}

private fun VehicleGear?.label(): String = when (this) {
    null, VehicleGear.GEAR_UNKNOWN -> "확인 중"
    VehicleGear.GEAR_PARK -> "P · 주차"
    VehicleGear.GEAR_REVERSE -> "R · 후진"
    VehicleGear.GEAR_NEUTRAL -> "N · 중립"
    VehicleGear.GEAR_DRIVE -> "D · 주행"
    else -> "D · 주행"
}
