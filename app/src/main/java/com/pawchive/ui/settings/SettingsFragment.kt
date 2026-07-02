package com.pawchive.ui.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.pawchive.R
import com.pawchive.data.SettingsManager
import com.pawchive.databinding.FragmentSettingsBinding
import com.pawchive.ui.MainActivity
import com.google.android.material.button.MaterialButton

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var settingsManager: SettingsManager

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
            // 如果语言没变，不重启
            if (language == currentLang) return@addOnButtonCheckedListener
            settingsManager.setLanguage(language)
            // 通过重启 Activity 应用语言变更，避免 setApplicationLocales 导致的黑屏闪烁
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
