package com.example.droidreel.base.model

data class User(
    val id: String,
    val nickname: String,
    val bio: String,
)

data class Video(
    val id: String,
    val title: String,
    val description: String,
    val authorId: String,
    val authorName: String,
    val videoUrl: String,
    val durationMs: Long,
    val likeCount: Int,
    val tags: List<String>,
    val createdAt: Long,
    /** Optional playback window; [hasTrim] is false when the whole media plays. */
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    /** Cover palette seed for published works; 0 means derive from the id. */
    val coverSeed: Int = 0,
) {
    val hasTrim: Boolean
        get() = trimEndMs > trimStartMs

    /** Stable gradient seed for covers and avatars. */
    val displayCoverSeed: Int
        get() = if (coverSeed != 0) coverSeed else id.hashCode()
}

data class FeedPage(
    val videoIds: List<String>,
    val hasMore: Boolean,
)

data class AuthResult(
    val success: Boolean,
    val message: String? = null,
    val user: User? = null,
)

data class InstallProgress(
    val moduleName: String,
    val status: InstallStatus,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val errorCode: Int = 0,
)

enum class InstallStatus {
    PENDING,
    DOWNLOADING,
    DOWNLOADED,
    INSTALLING,
    INSTALLED,
    FAILED,
    CANCELED,
    REQUIRES_USER_CONFIRMATION,
}
