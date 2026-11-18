package com.example.feature.home

import com.example.core.api.HomeService
import com.example.spi.ServiceProvider

/**
 * 同一个接口的 key 变体，调用方用 `ServiceLoader.load(HomeService::class.java, "vip")` 取到。
 */
@ServiceProvider(service = HomeService::class, key = "vip")
class VipHomeServiceImpl : HomeService {

    override fun title(): String = "首页 · VIP 变体"

    override fun recommendations(): List<String> = listOf(
        "专属客服",
        "免运费券",
        "生日礼包",
    )
}
