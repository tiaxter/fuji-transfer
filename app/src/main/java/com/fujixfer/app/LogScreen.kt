package com.fujixfer.app

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The chips of the screen: all the lines, one kind, or only the errors. */
private enum class LogFilter(val label: Int, val kind: LogKind?, val errors: Boolean) {
    ALL(R.string.log_all, null, false),
    CAMERA(R.string.camera_label, LogKind.CAMERA, false),
    WIFI(R.string.tab_wifi, LogKind.WIFI, false),
    IMMICH(R.string.tile_immich, LogKind.IMMICH, false),
    APP(R.string.log_app, LogKind.APP, false),
    ERRORS(R.string.log_errors, null, true),
}

/** Lines drawn at once, and lines "Copia" puts on the clipboard (the newest ones). */
private const val SHOWN = 1000
private const val COPIED = 5000

private sealed class LogItem {
    class Day(val label: String) : LogItem()
    class Line(val row: LogRow) : LogItem()
}

private fun dayLabel(ctx: android.content.Context, ts: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = ts }
    val today = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        same(day, today) -> ctx.getString(R.string.today)
        same(day, yesterday) -> ctx.getString(R.string.yesterday)
        else -> SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(ts)).replaceFirstChar { it.uppercase() }
    }
}

/** Day headers only when the lines on screen span more than one day (the table keeps up to two weeks). */
private fun withDays(ctx: android.content.Context, rows: List<LogRow>): List<LogItem> {
    if (rows.isEmpty()) return emptyList()
    val first = dayLabel(ctx, rows.first().ts)
    if (dayLabel(ctx, rows.last().ts) == first) return rows.map { LogItem.Line(it) }
    val out = ArrayList<LogItem>(rows.size + 8)
    var last = ""
    for (r in rows) {
        val d = dayLabel(ctx, r.ts)
        if (d != last) {
            out.add(LogItem.Day(d))
            last = d
        }
        out.add(LogItem.Line(r))
    }
    return out
}

/** The lines as plain text for the clipboard: date and time, the message, details indented. */
private fun asText(rows: List<LogRow>): String {
    val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    return rows.joinToString("\n") { f.format(Date(it.ts)) + (if (it.sub) "   " else " ") + it.message }
}

/**
 * "Registro tecnico" as in the design: a chip per kind of line (and one for the errors), the lines of the SQLite
 * table with a coloured bar and the time, "Copia" (what the chip shows) and "Condividi" (the full text files),
 * the bin at the top empties the log. New lines show up while it is open and the list follows the end unless the
 * user has scrolled up.
 */
@Composable
fun LogScreen(onBack: () -> Unit, onCopy: (String) -> Unit, onShare: () -> Unit, onClear: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var rows by remember { mutableStateOf<List<LogRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    val list = rememberLazyListState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val shown = remember(rows) { withDays(ctx, rows) }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT) }

    // the table is read off the main thread, again each time DebugLog says new lines arrived
    LaunchedEffect(filter) {
        var seen = -1
        while (true) {
            val rev = DebugLog.revision
            if (rev != seen) {
                seen = rev
                rows = withContext(Dispatchers.IO) { DebugLog.rows(filter.kind, filter.errors, SHOWN) }
                loaded = true
            }
            delay(700)
        }
    }
    // follow the end until the user scrolls away from it, and again once he is back there
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { moving -> if (!moving) follow = !list.canScrollForward }
    }
    LaunchedEffect(shown) {
        if (follow && shown.isNotEmpty()) list.scrollToItem(shown.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        // top bar: back, title, the bin
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back), tint = c.onSurface)
            }
            Text(stringResource(R.string.cd_log), Modifier.weight(1f).padding(start = 4.dp), color = c.onSurface, style = type(22, 28), maxLines = 1)
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(c.surfaceContainerHighest)
                    .clickable(role = Role.Button, onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.log_clear), tint = c.onSurfaceVariant)
            }
        }

        // what to show
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (f in LogFilter.entries) {
                val on = f == filter
                FilterChip(
                    stringResource(f.label), on, if (on) R.drawable.ic_check else null, null,
                    onClick = {
                        if (!on) {
                            filter = f
                            follow = true
                        }
                    },
                )
            }
        }

        // the lines
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(28.dp)).background(c.surfaceContainer)) {
            if (loaded && shown.isEmpty()) {
                Text(
                    if (filter == LogFilter.ALL) stringResource(R.string.log_empty)
                    else stringResource(R.string.log_empty_filter, stringResource(filter.label)),
                    Modifier.align(Alignment.Center).padding(32.dp), color = c.onSurfaceVariant, textAlign = TextAlign.Center,
                    style = type(14, 20),
                )
            }
            LazyColumn(
                Modifier.fillMaxSize(), state = list,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown) { item ->
                    when (item) {
                        is LogItem.Day -> Text(
                            item.label, Modifier.padding(top = 4.dp), color = c.primary, style = type(14, 20, medium = true),
                        )
                        is LogItem.Line -> LogLine(item.row, timeFmt.format(Date(item.row.ts)))
                    }
                }
            }
        }

        // copy what is shown, share everything
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            GroupButton(
                stringResource(R.string.copy), true, c.secondaryContainer, c.onSecondaryContainer,
                onClick = {
                    scope.launch {
                        val all = withContext(Dispatchers.IO) { DebugLog.rows(filter.kind, filter.errors, COPIED) }
                        onCopy(asText(all))
                    }
                },
                modifier = Modifier.weight(1f), enabled = rows.isNotEmpty(), icon = R.drawable.ic_copy,
            )
            GroupButton(
                stringResource(R.string.share), false, c.primary, c.onPrimary, onClick = onShare,
                modifier = Modifier.weight(1f), icon = R.drawable.ic_share,
            )
        }
    }
}

/** A line: the bar in the colour of its kind (red for an error), the time, the message. */
@Composable
private fun LogLine(row: LogRow, time: String) {
    val c = MaterialTheme.colorScheme
    val bar = if (row.error) c.error else colorResource(
        when (row.kind) {
            LogKind.CAMERA -> R.color.log_camera
            LogKind.WIFI -> R.color.log_wifi
            LogKind.IMMICH -> R.color.log_immich
            LogKind.APP -> R.color.log_app
        },
    )
    val mono = FontFamily.Monospace
    Row(
        Modifier.fillMaxWidth().drawBehind {
            drawRoundRect(bar, size = Size(4.dp.toPx(), size.height), cornerRadius = CornerRadius(2.dp.toPx()))
        }.padding(start = 16.dp),
    ) {
        Text(time, Modifier.width(88.dp), color = c.onSurfaceVariant, style = type(12, 18).copy(fontFamily = mono))
        Text(
            row.message, Modifier.weight(1f),
            color = when {
                row.error -> c.error
                row.sub -> c.onSurfaceVariant
                else -> c.onSurface
            },
            style = type(13, 18).copy(fontFamily = mono),
        )
    }
}
