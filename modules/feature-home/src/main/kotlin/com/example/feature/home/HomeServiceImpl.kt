package com.example.feature.home

import com.example.core.api.HomeService
import com.example.core.common.Trace
import top.jayvin.android.spi.ServiceProvider

@ServiceProvider(service = HomeService::class)
class HomeServiceImpl : HomeService {

    override fun title(): String = "Home · default implementation"

    override fun recommendations(): List<String> = listOf(
        Trace.line("home", "Recommendation A"),
        Trace.line("home", "Recommendation B"),
    )
}
