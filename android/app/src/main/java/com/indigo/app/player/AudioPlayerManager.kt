package com.indigo.app.player

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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

    interface Listener {
        fun onPlaybackStateChanged(state: PlaybackState)
        fun onPlaybackError(message: String)
    }

    companion object {
        private const val TAG = "AudioPlayerManager"
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_MS = 1500L
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var currentUrl: String? = null
    private var pendingAction: (() -> Unit)? = null
    private var lastSyncOffsetMs: Long = 0L
    private var retryCount = 0
    private var shouldResumePlayback = false
    private var hasPlaybackError = false
    private var listener: Listener? = null
    private val retryHandler = Handler(Looper.getMainLooper())
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    hasPlaybackError = false
                    listener?.onPlaybackStateChanged(PlaybackState.Buffering)
                }
                Player.STATE_READY -> {
                    if (controller?.isPlaying == true) {
                        hasPlaybackError = false
                        listener?.onPlaybackStateChanged(PlaybackState.Playing)
                    } else if (shouldResumePlayback) {
                        listener?.onPlaybackStateChanged(PlaybackState.Buffering)
                    } else {
                        listener?.onPlaybackStateChanged(PlaybackState.Paused)
                    }
                }
                Player.STATE_ENDED -> {
                    Log.d(TAG, "Playback reached end of test stream")
                    if (shouldResumePlayback && currentUrl != null) {
                        listener?.onPlaybackStateChanged(PlaybackState.Buffering)
                        retryHandler.postDelayed({ restartPlayback() }, 500L)
                    } else {
                        listener?.onPlaybackStateChanged(PlaybackState.Paused)
                    }
                }
                Player.STATE_IDLE -> listener?.onPlaybackStateChanged(PlaybackState.Paused)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                hasPlaybackError = false
                retryCount = 0
                listener?.onPlaybackStateChanged(PlaybackState.Playing)
            } else if (!shouldResumePlayback && !hasPlaybackError) {
                listener?.onPlaybackStateChanged(PlaybackState.Paused)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            hasPlaybackError = true
            Log.e(TAG, "Playback error", error)
            if (shouldResumePlayback && currentUrl != null && retryCount < MAX_RETRY_COUNT) {
                retryCount += 1
                listener?.onPlaybackStateChanged(PlaybackState.Buffering)
                Log.d(TAG, "Retrying playback attempt $retryCount")
                retryHandler.removeCallbacksAndMessages(null)
                retryHandler.postDelayed({ restartPlayback() }, RETRY_DELAY_MS)
                return
            }

            shouldResumePlayback = false
            listener?.onPlaybackStateChanged(PlaybackState.Error)
            listener?.onPlaybackError("Playback failed. Tap retry to try again.")
        }
    }

    init {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync().also { future ->
            future.addListener({
                controller = future.get()
                controller?.addListener(playerListener)
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

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun play(streamUrl: String) {
        shouldResumePlayback = true
        hasPlaybackError = false
        retryHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Starting playback for $streamUrl")
        withController { ctrl ->
            if (streamUrl != currentUrl) {
                currentUrl = streamUrl
                retryCount = 0
                ctrl.setMediaItem(MediaItem.fromUri(streamUrl))
                ctrl.prepare()
            }
            listener?.onPlaybackStateChanged(PlaybackState.Buffering)
            ctrl.play()
        }
    }

    fun pause() {
        shouldResumePlayback = false
        hasPlaybackError = false
        retryHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Pausing playback")
        withController { it.pause() }
        listener?.onPlaybackStateChanged(PlaybackState.Paused)
    }

    fun stop() {
        shouldResumePlayback = false
        hasPlaybackError = false
        retryHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Stopping playback")
        withController { it.stop() }
        currentUrl = null
        retryCount = 0
        lastSyncOffsetMs = 0L
        listener?.onPlaybackStateChanged(PlaybackState.Paused)
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

    fun retry() {
        if (currentUrl == null) return
        shouldResumePlayback = true
        hasPlaybackError = false
        retryCount = 0
        listener?.onPlaybackStateChanged(PlaybackState.Buffering)
        Log.d(TAG, "Retrying playback manually")
        restartPlayback()
    }

    private fun restartPlayback() {
        val streamUrl = currentUrl ?: return
        hasPlaybackError = false
        withController { ctrl ->
            ctrl.stop()
            ctrl.clearMediaItems()
            ctrl.setMediaItem(MediaItem.fromUri(streamUrl))
            ctrl.prepare()
            if (shouldResumePlayback) {
                ctrl.play()
            }
        }
    }

    fun release() {
        retryHandler.removeCallbacksAndMessages(null)
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        currentUrl = null
        listener = null
    }
}
