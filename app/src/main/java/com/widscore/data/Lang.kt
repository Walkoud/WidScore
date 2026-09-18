package com.widscore.data

import android.content.Context
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

// Langue app + widgets : anglais par défaut, français en option.
object Lang {
    const val EN = "en"
    const val FR = "fr"

    fun current(ctx: Context): String = Prefs.load(ctx).lang

    fun set(ctx: Context, lang: String) {
        val s = Prefs.load(ctx)
        s.lang = if (lang == FR) FR else EN
        Prefs.save(ctx, s)
        apply(lang)
    }

    fun apply(lang: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(Locale(lang)))
    }

    fun applySaved(ctx: Context) {
        apply(current(ctx))
    }

    fun localeOf(ctx: Context): Locale = Locale(current(ctx))

    // Contexte localisé pour les RemoteViews des widgets (worker/factory).
    fun localizedContext(ctx: Context): Context {
        return try {
            val config = android.content.res.Configuration(ctx.resources.configuration)
            config.setLocale(localeOf(ctx))
            config.setLocales(LocaleList(localeOf(ctx)))
            ctx.createConfigurationContext(config)
        } catch (_: Exception) { ctx }
    }
}
