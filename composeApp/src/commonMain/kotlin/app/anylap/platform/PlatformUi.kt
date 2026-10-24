package app.anylap.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** System back (Android back button / gesture). No-op where there is no such thing. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/**
 * Plays [path] looping between [loopStartMs] and [loopEndMs] at [speed].
 * When [seekToMs] changes to a non-null value the player jumps there.
 * Reports the playback position through [onPosition]. Keeps the video's aspect ratio inside [modifier].
 */
@Composable
expect fun VideoPlayer(
    path: String,
    loopStartMs: Long,
    loopEndMs: Long,
    speed: Float,
    playing: Boolean,
    seekToMs: Long?,
    onPosition: (Long) -> Unit,
    modifier: Modifier,
)
