package com.widscore.data

import android.content.Context
import java.util.Locale

// Langue app + widgets : anglais par défaut, français en option.
// Application manuelle (sans AppCompatDelegate : aucun intent externe).
object Lang {
    const val EN = "en"
    const val FR = "fr"

    fun current(ctx: Context): String = Prefs.load(ctx).lang

    fun set(ctx: Context, lang: String) {
        val s = Prefs.load(ctx)
        s.lang = if (lang == FR) FR else EN
        Prefs.save(ctx, s)
        applyLocale(ctx)
    }

    fun applySaved(ctx: Context) {
        applyLocale(ctx)
    }

    fun applyLocale(ctx: Context) {
        val locale = localeOf(ctx)
        Locale.setDefault(locale)
        try {
            val res = ctx.resources
            val config = android.content.res.Configuration(res.configuration)
            config.setLocale(locale)
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
        } catch (_: Exception) {}
    }

    fun localeOf(ctx: Context): Locale = Locale(current(ctx))

    // Contexte localisé pour les RemoteViews des widgets (worker/factory).
    fun localizedContext(ctx: Context): Context {
        return try {
            val config = android.content.res.Configuration(ctx.resources.configuration)
            config.setLocale(localeOf(ctx))
            ctx.createConfigurationContext(config)
        } catch (_: Exception) { ctx }
    }
}
