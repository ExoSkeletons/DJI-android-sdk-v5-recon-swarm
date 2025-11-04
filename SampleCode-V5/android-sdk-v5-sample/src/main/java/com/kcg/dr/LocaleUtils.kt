package com.kcg.dr

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import java.util.Locale

object LocaleUtils {
    const val LANG_KEY = "lang"
    const val COUNTRY_KEY = "country"

    fun Context.getLocalizedResources(locale: Locale): Resources {
        val context = this
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        val localizedContext = context.createConfigurationContext(config)
        return localizedContext.resources
    }

    fun setLocale(activity: FragmentActivity?, locale: Locale) {
        Locale.setDefault(locale)
        if (activity != null) {
            activity.createConfigurationContext(
                Configuration(activity.baseContext.resources.configuration)
                    .apply { setLocale(locale) }
            )
            activity.recreate()
        }
    }

    fun setLocale(fragment: Fragment, locale: Locale) {
        if (locale == Locale.getDefault()) return

        val context = fragment.requireContext()
        Locale.setDefault(locale)

        context.createConfigurationContext(
            Configuration(context.resources.configuration)
                .apply { setLocale(locale) }
        )

        // Replace the fragment with a fresh instance using the new context
        val newFragment = fragment::class.java.newInstance()

        fragment.parentFragmentManager.beginTransaction()
            .replace(fragment.id, newFragment)
            .commitAllowingStateLoss()
    }

    fun setLocale(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)

        return context.createConfigurationContext(config)
    }
}