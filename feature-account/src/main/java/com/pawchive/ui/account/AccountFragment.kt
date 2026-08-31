package com.pawchive.ui.account

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pawchive.common.BuildConfig
import com.pawchive.common.R
import com.pawchive.data.github.UpdateChecker
import com.pawchive.data.github.UpdateResult
import com.pawchive.data.repository.AuthRepository
import com.pawchive.common.databinding.FragmentAccountBinding
import com.pawchive.common.nav.AppNavigator
import com.pawchive.core.error.ErrorMessageHelper
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AccountFragment : Fragment() {
    private var _binding: FragmentAccountBinding? = null
    private val binding get() = _binding!!
    @Inject
    lateinit var authRepository: AuthRepository
    @Inject
    lateinit var updateChecker: UpdateChecker

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

        binding.btnLogin.setOnClickListener {
            (activity as? AppNavigator)?.openLogin()
        }

        binding.btnRegister.setOnClickListener {
            openRegisterPage()
        }

        binding.btnLogout.setOnClickListener {
            performLogout()
        }

        binding.btnSwitchAccount.setOnClickListener {
            showAccountSwitchDialog()
        }

        val openSettings = {
            (activity as? AppNavigator)?.openSettings()
        }
        binding.btnSettings.setOnClickListener { openSettings() }
        binding.btnSettingsLoggedOut.setOnClickListener { openSettings() }

        val checkUpdate = { checkForUpdates() }
        binding.btnCheckUpdate.setOnClickListener { checkUpdate() }
        binding.btnCheckUpdateLoggedOut.setOnClickListener { checkUpdate() }
    }

    private fun openRegisterPage() {
        val registerUrl = "https://pawchive.pw/account/register?location=/artists"
        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.launchUrl(requireContext(), Uri.parse(registerUrl))
        } catch (e: Exception) {
            // 回退到系统浏览器
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(registerUrl))
            startActivity(intent)
        }
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

    fun updateUIForLoginState() {
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
                (activity as? AppNavigator)?.updateBottomNavVisibility()
            } else {
                Toast.makeText(
                    requireContext(),
                    ErrorMessageHelper.getFriendlyMessage(requireContext(), result.exceptionOrNull()),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * 账号切换对话框（FEATURE-003）。
     * 显示已保存的账号列表，切换时清除当前账号本地数据。
     */
    private fun showAccountSwitchDialog() {
        val accounts = authRepository.getSavedAccounts()
        val currentUsername = authRepository.getUsername()
        val otherAccounts = accounts.keys.filter { it != currentUsername }

        if (otherAccounts.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_other_accounts, Toast.LENGTH_SHORT).show()
            return
        }

        val accountArray = otherAccounts.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.switch_account)
            .setItems(accountArray) { _, which ->
                val target = accountArray[which]
                confirmSwitchAccount(target)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmSwitchAccount(targetUsername: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.switch_account)
            .setMessage(R.string.logout_clear_data)
            .setPositiveButton(R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    val result = authRepository.switchAccount(targetUsername)
                    if (result.isSuccess) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.switched_to_account, targetUsername),
                            Toast.LENGTH_SHORT
                        ).show()
                        updateUIForLoginState()
                        (activity as? AppNavigator)?.updateBottomNavVisibility()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            ErrorMessageHelper.getFriendlyMessage(requireContext(), result.exceptionOrNull()),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
