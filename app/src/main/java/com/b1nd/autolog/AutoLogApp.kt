package com.b1nd.autolog

import android.app.Application
import com.b1nd.autolog.data.local.AutoLogDatabase
import com.b1nd.autolog.data.repository.DriverRepository
import com.b1nd.autolog.data.repository.TripRepository
import com.b1nd.autolog.vehicle.VehicleDataSource

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
