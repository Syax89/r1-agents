package dev.r1agents

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

fun uiLanguageChoice(value: String?) = if (value == "en") "en" else "it"

/** Prima di una scelta esplicita l'app segue la lingua del dispositivo (inglese se il dispositivo e' in inglese, altrimenti italiano). */
fun systemUiLanguage(): String = if (java.util.Locale.getDefault().language == "en") "en" else "it"

object AppLanguage {
    fun selected(ctx: Context): String = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("app_language", null)?.let(::uiLanguageChoice) ?: systemUiLanguage()
    fun locale(ctx: Context): Locale = Locale.forLanguageTag(selected(ctx))
    fun forLanguage(base: Context, language: String): Context {
        if (base.resources.configuration.locales[0].language == language) return base
        val config = Configuration().apply { setLocale(Locale.forLanguageTag(language)); setLayoutDirection(Locale.forLanguageTag(language)) }
        return base.createConfigurationContext(config)
    }
    fun wrap(base: Context): Context {
        val language = selected(base)
        return forLanguage(base, language)
    }
}

object UiText {
    fun text(ctx: Context, resource: Int, vararg args: Any?): String {
        val resources = AppLanguage.wrap(ctx).resources
        return if (args.isEmpty()) resources.getString(resource) else resources.getString(resource, *args)
    }
    /** Only for app-owned statuses/errors; never apply to chat messages, names or instructions. */
    fun message(ctx: Context, value: String?): String? {
        value ?: return null
        if (AppLanguage.selected(ctx) != "en") return value
        UiCatalog.exact[value]?.let { return text(ctx, it.resource) }
        for (entry in UiCatalog.formatted) {
            if (!value.startsWith(entry.prefix) || !value.endsWith(entry.suffix)) continue
            val match = entry.pattern.matchEntire(value) ?: continue
            val args = match.groupValues.drop(1).toMutableList()
            // Only these two app-generated errors contain provider metadata, never a user-defined name.
            if (entry.source.startsWith("Manca la chiave API di ") || entry.source.startsWith("Manca l'indirizzo di ")) {
                args.indices.forEach { index -> UiCatalog.exact[args[index]]?.let { args[index] = text(ctx, it.resource) } }
            }
            return text(ctx, entry.resource, *args.toTypedArray())
        }
        // A tool context is arbitrary user data: only translate its known activity prefix.
        val at = value.indexOf(": ")
        if (at > 0) UiCatalog.exact[value.substring(0, at)]?.let { return text(ctx, it.resource) + value.substring(at) }
        return value
    }
    fun owned(ctx: Context, value: String): String = message(ctx, value).orEmpty()
    private val translationCodes = listOf("en", "es", "fr", "de", "pt", "ja", "zh", "ru", "ar", "it")
    fun translationCodeAt(index: Int): String = translationCodes.getOrElse(index) { "en" }
    fun translationLanguage(ctx: Context, stored: String): String {
        val codeIndex = translationCodes.indexOf(stored)
        val italian = AppLanguage.forLanguage(ctx, "it").resources.getStringArray(R.array.translation_language_names)
        val english = AppLanguage.forLanguage(ctx, "en").resources.getStringArray(R.array.translation_language_names)
        val index = if (codeIndex >= 0) codeIndex else italian.indexOf(stored).takeIf { it >= 0 } ?: english.indexOf(stored).takeIf { it >= 0 } ?: 0
        return AppLanguage.wrap(ctx).resources.getStringArray(R.array.translation_language_names)[index]
    }
    fun days(ctx: Context, mask: Int): String = when (mask) {
        0 -> owned(ctx, "una volta")
        127 -> owned(ctx, "ogni giorno")
        31 -> owned(ctx, "lun-ven")
        else -> AppLanguage.wrap(ctx).resources.getStringArray(R.array.weekdays_short).filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString(" ")
    }
}
