package com.example.droidreel.base.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.droidreel.base.AccountManager
import com.example.droidreel.base.api.LoginEntry
import top.jayvin.android.spi.ServiceLoader
import java.util.Locale
import kotlin.math.abs

/** `gate { ... }` runs the action now when signed in, or after a successful login. */
typealias LoginGate = (() -> Unit) -> Unit

private val CoverPalette = listOf(
    Color(0xFF2B5876) to Color(0xFF4E4376),
    Color(0xFFF2994A) to Color(0xFFEB5757),
    Color(0xFF11998E) to Color(0xFF38EF7D),
    Color(0xFF4568DC) to Color(0xFFB06AB3),
    Color(0xFF1D976C) to Color(0xFF93F9B9),
    Color(0xFFFF512F) to Color(0xFFDD2476),
    Color(0xFF141E30) to Color(0xFF243B55),
    Color(0xFF6A3093) to Color(0xFFA044FF),
)

private val AvatarPalette = listOf(
    Color(0xFFE85D75),
    Color(0xFF5B8DEF),
    Color(0xFF31B57C),
    Color(0xFFE2A03F),
    Color(0xFF9B6BD6),
    Color(0xFF38A3A5),
)

/** Deterministic two-color gradient used as a cover placeholder and upload cover. */
fun coverGradient(seed: Int): List<Color> {
    val pair = CoverPalette[abs(seed) % CoverPalette.size]
    return listOf(pair.first, pair.second)
}

/** Deterministic gradient brush for a video/author key. */
fun coverBrush(seed: Int): Brush = Brush.linearGradient(coverGradient(seed))

/** Deterministic avatar color for a user id. */
fun avatarColor(key: String): Color {
    val hash = abs(key.hashCode())
    return AvatarPalette[hash % AvatarPalette.size]
}

/** Formats milliseconds as `mm:ss` (or `h:mm:ss` for long clips). */
fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/** Circular avatar with the first character of [name]. */
@Composable
fun Avatar(
    name: String,
    key: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarColor(key)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().take(1).ifBlank { "?" }.uppercase(),
            color = Color.White,
            fontSize = (size.value * 0.4f).sp,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/**
 * Central login gate. Must be called during composition of a `ComponentActivity` screen.
 * A gate holds one pending action at a time; cancelling login discards it.
 */
@Composable
fun rememberLoginGate(): LoginGate {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) pending?.invoke()
        pending = null
    }

    return remember(launcher) {
        { onLoggedIn ->
            if (AccountManager.isLoggedIn) {
                onLoggedIn()
            } else {
                val entry = ServiceLoader.load(LoginEntry::class.java)
                if (entry == null) {
                    Toast.makeText(context, "Login module unavailable", Toast.LENGTH_SHORT).show()
                } else {
                    pending = onLoggedIn
                    launcher.launch(entry.loginIntent(context))
                }
            }
        }
    }
}
