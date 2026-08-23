package com.kcg.dr.managers

import android.app.Application
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import com.kcg.dr.utils.getLocalizedResources
import java.util.Locale

object ResourcesManager {
    private lateinit var app: Application

    val resources: Resources get() = app.getLocalizedResources(locale)

    val locale: Locale
        get() = AppCompatDelegate.getApplicationLocales().get(0)
            ?: Locale.getDefault()

    fun init(application: Application) {
        app = application
    }
}