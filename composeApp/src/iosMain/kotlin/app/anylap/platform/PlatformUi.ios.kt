@file:OptIn(ExperimentalForeignApi::class)

package app.anylap.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.currentTime
import platform.AVFoundation.pause
import platform.AVFoundation.rate
import platform.AVFoundation.seekToTime
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS has no system back button; screens show their own back arrows.
}

/** UIView that keeps a CALayer (player / camera preview) filling its bounds. */
class LayerHostView(private val hosted: platform.QuartzCore.CALayer) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    init {
        backgroundColor = UIColor.blackColor
        layer.addSublayer(hosted)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        hosted.frame = bounds
        CATransaction.commit()
    }
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
    val player = remember(path) { AVPlayer(uRL = NSURL.fileURLWithPath(path)) }
    val playerLayer = remember(player) {
        AVPlayerLayer.playerLayerWithPlayer(player).apply { videoGravity = AVLayerVideoGravityResizeAspect }
    }
    val start by rememberUpdatedState(loopStartMs)
    val end by rememberUpdatedState(loopEndMs)
    val isPlaying by rememberUpdatedState(playing)
    val curRate by rememberUpdatedState(speed)
    val report by rememberUpdatedState(onPosition)

    fun seek(ms: Long) {
        player.seekToTime(
            CMTimeMakeWithSeconds(ms / 1000.0, 600),
            toleranceBefore = CMTimeMake(0, 600),
            toleranceAfter = CMTimeMake(0, 600),
        )
    }

    DisposableEffect(player) {
        onDispose { player.pause() }
    }
    LaunchedEffect(player, loopStartMs, speed) {
        seek(loopStartMs)
        if (playing) player.rate = speed else player.pause()
    }
    LaunchedEffect(player, playing) {
        if (playing) player.rate = curRate else player.pause()
    }
    LaunchedEffect(player, seekToMs) {
        seekToMs?.let { seek(it) }
    }
    LaunchedEffect(player) {
        while (true) {
            val ms = (CMTimeGetSeconds(player.currentTime()) * 1000.0).toLong()
            report(ms)
            if (isPlaying && ms >= end) {
                seek(start)
                player.rate = curRate
            }
            delay(40)
        }
    }

    UIKitView(
        factory = { LayerHostView(playerLayer) },
        modifier = modifier,
    )
}
