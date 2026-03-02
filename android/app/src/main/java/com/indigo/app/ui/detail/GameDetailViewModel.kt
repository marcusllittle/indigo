package com.indigo.app.ui.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.indigo.app.data.model.Channel
import com.indigo.app.data.model.Game
import com.indigo.app.data.model.PlaybackInfo
import com.indigo.app.data.repository.GamesRepository
import com.indigo.app.player.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "GameDetailViewModel"

data class GameDetailUiState(
    val game: Game? = null,
    val channels: List<Channel> = emptyList(),
    val selectedChannel: Channel? = null,
    val playbackInfo: PlaybackInfo? = null,
    val commentaryEnabled: Boolean = false,
    val shouldBePlaying: Boolean = false,
    val playbackState: PlaybackState = PlaybackState.Paused,
    val playbackError: String? = null,
    val syncOffsetMs: Long = 0L,
    val isLoading: Boolean = true,
    val error: String? = null,
)

class GameDetailViewModel(private val gameId: String) : ViewModel() {

    private val repository = GamesRepository()

    private val _uiState = MutableStateFlow(GameDetailUiState())
    val uiState: StateFlow<GameDetailUiState> = _uiState

    init {
        loadGameDetail()
    }

    private fun loadGameDetail() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Load game info from the live games list
            val gamesResult = repository.getLiveGames()
            val game = gamesResult.getOrNull()?.find { it.id == gameId }

            if (game == null) {
                _uiState.update { it.copy(isLoading = false, error = "Game not found") }
                return@launch
            }

            // Load channels
            val channelsResult = repository.getChannels(gameId)
            channelsResult
                .onSuccess { channels ->
                    val selected = channels.firstOrNull()
                    _uiState.update {
                        it.copy(
                            game = game,
                            channels = channels,
                            selectedChannel = selected,
                            isLoading = false,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            game = game,
                            isLoading = false,
                            error = e.message,
                        )
                    }
                }
        }
    }

    fun selectChannel(channel: Channel) {
        Log.d(TAG, "Selected channel ${channel.id}")
        _uiState.update {
            it.copy(
                selectedChannel = channel,
                commentaryEnabled = false,
                shouldBePlaying = false,
                playbackState = PlaybackState.Paused,
                playbackError = null,
                error = null,
                playbackInfo = null,
            )
        }
    }

    fun toggleCommentary() {
        val current = _uiState.value
        if (current.commentaryEnabled) {
            Log.d(TAG, "Stopping commentary")
            _uiState.update {
                it.copy(
                    commentaryEnabled = false,
                    shouldBePlaying = false,
                    playbackState = PlaybackState.Paused,
                    playbackError = null,
                    error = null,
                    playbackInfo = null,
                )
            }
        } else {
            val channel = current.selectedChannel ?: return
            Log.d(TAG, "Starting commentary for channel ${channel.id}")
            fetchPlayback(channel.id)
        }
    }

    fun togglePlayPause() {
        val current = _uiState.value
        if (current.playbackInfo == null) {
            retryPlayback()
            return
        }

        val shouldPlay = !current.shouldBePlaying
        Log.d(TAG, if (shouldPlay) "Resuming playback" else "Pausing playback")
        _uiState.update {
            it.copy(
                shouldBePlaying = shouldPlay,
                playbackState = if (shouldPlay) PlaybackState.Buffering else PlaybackState.Paused,
                playbackError = null,
                error = null,
            )
        }
    }

    fun adjustSync(deltaMs: Long) {
        _uiState.update { it.copy(syncOffsetMs = it.syncOffsetMs + deltaMs) }
    }

    fun resetSync() {
        _uiState.update { it.copy(syncOffsetMs = 0L) }
    }

    fun retryPlayback() {
        val current = _uiState.value
        val selected = current.selectedChannel ?: return
        Log.d(TAG, "Retrying playback for channel ${selected.id}")

        if (current.playbackInfo == null) {
            fetchPlayback(selected.id)
            return
        }

        _uiState.update {
            it.copy(
                commentaryEnabled = true,
                shouldBePlaying = true,
                playbackState = PlaybackState.Buffering,
                playbackError = null,
                error = null,
            )
        }
    }

    fun onPlaybackStateChanged(playbackState: PlaybackState) {
        _uiState.update {
            it.copy(
                playbackState = playbackState,
                playbackError = if (playbackState == PlaybackState.Error) it.playbackError else null,
            )
        }
    }

    fun onPlaybackError(message: String) {
        Log.d(TAG, "Playback error: $message")
        _uiState.update {
            it.copy(
                playbackState = PlaybackState.Error,
                playbackError = message,
                error = null,
                shouldBePlaying = false,
            )
        }
    }

    private fun fetchPlayback(channelId: String) {
        viewModelScope.launch {
            repository.getPlayback(channelId)
                .onSuccess { playback ->
                    Log.d(TAG, "Playback info loaded for channel ${playback.channelId}")
                    _uiState.update {
                        it.copy(
                            commentaryEnabled = true,
                            shouldBePlaying = true,
                            playbackState = PlaybackState.Buffering,
                            playbackError = null,
                            error = null,
                            playbackInfo = playback,
                            syncOffsetMs = playback.recommendedOffsetMs.toLong(),
                        )
                    }
                }
                .onFailure { e ->
                    Log.d(TAG, "Failed to load playback info", e)
                    _uiState.update {
                        it.copy(
                            commentaryEnabled = false,
                            shouldBePlaying = false,
                            playbackState = PlaybackState.Error,
                            playbackError = "Couldn't start commentary. Please try again.",
                            error = e.message,
                        )
                    }
                }
        }
    }

    class Factory(private val gameId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GameDetailViewModel(gameId) as T
        }
    }
}
