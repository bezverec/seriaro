package org.seriaro.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.seriaro.core.Language
import org.seriaro.core.NavigationTab
import org.seriaro.core.Settings
import org.seriaro.core.ThemeMode
import org.seriaro.core.ThemeSettings
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
    val accent = key(settings.theme.accentHex) { rememberTextFieldState(settings.theme.accentHex) }
    val url = key(state.credentials.webDavUrl) { rememberTextFieldState(state.credentials.webDavUrl) }
    val username = key(state.credentials.webDavUsername) { rememberTextFieldState(state.credentials.webDavUsername) }
    val password = remember { TextFieldState() }
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
            SettingsField(accent, R.string.accent, enabled)
            ActionButton(R.string.save_accent, enabled && accent.text.toString() != settings.theme.accentHex, primary = true) {
                model.applySettings(settings.copy(theme = ThemeSettings(settings.theme.mode, accent.text.toString())))
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                WebDavLocalNetworkPermission(enabled)
            }
            SettingsField(url, R.string.webdav_url, enabled, keyboardType = KeyboardType.Uri)
            SettingsField(username, R.string.webdav_username, enabled)
            OutlinedSecureTextField(
                state = password,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text(stringResource(R.string.webdav_password)) },
                shape = RectangleShape,
                textObfuscationMode = TextObfuscationMode.Hidden,
            )
            OptionRow {
                ActionButton(R.string.save, enabled && url.text.isNotBlank(), primary = true) {
                    model.saveWebDav(url.text.toString(), username.text.toString(), password.text.toString())
                    password.edit { replace(0, length, "") }
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

private enum class LocalNetworkDialog { RATIONALE, APP_SETTINGS }

@RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
@Composable
private fun WebDavLocalNetworkPermission(enabled: Boolean) {
    val activity = LocalActivity.current ?: return
    val permission = Manifest.permission.ACCESS_LOCAL_NETWORK
    var dialog by rememberSaveable { mutableStateOf<LocalNetworkDialog?>(null) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && !activity.shouldShowRequestPermissionRationale(permission)) {
            dialog = LocalNetworkDialog.APP_SETTINGS
        }
    }
    val openAppSettings = {
        activity.startActivity(
            Intent(
                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.packageName, null),
            ),
        )
    }

    ActionButton(R.string.webdav_local_network, enabled) {
        when {
            ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED ->
                openAppSettings()
            activity.shouldShowRequestPermissionRationale(permission) -> dialog = LocalNetworkDialog.RATIONALE
            else -> requestPermission.launch(permission)
        }
    }
    dialog?.let { step ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.webdav_local_network)) },
            text = { Text(stringResource(R.string.webdav_local_network_hint)) },
            shape = RectangleShape,
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 0.dp,
            confirmButton = {
                val label = when (step) {
                    LocalNetworkDialog.RATIONALE -> R.string.confirm
                    LocalNetworkDialog.APP_SETTINGS -> R.string.webdav_local_network_settings
                }
                ActionButton(label, primary = true) {
                    dialog = null
                    when (step) {
                        LocalNetworkDialog.RATIONALE -> requestPermission.launch(permission)
                        LocalNetworkDialog.APP_SETTINGS -> openAppSettings()
                    }
                }
            },
            dismissButton = { ActionButton(R.string.cancel) { dialog = null } },
        )
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
    state: TextFieldState,
    @StringRes label: Int,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(stringResource(label)) },
        shape = RectangleShape,
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
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
