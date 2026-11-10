package com.b1nd.autolog.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [DriverEntity::class, TripEntity::class], version = 1, exportSchema = false)
abstract class AutoLogDatabase : RoomDatabase() {
    abstract fun driverDao(): DriverDao
    abstract fun tripDao(): TripDao

    companion object {
        fun create(context: Context): AutoLogDatabase =
            Room.databaseBuilder(context, AutoLogDatabase::class.java, "autolog.db").build()
    }
}
