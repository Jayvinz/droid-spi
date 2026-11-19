package com.example.feature.home

import com.example.core.api.ChannelService
import top.jayvin.android.spi.ServiceProvider

/** `free`-flavor implementation, declared only under `src/free`. */
@ServiceProvider(service = ChannelService::class)
class FreeChannelServiceImpl : ChannelService {
    override fun channelName(): String = "free"

    override fun describe(): String = "free provider from feature-home/src/free"
}
