package com.example.droidreel.install

import android.os.Handler
import android.os.Looper
import com.example.droidreel.base.AppContextHolder
import com.example.droidreel.base.api.DynamicModuleInstaller
import com.example.droidreel.base.model.InstallProgress
import com.example.droidreel.base.model.InstallStatus
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.SplitInstallException
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import top.jayvin.android.spi.ServiceProvider

/**
 * The app owns Play Feature Delivery. Non-Play environments (a sideloaded APK, some emulators)
 * surface as FAILED instead of crashing.
 */
@ServiceProvider(service = DynamicModuleInstaller::class)
class PlayModuleInstaller : DynamicModuleInstaller {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val manager by lazy {
        runCatching { SplitInstallManagerFactory.create(AppContextHolder.appContext) }.getOrNull()
    }

    override fun isInstalled(moduleName: String): Boolean = runCatching {
        manager?.installedModules?.contains(moduleName) == true
    }.getOrDefault(false)

    override fun install(moduleName: String, onProgress: (InstallProgress) -> Unit) {
        val installManager = manager
        if (installManager == null) {
            report(onProgress, InstallProgress(moduleName, InstallStatus.FAILED, errorCode = -1))
            return
        }

        lateinit var listener: SplitInstallStateUpdatedListener
        listener = SplitInstallStateUpdatedListener { state ->
            if (state.moduleNames().contains(moduleName)) {
                val progress = InstallProgress(
                    moduleName = moduleName,
                    status = state.status().toInstallStatus(),
                    bytesDownloaded = state.bytesDownloaded(),
                    totalBytes = state.totalBytesToDownload(),
                    errorCode = state.errorCode(),
                )
                report(onProgress, progress)
                if (progress.status == InstallStatus.INSTALLED) {
                    // Make the freshly installed split visible to the current process's class
                    // loader; the SPI provider is then resolved on the next load().
                    runCatching { SplitCompat.install(AppContextHolder.appContext) }
                }
                if (progress.status.isTerminal()) {
                    installManager.unregisterListener(listener)
                }
            }
        }

        installManager.registerListener(listener)
        try {
            installManager.startInstall(
                SplitInstallRequest.newBuilder()
                    .addModule(moduleName)
                    .build(),
            ).addOnFailureListener { error ->
                val code = (error as? SplitInstallException)?.errorCode ?: -1
                report(onProgress, InstallProgress(moduleName, InstallStatus.FAILED, errorCode = code))
                installManager.unregisterListener(listener)
            }
        } catch (t: Throwable) {
            report(onProgress, InstallProgress(moduleName, InstallStatus.FAILED, errorCode = -1))
            installManager.unregisterListener(listener)
        }
    }

    private fun report(onProgress: (InstallProgress) -> Unit, progress: InstallProgress) {
        mainHandler.post { onProgress(progress) }
    }

    private fun Int.toInstallStatus(): InstallStatus = when (this) {
        SplitInstallSessionStatus.PENDING -> InstallStatus.PENDING
        SplitInstallSessionStatus.DOWNLOADING -> InstallStatus.DOWNLOADING
        SplitInstallSessionStatus.DOWNLOADED -> InstallStatus.DOWNLOADED
        SplitInstallSessionStatus.INSTALLING -> InstallStatus.INSTALLING
        SplitInstallSessionStatus.INSTALLED -> InstallStatus.INSTALLED
        SplitInstallSessionStatus.FAILED -> InstallStatus.FAILED
        SplitInstallSessionStatus.CANCELED -> InstallStatus.CANCELED
        SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION ->
            InstallStatus.REQUIRES_USER_CONFIRMATION
        else -> InstallStatus.PENDING
    }

    private fun InstallStatus.isTerminal(): Boolean =
        this == InstallStatus.INSTALLED ||
            this == InstallStatus.FAILED ||
            this == InstallStatus.CANCELED
}
