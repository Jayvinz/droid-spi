package com.example.droidreel.community

import com.example.droidreel.base.api.ChannelService
import top.jayvin.android.spi.ServiceProvider

/** `free`-flavor implementation, declared only under `src/free`. */
@ServiceProvider(service = ChannelService::class)
class FreeChannelServiceImpl : ChannelService {
    override fun channelName(): String = "free"

    override fun describe(): String = "free provider from modules/community/src/free"
}
