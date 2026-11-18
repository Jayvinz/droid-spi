package com.example.demo

import android.app.Application
import top.jayvin.android.spi.ServiceLoader

/** Application that initializes the SPI registry before any Activity starts. */
class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLoader.initialize()
    }
}
