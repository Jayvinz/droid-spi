package com.example.droidreel.community

import com.example.droidreel.base.model.FeedPage
import com.example.droidreel.community.data.VideoEntry

/** Shared paging helper for the demo's feed sources. */
internal fun pagedIds(all: List<VideoEntry>, page: Int, pageSize: Int): FeedPage {
    val size = pageSize.coerceAtLeast(1)
    val from = (page.coerceAtLeast(0) * size).coerceAtMost(all.size)
    val to = (from + size).coerceAtMost(all.size)
    return FeedPage(
        videoIds = all.subList(from, to).map { it.video.id },
        hasMore = to < all.size,
    )
}
