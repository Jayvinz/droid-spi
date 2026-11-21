package com.example.droidreel.community.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.droidreel.base.AccountListener
import com.example.droidreel.base.AccountManager
import com.example.droidreel.base.api.DynamicModuleInstaller
import com.example.droidreel.base.api.FeedSource
import com.example.droidreel.base.api.ProductionEntry
import com.example.droidreel.base.model.InstallProgress
import com.example.droidreel.base.model.InstallStatus
import com.example.droidreel.base.ui.Avatar
import com.example.droidreel.base.ui.DroidReelTheme
import com.example.droidreel.base.ui.ReelBackground
import com.example.droidreel.base.ui.ReelMuted
import com.example.droidreel.base.ui.ReelPrimary
import com.example.droidreel.base.ui.ReelSurface
import com.example.droidreel.base.ui.rememberLoginGate
import com.example.droidreel.community.data.CommunityRepository
import java.util.Locale
import top.jayvin.android.spi.ServiceLoader

class CommunityActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DroidReelTheme {
                CommunityScreen(
                    onOpenProfile = {
                        startActivity(Intent(this@CommunityActivity, ProfileActivity::class.java))
                    },
                    onOpenPlayer = { videoId ->
                        startActivity(
                            Intent(this@CommunityActivity, PlayerActivity::class.java)
                                .putExtra(PlayerActivity.EXTRA_VIDEO_ID, videoId),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun CommunityScreen(
    onOpenProfile: () -> Unit,
    onOpenPlayer: (String) -> Unit,
) {
    val context = LocalContext.current
    val gate = rememberLoginGate()

    var selectedTab by remember { mutableIntStateOf(0) }
    var feedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var feedError by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var accountTick by remember { mutableIntStateOf(0) }
    var installProgress by remember { mutableStateOf<InstallProgress?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }

    // Reading the repository version in composition makes every publish refresh the grid.
    val repoVersion = CommunityRepository.version
    val user = remember(accountTick) { AccountManager.currentUser }

    DisposableEffect(Unit) {
        val listener = AccountListener { accountTick++ }
        AccountManager.addListener(listener)
        onDispose { AccountManager.removeListener(listener) }
    }

    LaunchedEffect(accountTick, selectedTab) {
        if (selectedTab == 1 && !AccountManager.isLoggedIn) selectedTab = 0
    }

    LaunchedEffect(selectedTab, repoVersion, accountTick, reloadTick) {
        CommunityRepository.ensureSeeded()
        val key = if (selectedTab == 1) "following" else ""
        val source = ServiceLoader.load(FeedSource::class.java, key)
        if (source == null) {
            feedIds = emptyList()
            feedError = "FeedSource is not registered (key=${if (key.isEmpty()) "default" else key})"
        } else {
            val page = runCatching { source.load(0, 30) }.getOrNull()
            if (page == null) {
                feedIds = emptyList()
                feedError = "The feed could not be loaded"
            } else {
                feedIds = page.videoIds
                feedError = if (CommunityRepository.isEmpty()) "Demo media catalog is unavailable" else null
            }
        }
    }

    fun selectTab(index: Int) {
        if (index == selectedTab) return
        if (index == 1) {
            gate { selectedTab = 1 }
        } else {
            selectedTab = 0
        }
    }

    fun installProduction() {
        val installer = ServiceLoader.load(DynamicModuleInstaller::class.java)
        if (installer == null) {
            installError = "Dynamic delivery is unavailable, so the production module cannot load."
            return
        }
        installProgress = InstallProgress(moduleName = "production", status = InstallStatus.PENDING)
        installer.install("production") { progress ->
            Handler(Looper.getMainLooper()).post {
                when (progress.status) {
                    InstallStatus.INSTALLED -> {
                        installProgress = null
                        // No refresh: the next load() probes the now-visible class.
                        val production = ServiceLoader.load(ProductionEntry::class.java)
                        if (production == null) {
                            installError =
                                "Install finished but ProductionEntry is still unavailable; " +
                                    "check the generated ServiceConfiguration."
                        } else {
                            production.openCreate(context)
                        }
                    }

                    InstallStatus.FAILED -> {
                        installProgress = null
                        installError = "Dynamic module install failed " +
                            "(errorCode=${progress.errorCode}). Run from a Play internal test " +
                            "track, or install the AAB with bundletool --local-testing."
                    }

                    InstallStatus.CANCELED -> {
                        installProgress = null
                        installError = "Download canceled; tap Create again to retry."
                    }

                    else -> installProgress = progress
                }
            }
        }
    }

    fun startCreate() {
        gate {
            val production = ServiceLoader.load(ProductionEntry::class.java)
            if (production != null) {
                production.openCreate(context)
            } else {
                installProduction()
            }
        }
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "DroidReel",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onOpenProfile)
                    .semantics { contentDescription = "Open my profile" },
            ) {
                Avatar(
                    name = user?.nickname ?: "Guest",
                    key = user?.id ?: "guest",
                    modifier = Modifier.fillMaxSize(),
                    size = 36.dp,
                )
            }
        }

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = ReelPrimary,
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectTab(0) },
                text = { Text("Recommend") },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectTab(1) },
                text = { Text("Following") },
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                feedError != null -> MessageState(
                    title = feedError.orEmpty(),
                    body = "Confirm that Droid-spi is applied to the app and feature modules, then rebuild.",
                    actionLabel = "Retry",
                    onAction = { reloadTick++ },
                )

                feedIds.isEmpty() && selectedTab == 1 -> MessageState(
                    title = "Nothing followed yet",
                    body = "Follow a creator to see their works here.",
                )

                feedIds.isEmpty() -> MessageState(
                    title = "No works yet",
                    body = "Tap Create to publish the first video.",
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(items = feedIds, key = { it }) { id ->
                        CommunityRepository.find(id)?.let { entry ->
                            VideoCard(entry = entry, onClick = { onOpenPlayer(id) })
                        }
                    }
                }
            }
        }

        BottomBar(
            onCreate = { startCreate() },
            onOpenProfile = onOpenProfile,
        )
    }

    installProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Preparing the production module") },
            text = {
                Column {
                    Text(statusText(progress))
                    Spacer(Modifier.height(12.dp))
                    if (progress.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (progress.bytesDownloaded.toFloat() / progress.totalBytes)
                                    .coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "First use downloads the split from Play; the creation screen " +
                            "opens when it has been installed.",
                        color = ReelMuted,
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = {
                if (progress.status == InstallStatus.REQUIRES_USER_CONFIRMATION) {
                    TextButton(onClick = { installProgress = null }) {
                        Text("OK")
                    }
                }
            },
        )
    }

    installError?.let { message ->
        AlertDialog(
            onDismissRequest = { installError = null },
            title = { Text("Cannot open the production module") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { installError = null }) {
                    Text("OK")
                }
            },
        )
    }
}

@Composable
private fun BottomBar(
    onCreate: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(ReelSurface)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomItem(
            label = "Community",
            icon = Icons.Filled.Home,
            selected = true,
            onClick = {},
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onCreate,
            shape = RoundedCornerShape(50),
            modifier = Modifier.padding(horizontal = 10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("Create", fontSize = 14.sp)
        }
        BottomItem(
            label = "Me",
            icon = Icons.Filled.Person,
            selected = false,
            onClick = onOpenProfile,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BottomItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) ReelPrimary else ReelMuted,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            color = if (selected) ReelPrimary else ReelMuted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun MessageState(
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
        body?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, color = ReelMuted, fontSize = 13.sp)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onAction) {
                Text(actionLabel, color = ReelPrimary)
            }
        }
    }
}

private fun statusText(progress: InstallProgress): String = when (progress.status) {
    InstallStatus.PENDING -> "Preparing…"
    InstallStatus.DOWNLOADING ->
        "Downloading ${bytesLabel(progress.bytesDownloaded)} / ${bytesLabel(progress.totalBytes)}"
    InstallStatus.DOWNLOADED -> "Downloaded, installing…"
    InstallStatus.INSTALLING -> "Installing…"
    InstallStatus.REQUIRES_USER_CONFIRMATION -> "Confirm the download in Play, then retry."
    InstallStatus.INSTALLED -> "Installed"
    InstallStatus.FAILED -> "Install failed"
    InstallStatus.CANCELED -> "Canceled"
}

private fun bytesLabel(value: Long): String =
    if (value <= 0) "—" else String.format(Locale.US, "%.1f MB", value / 1_048_576.0)
