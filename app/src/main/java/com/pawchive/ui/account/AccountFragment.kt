package com.pawchive.ui.account

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pawchive.BuildConfig
import com.pawchive.R
import com.pawchive.data.github.UpdateChecker
import com.pawchive.data.github.UpdateResult
import com.pawchive.data.repository.AuthRepository
import com.pawchive.databinding.FragmentAccountBinding
import com.pawchive.ui.login.LoginFragment
import com.pawchive.ui.settings.SettingsFragment
import kotlinx.coroutines.launch

class AccountFragment : Fragment() {
    private var _binding: FragmentAccountBinding? = null
    private val binding get() = _binding!!
    private lateinit var authRepository: AuthRepository
    private lateinit var updateChecker: UpdateChecker

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAccountBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        authRepository = AuthRepository(requireContext())
        updateChecker = UpdateChecker(requireContext())

        binding.btnLogin.setOnClickListener {
            (activity as? com.pawchive.ui.MainActivity)?.loadFragment(LoginFragment())
        }

        binding.btnLogout.setOnClickListener {
            performLogout()
        }

        val openSettings = {
            (activity as? com.pawchive.ui.MainActivity)?.loadFragment(SettingsFragment())
        }
        binding.btnSettings.setOnClickListener { openSettings() }
        binding.btnSettingsLoggedOut.setOnClickListener { openSettings() }

        val checkUpdate = { checkForUpdates() }
        binding.btnCheckUpdate.setOnClickListener { checkUpdate() }
        binding.btnCheckUpdateLoggedOut.setOnClickListener { checkUpdate() }
    }

    private fun checkForUpdates() {
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdateLoggedOut.isEnabled = false

        lifecycleScope.launch {
            val result = updateChecker.check(BuildConfig.VERSION_NAME)

            binding.btnCheckUpdate.isEnabled = true
            binding.btnCheckUpdateLoggedOut.isEnabled = true

            when (result) {
                is UpdateResult.UpdateAvailable -> {
                    showUpdateDialog(result)
                }
                is UpdateResult.UpToDate -> {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.update_already_latest),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is UpdateResult.Error -> {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.update_check_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun showUpdateDialog(result: UpdateResult.UpdateAvailable) {
        val notes = if (result.releaseNotes.isNotBlank()) {
            result.releaseNotes
        } else {
            getString(R.string.update_available_title)
        }

        val currentVersionText = getString(R.string.update_current_version, result.currentVersion)
        val latestVersionText = getString(R.string.update_latest_version, result.latestVersion)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.update_available_title)
            .setMessage("$currentVersionText\n$latestVersionText\n\n$notes")
            .setPositiveButton(R.string.update_go_download) { _, _ ->
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(result.downloadUrl))
                startActivity(intent)
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun updateUIForLoginState() {
        if (authRepository.isLoggedIn()) {
            binding.layoutLoggedOut.visibility = View.GONE
            binding.layoutLoggedIn.visibility = View.VISIBLE
            binding.tvUsername.text = authRepository.getUsername()
        } else {
            binding.layoutLoggedOut.visibility = View.VISIBLE
            binding.layoutLoggedIn.visibility = View.GONE
        }
    }

    private fun performLogout() {
        lifecycleScope.launch {
            val result = authRepository.logout()
            if (result.isSuccess) {
                Toast.makeText(requireContext(), getString(R.string.toast_logged_out), Toast.LENGTH_SHORT).show()
                updateUIForLoginState()
                (activity as? com.pawchive.ui.MainActivity)?.updateBottomNavVisibility()
            } else {
                Toast.makeText(
                    requireContext(),
                    "${getString(R.string.logout)}: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUIForLoginState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
