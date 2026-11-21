package com.example.droidreel.base.api

import com.example.droidreel.base.model.Video

/** Seed catalog of public demo media. Implemented in base through `META-INF/services`. */
interface MediaCatalog {
    fun demoVideos(): List<Video>
}
