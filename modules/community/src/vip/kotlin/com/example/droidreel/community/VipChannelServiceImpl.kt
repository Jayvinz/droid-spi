package com.example.droidreel.community

import com.example.droidreel.base.api.ChannelService
import top.jayvin.android.spi.ServiceProvider

/** `vip`-flavor implementation, declared only under `src/vip`. */
@ServiceProvider(service = ChannelService::class)
class VipChannelServiceImpl : ChannelService {
    override fun channelName(): String = "vip"

    override fun describe(): String = "vip provider from modules/community/src/vip"
}
