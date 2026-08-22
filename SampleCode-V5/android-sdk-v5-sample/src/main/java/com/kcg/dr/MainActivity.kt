package com.kcg.dr

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.kcg.dr.recognition.ReconTTSFragment
import com.kcg.dr.utils.LocaleUtils
import com.kcg.dr.managers.SFXManager
import com.kcg.dr.managers.TTSManager
import com.kcg.dr.utils.setLocale
import com.kcg.dr.voice.VoiceControlFragment
import dji.sampleV5.aircraft.R
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val voiceFragment = VoiceControlFragment()
    private val reconTtsFragment = ReconTTSFragment()

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("prefs", MODE_PRIVATE)
        val langCode = prefs.getString(LocaleUtils.LANG_KEY, "en") ?: "en"
        val countryCode = prefs.getString(LocaleUtils.COUNTRY_KEY, "") ?: ""
        val locale = Locale(langCode, countryCode)
        val context = newBase.setLocale(locale)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_demo)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        SFXManager.init(this)
        TTSManager.init(this)

        setupLocaleSwitcherView()
        setupFragments(savedInstanceState)
    }

    override fun onDestroy() {
        super.onDestroy()
        SFXManager.release()
        TTSManager.release()
    }

    private fun setupFragments(savedInstanceState: Bundle?) {
        // Setup new fragment instances
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.frag, voiceFragment)
                .commit()
        }
        // setup fragment navigator
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.nav_voice -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.frag, voiceFragment)
                        .commit()
                    true
                }

                R.id.nav_tts -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.frag, reconTtsFragment)
                        .commit()
                    true
                }

                else -> false
            }
        }
    }

    private fun setupLocaleSwitcherView() {
        val langCodeMap: MutableMap<String, Locale> = mutableMapOf(
            getString(R.string.english) to Locale.ENGLISH,
            getString(R.string.hebrew) to Locale("he", "IL")
        )
        val langSpinner = findViewById<Spinner>(R.id.lang_spinner)
        val langList = langCodeMap.keys.toList()
        val langSpinnerAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            langList
        )
        langSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        langSpinner.adapter = langSpinnerAdapter

        // Set current lang selection
        val sp = getSharedPreferences("prefs", MODE_PRIVATE)
        val currentLang =
            Locale(
                sp.getString(LocaleUtils.LANG_KEY, "en") ?: "en",
                sp.getString(LocaleUtils.COUNTRY_KEY, "") ?: ""
            )
        langSpinner.setSelection(langCodeMap.values.indexOf(currentLang))

        langSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val selectedLang = langCodeMap[langList[pos]]
                if (selectedLang != currentLang) {
                    getSharedPreferences("prefs", MODE_PRIVATE).edit {
                        putString(LocaleUtils.LANG_KEY, selectedLang?.language)
                        putString(LocaleUtils.COUNTRY_KEY, selectedLang?.country)
                    }

                    recreate() // Restart activity to apply locale
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
}