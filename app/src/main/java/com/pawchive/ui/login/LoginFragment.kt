package com.pawchive.ui.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.pawchive.R
import com.pawchive.data.repository.AuthRepository
import com.pawchive.databinding.FragmentLoginBinding
import com.pawchive.ui.favorites.AccountFavoritesFragment
import kotlinx.coroutines.launch

class LoginFragment : Fragment() {
    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
    private lateinit var authRepository: AuthRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        authRepository = AuthRepository(requireContext())

        // 检查是否已登录，更新界面
        updateUIForLoginState()

        binding.btnLogin.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val password = binding.etPassword.text.toString()

            if (username.isEmpty() || password.isEmpty()) {
                binding.tvError.text = getString(R.string.error_username_password_empty)
                binding.tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            performLogin(username, password)
        }

        binding.btnLogout.setOnClickListener {
            performLogout()
        }
    }

    private fun updateUIForLoginState() {
        if (authRepository.isLoggedIn()) {
            // 已登录状态
            binding.tilUsername.visibility = View.GONE
            binding.tilPassword.visibility = View.GONE
            binding.btnLogin.visibility = View.GONE
            binding.tvLoggedInInfo.visibility = View.VISIBLE
            binding.tvUsernameDisplay.visibility = View.VISIBLE
            binding.tvUsernameDisplay.text =
                getString(R.string.username) + ": ${authRepository.getUsername()}"
            binding.btnLogout.visibility = View.VISIBLE
        } else {
            // 未登录状态
            binding.tilUsername.visibility = View.VISIBLE
            binding.tilPassword.visibility = View.VISIBLE
            binding.btnLogin.visibility = View.VISIBLE
            binding.tvLoggedInInfo.visibility = View.GONE
            binding.tvUsernameDisplay.visibility = View.GONE
            binding.btnLogout.visibility = View.GONE
        }
        binding.tvError.visibility = View.GONE
    }

    private fun performLogin(username: String, password: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvError.visibility = View.GONE
        binding.btnLogin.isEnabled = false

        lifecycleScope.launch {
            val result = authRepository.login(username, password)
            binding.progressBar.visibility = View.GONE
            binding.btnLogin.isEnabled = true

            if (result.isSuccess) {
                val user = result.getOrNull()
                Toast.makeText(
                    requireContext(),
                    "登录成功！正在同步收藏...",
                    Toast.LENGTH_LONG
                ).show()
                
                // 立即更新底部导航栏，显示 Bookmarks 按钮
                val mainActivity = activity as? com.pawchive.ui.MainActivity
                mainActivity?.updateBottomNavVisibility()
                
                // 跳转到账号收藏页面，自动同步加载
                mainActivity?.loadFragment(AccountFavoritesFragment())
            } else {
                val error = result.exceptionOrNull()?.message ?: getString(R.string.login_failed)
                binding.tvError.text = error
                binding.tvError.visibility = View.VISIBLE
            }
        }
    }

    private fun performLogout() {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnLogout.isEnabled = false

        lifecycleScope.launch {
            val result = authRepository.logout()
            binding.progressBar.visibility = View.GONE
            binding.btnLogout.isEnabled = true

            if (result.isSuccess) {
                Toast.makeText(requireContext(), getString(R.string.toast_logged_out), Toast.LENGTH_SHORT).show()
                updateUIForLoginState()
                // 清空输入框
                binding.etUsername.text?.clear()
                binding.etPassword.text?.clear()
                // 更新底部导航栏，隐藏 Bookmarks 按钮
                val mainActivity = activity as? com.pawchive.ui.MainActivity
                mainActivity?.updateBottomNavVisibility()
            } else {
                Toast.makeText(
                    requireContext(),
                    "登出失败: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}