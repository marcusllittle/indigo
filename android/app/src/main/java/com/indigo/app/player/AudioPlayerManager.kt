package com.indigo.app.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * Manages audio playback for commentary streams.
 * Connects to PlaybackService via MediaController so audio
 * survives app backgrounding.
 */
class AudioPlayerManager(private val context: Context) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var currentUrl: String? = null
    private var pendingAction: (() -> Unit)? = null
    private var lastSyncOffsetMs: Long = 0L

    init {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync().also { future ->
            future.addListener({
                controller = future.get()
                pendingAction?.invoke()
                pendingAction = null
            }, MoreExecutors.directExecutor())
        }
    }

    private fun withController(action: (MediaController) -> Unit) {
        val ctrl = controller
        if (ctrl != null) {
            action(ctrl)
        } else {
            pendingAction = { controller?.let(action) }
        }
    }

    fun play(streamUrl: String) {
        withController { ctrl ->
            if (streamUrl != currentUrl) {
                currentUrl = streamUrl
                ctrl.setMediaItem(MediaItem.fromUri(streamUrl))
                ctrl.prepare()
            }
            ctrl.play()
        }
    }

    fun pause() {
        withController { it.pause() }
    }

    fun stop() {
        withController { it.stop() }
        currentUrl = null
    }

    fun setSyncOffset(offsetMs: Long) {
        val delta = offsetMs - lastSyncOffsetMs
        lastSyncOffsetMs = offsetMs
        if (delta == 0L) return

        withController { ctrl ->
            if (ctrl.isPlaying || ctrl.playbackState == Player.STATE_READY) {
                val target = ctrl.currentPosition + delta
                if (target >= 0) {
                    ctrl.seekTo(target)
                }
            }
        }
    }

    fun release() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        currentUrl = null
    }
}
