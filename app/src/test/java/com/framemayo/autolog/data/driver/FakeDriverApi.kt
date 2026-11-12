package com.framemayo.autolog.data.driver

import com.framemayo.autolog.data.local.DriverStatus
import com.framemayo.autolog.data.repository.RemoteDriver

class FakeDriverApi : DriverApi {
    @Volatile var online = true
    @Volatile var unauthorized = false

    @Volatile var loseNextRegisterResponse = false
    @Volatile var failConsent = false

    val server = mutableListOf<RemoteDriver>()
    val registered = mutableListOf<Pair<String, String>>()
    val consents = mutableListOf<Triple<String, Boolean, String>>()
    val consentRejectedFor = mutableSetOf<String>()
    private var next = 0

    private fun <T> gate(): ApiOutcome<T>? = when {
        !online -> ApiOutcome.Retry
        unauthorized -> ApiOutcome.Unauthorized
        else -> null
    }

    override suspend fun list(): ApiOutcome<List<RemoteDriver>> = gate() ?: ApiOutcome.Ok(server.toList())

    override suspend fun register(name: String, department: String): ApiOutcome<RemoteDriver> {
        gate<RemoteDriver>()?.let { return it }
        registered += name to department
        val d = RemoteDriver("00000000-0000-4000-8000-%012d".format(++next), name, department, DriverStatus.PENDING)
        server += d
        if (loseNextRegisterResponse) {
            loseNextRegisterResponse = false
            return ApiOutcome.Retry
        }
        return ApiOutcome.Ok(d)
    }

    override suspend fun sendConsent(remoteId: String, granted: Boolean, noticeVersion: String): ApiOutcome<Unit> {
        gate<Unit>()?.let { return it }
        if (failConsent) return ApiOutcome.Retry
        if (remoteId in consentRejectedFor) return ApiOutcome.Rejected
        consents += Triple(remoteId, granted, noticeVersion)
        return ApiOutcome.Ok(Unit)
    }

    fun serverDriver(name: String, department: String, status: DriverStatus = DriverStatus.ACTIVE): RemoteDriver =
        RemoteDriver("11111111-1111-4111-8111-%012d".format(++next), name, department, status).also { server += it }
}
