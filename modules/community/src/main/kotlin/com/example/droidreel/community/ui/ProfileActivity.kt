package com.example.droidreel.community.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.droidreel.base.AccountListener
import com.example.droidreel.base.AccountManager
import com.example.droidreel.base.api.AccountService
import com.example.droidreel.base.api.ChannelService
import com.example.droidreel.base.api.DemoInfoService
import com.example.droidreel.base.api.ProductionEntry
import com.example.droidreel.base.ui.Avatar
import com.example.droidreel.base.ui.DroidReelTheme
import com.example.droidreel.base.ui.ReelBackground
import com.example.droidreel.base.ui.ReelMuted
import com.example.droidreel.base.ui.coverBrush
import com.example.droidreel.base.ui.formatDuration
import com.example.droidreel.base.ui.rememberLoginGate
import com.example.droidreel.community.data.CommunityRepository
import com.example.droidreel.community.data.VideoEntry
import java.util.Locale
import top.jayvin.android.spi.ServiceLoader

class ProfileActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DroidReelTheme {
                ProfileScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun ProfileScreen(onBack: () -> Unit) {
    val gate = rememberLoginGate()
    var accountTick by remember { mutableIntStateOf(0) }
    var showStatus by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val listener = AccountListener { accountTick++ }
        AccountManager.addListener(listener)
        onDispose { AccountManager.removeListener(listener) }
    }

    val user = remember(accountTick) { AccountManager.currentUser }
    val repoVersion = CommunityRepository.version
    val works = remember(user, repoVersion) {
        CommunityRepository.ensureSeeded()
        user?.let { CommunityRepository.byAuthor(it.id) }.orEmpty()
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
                text = "Profile",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            Avatar(
                name = user?.nickname ?: "Guest",
                key = user?.id ?: "guest",
                size = 68.dp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = user?.nickname ?: "Guest",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = user?.bio ?: "Sign in to publish works and open the following feed.",
                color = ReelMuted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            if (user == null) {
                Button(onClick = { gate { } }) {
                    Text("Sign in")
                }
            } else {
                Text(
                    text = "${works.size} works",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { ServiceLoader.load(AccountService::class.java)?.logout() },
                ) {
                    Text("Sign out")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showStatus = true }) {
                Text("SPI status")
            }
            Spacer(Modifier.height(12.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = works, key = { it.video.id }) { entry ->
                WorkRow(entry)
            }
        }
    }

    if (showStatus) {
        AlertDialog(
            onDismissRequest = { showStatus = false },
            title = { Text("Droid-spi provider table") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = spiReport(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showStatus = false }) {
                    Text("Close")
                }
            },
        )
    }
}

@Composable
private fun WorkRow(entry: VideoEntry) {
    val video = entry.video
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp, 64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(coverBrush(video.displayCoverSeed)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text = "${formatDuration(video.durationMs)} · ${video.likeCount} likes",
                color = ReelMuted,
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * Mirrors what the generated table contains in this build. Installed with a debug APK the
 * report should list 11 entries (10 in release), the matching flavor channel, the debug-only
 * diagnostics provider, and a MISSING production provider before the split is installed.
 */
private fun spiReport(): String {
    val sb = StringBuilder()
    val snapshot = ServiceLoader.snapshot()

    sb.appendLine("initialized = ${ServiceLoader.isInitialized()}")
    sb.appendLine("entries = ${snapshot.size}")
    sb.appendLine()
    snapshot.forEach { (api, key, state) ->
        sb.appendLine(
            String.format(
                Locale.US,
                "%-20s %-12s %s",
                api.substringAfterLast('.'),
                "key=${if (key.isEmpty()) "\"\"" else key}",
                state,
            ),
        )
    }

    sb.appendLine()
    val channel = ServiceLoader.load(ChannelService::class.java)
    sb.appendLine("Channel = " + (channel?.let { "${it.channelName()} · ${it.describe()}" } ?: "absent"))

    val demoInfo = ServiceLoader.load(DemoInfoService::class.java, "debug")
    sb.appendLine("DemoInfo(debug key) = " + (demoInfo?.describe() ?: "absent"))

    val productionState = ServiceLoader.stateOf(ProductionEntry::class.java)
    val production = ServiceLoader.load(ProductionEntry::class.java)
    sb.appendLine(
        "ProductionEntry = $productionState, " +
            "load=" + (if (production == null) "null" else "instance"),
    )
    sb.appendLine()
    sb.appendLine("FeedSource providers = " + ServiceLoader.loadAllClasses(FeedSourceKeys).size)
    return sb.toString()
}

private val FeedSourceKeys = com.example.droidreel.base.api.FeedSource::class.java
