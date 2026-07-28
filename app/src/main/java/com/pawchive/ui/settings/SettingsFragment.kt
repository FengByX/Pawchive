package com.pawchive.ui.settings

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.pawchive.BuildConfig
import com.pawchive.R
import com.pawchive.data.SettingsManager
import com.pawchive.databinding.FragmentSettingsBinding
import com.pawchive.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var settingsManager: SettingsManager

    private val pickDownloadLocation = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val activity = activity ?: return@registerForActivityResult

            try {
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val displayName = getFolderDisplayName(uri)
            settingsManager.setDownloadTreeUri(uri, displayName)
            updateDownloadLocationText()

            Toast.makeText(
                activity,
                getString(R.string.download_location_set_to, displayName),
                Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {
            activity?.let {
                Toast.makeText(
                    it,
                    R.string.file_picker_not_available,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsManager = SettingsManager(requireContext())

        setupBackButton()
        setupToggleButtonColors()
        setupLanguage()
        setupAppearance()
        setupDownloadLocation()
        setupAutoCleanCache()
        setupManualCleanCache()
        setupVersionInfo()
    }

    override fun onResume() {
        super.onResume()
        updateCacheSize()
        updateDownloadLocationText()
    }

    private fun setupBackButton() {
        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupToggleButtonColors() {
        val primaryContainer = getThemeColor(com.google.android.material.R.attr.colorPrimaryContainer)
        val onPrimaryContainer = getThemeColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
        val onSurface = getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val outline = getThemeColor(com.google.android.material.R.attr.colorOutline)

        val toggleGroups = listOf(binding.toggleLanguage, binding.toggleAppearance)
        for (group in toggleGroups) {
            for (i in 0 until group.childCount) {
                val button = group.getChildAt(i) as? MaterialButton ?: continue
                val states = arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked)
                )
                val bgColors = intArrayOf(primaryContainer, android.graphics.Color.TRANSPARENT)
                val textColors = intArrayOf(onPrimaryContainer, onSurface)
                val strokeColors = intArrayOf(primaryContainer, outline)

                button.backgroundTintList = ColorStateList(states, bgColors)
                button.setTextColor(ColorStateList(states, textColors))
                button.strokeColor = ColorStateList(states, strokeColors)
            }
        }
    }

    private fun getThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun setupLanguage() {
        val currentLang = settingsManager.getLanguage()
        when (currentLang) {
            SettingsManager.Language.CHINESE -> binding.toggleLanguage.check(R.id.btn_lang_zh)
            SettingsManager.Language.ENGLISH -> binding.toggleLanguage.check(R.id.btn_lang_en)
            SettingsManager.Language.JAPANESE -> binding.toggleLanguage.check(R.id.btn_lang_ja)
        }

        binding.toggleLanguage.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val language = when (checkedId) {
                R.id.btn_lang_zh -> SettingsManager.Language.CHINESE
                R.id.btn_lang_en -> SettingsManager.Language.ENGLISH
                R.id.btn_lang_ja -> SettingsManager.Language.JAPANESE
                else -> return@addOnButtonCheckedListener
            }
            if (language == currentLang) return@addOnButtonCheckedListener
            settingsManager.setLanguage(language)
            (activity as? MainActivity)?.restartForLanguageChange()
        }
    }

    private fun setupAppearance() {
        val currentAppearance = settingsManager.getAppearance()
        when (currentAppearance) {
            SettingsManager.Appearance.LIGHT -> binding.toggleAppearance.check(R.id.btn_appearance_light)
            SettingsManager.Appearance.DARK -> binding.toggleAppearance.check(R.id.btn_appearance_dark)
            SettingsManager.Appearance.FOLLOW_SYSTEM -> binding.toggleAppearance.check(R.id.btn_appearance_system)
        }

        binding.toggleAppearance.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val appearance = when (checkedId) {
                R.id.btn_appearance_light -> SettingsManager.Appearance.LIGHT
                R.id.btn_appearance_dark -> SettingsManager.Appearance.DARK
                R.id.btn_appearance_system -> SettingsManager.Appearance.FOLLOW_SYSTEM
                else -> return@addOnButtonCheckedListener
            }
            settingsManager.setAppearance(appearance)
        }
    }

    private fun setupDownloadLocation() {
        updateDownloadLocationText()

        binding.btnChangeLocation.setOnClickListener {
            openSystemFilePicker()
        }
    }

    private fun openSystemFilePicker() {
        try {
            pickDownloadLocation.launch(null)
        } catch (_: Exception) {
            Toast.makeText(
                requireContext(),
                R.string.file_picker_not_available,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun getFolderDisplayName(uri: Uri): String {
        try {
            val projection = arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val cursor = requireContext().contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val name = it.getString(0)
                    if (!name.isNullOrEmpty()) return name
                }
            }
        } catch (_: Exception) {}
        val lastSegment = uri.lastPathSegment ?: ""
        val name = lastSegment.substringAfterLast('/')
        return name.ifEmpty { getString(R.string.unknown_folder) }
    }

    private fun updateDownloadLocationText() {
        val name = settingsManager.getDownloadLocationName()
        val uri = settingsManager.getDownloadTreeUri()
        if (uri != null && name.isNotEmpty()) {
            binding.tvDownloadLocation.text = getString(R.string.download_location_label) + name
        } else {
            binding.tvDownloadLocation.text = getString(R.string.download_location_not_set)
        }
    }

    private fun setupAutoCleanCache() {
        binding.switchAutoClean.isChecked = settingsManager.isAutoCleanCacheEnabled()
        binding.switchAutoClean.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.setAutoCleanCacheEnabled(isChecked)
        }
    }

    private fun setupManualCleanCache() {
        updateCacheSize()

        binding.btnCleanCache.setOnClickListener {
            cleanCache()
        }
    }

    private fun updateCacheSize() {
        viewLifecycleOwner.lifecycleScope.launch {
            val size = withContext(Dispatchers.IO) {
                SettingsManager.getCacheSize(requireContext())
            }
            if (size > 0) {
                binding.tvCacheSize.text = getString(
                    R.string.cache_size,
                    SettingsManager.formatSize(size)
                )
            } else {
                binding.tvCacheSize.text = getString(R.string.cache_empty)
            }
        }
    }

    private fun cleanCache() {
        viewLifecycleOwner.lifecycleScope.launch {
            val loadingToast = Toast.makeText(
                requireContext(),
                R.string.cache_cleaning,
                Toast.LENGTH_SHORT
            )
            loadingToast.show()

            withContext(Dispatchers.IO) {
                try {
                    val app = requireActivity().application as? com.pawchive.PawchiveApplication
                    app?.clearCache()

                    val cacheDir = requireContext().cacheDir
                    if (cacheDir.exists()) {
                        cacheDir.deleteRecursively()
                        cacheDir.mkdirs()
                    }
                } catch (_: Exception) {}
            }

            loadingToast.cancel()

            Toast.makeText(
                requireContext(),
                R.string.cache_cleaned,
                Toast.LENGTH_SHORT
            ).show()

            updateCacheSize()
        }
    }

    private fun setupVersionInfo() {
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE
        binding.tvVersion.text = getString(
            R.string.version_format,
            versionName,
            versionCode
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
