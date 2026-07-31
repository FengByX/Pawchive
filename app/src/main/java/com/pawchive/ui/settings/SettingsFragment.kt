package com.pawchive.ui.settings

import android.content.Intent
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
import com.pawchive.data.repository.BlockedCreatorManager
import com.pawchive.databinding.FragmentSettingsBinding
import com.pawchive.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var settingsManager: SettingsManager
    private lateinit var blockedCreatorManager: BlockedCreatorManager

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
        blockedCreatorManager = BlockedCreatorManager.getInstance(requireContext())

        setupBackButton()
        setupToggleButtonColors()
        setupLanguage()
        setupAppearance()
