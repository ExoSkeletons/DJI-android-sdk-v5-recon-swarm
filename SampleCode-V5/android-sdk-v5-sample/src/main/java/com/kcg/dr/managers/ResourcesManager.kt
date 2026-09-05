package com.kcg.dr.managers

import android.app.Application
import android.content.res.Resources
import com.aviad40l.dr.util.LocaleUtils
import com.aviad40l.dr.util.getLocalizedResources

object ResourcesManager {
    private var app: Application? = null

    val resources: Resources
        get() = app?.getLocalizedResources(LocaleUtils.preferred) ?: Resources.getSystem()

    fun init(application: Application) {
        if (app != null) return
        app = application
    }
}