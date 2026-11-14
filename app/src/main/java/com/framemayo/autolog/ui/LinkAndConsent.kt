package com.framemayo.autolog.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.framemayo.autolog.data.link.VehicleLinkRepository
import com.framemayo.autolog.data.link.VehicleLink
import com.framemayo.autolog.data.local.DriverEntity

@Composable
internal fun ConsentDialog(driver: DriverEntity, name: String, vm: MainViewModel, requestLocationPermission: () -> Unit) {
    ConsentPromptDialog(
        name = name,
        previouslyDeclined = driver.locationConsent == false,
        onAgree = { vm.answerConsent(true); requestLocationPermission() },
        onDecline = if (driver.locationConsent == false) vm::startWithoutConsent else vm::declineConsent,
        onDismiss = vm::dismissConsent,
    )
}

/** 화면 크기에 맞춰 안내만 스크롤하고, 동의 선택 버튼은 항상 아래에 남긴다. */
@Composable
internal fun ConsentPromptDialog(
    name: String,
    previouslyDeclined: Boolean,
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().semantics { contentDescription = "위치정보 동의 모달 배경" }
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
                .safeDrawingPadding().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val stackedButtons = maxWidth < 560.dp
            val padding = if (stackedButtons) 24.dp else 40.dp
            Surface(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().heightIn(max = maxHeight)
                    .pointerInput(Unit) { detectTapGestures {} },
                shape = RoundedCornerShape(28.dp), color = VehicleUi.Surface,
            ) {
                Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Text(
                            if (previouslyDeclined) "위치정보 수집에 동의하지 않았어요" else "위치정보 수집 동의",
                            fontSize = VehicleUi.Title, lineHeight = 42.sp, fontWeight = FontWeight.Bold,
                            color = if (previouslyDeclined) VehicleUi.Caution else VehicleUi.Primary,
                        )
                        Text(
                            if (previouslyDeclined) "${name}님의 업무 운행 도착 위치가 저장되지 않습니다."
                            else "${name}님의 업무 운행에 한해 도착 위치를 저장합니다.",
                            fontSize = 24.sp, lineHeight = 36.sp, color = VehicleUi.Primary,
                        )
                        Text(
                            if (previouslyDeclined) "운행 거리와 시간은 정상 기록됩니다."
                            else "개인 운행은 저장하지 않으며,\n동의하지 않아도 운행 기록은 정상 작성됩니다.",
                            fontSize = 24.sp, lineHeight = 36.sp, color = VehicleUi.Secondary,
                        )
                    }
                    val declineText = if (previouslyDeclined) "동의 없이 시작" else "동의 안 함"
                    val agreeText = if (previouslyDeclined) "동의하고 시작" else "동의"
                    if (stackedButtons) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            VehicleSecondaryButton(declineText, onDecline, Modifier.fillMaxWidth().heightIn(min = 64.dp))
                            VehiclePrimaryButton(agreeText, onAgree, Modifier.fillMaxWidth().heightIn(min = 64.dp))
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            VehicleSecondaryButton(declineText, onDecline, Modifier.weight(1f).heightIn(min = 64.dp))
                            VehiclePrimaryButton(agreeText, onAgree, Modifier.weight(1f).heightIn(min = 64.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun VehicleLinkStatusCard(link: VehicleLink?) {
    val status = if (link == null) "연결된 차량 없음" else "연결됨"
    val statusColor = if (link == null) VehicleUi.Secondary else VehicleUi.Positive
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("vehicle-link-status")) {
        if (maxWidth < 640.dp) Surface(shape = RoundedCornerShape(24.dp), color = VehicleUi.Surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("차량 연결", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = VehicleUi.Primary)
                    Text(status, fontSize = 18.sp, color = statusColor)
                }
                if (link != null) Text(listOf(link.registrationNumber, link.model).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 20.sp, color = VehicleUi.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else VehicleSection("차량 연결") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(status, fontSize = VehicleUi.Caption, color = statusColor)
                if (link != null) {
                    Text(link.registrationNumber, fontSize = VehicleUi.Value, fontWeight = FontWeight.Bold, color = VehicleUi.Primary)
                    if (link.model.isNotBlank()) Text(link.model, fontSize = VehicleUi.Caption, color = VehicleUi.Secondary)
                }
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
                    Text(link.registrationNumber, fontSize = VehicleUi.Value, fontWeight = FontWeight.Bold, color = VehicleUi.Primary)
                    if (link.model.isNotBlank()) Text(link.model, fontSize = VehicleUi.Caption, color = VehicleUi.Secondary)
                }
                VehicleSecondaryButton("연결 해제", { confirming = true })
            }
        }
        if (confirming) AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = VehicleUi.Surface,
            title = { Text("차량 연결을 해제할까요?", fontSize = VehicleUi.Title, color = VehicleUi.Primary) },
            text = {
                Text(
                    "해제하면 운행을 시작할 수 없고, 아직 전송되지 않은 기록은 다시 연결한 뒤 처음부터 전송합니다.",
                    fontSize = VehicleUi.Body, lineHeight = 34.sp, color = VehicleUi.Secondary,
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
                textStyle = TextStyle(fontSize = VehicleUi.Value, letterSpacing = 4.sp, color = VehicleUi.Primary),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VehicleUi.Primary, unfocusedBorderColor = VehicleUi.Secondary,
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
        s.error?.let { Text(it, fontSize = VehicleUi.Caption, lineHeight = 24.sp, color = VehicleUi.Error) }
    }
}
