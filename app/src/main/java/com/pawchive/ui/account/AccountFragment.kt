package com.pawchive.ui.account

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.pawchive.R
import com.pawchive.data.repository.AuthRepository
import com.pawchive.databinding.FragmentAccountBinding
import com.pawchive.ui.login.LoginFragment
import com.pawchive.ui.settings.SettingsFragment
import kotlinx.coroutines.launch

class AccountFragment : Fragment() {
    private var _binding: FragmentAccountBinding? = null
    private val binding get() = _binding!!
    private lateinit var authRepository: AuthRepository

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
    }

    private fun updateUIForLoginState() {
        if (authRepository.isLoggedIn()) {
            // 已登录：显示用户信息卡片
            binding.layoutLoggedOut.visibility = View.GONE
            binding.layoutLoggedIn.visibility = View.VISIBLE
            binding.tvUsername.text = authRepository.getUsername()
        } else {
            // 未登录：显示登录按钮
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
                // 更新底部导航栏
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
        // 每次返回时更新登录状态
        updateUIForLoginState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}