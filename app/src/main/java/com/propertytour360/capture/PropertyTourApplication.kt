package com.propertytour360.capture

import android.app.Application
import com.propertytour360.capture.data.AppContainer

class PropertyTourApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
