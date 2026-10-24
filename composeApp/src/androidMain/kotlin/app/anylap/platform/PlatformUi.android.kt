package app.anylap.platform

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

@Composable
actual fun VideoPlayer(
    path: String,
    loopStartMs: Long,
    loopEndMs: Long,
    speed: Float,
    playing: Boolean,
    seekToMs: Long?,
    onPosition: (Long) -> Unit,
    modifier: Modifier,
) {
    val player = remember(path) { MediaPlayer() }
    var prepared by remember(path) { mutableStateOf(false) }
    var aspect by remember(path) { mutableFloatStateOf(16f / 9f) }
    val start by rememberUpdatedState(loopStartMs)
    val end by rememberUpdatedState(loopEndMs)
    val isPlaying by rememberUpdatedState(playing)
    val report by rememberUpdatedState(onPosition)

    DisposableEffect(player) {
        try {
            player.setDataSource(path)
            player.setOnPreparedListener { mp ->
                if (mp.videoHeight > 0) aspect = mp.videoWidth.toFloat() / mp.videoHeight
                prepared = true
            }
            player.prepareAsync()
        } catch (_: Exception) {
        }
        onDispose {
            try { player.stop() } catch (_: Exception) {}
            player.release()
        }
    }

    LaunchedEffect(prepared, loopStartMs, speed) {
        if (!prepared) return@LaunchedEffect
        player.seekTo(loopStartMs, MediaPlayer.SEEK_CLOSEST)
        try { player.playbackParams = PlaybackParams().setSpeed(speed) } catch (_: Exception) {}
        if (playing) player.start() else player.pause()
    }
    LaunchedEffect(prepared, playing) {
        if (!prepared) return@LaunchedEffect
        if (playing) {
            try { player.playbackParams = PlaybackParams().setSpeed(speed) } catch (_: Exception) {}
            player.start()
        } else player.pause()
    }
    LaunchedEffect(prepared, seekToMs) {
        if (prepared && seekToMs != null) player.seekTo(seekToMs, MediaPlayer.SEEK_CLOSEST)
    }
    LaunchedEffect(prepared) {
        while (prepared) {
            val p = try { player.currentPosition.toLong() } catch (_: Exception) { 0L }
            report(p)
            if (isPlaying && p >= end) player.seekTo(start, MediaPlayer.SEEK_CLOSEST)
            delay(40)
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.aspectRatio(aspect),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { try { player.setDisplay(h) } catch (_: Exception) {} }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) { try { player.setDisplay(null) } catch (_: Exception) {} }
                    })
                }
            },
        )
    }
}
