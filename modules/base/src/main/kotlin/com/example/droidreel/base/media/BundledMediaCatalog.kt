package com.example.droidreel.base.media

import com.example.droidreel.base.api.MediaCatalog
import com.example.droidreel.base.model.Video

/**
 * Seeded demo catalog. Declared through `META-INF/services` rather than `@ServiceProvider`, so
 * the example also exercises the JDK-style configuration path.
 *
 * Videos are public W3C/MDN sample MP4s. Covers are deterministic local gradients, so the feed
 * renders even when the network is unavailable.
 */
class BundledMediaCatalog : MediaCatalog {

    override fun demoVideos(): List<Video> {
        val now = System.currentTimeMillis()
        return listOf(
            seed(0, "reel-1", SINTEL, 0, 10_000, "Rain on the mountain road",
                "The fog has not lifted yet after the rain.", "u_shan", "ShanYe", 1284,
                listOf("#travel", "#calm"), now),
            seed(1, "reel-2", SINTEL, 10_000, 20_000, "City at three in the morning",
                "Office towers still lit, streets finally quiet.", "u_night", "NightWalker", 2360,
                listOf("#city", "#night"), now),
            seed(2, "reel-3", SINTEL, 20_000, 30_000, "A glass of soda in summer",
                "Ice cubes clink, bubbles rise.", "u_soda", "SummerSoda", 987,
                listOf("#food", "#summer"), now),
            seed(3, "reel-4", SINTEL, 30_000, 40_000, "Today's outfit",
                "A plain white tee and old jeans.", "u_demo", "Xiao D", 421,
                listOf("#style", "#daily"), now),
            seed(4, "reel-5", BUNNY, 0, 10_000, "Waves against the rocks",
                "Left the phone on a reef for two minutes.", "u_shan", "ShanYe", 3105,
                listOf("#sea", "#ambient"), now),
            seed(5, "reel-6", BUNNY, 10_000, 20_000, "Rooftop sunset etude",
                "Caught the last ten minutes of orange after work.", "u_night", "NightWalker", 1567,
                listOf("#sunset", "#city"), now),
        )
    }

    private fun seed(
        index: Int,
        id: String,
        url: String,
        startMs: Long,
        endMs: Long,
        title: String,
        description: String,
        authorId: String,
        authorName: String,
        likes: Int,
        tags: List<String>,
        now: Long,
    ): Video = Video(
        id = id,
        title = title,
        description = description,
        authorId = authorId,
        authorName = authorName,
        videoUrl = url,
        durationMs = endMs - startMs,
        likeCount = likes,
        tags = tags,
        createdAt = now - index * 3_600_000L,
        trimStartMs = startMs,
        trimEndMs = endMs,
        coverSeed = index + 1,
    )

    private companion object {
        const val SINTEL = "https://media.w3.org/2010/05/sintel/trailer.mp4"
        const val BUNNY = "https://media.w3.org/2010/05/bunny/trailer.mp4"
    }
}
