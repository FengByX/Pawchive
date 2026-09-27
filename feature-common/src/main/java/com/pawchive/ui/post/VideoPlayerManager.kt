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
import com.pawchive.core.api.ApiClient
import java.util.concurrent.CopyOnWriteArrayList

class VideoPlayerManager(
    private val context: Context,
    /**
     * 初始播放倍速（FEATURE：记住上次倍速）。
     * 调用方传入设置页记忆的"上次倍速"；默认 1.0x 保持旧行为。
     */
    initialSpeed: Float = 1.0f
) {

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
    var playbackSpeed: Float = initialSpeed
        private set

    // 监听器列表：内嵌页与全屏页可同时监听同一播放器（全屏复用实例时各自更新各自 UI）
    private val listeners = CopyOnWriteArrayList<VideoPlayerListener>()

    // 保存的播放状态，用于生命周期(onStop/onStart)间恢复播放
    private var currentUrl: String? = null
    private var savedPosition: Long = 0
    private var savedPlayWhenReady: Boolean = true

    // 释放后标记，防止后续操作误用已释放的播放器
    @Volatile
    private var isReleased: Boolean = false
    private val playerLock = Any()

    /** 替换式设置监听器（兼容旧调用方：清空后仅保留当前） */
    fun setListener(listener: VideoPlayerListener?) {
        listeners.clear()
        listener?.let { listeners.add(it) }
    }

    /** 追加监听器（全屏复用播放器时叠加监听，不移除内嵌页的监听） */
    fun addListener(listener: VideoPlayerListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: VideoPlayerListener) {
        listeners.remove(listener)
    }

    @OptIn(UnstableApi::class)
    fun attachPlayerView(playerView: PlayerView) {
        this.playerView = playerView
        player?.let {
            playerView.player = it
        }
    }

    fun detachPlayerView() {
        // 清空 PlayerView 的 player 引用，避免旧 View 持有已释放的 player
        playerView?.player = null
        playerView = null
    }

    @OptIn(UnstableApi::class)
    fun play(url: String, startPositionMs: Long = 0) {
        if (isReleased) return
        synchronized(playerLock) {
            if (isReleased) return
            if (player == null) {
                initializePlayer()
            }
            currentUrl = url
            val mediaItem = MediaItem.fromUri(url)
            // 指定起始位置时直接从该处缓冲，避免先从 0 下载再 seek（全屏兜底路径）
            if (startPositionMs > 0) {
                player?.setMediaItem(mediaItem, startPositionMs)
            } else {
                player?.setMediaItem(mediaItem)
            }
            player?.prepare()
            player?.play()
        }
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
        // 幂等释放：多次调用安全，使用同步锁串行化
        synchronized(playerLock) {
            if (isReleased) return
            isReleased = true
            player?.release()
            player = null
            // 清空 PlayerView 引用，避免残留 player
            playerView?.player = null
            playerView = null
            listeners.clear()
            isPlaying = false
            currentPosition = 0
            duration = 0
        }
    }

    /** 保存当前播放位置与播放状态，用于释放前记录，便于之后恢复 */
    fun savePlaybackState() {
        player?.let {
            savedPosition = it.currentPosition
            savedPlayWhenReady = it.playWhenReady
        }
    }

    /** 若存在已保存的播放地址，则重新初始化播放器并从上次位置恢复 */
    @OptIn(UnstableApi::class)
    fun restore(): Boolean {
        if (isReleased) return false
        val url = currentUrl ?: return false
        synchronized(playerLock) {
            if (isReleased) return false
            if (player == null) {
                initializePlayer()
            }
            val mediaItem = MediaItem.fromUri(url)
            player?.setMediaItem(mediaItem)
            player?.prepare()
            if (savedPosition > 0) {
                player?.seekTo(savedPosition)
            }
            player?.playWhenReady = savedPlayWhenReady
        }
        return true
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

        // 复用 ApiClient.sharedOkHttpClient：自动注入 cf_clearance / User-Agent，
        // 在 403 时透明过盾重试。Media3 视频流也走 Cloudflare 防护站点。
        val dataSourceFactory = OkHttpDataSource.Factory(ApiClient.sharedOkHttpClient)

        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        // 应用初始倍速（默认 1.0x；"记住上次倍速"开启时由调用方传入上次值）
        player?.setPlaybackSpeed(playbackSpeed)

        playerView?.let {
            it.player = player
        }

        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                listeners.forEach { it.onPlaybackStateChanged(playbackState) }
                if (playbackState == Player.STATE_READY) {
                    duration = player?.duration ?: 0
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                listeners.forEach { it.onIsPlayingChanged(playing) }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                listeners.forEach { it.onVideoSizeChanged(videoSize.width, videoSize.height) }
            }

            override fun onPlayerError(error: PlaybackException) {
                listeners.forEach {
                    it.onError(error.message ?: "Unknown error")
                }
            }
        })
    }

    fun updateCurrentPosition() {
        currentPosition = player?.currentPosition ?: 0
    }
}
