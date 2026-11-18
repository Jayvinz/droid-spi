package com.example.spi.internal

import android.content.Context
import android.util.Log
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus

/**
 * 唯一与 Play 安装状态耦合的地方：收到 `INSTALLED` 就把 provider 配置表重探一遍。
 *
 * 覆盖的场景只有"本次进程内装完"。两种情况不需要它：
 * - 冷启动：模块早就装好了，类一开始就可见，`initialize()` 里的首次 `refresh()` 已经是 READY；
 * - 进程被杀后重启：同上，重启后的首扫就是最终状态。
 */
internal class InstallWatcher private constructor(
    private val manager: SplitInstallManager,
) {

    private var onInstalled: () -> Unit = {}

    private val listener = SplitInstallStateUpdatedListener { state ->
        if (state.status() == SplitInstallSessionStatus.INSTALLED) {
            onInstalled()
        }
    }

    fun attach(callback: () -> Unit) {
        onInstalled = callback
        manager.registerListener(listener)
    }

    fun detach() {
        manager.unregisterListener(listener)
    }

    companion object {
        /** Play 不可用（国内渠道、无 GMS）时返回 null，框架退化为"纯静态 provider 配置表"。 */
        fun create(context: Context): InstallWatcher? = try {
            InstallWatcher(SplitInstallManagerFactory.create(context))
        } catch (t: Throwable) {
            Log.w(TAG, "Play Feature Delivery 不可用，动态模块将不会被监听", t)
            null
        }

        /** 让动态模块的类/资源对当前进程可见，未继承 SplitCompatApplication 时也能工作。 */
        fun ensureSplitCompat(context: Context) {
            try {
                SplitCompat.install(context)
            } catch (t: Throwable) {
                Log.w(TAG, "SplitCompat.install 失败", t)
            }
        }

        private const val TAG = "AndroidSpi"
    }
}
