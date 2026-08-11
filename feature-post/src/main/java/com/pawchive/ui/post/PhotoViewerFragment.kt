package com.pawchive.ui.post

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class PhotoViewerFragment : Fragment() {

    private var _binding: FragmentPhotoViewerBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var downloadRepository: DownloadRepository

    private var imageUrl: String = ""
    private var imageName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            imageUrl = it.getString(ARG_IMAGE_URL, "")
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

        binding.ivPhoto.load(imageUrl) {
            crossfade(true)
            placeholder(R.drawable.ic_image)
            error(R.drawable.ic_image_off)
        }

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
                // 使用 sharedOkHttpClient：img.pawchive.pw 也可能被 Cloudflare 拦截，
                // sharedOkHttpClient 内置 CF 重试逻辑。
                val okHttpClient = ApiClient.sharedOkHttpClient.newBuilder()
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(imageUrl)
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
            val fileName = "Pawchive_${System.currentTimeMillis()}_${imageName.takeLast(30)}"

            // 统一下载入口：优先用 SAF 树 URI，未配置时回退 MediaStore（P1）
            val target = DownloadRepository.DownloadTarget(
                type = DownloadRepository.DownloadType.IMAGE,
                displayName = fileName,
                mimeType = mimeType
            )
            val (outputStream, mediaUri, requiresFinalize) = downloadRepository.openDownloadStream(target)

            outputStream.use { out ->
                inputStream.use { input -> input.copyTo(out) }
            }
            if (requiresFinalize) downloadRepository.finalizeDownload(mediaUri)

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
        private const val ARG_IMAGE_URL = "image_url"
        private const val ARG_IMAGE_NAME = "image_name"

        fun newInstance(imageUrl: String, imageName: String = "image.jpg"): PhotoViewerFragment {
            return PhotoViewerFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_IMAGE_URL, imageUrl)
                    putString(ARG_IMAGE_NAME, imageName)
                }
            }
        }
    }
}
