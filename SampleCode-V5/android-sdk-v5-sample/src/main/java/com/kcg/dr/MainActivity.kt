package com.kcg.dr

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.kcg.dr.managers.SFXManager
import com.kcg.dr.managers.TTSManager
import com.kcg.dr.recognition.ReconTTSFragment
import dji.sampleV5.aircraft.R
import java.util.Locale

class MainActivity : AppCompatActivity() {
    // private val voiceFragment = VoiceControlFragment()
    private val reconTtsFragment = ReconTTSFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_demo)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupLocaleSwitcherView()
        setupFragments(savedInstanceState)

        SFXManager.init(this)
        TTSManager.init(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        SFXManager.release()
        TTSManager.release()
    }

    private fun setupFragments(savedInstanceState: Bundle?) {
        val fragMap = mapOf(
            // R.id.nav_voice to voiceFragment,
            R.id.nav_tts to reconTtsFragment,
        )
        // Setup new fragment instances
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.frag, fragMap.values.first())
                .commit()
        }
        // setup fragment navigator
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            val fragment = fragMap.entries.firstOrNull { it.key == item.itemId }?.value
                ?: return@setOnItemSelectedListener false

            supportFragmentManager.beginTransaction()
                .replace(R.id.frag, fragment)
                .commit()
            return@setOnItemSelectedListener true
        }
    }

    private fun setupLocaleSwitcherView() {
        val localeMap: MutableMap<String, Locale> = mutableMapOf(
            getString(R.string.english) to Locale.ENGLISH,
            getString(R.string.hebrew) to Locale("he", "IL")
        )
        val keyList = localeMap.keys.toList()

        val langSpinner = findViewById<Spinner>(R.id.lang_spinner)
        langSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            keyList
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        // Set current lang selection
        val currentLocale = AppCompatDelegate.getApplicationLocales().get(0) ?: Locale.getDefault()
        val selectedIndex = localeMap.values.indexOfFirst {
            it.language == currentLocale?.language
        }
        langSpinner.setSelection(selectedIndex)
        langSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                pos.takeIf {
                    it != AdapterView.INVALID_POSITION && it != selectedIndex
                }?.let {
                    val key = keyList.elementAtOrNull(pos) ?: return@let
                    val selectedLocale = localeMap[key] ?: return@let
                    if (selectedLocale == currentLocale) return@let
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.create(selectedLocale)
                    )
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
}