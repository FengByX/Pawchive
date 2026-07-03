package com.pawchive.ui.post

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

class VideoPlayerManager(private val context: Context) {

    interface VideoPlayerListener {
        fun onPlaybackStateChanged(state: Int)
        fun onIsPlayingChanged(isPlaying: Boolean)
        fun onVideoSizeChanged(width: Int, height: Int)
        fun onError(message: String)
    }

    var player: ExoPlayer? = null
        private set
    var playerView: PlayerView? = null
        private set

    var isPlaying: Boolean = false
        private set
    var currentPosition: Long = 0
        private set
    var duration: Long = 0
        private set
    var playbackSpeed: Float = 1.0f
        private set

    private var listener: VideoPlayerListener? = null

    fun setListener(listener: VideoPlayerListener?) {
        this.listener = listener
    }

    @OptIn(UnstableApi::class)
    fun attachPlayerView(playerView: PlayerView) {
        this.playerView = playerView
        player?.let {
            playerView.player = it
        }
    }

    fun detachPlayerView() {
        playerView?.player = null
        playerView = null
    }

    @OptIn(UnstableApi::class)
    fun play(url: String) {
        if (player == null) {
            initializePlayer()
        }
        val mediaItem = MediaItem.fromUri(url)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun stop() {
        player?.stop()
    }

    fun release() {
        player?.release()
        player = null
        isPlaying = false
        currentPosition = 0
        duration = 0
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        player?.setPlaybackSpeed(speed)
    }

    fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer() {
        if (player != null) return

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)

        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()

        playerView?.let {
            it.player = player
        }

        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                listener?.onPlaybackStateChanged(playbackState)
                if (playbackState == Player.STATE_READY) {
                    duration = player?.duration ?: 0
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                listener?.onIsPlayingChanged(playing)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                listener?.onVideoSizeChanged(videoSize.width, videoSize.height)
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(error.message ?: "Unknown error")
            }
        })
    }

    fun updateCurrentPosition() {
        currentPosition = player?.currentPosition ?: 0
    }
}
