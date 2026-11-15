package com.framemayo.autolog.ui

import com.framemayo.autolog.data.local.EnergyEventEntity
import com.framemayo.autolog.data.local.EnergyEventKind
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.local.TripStatus
import com.framemayo.autolog.data.local.TripStopEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TripTimelineTest {
    private val trip = TripEntity(
        id = 1, driverId = 1, startedAt = 0, endedAt = 3_600_000, startOdometerKm = 100f, endOdometerKm = 130f,
        lastOdometerKm = 130f, lastSavedAt = 3_600_000, status = TripStatus.COMPLETED,
    )

    @Test fun middleStopsSplitDrivesAndFinalParkingMergesIntoArrival() {
        val stops = listOf(
            TripStopEntity(tripId = 1, startedAt = 3_300_000, endedAt = 3_600_000, odometerKm = 130f),
            TripStopEntity(tripId = 1, startedAt = 1_200_000, endedAt = 1_500_000, odometerKm = 110f),
        )
        val entries = buildTripTimeline(trip, stops)
        assertEquals(
            listOf(
                TimelineEntry.Start(0, 100f, null, null),
                TimelineEntry.Drive(0, 1_200_000, 10f),
                TimelineEntry.Stop(1_200_000, 1_500_000, 110f),
                TimelineEntry.Drive(1_500_000, 3_300_000, 20f),
                TimelineEntry.End(3_600_000, 3_300_000, 130f, null, null),
            ),
            entries,
        )
    }

    @Test fun tripWithoutStopsIsOneDrive() {
        assertEquals(
            listOf(TimelineEntry.Start(0, 100f, null, null), TimelineEntry.Drive(0, 3_600_000, 30f), TimelineEntry.End(3_600_000, null, 130f, null, null)),
            buildTripTimeline(trip, emptyList()),
        )
    }

    @Test fun activeTripEndsWithOpenStopAndNoEmptyDrive() {
        val active = trip.copy(endedAt = null, endOdometerKm = null, lastOdometerKm = 110f, lastSavedAt = 2_000_000, status = TripStatus.ACTIVE)
        val entries = buildTripTimeline(active, listOf(TripStopEntity(tripId = 1, startedAt = 1_200_000, odometerKm = 110f)))
        assertEquals(
            listOf(
                TimelineEntry.Start(0, 100f, null, null),
                TimelineEntry.Drive(0, 1_200_000, 10f),
                TimelineEntry.Stop(1_200_000, 2_000_000, 110f),
                TimelineEntry.Recording(2_000_000, 110f),
            ),
            entries,
        )
    }

    @Test fun interruptedTripKeepsLastSavedPoint() {
        val interrupted = trip.copy(endedAt = null, endOdometerKm = null, lastOdometerKm = 120f, lastSavedAt = 1_800_000, status = TripStatus.INTERRUPTED)
        assertEquals(TimelineEntry.Interrupted(1_800_000, 120f), buildTripTimeline(interrupted, emptyList()).last())
    }

    @Test fun energyEventsAreInsertedByTimeBeforeArrival() {
        val stop = TripStopEntity(tripId = 1, startedAt = 1_200_000, endedAt = 2_400_000, odometerKm = 110f)
        val charge = EnergyEventEntity(kind = EnergyEventKind.CHARGE, tripId = 1, detectedAt = 2_000_000, odometerKm = 110f,
            beforePercent = 40f, afterPercent = 60f, estimatedAmount = null, estimatedCostWon = null)
        val late = charge.copy(detectedAt = 9_000_000)
        val kinds = buildTripTimeline(trip, listOf(stop), listOf(late, charge)).map { it::class.simpleName }
        assertEquals(listOf("Start", "Drive", "Stop", "Energy", "Drive", "Energy", "End"), kinds)
    }

    @Test fun durationsAndPendingActionsReadNaturally() {
        assertEquals("1분 미만", formatTripDuration(30_000))
        assertEquals("45분", formatTripDuration(45 * 60_000L))
        assertEquals("2시간", formatTripDuration(120 * 60_000L))
        assertEquals("31시간 54분", formatTripDuration(1914 * 60_000L))
        assertEquals(listOf("목적 선택", "운전자 지정"), trip.copy(driverId = null).pendingActions())
        assertEquals(emptyList<String>(), trip.copy(purpose = TripPurpose.PERSONAL).pendingActions())
        assertEquals(emptyList<String>(), trip.copy(endedAt = null, driverId = null).pendingActions())
    }
}
