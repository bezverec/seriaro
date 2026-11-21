package org.seriestracker.android

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import org.seriestracker.core.Language
import org.seriestracker.core.Tracker
import java.time.LocalDate
import java.util.Locale

class TrackerApplication : Application() {
    val tracker by lazy {
        Tracker.open(getDatabasePath("series.sqlite3").also { it.parentFile?.mkdirs() }.absolutePath)
    }
    internal val secrets by lazy { SecretStore(this) }
}

internal fun localDate(): String = LocalDate.now().toString()

internal fun Language.locale(): Locale = when (this) {
    Language.EN -> Locale.US
    Language.CS -> Locale.forLanguageTag("cs-CZ")
    Language.EO -> Locale.forLanguageTag("eo")
    Language.KO -> Locale.KOREA
    Language.UK -> Locale.forLanguageTag("uk-UA")
    Language.TLH -> Locale.forLanguageTag("tlh")
}

internal fun Context.localized(language: Language): Context {
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(language.locale())
    return createConfigurationContext(configuration)
}
