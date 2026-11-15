package com.framemayo.autolog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.distanceKm
import com.framemayo.autolog.data.local.durationMs

internal const val RecentSummaryCount = 3
internal const val TripHistoryPageSize = 5

/**
 * 최근 운행: 최근 [RecentSummaryCount]건은 요약 카드, 나머지는 [TripHistoryPageSize]건씩 페이지로 나눈 목록.
 * 배지·강조색 없이 거리·시간·운전자·목적과 전송 아이콘만 보여 준다. 누르면 [onOpen]으로 상세를 연다.
 */
internal fun LazyListScope.recentTrips(
    trips: List<TripEntity>,
    state: MainUiState,
    page: Int,
    onPage: (Int) -> Unit,
    onOpen: (TripEntity) -> Unit,
) {
    item(key = "recent-header") {
        Text("최근 운행", Modifier.padding(top = 12.dp), fontSize = VehicleUi.Title, fontWeight = FontWeight.Bold, color = VehicleUi.Primary)
    }
    if (trips.isEmpty()) {
        item(key = "recent-empty") { Text("아직 운행 기록이 없어요.", fontSize = VehicleUi.Body, color = VehicleUi.Secondary) }
        return
    }
    item(key = "recent-summary") { TripSummaryCards(trips.take(RecentSummaryCount), state, onOpen) }
    val rest = trips.drop(RecentSummaryCount)
    if (rest.isNotEmpty()) {
        val pages = (rest.size + TripHistoryPageSize - 1) / TripHistoryPageSize
        val current = page.coerceIn(0, pages - 1)
        val from = current * TripHistoryPageSize
        item(key = "recent-history") {
            TripHistory(rest.subList(from, minOf(from + TripHistoryPageSize, rest.size)), state, current, pages, onPage, onOpen)
        }
    }
}

@Composable
private fun TripSummaryCards(trips: List<TripEntity>, state: MainUiState, onOpen: (TripEntity) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                trips.forEach { TripSummaryCard(it, state, { onOpen(it) }, Modifier.weight(1f).fillMaxHeight()) }
                repeat(RecentSummaryCount - trips.size) { Spacer(Modifier.weight(1f)) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                trips.forEach { TripSummaryCard(it, state, { onOpen(it) }, Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun TripSummaryCard(trip: TripEntity, state: MainUiState, onClick: () -> Unit, modifier: Modifier) {
    val name = state.driverName(trip.driverId)
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = "${formatTripDateTime(trip.startedAt)} $name 운행 상세 보기" },
        shape = RoundedCornerShape(24.dp), color = VehicleUi.Surface,
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTripDateTime(trip.startedAt), Modifier.weight(1f), fontSize = VehicleUi.Caption, color = VehicleUi.Secondary)
                SyncStatusIcon(state.syncStatusOf(trip))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(formatKm(trip.distanceKmSafe()), fontSize = VehicleUi.Value, fontWeight = FontWeight.Bold, color = VehicleUi.Primary)
                Text(formatTripDuration(trip.durationMsSafe()), fontSize = VehicleUi.Body, color = VehicleUi.Secondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TripDriverAvatar(trip, state, 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        name, fontSize = VehicleUi.Body, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = VehicleUi.Primary,
                    )
                    Text(
                        trip.purposeLine(), fontSize = VehicleUi.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = VehicleUi.Secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun TripHistory(
    trips: List<TripEntity>,
    state: MainUiState,
    page: Int,
    pages: Int,
    onPage: (Int) -> Unit,
    onOpen: (TripEntity) -> Unit,
) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = VehicleUi.Surface) {
        Column {
            trips.forEachIndexed { i, trip ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = VehicleUi.Outline)
                TripHistoryRow(trip, state) { onOpen(trip) }
            }
            HorizontalDivider(color = VehicleUi.Outline)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                PageArrow("‹", "이전 페이지", page > 0) { onPage(page - 1) }
                Text("${page + 1} / $pages", Modifier.padding(horizontal = 24.dp), fontSize = VehicleUi.Body, color = VehicleUi.Primary)
                PageArrow("›", "다음 페이지", page < pages - 1) { onPage(page + 1) }
            }
        }
    }
}

@Composable
private fun TripHistoryRow(trip: TripEntity, state: MainUiState, onClick: () -> Unit) {
    val name = state.driverName(trip.driverId)
    val subtitle = "${formatTripDateTime(trip.startedAt)} · $name · ${trip.purposeLine()}"
    Surface(
        onClick = onClick, color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().heightIn(min = VehicleUi.MinTouch)
            .semantics { contentDescription = "${formatTripDateTime(trip.startedAt)} $name 운행 상세 보기" },
    ) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${formatKm(trip.distanceKmSafe())} · ${formatTripDuration(trip.durationMsSafe())}",
                    fontSize = VehicleUi.Body, fontWeight = FontWeight.SemiBold, color = VehicleUi.Primary,
                )
                Text(subtitle, fontSize = VehicleUi.Caption, color = VehicleUi.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SyncStatusIcon(state.syncStatusOf(trip))
        }
    }
}

@Composable
private fun PageArrow(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, color = Color.Transparent, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.size(VehicleUi.MinTouch + 8.dp).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(symbol, fontSize = VehicleUi.Title, color = if (enabled) VehicleUi.Primary else VehicleUi.Disabled)
        }
    }
}

/** 프로필이 없는 운행은 캐릭터 대신 물음표를 보여 다른 운전자와 헷갈리지 않게 한다. */
@Composable
internal fun TripDriverAvatar(trip: TripEntity, state: MainUiState, size: Dp) {
    val profile = state.profiles.find { it.driver.id == trip.driverId }
    if (profile == null) {
        Surface(Modifier.size(size), shape = RoundedCornerShape(size / 4), color = VehicleUi.Tile) {
            Box(contentAlignment = Alignment.Center) {
                Text("?", fontSize = VehicleUi.Body, fontWeight = FontWeight.Bold, color = VehicleUi.Secondary)
            }
        }
    } else {
        Surface(Modifier.size(size), shape = RoundedCornerShape(size / 4), color = Color.White) {
            Box(contentAlignment = Alignment.Center) {
                DriverAvatar(profile.color, Modifier.fillMaxWidth().aspectRatio(4f / 3f), expression = profile.expression)
            }
        }
    }
}

internal fun TripEntity.distanceKmSafe(): Float = distanceKm().coerceAtLeast(0f)
internal fun TripEntity.durationMsSafe(): Long = durationMs().coerceAtLeast(0)
