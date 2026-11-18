package com.example.demo

import android.app.Application
import top.jayvin.android.spi.ServiceLoader

class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLoader.initialize()
    }
}
