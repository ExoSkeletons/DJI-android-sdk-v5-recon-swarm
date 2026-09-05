package com.aviad40l.dr.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.XmlRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

object LocaleUtils {
    val preferred: Locale
        get() = AppCompatDelegate.getApplicationLocales().get(0)
            ?: Locale.getDefault()
}

fun Context.getSupportedLocales(@XmlRes configRes: Int): List<Locale> {
    val locales = mutableListOf<Locale>()
    val parser = resources.getXml(configRes)
    try {
        var eventType = parser.next()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "locale")
                for (i in 0 until parser.attributeCount)
                    if (parser.getAttributeName(i) == "name")
                        locales.add(
                            Locale.forLanguageTag(
                                parser.getAttributeValue(i)
                            )
                        )
            eventType = parser.next()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return locales
}

fun Context.getLocalizedResources(locale: Locale): Resources {
    val context = this
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    config.setLayoutDirection(locale)
    val localizedContext = context.createConfigurationContext(config)
    return localizedContext.resources
}

fun FragmentActivity?.setLocale(locale: Locale) {
    val activity = this
    Locale.setDefault(locale)
    if (activity != null) {
        activity.createConfigurationContext(
            Configuration(activity.baseContext.resources.configuration)
                .apply { setLocale(locale) }
        )
        activity.recreate()
    }
}

fun Fragment.setLocale(locale: Locale) {
    if (locale == Locale.getDefault()) return

    val fragment = this
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

fun Context.setLocale(locale: Locale): Context {
    val context = this
    Locale.setDefault(locale)

    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    config.setLayoutDirection(locale)

    return context.createConfigurationContext(config)
}