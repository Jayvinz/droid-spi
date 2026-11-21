package com.example.droidreel.community.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.droidreel.base.ui.DroidReelTheme
import com.example.droidreel.base.ui.ReelMuted
import com.example.droidreel.community.data.CommunityRepository

/**
 * Single-video player. The pager, comments and interaction overlays of the full DroidReel demo
 * are intentionally out of scope here; playback exists only so the feed opens something real.
 */
class PlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        setContent {
            DroidReelTheme {
                PlayerScreen(videoId = videoId, onBack = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_VIDEO_ID = "videoId"
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PlayerScreen(videoId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    CommunityRepository.ensureSeeded()
    val entry = remember(videoId) { CommunityRepository.find(videoId) }
    val video = entry?.video

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    LaunchedEffect(video) {
        if (video == null) return@LaunchedEffect
        val builder = MediaItem.Builder().setUri(video.videoUrl)
        if (video.hasTrim) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(video.trimStartMs)
                    .setEndPositionMs(video.trimEndMs)
                    .build(),
            )
        }
        player.setMediaItem(builder.build())
        player.prepare()
        player.play()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (video == null) {
            Text(
                text = "Video not found",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        this.player = player
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .safeDrawingPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        text = video?.title.orEmpty(),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = video?.let { "@${it.authorName} · ${it.likeCount} likes · ${it.tags.joinToString(" ")}" }.orEmpty(),
                        color = ReelMuted,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
