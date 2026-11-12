package com.framemayo.autolog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.framemayo.autolog.data.link.VehicleLinkRepository

@Composable
internal fun ConsentDialog(name: String, vm: MainViewModel, requestLocationPermission: () -> Unit) {
    AlertDialog(
        onDismissRequest = vm::dismissConsent,
        containerColor = VehicleUi.Surface,
        title = { Text("위치정보 수집 동의", fontSize = 28.sp, color = VehicleUi.Primary) },
        text = {
            Text(
                "${name}님의 업무 운행에 한해 도착 위치를 저장합니다. 개인 운행은 저장하지 않으며, " +
                    "동의하지 않아도 운행 기록은 정상 작성됩니다. 동의는 언제든 철회할 수 있습니다.",
                fontSize = VehicleUi.Body, lineHeight = 32.sp, color = VehicleUi.Secondary,
            )
        },
        confirmButton = { VehiclePrimaryButton("동의", { vm.answerConsent(true); requestLocationPermission() }) },
        dismissButton = { VehicleSecondaryButton("동의 안 함", { vm.answerConsent(false) }) },
    )
}

@Composable
internal fun LocationConsentCard(state: MainUiState, vm: MainViewModel, requestLocationPermission: () -> Unit) {
    if (state.profiles.isEmpty()) return
    VehicleSection("위치정보 수집") {
        Text("업무 운행의 도착 위치만 저장합니다.", fontSize = VehicleUi.Caption, color = VehicleUi.Secondary)
        state.profiles.forEachIndexed { index, profile ->
            if (index > 0) HorizontalDivider(color = VehicleUi.Outline.copy(alpha = 0.4f))
            val d = profile.driver
            val agreed = d.locationConsent == true
            val (label, color) = when (d.locationConsent) {
                true -> "동의" to VehicleUi.Positive
                false -> "동의 안 함" to VehicleUi.Secondary
                null -> "미확인" to VehicleUi.Caution
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(profile.displayName, Modifier.weight(1f), fontSize = VehicleUi.Body, color = VehicleUi.Primary)
                Text(label, Modifier.padding(end = 20.dp), fontSize = VehicleUi.Caption, color = color)
                VehicleSecondaryButton(
                    if (agreed) "철회" else "동의",
                    { vm.setLocationConsent(d.id, !agreed); if (!agreed) requestLocationPermission() },
                    Modifier.widthIn(min = 120.dp),
                )
            }
        }
    }
}

@Composable
internal fun VehicleLinkCard(vm: MainViewModel) {
    val s by vm.linkState.collectAsStateWithLifecycle()
    val link = s.link
    if (link != null) {
        var confirming by remember { mutableStateOf(false) }
        VehicleSection("차량 연결") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("연결됨", fontSize = VehicleUi.Caption, color = VehicleUi.Positive)
                    Text(link.registrationNumber, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = VehicleUi.Primary)
                    if (link.model.isNotBlank()) Text(link.model, fontSize = VehicleUi.Caption, color = VehicleUi.Secondary)
                }
                VehicleSecondaryButton("연결 해제", { confirming = true })
            }
        }
        if (confirming) AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = VehicleUi.Surface,
            title = { Text("차량 연결을 해제할까요?", fontSize = 28.sp, color = VehicleUi.Primary) },
            text = {
                Text(
                    "해제하면 운행을 시작할 수 없고, 아직 전송되지 않은 기록은 다시 연결한 뒤 처음부터 전송합니다.",
                    fontSize = VehicleUi.Body, lineHeight = 32.sp, color = VehicleUi.Secondary,
                )
            },
            confirmButton = {
                TextButton({ confirming = false; vm.disconnectVehicle() }, Modifier.heightIn(min = VehicleUi.MinTouch)) {
                    Text("연결 해제", fontSize = VehicleUi.ButtonText, color = VehicleUi.Error)
                }
            },
            dismissButton = {
                TextButton({ confirming = false }, Modifier.heightIn(min = VehicleUi.MinTouch)) {
                    Text("취소", fontSize = VehicleUi.ButtonText, color = VehicleUi.Primary)
                }
            },
        )
        return
    }
    var code by remember { mutableStateOf("") }
    VehicleSection("차량 연결") {
        Text("관리자가 발급한 8자리 숫자 코드를 입력하세요.", fontSize = VehicleUi.Body, color = VehicleUi.Secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                code,
                { code = it.filter { c -> c in '0'..'9' }.take(VehicleLinkRepository.CODE_LENGTH) }, // 앞자리 0 유지를 위해 문자열 그대로
                Modifier.weight(1f).widthIn(max = 480.dp),
                label = { Text("페어링 코드", fontSize = VehicleUi.Caption) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 28.sp, letterSpacing = 4.sp, color = VehicleUi.Primary),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VehicleUi.Primary, unfocusedBorderColor = VehicleUi.Outline,
                    focusedLabelColor = VehicleUi.Primary, unfocusedLabelColor = VehicleUi.Secondary,
                    cursorColor = VehicleUi.Primary,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            VehiclePrimaryButton(
                if (s.busy) "연결 중…" else "연결",
                { vm.connectVehicle(code) },
                Modifier.widthIn(min = 140.dp),
                enabled = !s.busy && code.length == VehicleLinkRepository.CODE_LENGTH,
            )
        }
        Text("${code.length}/${VehicleLinkRepository.CODE_LENGTH}자리", fontSize = VehicleUi.Caption, color = VehicleUi.Secondary)
        s.error?.let { Text(it, fontSize = VehicleUi.Caption, lineHeight = 26.sp, color = VehicleUi.Error) }
    }
}
