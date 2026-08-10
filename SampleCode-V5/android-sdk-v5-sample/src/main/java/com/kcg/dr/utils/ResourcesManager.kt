package com.kcg.dr.utils

import android.content.Context
import android.content.res.Resources
import com.kcg.dr.utils.LocaleUtils.getLocalizedResources
import java.util.Locale

object ResourcesManager {
    var resources: Resources = Resources.getSystem()
    private var _locale: Locale? = null
    val locale: Locale get() = _locale ?: Locale.getDefault()

    fun setLocale(context: Context, locale: Locale? = null) {
        _locale = locale
        resources = with(context.applicationContext) {
            locale?.let { getLocalizedResources(it) } ?: resources
        }
    }
}