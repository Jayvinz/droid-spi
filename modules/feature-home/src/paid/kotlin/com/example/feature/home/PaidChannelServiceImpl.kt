package com.example.feature.home

import com.example.core.api.ChannelService
import top.jayvin.android.spi.ServiceProvider

/** `paid`-flavor implementation, declared only under `src/paid`. */
@ServiceProvider(service = ChannelService::class)
class PaidChannelServiceImpl : ChannelService {
    override fun channelName(): String = "paid"

    override fun describe(): String = "paid provider from feature-home/src/paid"
}
