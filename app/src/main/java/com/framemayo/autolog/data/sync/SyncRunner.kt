package com.framemayo.autolog.data.sync

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SyncRunner(
    private val driverSync: suspend () -> Boolean,
    private val tripSync: suspend () -> Boolean,
) {
    private val lock = Mutex()

    suspend fun run(): Boolean = lock.withLock {
        val driversDone = attempt("driver sync", driverSync)
        val tripsDone = attempt("trip sync", tripSync)
        driversDone && tripsDone
    }

    private suspend fun attempt(name: String, block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "$name failed", e)
        false
    }

    private companion object {
        const val TAG = "SyncRunner"
    }
}
