package com.framemayo.autolog.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.framemayo.autolog.data.local.EnergyEventEntity
import com.framemayo.autolog.data.local.EnergyEventKind
import com.framemayo.autolog.data.local.PurposeEntity
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.local.TripStopEntity
import com.framemayo.autolog.data.sync.TripSyncStatus

private val DriveLine = Color(0xFF4C8DFF)

/**
 * 운행 상세: 요약(누가·언제·얼마나) → 출발부터 도착까지의 타임라인 → 확인 버튼.
 * 목적 확인과 운전자 지정은 기존 [TripReviewDialogs]를 그대로 쓴다.
 */
@Composable
internal fun TripDetailScreen(
    trip: TripEntity,
    state: MainUiState,
    parked: Boolean,
    stops: List<TripStopEntity>,
    energy: List<EnergyEventEntity>,
    onBack: () -> Unit,
    onAssign: (Long) -> Unit,
    onPurpose: suspend (TripPurpose, String?) -> Unit,
    loadPurposes: suspend () -> List<PurposeEntity>? = { emptyList() },
) {
    var reviewing by rememberSaveable(trip.id) { mutableStateOf(false) }
    LaunchedEffect(parked) { if (!parked) reviewing = false }
    val timeline = buildTripTimeline(trip, stops, energy)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 1100.dp).fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp),
            contentPadding = PaddingValues(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item { VehicleSecondaryButton("← 최근 운행으로 돌아가기", onBack) }
            item { TripOverview(trip, state) }
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = VehicleUi.Surface) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                        timeline.forEachIndexed { i, entry -> TimelineRow(entry, trip.startedAt, i == 0, i == timeline.lastIndex) }
                    }
                }
            }
            item { TripReviewBar(trip, state, parked) { reviewing = true } }
        }
    }
    TripReviewDialogs(trip, state, parked, reviewing, { reviewing = false }, onAssign, onPurpose, loadPurposes)
}

@Composable
private fun TripOverview(trip: TripEntity, state: MainUiState) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = VehicleUi.Surface) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TripDriverAvatar(trip, state, 64.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        state.driverName(trip.driverId), fontSize = VehicleUi.Title, fontWeight = FontWeight.Bold,
                        color = VehicleUi.Primary,
                    )
                    Text(formatTripDateTime(trip.startedAt), fontSize = VehicleUi.Caption, color = VehicleUi.Secondary)
                }
                SyncStatusIcon(state.syncStatusOf(trip), size = 40.dp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${formatKm(trip.distanceKmSafe())} · ${formatTripDuration(trip.durationMsSafe())}",
                    fontSize = VehicleUi.Value, fontWeight = FontWeight.Bold, color = VehicleUi.Primary,
                )
                Text(trip.purposeLine(), fontSize = VehicleUi.Body, color = VehicleUi.Secondary)
            }
        }
    }
}

/** 해야 할 확인과 버튼 한 줄. 전송이 끝나지 않았을 때만 이유를 한 줄 덧붙인다. */
@Composable
private fun TripReviewBar(trip: TripEntity, state: MainUiState, parked: Boolean, onReview: () -> Unit) {
    val status = state.syncStatusOf(trip)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = VehicleUi.Surface) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val pending = trip.pendingActions()
                when {
                    trip.endedAt == null -> Text("운행을 종료하면 목적을 확인할 수 있어요.", fontSize = VehicleUi.Body, color = VehicleUi.Secondary)
                    pending.isNotEmpty() -> Text("${pending.joinToString(" · ")}이 필요해요", fontSize = VehicleUi.Body, color = VehicleUi.Primary)
                    else -> Text("확인한 운행이에요", fontSize = VehicleUi.Body, color = VehicleUi.Secondary)
                }
                if (status != TripSyncStatus.Synced) {
                    Text(status.presentation(trip.purpose).description, fontSize = VehicleUi.Caption, color = VehicleUi.Secondary)
                }
            }
            if (trip.endedAt != null) VehiclePrimaryButton(
                if (trip.purpose == TripPurpose.UNCONFIRMED) "운행 확인" else "확인 및 수정", onReview, enabled = parked,
            )
        }
    }
}

private data class TimelineText(val title: String, val detail: String?, val time: String)

private fun TimelineEntry.text(tripStart: Long): TimelineText {
    fun clock(at: Long) = formatClock(at, tripStart)
    return when (this) {
        is TimelineEntry.Start -> TimelineText("출발", "누적 ${formatKm(odometerKm)}", clock(at))
        is TimelineEntry.Drive -> TimelineText("주행", "${formatKm(distanceKm)} · ${formatTripDuration(until - at)}", "${clock(at)} ~ ${clock(until)}")
        is TimelineEntry.Stop -> TimelineText("정차", formatTripDuration(until - at), "${clock(at)} ~ ${clock(until)}")
        is TimelineEntry.Energy -> energyText(event, clock(at))
        is TimelineEntry.End -> TimelineText("도착", "누적 ${formatKm(odometerKm)}", clock(at))
        is TimelineEntry.Recording -> TimelineText("운행 중", "누적 ${formatKm(odometerKm)}", "마지막 기록 ${clock(at)}")
        is TimelineEntry.Interrupted -> TimelineText("기록 중단", "누적 ${formatKm(odometerKm)}까지 기록됐어요", "마지막 기록 ${clock(at)}")
    }
}

private fun energyText(event: EnergyEventEntity, time: String): TimelineText {
    val charge = event.kind == EnergyEventKind.CHARGE
    val change = "${if (charge) "배터리" else "연료"} %.0f%% → %.0f%%".format(event.beforePercent, event.afterPercent)
    val cost = event.estimatedCostWon?.let { "약 %,d원".format(it) }
    return TimelineText(if (charge) "충전" else "주유", listOfNotNull(change, cost).joinToString(" · "), time)
}

/** 구글 지도 타임라인처럼 왼쪽 세로선에 지점을 찍고, 주행 구간은 굵은 파란 선으로 잇는다. */
@Composable
private fun TimelineRow(entry: TimelineEntry, tripStart: Long, first: Boolean, last: Boolean) {
    val text = entry.text(tripStart)
    val drive = entry is TimelineEntry.Drive
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(64.dp).fillMaxHeight()) {
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width / 2
                val markerY = 34.dp.toPx()
                if (drive) {
                    drawLine(DriveLine, Offset(x, 0f), Offset(x, size.height), 8.dp.toPx(), StrokeCap.Butt)
                } else {
                    val stroke = 3.dp.toPx()
                    if (!first) drawLine(VehicleUi.Outline, Offset(x, 0f), Offset(x, markerY), stroke)
                    if (!last) drawLine(VehicleUi.Outline, Offset(x, markerY), Offset(x, size.height), stroke)
                }
            }
            if (!drive) TimelineMarker(entry, Modifier.align(Alignment.TopCenter).padding(top = 16.dp))
        }
        Column(
            Modifier.weight(1f).padding(start = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text.title, fontSize = if (drive) VehicleUi.ButtonText else VehicleUi.Body,
                fontWeight = FontWeight.SemiBold, color = if (drive) DriveLine else VehicleUi.Primary,
            )
            text.detail?.let { Text(it, fontSize = VehicleUi.Caption, color = Color(0xFFD7DAE1)) }
            Text(text.time, fontSize = VehicleUi.Caption, color = VehicleUi.Secondary)
        }
    }
}

@Composable
private fun TimelineMarker(entry: TimelineEntry, modifier: Modifier) {
    val (fill, border, label) = when (entry) {
        is TimelineEntry.Start -> Triple(VehicleUi.Surface, Color.White, null)
        is TimelineEntry.End -> Triple(Color.White, Color.White, null)
        is TimelineEntry.Stop -> Triple(VehicleUi.Tile, VehicleUi.Secondary, "P")
        is TimelineEntry.Energy -> Triple(VehicleUi.Positive, VehicleUi.Positive, "+")
        is TimelineEntry.Recording -> Triple(DriveLine, DriveLine, null)
        is TimelineEntry.Interrupted -> Triple(VehicleUi.Caution, VehicleUi.Caution, "!")
        is TimelineEntry.Drive -> return
    }
    Surface(modifier.size(36.dp), shape = CircleShape, color = fill, border = BorderStroke(4.dp, border)) {
        Box(contentAlignment = Alignment.Center) {
            when {
                label != null -> Text(label, fontSize = VehicleUi.Caption, fontWeight = FontWeight.Bold, color = if (fill == VehicleUi.Tile) Color.White else VehicleUi.Surface)
                entry is TimelineEntry.End -> Surface(Modifier.size(10.dp), shape = CircleShape, color = VehicleUi.Surface) {}
            }
        }
    }
}
