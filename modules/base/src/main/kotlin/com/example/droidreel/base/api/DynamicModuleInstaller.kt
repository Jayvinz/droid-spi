package com.example.droidreel.base.api

import com.example.droidreel.base.model.InstallProgress

/**
 * Dynamic-delivery bridge. Implemented in the app module so Play Core stays out of every other
 * module. The caller decides when to install and what to do when the module becomes available.
 */
interface DynamicModuleInstaller {
    fun isInstalled(moduleName: String): Boolean

    /**
     * Starts installation and reports progress on the main thread. Terminal statuses are
     * [com.example.droidreel.base.model.InstallStatus.INSTALLED],
     * [com.example.droidreel.base.model.InstallStatus.FAILED], and
     * [com.example.droidreel.base.model.InstallStatus.CANCELED].
     */
    fun install(moduleName: String, onProgress: (InstallProgress) -> Unit)
}
