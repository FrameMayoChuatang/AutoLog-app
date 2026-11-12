package com.framemayo.autolog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemayo.autolog.data.local.DriverProfile
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.distanceKm
import com.framemayo.autolog.data.local.durationMs

@Composable
internal fun DrivingScreen(
    trip: TripEntity,
    driverName: String,
    profile: DriverProfile?,
    parked: Boolean,
    canEnd: Boolean,
    onEnd: () -> Unit,
    onOpenVehicle: () -> Unit,
) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("주행 중", fontSize = VehicleUi.ButtonText, color = VehicleUi.Positive, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Surface(Modifier.widthIn(max = 220.dp).fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(28.dp), color = Color.White) {
                Box(contentAlignment = Alignment.Center) {
                    if (profile != null) DriverAvatar(profile.color, Modifier.fillMaxWidth().aspectRatio(4f / 3f), expression = profile.expression)
                    else Text("?", fontSize = 72.sp, color = VehicleUi.Surface)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(driverName, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = VehicleUi.Primary)
            if (trip.driverId == null) Text("운행이 끝나면 운전자를 지정해 주세요.", fontSize = VehicleUi.Caption, color = VehicleUi.Caution)
            Spacer(Modifier.height(32.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DrivingValue("주행 거리", "%.1f km".format(trip.distanceKm()), Modifier.weight(1f))
                DrivingValue("주행 시간", formatDuration(trip.durationMs()), Modifier.weight(1f))
            }
            Spacer(Modifier.height(32.dp))
            VehiclePrimaryButton("운행 종료", onEnd, Modifier.fillMaxWidth(), enabled = canEnd)
            if (!parked) {
                Text("주차(P) 후 종료할 수 있어요.", Modifier.padding(top = 12.dp), fontSize = VehicleUi.Caption, color = VehicleUi.Caution)
            } else {
                TextButton(onOpenVehicle, Modifier.padding(top = 8.dp)) {
                    Text("차량 상태와 최근 운행 보기", fontSize = VehicleUi.Caption, color = VehicleUi.Secondary)
                }
            }
        }
    }
}

@Composable
private fun DrivingValue(label: String, value: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = VehicleUi.Surface) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, fontSize = VehicleUi.Caption, color = VehicleUi.Secondary)
            Text(value, fontSize = 48.sp, fontWeight = FontWeight.Bold, color = VehicleUi.Primary)
        }
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(0)
    return if (minutes < 60) "${minutes}분" else "${minutes / 60}시간 ${minutes % 60}분"
}
