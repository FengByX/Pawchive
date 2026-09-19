package com.pawchive.ui.post

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pawchive.common.R
import com.pawchive.core.api.ApiClient
import com.pawchive.data.repository.DownloadRepository
import com.pawchive.common.databinding.FragmentPhotoViewerBinding
import com.pawchive.core.error.ErrorMessageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class PhotoViewerFragment : Fragment() {

    private var _binding: FragmentPhotoViewerBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var downloadRepository: DownloadRepository
    @Inject
    lateinit var settingsManager: com.pawchive.core.store.SettingsManager

    /** 候选 URL 链：原图在前、缩略图兜底（由调用方按同一顺序传入）。 */
    private var imageUrls: List<String> = emptyList()
    private var imageName: String = ""

    /** 主图 URL（原图）。加载与保存都以它为准。 */
    private val primaryUrl: String get() = imageUrls.firstOrNull().orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            imageUrls = it.getStringArrayList(ARG_IMAGE_URLS).orEmpty()
            imageName = it.getString(ARG_IMAGE_NAME, "image.jpg")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPhotoViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadImageWithFallback(0)

        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.ivPhoto.setOnTapListener {
            binding.topBar.visibility = if (binding.topBar.visibility == View.VISIBLE) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }

        binding.ivPhoto.setOnLongPressListener {
            showSaveDialog()
        }
    }

    /**
     * 按候选链顺序加载：原图优先，失败逐级回退（末级为缩略图），全失败才显示错误占位。
     *
     * 上游实测（2026-09-19）：`file.pawchive.pw/data/...` 是唯一带动画的来源，
     * 但并非每个帖子都有原图（`has_full=false` 的帖子原图会 404），故必须保留兜底，
     * 否则这类帖子在大图页会直接显示加载失败。
     */
    private fun loadImageWithFallback(index: Int) {
        val url = imageUrls.getOrNull(index) ?: return
        val isLast = index == imageUrls.lastIndex
        binding.ivPhoto.load(url) {
            crossfade(true)
            placeholder(R.drawable.ic_image)
            error(if (isLast) R.drawable.ic_image_off else R.drawable.ic_image)
            listener(
                onError = { _, _ ->
                    if (!isLast) loadImageWithFallback(index + 1)
                }
            )
        }
    }

    private fun showSaveDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.save_image))
            .setMessage(getString(R.string.save_image_confirm))
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                saveImage()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun saveImage() {
        Toast.makeText(requireContext(), getString(R.string.saving_image), Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var response: okhttp3.Response? = null
            try {
                // 使用 downloadOkHttpClient：既带 Cloudflare 拦截/重试逻辑，又不带
                // sharedOkHttpClient 的 60s 总调用超时——原图动辄几十 MB，慢网下
                // 边下边读会被 OkHttp 看门狗在 60s 处掐断，表现为"保存失败"。
                val okHttpClient = ApiClient.downloadOkHttpClient

                // 保存的必须是**原图**：此前调用方传进来的其实是缩略图 URL，
                // 于是"保存图片"实际存下的是缩略图（下面的注释却已假定原图动辄几十 MB）。
                // 本次随候选链一并修正为主图。
                val request = Request.Builder()
                    .url(primaryUrl)
                    .header("Accept", "*/*")
                    .build()

                val networkResponse = okHttpClient.newCall(request).execute()
                response = networkResponse
                if (!networkResponse.isSuccessful) {
                    throw Exception("HTTP ${networkResponse.code}")
                }

                val inputStream = networkResponse.body?.byteStream()
                    ?: throw Exception("Empty response body")

                val contentType = networkResponse.header("Content-Type")
                val mimeType = when {
                    imageName.endsWith(".png", true) -> "image/png"
                    imageName.endsWith(".webp", true) -> "image/webp"
                    imageName.endsWith(".gif", true) -> "image/gif"
                    imageName.endsWith(".bmp", true) -> "image/bmp"
                    contentType != null && contentType.startsWith("image/") -> contentType
                    else -> "image/jpeg"
                }

                saveImageStreamToGallery(inputStream, mimeType)
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        ErrorMessageHelper.getFriendlyMessage(requireContext(), e),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                runCatching { response?.close() }
            }
        }
    }

    private suspend fun saveImageStreamToGallery(inputStream: java.io.InputStream, mimeType: String) {
        try {
            // FEATURE 设置项扩展（批次三）：文件命名格式（原硬编码 Pawchive_时间戳_原名尾，现为可配置默认值）
            val fileName = com.pawchive.core.util.DownloadFileNameFormatter.format(
                settingsManager.getFilenameFormat(),
                originalName = imageName
            )

            // 统一下载入口：优先用 SAF 树 URI，未配置时回退 MediaStore（P1）
            val target = DownloadRepository.DownloadTarget(
                type = DownloadRepository.DownloadType.IMAGE,
                displayName = fileName,
                mimeType = mimeType
            )
            // opened 声明在 try 外，失败时才能拿到 uri 清理半截文件
            val opened = downloadRepository.openDownloadStream(target)
            try {
                opened.outputStream.use { out ->
                    inputStream.copyTo(out)
                }
                // 先关流（use 已关闭）再 finalize，保证落盘后才对系统可见
                if (opened.requiresFinalize) downloadRepository.finalizeDownload(opened.uri)
            } catch (e: Exception) {
                downloadRepository.abandonDownload(opened.uri, opened.isSaf)
                throw e
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.image_saved),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    requireContext(),
                    ErrorMessageHelper.getFriendlyMessage(requireContext(), e),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_IMAGE_URLS = "image_urls"
        private const val ARG_IMAGE_NAME = "image_name"

        /**
         * @param imageUrls 候选 URL 链，**原图在前、缩略图兜底**。
         *   顺序即优先级：显示与保存都从第一个开始，失败才依次回退。
         */
        fun newInstance(imageUrls: List<String>, imageName: String = "image.jpg"): PhotoViewerFragment {
            return PhotoViewerFragment().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_IMAGE_URLS, ArrayList(imageUrls))
                    putString(ARG_IMAGE_NAME, imageName)
                }
            }
        }
    }
}
