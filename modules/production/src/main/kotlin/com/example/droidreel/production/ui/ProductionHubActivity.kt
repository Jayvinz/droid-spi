package com.example.droidreel.production.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.droidreel.base.AccountListener
import com.example.droidreel.base.AccountManager
import com.example.droidreel.base.api.CommunityEntry
import com.example.droidreel.base.api.FeedPublisher
import com.example.droidreel.base.api.MediaCatalog
import com.example.droidreel.base.api.ProductionEntry
import com.example.droidreel.base.model.Video
import com.example.droidreel.base.ui.DroidReelTheme
import com.example.droidreel.base.ui.ReelBackground
import com.example.droidreel.base.ui.ReelMuted
import com.example.droidreel.base.ui.ReelPrimary
import com.example.droidreel.base.ui.ReelSurfaceHigh
import com.example.droidreel.base.ui.coverBrush
import com.example.droidreel.base.ui.formatDuration
import com.example.droidreel.base.ui.rememberLoginGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.jayvin.android.spi.ServiceLoader
import kotlin.math.abs

/**
 * Simplified creation screen. Picking a seeded clip and pressing Publish demonstrates the real
 * point of the dynamic module: it calls the resident `FeedPublisher` through the contract even
 * though production has no dependency on the community module.
 */
class ProductionHubActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DroidReelTheme {
                ProductionScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun ProductionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val gate = rememberLoginGate()
    val scope = rememberCoroutineScope()

    var accountTick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener = AccountListener { accountTick++ }
        AccountManager.addListener(listener)
        onDispose { AccountManager.removeListener(listener) }
    }
    val loggedIn = remember(accountTick) { AccountManager.isLoggedIn }

    val samples = remember {
        ServiceLoader.load(MediaCatalog::class.java)?.demoVideos().orEmpty()
    }
    val versionLabel = remember {
        ServiceLoader.load(ProductionEntry::class.java)?.versionLabel() ?: "production · dynamic"
    }

    var selectedIndex by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var publishing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var published by remember { mutableStateOf(false) }

    LaunchedEffect(samples) {
        if (title.isBlank()) title = samples.firstOrNull()?.title?.take(30).orEmpty()
    }

    fun publishNow() {
        val user = AccountManager.currentUser ?: return
        val sample = samples.getOrNull(selectedIndex) ?: return
        if (title.isBlank() || publishing) return
        publishing = true
        error = null
        progress = 0f
        scope.launch {
            repeat(15) { step ->
                delay(120)
                progress = (step + 1) / 15f
            }
            val video = Video(
                id = "upload-${System.currentTimeMillis()}",
                title = title.trim(),
                description = description.trim(),
                authorId = user.id,
                authorName = user.nickname,
                videoUrl = sample.videoUrl,
                durationMs = sample.durationMs,
                likeCount = 0,
                tags = listOf("#created"),
                createdAt = System.currentTimeMillis(),
                coverSeed = abs(title.hashCode()) + 1,
            )
            val accepted = ServiceLoader.load(FeedPublisher::class.java)?.publish(video) == true
            publishing = false
            if (accepted) {
                published = true
            } else {
                error = "FeedPublisher is unavailable, so the work was not published."
            }
        }
    }

    fun requestPublish() {
        if (AccountManager.isLoggedIn) publishNow() else gate { publishNow() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ReelBackground)
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = "Create",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = versionLabel,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "This screen lives in the on-demand split. Publishing calls the resident " +
                    "FeedPublisher through the contract; there is no project dependency on community.",
                color = ReelMuted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (loggedIn) "Signed in; you can publish." else "Guest; publishing asks for login first.",
                color = ReelMuted,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(18.dp))
            Text("Choose a sample clip", color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))

            samples.forEachIndexed { index, sample ->
                val selected = index == selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ReelSurfaceHigh)
                        .border(
                            width = if (selected) 2.dp else 0.dp,
                            color = if (selected) ReelPrimary else Color.Transparent,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable { selectedIndex = index }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp, 56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(coverBrush(sample.displayCoverSeed)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = sample.title,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "@${sample.authorName} · ${formatDuration(sample.durationMs)}",
                            color = ReelMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(30) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Title") },
                singleLine = true,
                supportingText = { Text("${title.length}/30", color = ReelMuted, fontSize = 11.sp) },
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(100) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Description (optional)") },
                minLines = 2,
                maxLines = 3,
                supportingText = { Text("${description.length}/100", color = ReelMuted, fontSize = 11.sp) },
            )

            error?.let { message ->
                Spacer(Modifier.height(10.dp))
                Text(text = message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { requestPublish() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = title.isNotBlank() && !publishing,
            ) {
                Text(if (publishing) "Publishing…" else "Publish")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (publishing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Uploading") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${(progress * 100).toInt()}%", color = ReelMuted, fontSize = 12.sp)
                }
            },
            confirmButton = {},
        )
    }

    if (published) {
        AlertDialog(
            onDismissRequest = {
                published = false
                onBack()
            },
            title = { Text("Published") },
            text = { Text("The work was added to the community feed and to your profile.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        published = false
                        ServiceLoader.load(CommunityEntry::class.java)?.openProfile(context)
                        onBack()
                    },
                ) {
                    Text("View my profile")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        published = false
                        onBack()
                    },
                ) {
                    Text("Done")
                }
            },
        )
    }
}
