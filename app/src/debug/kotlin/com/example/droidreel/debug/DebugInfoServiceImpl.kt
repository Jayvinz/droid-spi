package com.example.droidreel.debug

import com.example.droidreel.BuildConfig
import com.example.droidreel.base.api.DemoInfoService
import top.jayvin.android.spi.ServiceProvider

/** Debug-only provider: its absence from release tables proves build-type isolation. */
@ServiceProvider(service = DemoInfoService::class, key = "debug")
class DebugInfoServiceImpl : DemoInfoService {

    override fun describe(): String =
        "debug build · ${BuildConfig.BUILD_TYPE} · v${BuildConfig.VERSION_NAME}"
}
