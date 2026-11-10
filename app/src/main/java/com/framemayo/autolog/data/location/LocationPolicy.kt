package com.framemayo.autolog.data.location

import com.framemayo.autolog.data.local.TripPurpose

object LocationPolicy {
    fun canStore(consent: Boolean?, purpose: TripPurpose): Boolean =
        consent == true && (purpose == TripPurpose.BUSINESS_COMMUTE || purpose == TripPurpose.BUSINESS_GENERAL)

    fun canCapture(consent: Boolean?): Boolean = consent == true

    fun shouldPurge(consent: Boolean?, purpose: TripPurpose): Boolean =
        consent != true || purpose == TripPurpose.PERSONAL
}
