/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.playback.PlaybackErrorLog
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackErrorLogScreen(
    navController: NavController
) {
    val context = LocalContext.current
    val entries by PlaybackErrorLog.entries.collectAsState()
    var showClearDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { PlaybackErrorLog.load(context.applicationContext) }
    }

    if (showClearDialog) {
        DefaultDialog(
            onDismiss = { showClearDialog = false },
            buttons = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        PlaybackErrorLog.clear(context.applicationContext)
                        showClearDialog = false
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        ) {
            Text(
                text = stringResource(R.string.playback_error_log_clear_confirm),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp)
            )
        }
    }

    LazyColumn(
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().let {
            PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = it.calculateTopPadding() + 8.dp,
                bottom = it.calculateBottomPadding() + 8.dp,
            )
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (entries.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.playback_error_log_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(entries.asReversed()) { entry ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = entry.toText(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.playback_error_log)) },
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
        },
        actions = {
            IconButton(
                onClick = {
                    val text = entries.asReversed().joinToString("\n\n") { it.toText() }
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, text),
                            null,
                        )
                    )
                },
                onLongClick = {},
                enabled = entries.isNotEmpty(),
            ) {
                Icon(
                    painterResource(R.drawable.share),
                    contentDescription = stringResource(R.string.playback_error_log_share),
                )
            }
            IconButton(
                onClick = { showClearDialog = true },
                onLongClick = {},
                enabled = entries.isNotEmpty(),
            ) {
                Icon(
                    painterResource(R.drawable.delete),
                    contentDescription = stringResource(R.string.playback_error_log_clear),
                )
            }
        }
    )
}
