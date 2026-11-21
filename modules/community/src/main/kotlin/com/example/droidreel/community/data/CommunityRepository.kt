package com.example.droidreel.community.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.example.droidreel.base.AccountManager
import com.example.droidreel.base.api.MediaCatalog
import com.example.droidreel.base.model.Video
import top.jayvin.android.spi.ServiceLoader

/** One feed entry. The [Video] itself stays immutable. */
class VideoEntry internal constructor(val video: Video)

/**
 * Process-wide community state, seeded once from [MediaCatalog]. Published works are
 * session-only: they disappear when the process dies.
 */
object CommunityRepository {

    private val entries = LinkedHashMap<String, VideoEntry>()
    private var seeded = false
    private var versionState by mutableIntStateOf(0)

    /** Changes whenever a screen should re-read the repository. */
    val version: Int
        get() = versionState

    private val followedAuthorIds = setOf("u_shan", "u_night")

    fun ensureSeeded() {
        if (seeded) return
        seeded = true
        val catalog = ServiceLoader.load(MediaCatalog::class.java)
        catalog?.demoVideos().orEmpty().forEach { entries[it.id] = VideoEntry(it) }
        versionState++
    }

    fun isEmpty(): Boolean = entries.isEmpty()

    fun find(videoId: String): VideoEntry? = entries[videoId]

    fun recommend(): List<VideoEntry> =
        entries.values.sortedByDescending { it.video.createdAt }

    fun following(): List<VideoEntry> {
        if (!AccountManager.isLoggedIn) return emptyList()
        return recommend().filter { it.video.authorId in followedAuthorIds }
    }

    fun byAuthor(authorId: String): List<VideoEntry> =
        recommend().filter { it.video.authorId == authorId }

    /** Adds a work published by the production module. Returns false for a duplicate or empty id. */
    fun publish(video: Video): Boolean {
        if (video.id.isBlank() || video.videoUrl.isBlank() || entries.containsKey(video.id)) {
            return false
        }
        entries[video.id] = VideoEntry(video)
        versionState++
        return true
    }
}
