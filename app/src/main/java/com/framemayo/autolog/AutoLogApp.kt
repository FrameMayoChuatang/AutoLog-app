package com.framemayo.autolog

import android.app.Application
import com.framemayo.autolog.data.local.AutoLogDatabase
import com.framemayo.autolog.data.repository.DriverRepository
import com.framemayo.autolog.data.repository.TripRepository
import com.framemayo.autolog.vehicle.VehicleDataSource

class AutoLogApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    private val db = AutoLogDatabase.create(app)
    val vehicle = VehicleDataSource(app)
    val trips = TripRepository(db.tripDao())
    val drivers = DriverRepository(db.driverDao())
}

val android.content.Context.container: AppContainer
    get() = (applicationContext as AutoLogApp).container
