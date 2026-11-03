/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.YtMusicScrobbleEnabledKey
import com.metrolist.music.constants.YtMusicScrobbleLastStatusKey
import com.metrolist.music.constants.YtMusicScrobbleMinListenSecondsKey
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.ytscrobble.YtMusicListenerService
import com.metrolist.music.ytscrobble.YtMusicScrobbler
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtMusicScrobbleSettings(
    navController: NavController
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val (enabled, onEnabledChange) = rememberPreference(
        key = YtMusicScrobbleEnabledKey,
        defaultValue = false
    )

    val (minListenSeconds, onMinListenSecondsChange) = rememberPreference(
        key = YtMusicScrobbleMinListenSecondsKey,
        defaultValue = YtMusicListenerService.DEFAULT_MIN_LISTEN_SECONDS
    )
    var showMinListenDialog by rememberSaveable { mutableStateOf(false) }

    val lastStatus by rememberPreference(YtMusicScrobbleLastStatusKey, "")
    val lastStatusText = lastStatus.split('	', limit = 2).takeIf { it.size == 2 }?.let { (status, track) ->
        val res = when (runCatching { YtMusicScrobbler.Status.valueOf(status) }.getOrNull()) {
            YtMusicScrobbler.Status.DETECTED -> R.string.yt_music_scrobble_status_detected
            YtMusicScrobbler.Status.ADDED -> R.string.yt_music_scrobble_status_added
            YtMusicScrobbler.Status.DUPLICATE -> R.string.yt_music_scrobble_status_duplicate
            YtMusicScrobbler.Status.NO_MATCH -> R.string.yt_music_scrobble_status_no_match
            YtMusicScrobbler.Status.FAILED, null -> R.string.yt_music_scrobble_status_failed
        }
        stringResource(res, track)
    } ?: stringResource(R.string.yt_music_scrobble_status_none)

    var hasAccess by remember { mutableStateOf(YtMusicListenerService.hasNotificationAccess(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = YtMusicListenerService.hasNotificationAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showMinListenDialog) {
        var tempSeconds by remember { mutableIntStateOf(minListenSeconds) }

        DefaultDialog(
            onDismiss = { showMinListenDialog = false },
            buttons = {
                TextButton(onClick = { tempSeconds = YtMusicListenerService.DEFAULT_MIN_LISTEN_SECONDS }) {
                    Text(stringResource(R.string.reset))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { showMinListenDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        onMinListenSecondsChange(tempSeconds)
                        showMinListenDialog = false
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.yt_music_scrobble_min_listen),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Text(
                    text = stringResource(R.string.yt_music_scrobble_min_listen_value, tempSeconds),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Slider(
                    value = tempSeconds.toFloat(),
                    onValueChange = { tempSeconds = (it / 5).roundToInt() * 5 },
                    valueRange = 5f..240f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Material3SettingsGroup(
            title = stringResource(R.string.general),
            items = listOf(
                Material3SettingsItem(
                    title = { Text(stringResource(R.string.yt_music_scrobble_enable)) },
                    description = { Text(stringResource(R.string.yt_music_scrobble_enable_description)) },
                    trailingContent = {
                        Switch(
                            checked = enabled,
                            onCheckedChange = onEnabledChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (enabled) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            }
                        )
                    },
                    icon = painterResource(R.drawable.queue_music),
                    onClick = { onEnabledChange(!enabled) }
                ),
                Material3SettingsItem(
                    title = { Text(stringResource(R.string.yt_music_scrobble_min_listen)) },
                    description = { Text(stringResource(R.string.yt_music_scrobble_min_listen_value, minListenSeconds)) },
                    icon = painterResource(R.drawable.timer),
                    onClick = { showMinListenDialog = true }
                ),
                Material3SettingsItem(
                    title = { Text(stringResource(R.string.yt_music_scrobble_access)) },
                    description = {
                        Text(
                            stringResource(
                                if (hasAccess) R.string.yt_music_scrobble_access_granted
                                else R.string.yt_music_scrobble_access_missing
                            )
                        )
                    },
                    icon = painterResource(if (hasAccess) R.drawable.check else R.drawable.close),
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                ),
                Material3SettingsItem(
                    title = { Text(stringResource(R.string.yt_music_scrobble_last_activity)) },
                    description = { Text(lastStatusText) },
                    icon = painterResource(R.drawable.music_note),
                ),
            )
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.yt_music_scrobble_integration)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        }
    )
}
