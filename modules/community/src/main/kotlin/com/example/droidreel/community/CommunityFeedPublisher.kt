package com.example.droidreel.community

import com.example.droidreel.base.api.FeedPublisher
import com.example.droidreel.base.model.Video
import com.example.droidreel.community.data.CommunityRepository
import top.jayvin.android.spi.ServiceProvider

/** Resident publisher called by the on-demand production module. */
@ServiceProvider(service = FeedPublisher::class)
class CommunityFeedPublisher : FeedPublisher {

    override fun publish(video: Video): Boolean = CommunityRepository.publish(video)
}
