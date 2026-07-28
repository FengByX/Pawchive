package com.pawchive.ui.post

import android.app.Dialog
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.pawchive.R

class FullscreenVideoDialog : DialogFragment() {

    /**
     * 全屏退出回调：把退出时的播放位置与是否在播放状态传回宿主，
     * 宿主可据此恢复原视频播放（避免双 ExoPlayer 同时存在 + 退出后不恢复）。
     */
    interface FullscreenVideoListener {
        fun onFullscreenClosed(position: Long, isPlaying: Boolean)
    }

    private var listener: FullscreenVideoListener? = null

    fun setListener(l: FullscreenVideoListener) {
        listener = l
    }

    private lateinit var playerView: PlayerView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnFullscreen: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var seekbarVideo: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvDuration: TextView

    private var videoUrl: String = ""
    private var videoTitle: String = ""
    private var currentPosition: Long = 0
    private var isPlaying: Boolean = false
    // 标记是否已在首次 STATE_READY 时 seek 到进入全屏的位置，避免缓冲恢复时反复 seek
    private var hasSeekedToInitial: Boolean = false
    // 进入全屏前 Activity 的方向，退出时恢复（Bug 17）
    private var previousOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private var videoPlayerManager: VideoPlayerManager? = null

    companion object {
        fun newInstance(url: String, title: String, position: Long, isPlaying: Boolean): FullscreenVideoDialog {
            val fragment = FullscreenVideoDialog()
            val args = Bundle().apply {
                putString("url", url)
                putString("title", title)
                putLong("position", position)
                putBoolean("isPlaying", isPlaying)
            }
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            videoUrl = it.getString("url", "")
            videoTitle = it.getString("title", "")
            currentPosition = it.getLong("position", 0)
            isPlaying = it.getBoolean("isPlaying", false)
        }
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_fullscreen_video, container, false)

        playerView = view.findViewById(R.id.player_view)
        btnPlayPause = view.findViewById(R.id.btn_play_pause)
        btnFullscreen = view.findViewById(R.id.btn_fullscreen)
        btnClose = view.findViewById(R.id.btn_close_video)
        seekbarVideo = view.findViewById(R.id.seekbar_video)
        tvCurrentTime = view.findViewById(R.id.tv_current_time)
        tvDuration = view.findViewById(R.id.tv_duration)

        setupVideoPlayer()
        setupListeners()

        return view
    }

    @OptIn(UnstableApi::class)
    private fun setupVideoPlayer() {
        videoPlayerManager = VideoPlayerManager(requireContext())
        videoPlayerManager?.attachPlayerView(playerView)
        videoPlayerManager?.setListener(object : VideoPlayerManager.VideoPlayerListener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        val duration = videoPlayerManager?.duration ?: 0
                        if (duration > 0) {
                            seekbarVideo.max = duration.toInt()
                            tvDuration.text = videoPlayerManager?.formatTime(duration)
                        }
                        // 关键修复：STATE_READY 在缓冲恢复后会多次触发，
                        // 旧实现每次都 seekTo(currentPosition)，导致用户拖动进度条后
                        // 缓冲结束就被强行拉回进入全屏时的位置，无法正常跳转。
                        // 改为仅在首次 READY 时 seek 一次。
                        if (!hasSeekedToInitial && currentPosition > 0) {
                            videoPlayerManager?.seekTo(currentPosition)
                            hasSeekedToInitial = true
                        }
                        if (isPlaying) {
                            videoPlayerManager?.resume()
                        }
                    }
                    Player.STATE_ENDED -> {
                        btnPlayPause.setImageResource(R.drawable.ic_play)
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            }

            override fun onVideoSizeChanged(width: Int, height: Int) {}

            override fun onError(message: String) {
                Toast.makeText(context, "${getString(R.string.video_play_failed)}: $message", Toast.LENGTH_SHORT).show()
            }
        })
        videoPlayerManager?.play(videoUrl)
    }

    private fun setupListeners() {
        btnPlayPause.setOnClickListener {
            if (videoPlayerManager?.isPlaying == true) {
                videoPlayerManager?.pause()
            } else {
                videoPlayerManager?.resume()
            }
        }

        btnClose.setOnClickListener {
            dismiss()
        }

        btnFullscreen.setOnClickListener {
            dismiss()
        }

        seekbarVideo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCurrentTime.text = videoPlayerManager?.formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                videoPlayerManager?.seekTo(progress.toLong())
                tvCurrentTime.text = videoPlayerManager?.formatTime(progress.toLong())
            }
        })

        playerView.setOnClickListener {
            val controls = view?.findViewById<View>(R.id.video_controller_bar)
            controls?.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    override fun onStart() {
        super.onStart()
        // 进入全屏：记录原方向 → 切横屏；退出时恢复为原方向，
        // 旧实现无条件改为竖屏，导致平板/横屏默认设备退出全屏后整个 App 被强制改为竖屏。
        previousOrientation = requireActivity().requestedOrientation
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requireActivity().window.insetsController?.hide(
                android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    override fun onStop() {
        super.onStop()
        // 恢复进入全屏前的方向，避免强制竖屏
        requireActivity().requestedOrientation = previousOrientation

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requireActivity().window.insetsController?.show(
                android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun onDestroyView() {
        // 退出全屏前把当前位置与播放状态回调给宿主，宿主据此恢复原视频播放
        val position = videoPlayerManager?.currentPosition ?: 0L
        val playing = videoPlayerManager?.isPlaying ?: false
        listener?.onFullscreenClosed(position, playing)
        listener = null

        videoPlayerManager?.release()
        videoPlayerManager = null
        super.onDestroyView()
    }
}