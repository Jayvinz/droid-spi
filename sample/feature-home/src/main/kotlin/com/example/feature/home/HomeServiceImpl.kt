package com.example.feature.home

import com.example.core.api.HomeService
import com.example.core.common.Trace
import top.jayvin.android.spi.ServiceProvider

@ServiceProvider(service = HomeService::class)
class HomeServiceImpl : HomeService {

    override fun title(): String = "首页 · 默认实现"

    override fun recommendations(): List<String> = listOf(
        Trace.line("home", "推荐 A"),
        Trace.line("home", "推荐 B"),
    )
}
