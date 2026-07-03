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
                        if (currentPosition > 0) {
                            videoPlayerManager?.seekTo(currentPosition)
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
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

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
        videoPlayerManager?.release()
        videoPlayerManager = null
        super.onDestroyView()
    }
}