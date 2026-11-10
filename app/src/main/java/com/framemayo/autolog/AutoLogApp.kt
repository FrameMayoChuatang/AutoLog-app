package com.framemayo.autolog

import android.app.Application
import com.framemayo.autolog.data.link.HttpVehicleLinkApi
import com.framemayo.autolog.data.link.VehicleLinkRepository
import com.framemayo.autolog.data.local.AutoLogDatabase
import com.framemayo.autolog.data.repository.DriverRepository
import com.framemayo.autolog.data.repository.DriverRepositoryImpl
import com.framemayo.autolog.data.repository.PurposeRepository
import com.framemayo.autolog.data.repository.PurposeRepositoryImpl
import com.framemayo.autolog.data.repository.TripRepository
import com.framemayo.autolog.data.repository.TripRepositoryImpl
import com.framemayo.autolog.data.location.AndroidLocationSource
import com.framemayo.autolog.data.location.FusedLocationSource
import com.framemayo.autolog.data.location.ArrivalLocationRecorder
import com.framemayo.autolog.data.remote.ServerApi
import com.framemayo.autolog.data.retention.RetentionScheduler
import com.framemayo.autolog.data.sync.FakeSyncApi
import com.framemayo.autolog.data.sync.SyncManager
import com.framemayo.autolog.data.sync.SyncScheduler
import com.framemayo.autolog.vehicle.VehicleDataSource

class AutoLogApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncScheduler.enqueue(this)
        RetentionScheduler.schedule(this)
    }
}

class AppContainer(app: Application) {
    private val db = AutoLogDatabase.create(app)
    val vehicle = VehicleDataSource(app)
    val trips: TripRepository = TripRepositoryImpl(db.tripDao(), onPending = { SyncScheduler.enqueue(app) })
    val drivers: DriverRepository = DriverRepositoryImpl(db.driverDao())
    val purposes: PurposeRepository = PurposeRepositoryImpl(db.purposeDao())

    val arrival = ArrivalLocationRecorder(db.tripLocationDao(), FusedLocationSource(app, AndroidLocationSource(app)))

    val server: ServerApi = ServerApi(app.getString(R.string.server_url), token = { vehicleLink.link.value?.accessToken }, onUnauthorized = { vehicleLink.disconnect() })
    val vehicleLink: VehicleLinkRepository = VehicleLinkRepository(app.getSharedPreferences("vehicle_link", android.content.Context.MODE_PRIVATE), HttpVehicleLinkApi(server))

    val sync = SyncManager(trips, drivers, FakeSyncApi())
}

val android.content.Context.container: AppContainer
    get() = (applicationContext as AutoLogApp).container
