package com.kcg.dr.managers

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.res.Resources
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.kcg.dr.utils.LocaleUtils
import com.kcg.dr.utils.getLocalizedResources
import java.util.Locale

object ResourcesManager {
    private var app: Application? = null

    val resources: Resources get() = app?.getLocalizedResources(LocaleUtils.preferred) ?: Resources.getSystem()

    fun init(application: Application) {
        if (app != null) return
        app = application
    }
}