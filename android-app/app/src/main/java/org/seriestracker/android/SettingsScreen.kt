package org.seriestracker.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.annotation.StringRes
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.seriestracker.core.Language
import org.seriestracker.core.NavigationTab
import org.seriestracker.core.Settings
import org.seriestracker.core.ThemeMode
import org.seriestracker.core.ThemeSettings
import java.util.Collections

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(
    settings: Settings,
    state: ScreenState,
    model: TrackerViewModel,
    onExport: () -> Unit,
    onImport: (Boolean) -> Unit,
    onUpload: () -> Unit,
    onDownload: (Boolean) -> Unit,
    onNotifications: () -> Unit,
) {
    var accent by rememberSaveable(settings.theme.accentHex) { mutableStateOf(settings.theme.accentHex) }
    var url by rememberSaveable(state.credentials.webDavUrl) { mutableStateOf(state.credentials.webDavUrl) }
    var username by rememberSaveable(state.credentials.webDavUsername) { mutableStateOf(state.credentials.webDavUsername) }
    var password by remember { mutableStateOf("") }
    val enabled = !state.busy
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = screenSpacing,
    ) {
        SectionHeading(R.string.nav_settings)
        BorderedPanel {
            SettingsHeading(R.string.appearance)
            OptionRow {
                ThemeMode.entries.forEach { mode ->
                    ActionButton(mode.title, enabled, settings.theme.mode == mode) {
                        model.applySettings(settings.copy(theme = settings.theme.copy(mode = mode)))
                    }
                }
            }
            SettingsField(accent, { accent = it }, R.string.accent, enabled)
            ActionButton(R.string.save_accent, enabled && accent != settings.theme.accentHex, primary = true) {
                model.applySettings(settings.copy(theme = ThemeSettings(settings.theme.mode, accent)))
            }
            SettingsHeading(R.string.language)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Language.entries.forEach { language ->
                    ActionButton(language.label, enabled, settings.language == language) {
                        model.applySettings(settings.copy(language = language))
                    }
                }
            }
        }
        BorderedPanel {
            SettingsHeading(R.string.navigation_order)
            NavigationOrder(settings.navigationOrder, enabled) { order ->
                model.applySettings(settings.copy(navigationOrder = order))
            }
        }
        BorderedPanel {
            SettingsHeading(R.string.notifications)
            Subtle(stringResource(R.string.notifications_hint))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(R.string.notifications_enabled, enabled, settings.notificationsEnabled) {
                    if (!settings.notificationsEnabled) {
                        model.applySettings(settings.copy(notificationsEnabled = true))
                    }
                }
                ActionButton(R.string.notifications_disabled, enabled, !settings.notificationsEnabled) {
                    if (settings.notificationsEnabled) {
                        model.applySettings(settings.copy(notificationsEnabled = false))
                    }
                }
            }
            ActionButton(R.string.notifications_action, enabled, onClick = onNotifications)
        }
        BorderedPanel {
            SettingsHeading(R.string.backup)
            Subtle(stringResource(R.string.backup_hint))
            ActionButton(R.string.export_file, enabled, onClick = onExport)
            ActionButton(R.string.merge_file, enabled) { onImport(true) }
            ActionButton(R.string.replace_file, enabled) { onImport(false) }
        }
        BorderedPanel {
            SettingsHeading(R.string.webdav)
            Subtle(stringResource(R.string.webdav_hint))
            SettingsField(url, { url = it }, R.string.webdav_url, enabled, keyboardType = KeyboardType.Uri)
            SettingsField(username, { username = it }, R.string.webdav_username, enabled)
            SettingsField(password, { password = it }, R.string.webdav_password, enabled, secret = true)
            OptionRow {
                ActionButton(R.string.save, enabled && url.isNotBlank(), primary = true) {
                    model.saveWebDav(url, username, password)
                    password = ""
                }
                ActionButton(R.string.remove_webdav, enabled && state.credentials.webDavUrl.isNotEmpty(), onClick = model::clearWebDav)
            }
            val configured = enabled && state.credentials.webDavUrl.isNotEmpty()
            ActionButton(R.string.upload_webdav, configured, onClick = onUpload)
            ActionButton(R.string.merge_webdav, configured) { onDownload(true) }
            ActionButton(R.string.replace_webdav, configured) { onDownload(false) }
        }
        BorderedPanel {
            SettingsHeading(R.string.about)
            val uri = LocalUriHandler.current
            val credit = stringResource(R.string.tvmaze_license)
            Subtle(credit)
            ActionButton(R.string.tvmaze_credit) { uri.openUri("https://www.tvmaze.com/") }
            ActionButton(R.string.tvmaze_license_link) { uri.openUri("https://creativecommons.org/licenses/by-sa/4.0/") }
        }
    }
}

@Composable
private fun NavigationOrder(
    tabs: List<NavigationTab>,
    enabled: Boolean,
    onOrderChanged: (List<NavigationTab>) -> Unit,
) {
    tabs.forEachIndexed { index, tab ->
        val label = stringResource(tab.label)
        val move = { destination: Int ->
            val reordered = tabs.toMutableList()
            Collections.swap(reordered, index, destination)
            onOrderChanged(reordered)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f))
            OutlinedIconButton(
                onClick = { move(index - 1) },
                enabled = enabled && index > 0,
                shape = RectangleShape,
            ) {
                Icon(painterResource(R.drawable.ic_move_up), stringResource(R.string.move_tab_up, label))
            }
            OutlinedIconButton(
                onClick = { move(index + 1) },
                enabled = enabled && index < tabs.lastIndex,
                shape = RectangleShape,
            ) {
                Icon(
                    painterResource(R.drawable.ic_move_up),
                    stringResource(R.string.move_tab_down, label),
                    modifier = Modifier.rotate(180f),
                )
            }
        }
    }
}

@Composable
private fun SettingsHeading(title: Int) =
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)

@Composable
private fun OptionRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        content()
    }
}

@Composable
private fun SettingsField(
    value: String,
    change: (String) -> Unit,
    label: Int,
    enabled: Boolean,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = change,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(stringResource(label)) },
        shape = RectangleShape,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}

private val ThemeMode.title: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
        ThemeMode.AMOLED -> R.string.theme_amoled
    }

@get:StringRes
private val Language.label: Int
    get() = when (this) {
        Language.EN -> R.string.language_en
        Language.CS -> R.string.language_cs
        Language.EO -> R.string.language_eo
        Language.KO -> R.string.language_ko
        Language.UK -> R.string.language_uk
        Language.TLH -> R.string.language_tlh
    }
