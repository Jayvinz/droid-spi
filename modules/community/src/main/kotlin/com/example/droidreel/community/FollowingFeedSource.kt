package com.example.droidreel.community

import com.example.droidreel.base.api.FeedSource
import com.example.droidreel.base.model.FeedPage
import com.example.droidreel.community.data.CommunityRepository
import top.jayvin.android.spi.ServiceProvider

/** Keyed feed: works from the demo creators the signed-in user follows. */
@ServiceProvider(service = FeedSource::class, key = "following")
class FollowingFeedSource : FeedSource {

    override fun load(page: Int, pageSize: Int): FeedPage {
        CommunityRepository.ensureSeeded()
        return pagedIds(CommunityRepository.following(), page, pageSize)
    }
}
