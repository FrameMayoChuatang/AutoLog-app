package com.framemayo.autolog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.sync.HoldReason
import com.framemayo.autolog.data.sync.TripSyncStatus

internal data class SyncPresentation(val title: String, val description: String, val accent: Color)

/** 전송 완료와 운전자 확인 완료를 구분하고, 서버가 제공하지 않은 실패 원인은 추정하지 않는다. */
internal fun TripSyncStatus.presentation(purpose: TripPurpose): SyncPresentation = when (this) {
    TripSyncStatus.Synced -> SyncPresentation(
        "전송 완료",
        if (purpose == TripPurpose.UNCONFIRMED) "측정값이 전송됐어요. 목적을 선택해 운행을 확인해 주세요." else "운행 기록이 서버에 반영됐어요.",
        Color(0xFF95D7BA),
    )
    TripSyncStatus.Waiting -> SyncPresentation("전송 대기", "연결 상태를 확인하고 자동으로 다시 전송합니다.", Color(0xFFBDD2F9))
    TripSyncStatus.Failed -> SyncPresentation(
        "전송 실패",
        "서버가 기록을 거절했어요. 운전자와 목적을 확인해 수정해 주세요. 계속 실패하거나 수정할 수 없으면 관리자에게 확인을 요청해 주세요.",
        Color(0xFFFFB4AB),
    )
    is TripSyncStatus.Held -> SyncPresentation(
        "전송 보류",
        when (reason) {
            HoldReason.NOT_LINKED -> "차량을 연결하면 전송할 수 있어요."
            HoldReason.INTERRUPTED -> "강제 종료된 기록이에요. 실제 종료값을 확인하기 전에는 확정할 수 없어요."
            HoldReason.NO_DRIVER -> "운전자를 지정하면 확인을 전송할 수 있어요."
            HoldReason.DRIVER_UNREGISTERED -> "운전자가 서버에 등록되기를 기다리고 있어요."
            HoldReason.DRIVER_PENDING -> "관리자의 운전자 승인을 기다리고 있어요."
            HoldReason.DRIVER_INACTIVE -> "비활성 운전자예요. 관리자에게 운전자 상태 확인을 요청해 주세요."
        },
        Color(0xFFF0CF8A),
    )
}

@Composable
internal fun TripSyncStatusNotice(status: TripSyncStatus, purpose: TripPurpose, modifier: Modifier = Modifier) {
    val (title, description, accent) = status.presentation(purpose)
    Surface(modifier, color = accent.copy(alpha = 0.08f), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = accent, fontSize = VehicleUi.ButtonText, fontWeight = FontWeight.SemiBold)
            Text(description, color = Color(0xFFD7DAE1), fontSize = VehicleUi.Caption, lineHeight = 28.sp)
        }
    }
}

/** 전송 상태를 아이콘 하나로 표시한다. 완료=체크, 전송 중=회전, 미전송(보류·실패)=업로드. */
@Composable
internal fun SyncStatusIcon(status: TripSyncStatus, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val description = when (status) {
        TripSyncStatus.Synced -> "전송 완료"
        TripSyncStatus.Waiting -> "전송 중"
        is TripSyncStatus.Held, TripSyncStatus.Failed -> "미전송"
    }
    Box(modifier.size(size).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        when (status) {
            TripSyncStatus.Synced -> Canvas(Modifier.fillMaxSize()) {
                val w = this.size.width
                val path = Path().apply {
                    moveTo(w * 0.2f, w * 0.52f); lineTo(w * 0.42f, w * 0.74f); lineTo(w * 0.8f, w * 0.3f)
                }
                drawPath(path, SentColor, style = Stroke(w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            TripSyncStatus.Waiting -> CircularProgressIndicator(Modifier.fillMaxSize(0.72f), color = SendingColor, strokeWidth = 3.dp)
            is TripSyncStatus.Held, TripSyncStatus.Failed -> Canvas(Modifier.fillMaxSize()) {
                val w = this.size.width
                val color = if (status == TripSyncStatus.Failed) FailedColor else VehicleUi.Caution
                val stroke = Stroke(w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(Path().apply { moveTo(w * 0.5f, w * 0.64f); lineTo(w * 0.5f, w * 0.18f) }, color, style = stroke)
                drawPath(Path().apply { moveTo(w * 0.3f, w * 0.36f); lineTo(w * 0.5f, w * 0.16f); lineTo(w * 0.7f, w * 0.36f) }, color, style = stroke)
                drawPath(Path().apply { moveTo(w * 0.2f, w * 0.82f); lineTo(w * 0.8f, w * 0.82f) }, color, style = stroke)
            }
        }
    }
}

private val SentColor = Color(0xFF95D7BA)
private val SendingColor = Color(0xFFBDD2F9)
private val FailedColor = Color(0xFFFFB4AB)
