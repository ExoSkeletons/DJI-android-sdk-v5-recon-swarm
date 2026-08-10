package com.kcg.dr.utils

import android.content.Context
import android.content.res.Resources
import com.kcg.dr.utils.LocaleUtils.getLocalizedResources
import java.util.Locale

object ResourcesManager {
    var resources: Resources = Resources.getSystem()
    private var _locale: Locale = Locale.getDefault()
    val locale: Locale get() = _locale

    fun setLocale(context: Context, locale: Locale) {
        _locale = locale
        resources = context.getLocalizedResources(locale)
    }
}