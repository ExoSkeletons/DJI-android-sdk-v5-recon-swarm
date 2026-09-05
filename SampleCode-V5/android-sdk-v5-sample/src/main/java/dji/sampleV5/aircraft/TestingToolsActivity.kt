package dji.sampleV5.aircraft

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.commit
import androidx.navigation.Navigation
import com.aviad40l.dr.util.LocaleUtils
import com.aviad40l.dr.util.getSupportedLocales
import dji.sampleV5.aircraft.databinding.ActivityTestingToolsBinding
import dji.sampleV5.aircraft.models.MSDKCommonOperateVm
import dji.sampleV5.aircraft.util.DJIToastUtil
import dji.sampleV5.aircraft.util.ToastUtils
import dji.sampleV5.aircraft.views.MSDKInfoFragment
import dji.v5.ux.core.util.ViewUtil

/**
 * Class Description
 *
 * @author Hoker
 * @date 2021/7/23
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
abstract class TestingToolsActivity : AppCompatActivity() {

    protected lateinit var binding: ActivityTestingToolsBinding
    protected val msdkCommonOperateVm: MSDKCommonOperateVm by viewModels()

    private val testToolsVM: TestToolsVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestingToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.decorView.apply {
            systemUiVisibility =
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }

        // 设置Listener防止系统UI获取焦点后进入到非全屏状态
        // ---
        // Set a Listener to prevent the system UI from
        // entering a non-full screen state after gaining focus
        window.decorView.setOnSystemUiVisibilityChangeListener() {
            if (it and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
            }
        }

        loadTitleView()

        DJIToastUtil.dJIToastLD = testToolsVM.djiToastResult
        testToolsVM.djiToastResult.observe(this) { result ->
            result?.msg?.let {
                ToastUtils.showToast(it)
            }
        }

        msdkCommonOperateVm.mainPageInfoList.observe(this) { list ->
            list.iterator().forEach {
                addDestination(it.vavGraphId)
            }
        }

        loadPages()

        setupLocaleSwitcher()
    }

    override fun onResume() {
        super.onResume()
        ViewUtil.setKeepScreen(this, true)
    }

    override fun onPause() {
        super.onPause()
        ViewUtil.setKeepScreen(this, false)
    }

    /**
     * 本activity的NavController，都是基于nav_host_fragment_container的
     * ---
     * The NavController of this activity is based on nav_host_fragment_container
     */
    private fun addDestination(id: Int) {
        val v = Navigation.findNavController(binding.navHostFragmentContainer).navInflater.inflate(id)
        Navigation.findNavController(binding.navHostFragmentContainer).graph.addAll(v)
    }

    override fun onDestroy() {
        super.onDestroy()
        DJIToastUtil.dJIToastLD = null
    }

    private fun setupLocaleSwitcher() {
        val locales = getSupportedLocales(R.xml.locales_config)
        val currentLocale = LocaleUtils.preferred

        binding.langSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            locales.map { it.getDisplayName(currentLocale) }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val selectedIndex = locales.indexOfFirst {
            it.language == currentLocale.language
        }

        binding.tvCurrentLocale.text = currentLocale.toLanguageTag()
        binding.tvSupportedLocales.text = locales.joinToString { it.language }

        if (selectedIndex != -1) {
            binding.langSpinner.setSelection(selectedIndex)
        }

        binding.langSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (pos != AdapterView.INVALID_POSITION && pos != selectedIndex) {
                    val selectedLocale = locales.getOrNull(pos) ?: return
                    if (selectedLocale.language != currentLocale.language) {
                        AppCompatDelegate.setApplicationLocales(
                            LocaleListCompat.create(selectedLocale)
                        )
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    open fun loadTitleView() {
        supportFragmentManager.commit {
            replace(R.id.main_info_fragment_container, MSDKInfoFragment())
        }
    }

    abstract fun loadPages()
}