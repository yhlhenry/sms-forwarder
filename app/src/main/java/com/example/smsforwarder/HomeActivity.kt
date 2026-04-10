package com.example.smsforwarder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.example.smsforwarder.databinding.ActivityHomeBinding
import com.google.android.material.chip.Chip

class HomeActivity : AppCompatActivity() {

    private data class Language(val tag: String, val nativeName: String)

    private val languages = listOf(
        Language("en",    "English"),
        Language("zh-TW", "繁體中文"),
        Language("zh-CN", "简体中文"),
        Language("ja",    "日本語"),
        Language("ko",    "한국어"),
        Language("es",    "Español"),
        Language("fr",    "Français"),
        Language("de",    "Deutsch")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cardTutorial.setOnClickListener {
            startActivity(Intent(this, TutorialActivity::class.java))
        }
        binding.cardApp.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        binding.btnGithub.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.home_github_url))))
        }

        setupLanguageChips(binding)
    }

    private fun setupLanguageChips(binding: ActivityHomeBinding) {
        val currentTag = currentLanguageTag()

        languages.forEach { lang ->
            val chip = Chip(this).apply {
                text = lang.nativeName
                isCheckable = true
                isChecked = (lang.tag == currentTag || (lang.tag == "en" && currentTag == null))
                setOnClickListener {
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(lang.tag)
                    )
                }
            }
            binding.chipGroupLanguage.addView(chip)
        }
    }

    /** Returns the currently selected per-app language tag, or null if using system default. */
    private fun currentLanguageTag(): String? {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return null
        val tag = locales.toLanguageTags()
        // Normalize: "zh-TW" might come back as "zh-Hant-TW", etc.
        return languages.firstOrNull { lang ->
            tag.startsWith(lang.tag, ignoreCase = true) ||
            lang.tag.startsWith(tag.take(2), ignoreCase = true) &&
            tag.contains(lang.tag.substringAfter("-", ""), ignoreCase = true)
        }?.tag ?: tag.take(5).ifBlank { null }
    }
}
