package com.example.droidreel.base.api

import android.content.Context

/**
 * Production entry point. Implemented only by the on-demand `production` dynamic feature, so
 * the class is absent from the base APK until the split is installed.
 */
interface ProductionEntry {
    /** Opens the publishing screen. */
    fun openCreate(context: Context)

    /** Short label shown by the screen to prove the split is active. */
    fun versionLabel(): String
}
