package com.example.feature.home

import com.example.core.api.HomeService
import top.jayvin.android.spi.ServiceProvider

/**
 * A key variant of the same interface; callers fetch it with
 * `ServiceLoader.load(HomeService::class.java, "vip")`.
 */
@ServiceProvider(service = HomeService::class, key = "vip")
class VipHomeServiceImpl : HomeService {

    override fun title(): String = "Home · VIP variant"

    override fun recommendations(): List<String> = listOf(
        "Dedicated support",
        "Free shipping voucher",
        "Birthday gift",
    )
}
