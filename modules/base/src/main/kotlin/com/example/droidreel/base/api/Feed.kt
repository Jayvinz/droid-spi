package com.example.droidreel.base.api

import com.example.droidreel.base.model.FeedPage
import com.example.droidreel.base.model.Video

/**
 * Keyed feed provider. The default key is the recommend feed; the `"following"` key is the
 * login-gated following feed.
 */
interface FeedSource {
    fun load(page: Int, pageSize: Int): FeedPage
}

/**
 * Publish contract used by the on-demand production module to add a work to the resident
 * community repository.
 */
interface FeedPublisher {
    fun publish(video: Video): Boolean
}
