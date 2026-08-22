package com.dataproxy.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.Danger
import com.dataproxy.ui.theme.OutlineSoft
import com.dataproxy.ui.theme.SurfaceLow
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.TextSecondary
import com.dataproxy.ui.theme.Warning
import com.dataproxy.util.AppLog
import com.dataproxy.util.AppLogEntry
import com.dataproxy.util.AppLogLevel

@Composable
fun LogsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val entries by AppLog.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    var followLive by remember { mutableStateOf(true) }
    val clipboard = LocalClipboardManager.current

    // User interaction owns follow mode: scrolling away pauses it; dragging
    // back to the bottom resumes it. Programmatic auto-scroll does not count
    // as a user drag, so new entries continue following while already live.
    LaunchedEffect(isUserDragging, listState.canScrollForward) {
        if (isUserDragging) {
            followLive = !listState.canScrollForward
        } else if (!listState.canScrollForward) {
            followLive = true
        }
    }

    LaunchedEffect(entries.lastOrNull()?.id) {
        if (followLive && entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .padding(top = 4.dp, bottom = 12.dp),
    ) {
        TopBar(
            title = "Logs",
            onBack = onBack,
            action = {
                Row {
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(AppLog.exportText())) },
                        enabled = entries.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = "Copy logs",
                            tint = if (entries.isEmpty()) TextMuted else TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = AppLog::clear, enabled = entries.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = "Clear logs",
                            tint = if (entries.isEmpty()) TextMuted else TextSecondary,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
            },
        )
        Text(
            text = "${if (followLive) "Live" else "Paused"} · ${entries.size}/500 entries · " +
                "stored only until the app exits",
            color = TextMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(SurfaceLow, RoundedCornerShape(14.dp))
                .border(1.dp, OutlineSoft, RoundedCornerShape(14.dp))
                .padding(10.dp),
        ) {
            if (entries.isEmpty()) {
                Text(
                    text = "No logs yet. Start the proxy, then retry the failing connection.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                // A SelectionContainer spanning a live LazyColumn can retain
                // stale selectable IDs while rows are added or recycled. That
                // crashes inside Compose's MultiWidgetSelectionDelegate. The
                // toolbar Copy action remains the reliable way to copy logs.
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(entries, key = AppLogEntry::id) { entry ->
                        LogRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: AppLogEntry) {
    val levelColor: Color = when (entry.level) {
        AppLogLevel.DEBUG -> TextMuted
        AppLogLevel.INFO -> Accent
        AppLogLevel.WARN -> Warning
        AppLogLevel.ERROR -> Danger
    }
    Text(
        text = buildString {
            append(AppLog.formatTime(entry.timestampMillis))
            append(' ')
            append(entry.level.name.first())
            append('/')
            append(entry.tag)
            append(": ")
            append(entry.message)
        },
        color = if (entry.level == AppLogLevel.DEBUG) TextPrimary else levelColor,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        ),
    )
}
