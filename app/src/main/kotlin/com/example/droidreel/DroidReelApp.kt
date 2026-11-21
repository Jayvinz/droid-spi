package com.example.droidreel

import com.example.droidreel.base.AppContextHolder
import com.example.droidreel.base.api.AccountService
import com.google.android.play.core.splitcompat.SplitCompatApplication
import top.jayvin.android.spi.ServiceLoader

/**
 * Uses [SplitCompatApplication] so Play Core can load on-demand splits (including bundletool
 * local-testing builds) into the app's class loader before the SPI table needs them.
 */
class DroidReelApp : SplitCompatApplication() {

    override fun onCreate() {
        super.onCreate()
        AppContextHolder.init(this)
        ServiceLoader.initialize()
        // Restore the persisted demo session before any screen asks about login state.
        ServiceLoader.load(AccountService::class.java)?.restoreSession()
    }
}
