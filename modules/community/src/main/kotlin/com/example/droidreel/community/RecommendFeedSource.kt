package com.example.droidreel.community

import com.example.droidreel.base.api.FeedSource
import com.example.droidreel.base.model.FeedPage
import com.example.droidreel.community.data.CommunityRepository
import top.jayvin.android.spi.ServiceProvider

/** Default-key feed: every video, newest first. */
@ServiceProvider(service = FeedSource::class)
class RecommendFeedSource : FeedSource {

    override fun load(page: Int, pageSize: Int): FeedPage {
        CommunityRepository.ensureSeeded()
        return pagedIds(CommunityRepository.recommend(), page, pageSize)
    }
}
