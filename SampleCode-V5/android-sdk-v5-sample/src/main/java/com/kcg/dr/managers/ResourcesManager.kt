package com.kcg.dr.managers

import android.app.Application
import android.content.res.Resources
import com.aviadl40.utils.android.LocaleUtils
import com.aviadl40.utils.android.getLocalizedResources

object ResourcesManager {
    private var app: Application? = null

    val resources: Resources
        get() = app?.getLocalizedResources(LocaleUtils.preferred) ?: Resources.getSystem()

    fun init(application: Application) {
        if (app != null) return
        app = application
    }
}