package com.framemayo.autolog.ui

import com.framemayo.autolog.data.local.EnergyEventEntity
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.local.TripStatus
import com.framemayo.autolog.data.local.TripStopEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 운행 상세의 세로 타임라인 한 줄. [at]은 그 줄이 시작되는 시각. */
internal sealed interface TimelineEntry {
    val at: Long

    data class Start(override val at: Long, val odometerKm: Float, val batteryPercent: Float?, val fuelPercent: Float?) : TimelineEntry
    data class Drive(override val at: Long, val until: Long, val distanceKm: Float) : TimelineEntry
    data class Stop(override val at: Long, val until: Long, val odometerKm: Float) : TimelineEntry
    data class Energy(override val at: Long, val event: EnergyEventEntity) : TimelineEntry

    /** [parkedAt]은 종료 버튼을 누르기 전 마지막으로 P에 둔 시각. 기록이 없으면 null. */
    data class End(
        override val at: Long,
        val parkedAt: Long?,
        val odometerKm: Float,
        val batteryPercent: Float?,
        val fuelPercent: Float?,
    ) : TimelineEntry

    data class Recording(override val at: Long, val odometerKm: Float) : TimelineEntry
    data class Interrupted(override val at: Long, val odometerKm: Float) : TimelineEntry
}

/**
 * 출발 → (주행 → 정차)* → 주행 → 도착 순서로 펼친다. 종료 직전의 P 구간은 정차가 아니라 도착에 합친다.
 * 주유·충전은 감지 시각에 맞춰 끼워 넣는다. 저장된 값만 쓰고 경로·주소는 추정하지 않는다.
 */
internal fun buildTripTimeline(
    trip: TripEntity,
    stops: List<TripStopEntity>,
    energy: List<EnergyEventEntity> = emptyList(),
): List<TimelineEntry> {
    val finishedAt = trip.endedAt ?: trip.lastSavedAt
    val finalKm = trip.endOdometerKm ?: trip.lastOdometerKm
    val ordered = stops.filter { it.startedAt in trip.startedAt..finishedAt }.sortedBy { it.startedAt }
    val arrival = ordered.lastOrNull()?.takeIf { stop ->
        trip.status == TripStatus.COMPLETED && (stop.endedAt == null || stop.endedAt >= finishedAt)
    }
    val middle = if (arrival != null) ordered.dropLast(1) else ordered

    val entries = mutableListOf<TimelineEntry>(
        TimelineEntry.Start(trip.startedAt, trip.startOdometerKm, trip.startBatteryPercent, trip.startFuelPercent),
    )
    var at = trip.startedAt
    var km = trip.startOdometerKm
    for (stop in middle) {
        entries += TimelineEntry.Drive(at, stop.startedAt, (stop.odometerKm - km).coerceAtLeast(0f))
        val until = (stop.endedAt ?: finishedAt).coerceAtLeast(stop.startedAt)
        entries += TimelineEntry.Stop(stop.startedAt, until, stop.odometerKm)
        at = until
        km = stop.odometerKm
    }
    val driveUntil = arrival?.startedAt ?: finishedAt
    val driveKm = arrival?.odometerKm ?: finalKm
    // 운행 중 열린 정차로 끝나면 이어지는 주행이 없다.
    if (driveUntil > at || driveKm > km) entries += TimelineEntry.Drive(at, driveUntil, (driveKm - km).coerceAtLeast(0f))
    entries += when (trip.status) {
        TripStatus.COMPLETED -> TimelineEntry.End(finishedAt, arrival?.startedAt, finalKm, trip.endBatteryPercent, trip.endFuelPercent)
        TripStatus.ACTIVE -> TimelineEntry.Recording(finishedAt, finalKm)
        TripStatus.INTERRUPTED -> TimelineEntry.Interrupted(finishedAt, finalKm)
    }
    for (event in energy.sortedBy { it.detectedAt }) {
        val after = entries.subList(0, entries.lastIndex).indexOfLast { it.at <= event.detectedAt }
        entries.add((after + 1).coerceIn(1, entries.lastIndex), TimelineEntry.Energy(event.detectedAt, event))
    }
    return entries
}

private val Seoul: ZoneId = ZoneId.of("Asia/Seoul")
private val DateTime = DateTimeFormatter.ofPattern("M월 d일 (E) HH:mm", Locale.KOREAN).withZone(Seoul)
private val DateOnly = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN).withZone(Seoul)
private val Clock = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREAN).withZone(Seoul)

internal fun formatTripDateTime(at: Long): String = DateTime.format(Instant.ofEpochMilli(at))

/** 같은 날이면 시각만, 날짜가 바뀌었으면 날짜까지 표시한다. */
internal fun formatClock(at: Long, reference: Long): String {
    val time = Instant.ofEpochMilli(at)
    val sameDay = time.atZone(Seoul).toLocalDate() == Instant.ofEpochMilli(reference).atZone(Seoul).toLocalDate()
    return if (sameDay) Clock.format(time) else "${DateOnly.format(time)} ${Clock.format(time)}"
}

internal fun formatTripDuration(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "1분 미만"
        minutes < 60 -> "${minutes}분"
        minutes % 60 == 0L -> "${minutes / 60}시간"
        else -> "${minutes / 60}시간 ${minutes % 60}분"
    }
}

internal fun formatKm(km: Float): String = "%,.1f km".format(km)

internal fun TripEntity.purposeLine(): String =
    if (purpose == TripPurpose.BUSINESS_GENERAL) "${purpose.label()} · ${purposeText ?: "일반 업무"}" else purpose.label()

/** 종료된 운행에서 운전자가 아직 해야 하는 확인. */
internal fun TripEntity.pendingActions(): List<String> = if (endedAt == null) emptyList() else buildList {
    if (purpose == TripPurpose.UNCONFIRMED) add("목적 선택")
    if (driverId == null) add("운전자 지정")
}
