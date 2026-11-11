package com.framemayo.autolog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.framemayo.autolog.data.link.VehicleLinkRepository

// 앱 C 담당 화면: 차량 연결, 위치정보 수집 동의. 메인 화면(MainActivity)은 이 컴포저블을 가져다 배치만 한다.

@Composable
internal fun ConsentDialog(name: String, vm: MainViewModel, requestLocationPermission: () -> Unit) {
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
internal fun LocationConsentCard(state: MainUiState, vm: MainViewModel, requestLocationPermission: () -> Unit) {
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
internal fun VehicleLinkCard(vm: MainViewModel) {
    val s by vm.linkState.collectAsStateWithLifecycle()
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val link = s.link
            if (link != null) {
                Text("차량 연결됨", style = MaterialTheme.typography.titleMedium)
                Text(listOf(link.registrationNumber, link.model).filter { it.isNotBlank() }.joinToString(" · "))
                OutlinedButton(onClick = vm::disconnectVehicle) { Text("연결 해제") }
                return@Column
            }
            var code by remember { mutableStateOf("") }
            Text("차량 연결", style = MaterialTheme.typography.titleMedium)
            Text("관리자가 발급한 8자리 숫자 코드를 입력하세요.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    code,
                    { code = it.filter { c -> c in '0'..'9' }.take(VehicleLinkRepository.CODE_LENGTH) }, // 앞자리 0 유지를 위해 문자열 그대로
                    label = { Text("페어링 코드") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Button(
                    onClick = { vm.connectVehicle(code) },
                    enabled = !s.busy && code.length == VehicleLinkRepository.CODE_LENGTH,
                ) { Text("연결") }
            }
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
