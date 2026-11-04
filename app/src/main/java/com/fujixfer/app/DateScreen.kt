package com.fujixfer.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * "Filtra per data" as in the design (DatePicker): quick ranges, months with a dot on the days that have photos
 * on the camera, tap a first and a last day; "Mostra N foto". Days are "yyyyMMdd" strings.
 *
 * It is not a dialog but a layer of MainActivity (the ComposeView dateArea), shown over the gallery. The layer stays
 * composed while hidden, so each opening brings a new [DateSession]: its id resets the state of the screen.
 */
class DateSession(val id: Int, val initial: Pair<String, String>?, val counts: Map<String, Int>)

/** Photos per day: the listing's days (0x9053) while it runs, the files read otherwise. */
fun dayCounts(): Map<String, Int> {
    val st = TransferState
    val counts = HashMap<String, Int>()
    if (st.listing && st.dayGroups.isNotEmpty()) for (g in st.dayGroups) counts[g.day] = g.count
    else for (o in st.objects) {
        val k = o.captureDate.take(8)
        counts[k] = (counts[k] ?: 0) + 1
    }
    return counts
}

private fun dateKey(c: Calendar): String = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(c.time)

private fun dateKey(year: Int, month: Int, day: Int): String = String.format(Locale.ROOT, "%04d%02d%02d", year, month + 1, day)

private fun short(k: String): String = try {
    SimpleDateFormat("d MMM", Locale.ITALIAN).format(SimpleDateFormat("yyyyMMdd", Locale.ROOT).parse(k)!!).trimEnd('.')
} catch (e: Exception) {
    k
}

/** The quick ranges: the last day out shooting first, then the usual ones; those with no photo in them are left out. */
private fun presetRanges(counts: Map<String, Int>, today: String): List<Pair<String, Pair<String, String>>> {
    val cal = Calendar.getInstance()
    fun daysAgo(n: Int) = dateKey((cal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -n) })
    val monthStart = dateKey((cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) })
    val monthName = SimpleDateFormat("LLLL", Locale.ITALIAN).format(cal.time).replaceFirstChar { it.uppercase() }
    // the photos on the card are often older than "today"
    val lastShot = counts.filterValues { it > 0 }.keys.maxOrNull()
    val ranges = ArrayList<Pair<String, Pair<String, String>>>()
    if (lastShot != null && lastShot != today) ranges.add("Ultimo giorno di scatto · " + short(lastShot) to (lastShot to lastShot))
    ranges.addAll(
        listOf(
            "Oggi" to (today to today), "Ieri" to (daysAgo(1) to daysAgo(1)),
            "Ultimi 7 giorni" to (daysAgo(6) to today), monthName to (monthStart to today),
            "Ultimi 30 giorni" to (daysAgo(29) to today),
        ),
    )
    fun has(r: Pair<String, String>) = counts.any { (k, n) -> n > 0 && k in r.first..r.second }
    return ranges.filter { counts.isEmpty() || has(it.second) } // all of them only while nothing is known
}

/** (year, month 0-11) from the oldest photo to this month. */
private fun monthList(counts: Map<String, Int>, today: String): List<Pair<Int, Int>> {
    val oldest = counts.keys.filter { it.length == 8 && it.all(Char::isDigit) }.minOrNull() ?: today
    var y = oldest.substring(0, 4).toInt()
    var m = oldest.substring(4, 6).toInt() - 1
    val ty = today.substring(0, 4).toInt()
    val tm = today.substring(4, 6).toInt() - 1
    val out = ArrayList<Pair<Int, Int>>()
    while (y < ty || (y == ty && m <= tm)) {
        out.add(y to m)
        m++
        if (m == 12) {
            m = 0
            y++
        }
    }
    if (out.isEmpty()) out.add(ty to tm)
    return out
}

/** @param onApply the range picked (null = no date filter) and the label for the gallery chip. */
@Composable
fun DateScreen(session: DateSession, onApply: (range: Pair<String, String>?, label: String) -> Unit, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val counts = session.counts
    val ini = session.initial
    var start by remember(session.id) { mutableStateOf(ini?.first) }
    var end by remember(session.id) { mutableStateOf(if (ini != null && ini.second != ini.first) ini.second else null) }
    val today = remember(session.id) { dateKey(Calendar.getInstance()) }
    val presets = remember(session.id) { presetRanges(counts, today) }
    val months = remember(session.id) { monthList(counts, today) }

    fun set(a: String?, b: String?) {
        start = a
        end = b
    }

    fun pick(k: String) {
        val s = start
        when {
            s == null || end != null -> set(k, null)
            k < s -> set(k, s)
            k == s -> set(null, null)
            else -> set(s, k)
        }
    }

    val lo = start
    val hi = end ?: start
    var count = 0
    var days = 0
    if (lo != null && hi != null) for ((k, n) in counts) if (k in lo..hi) {
        count += n
        days++
    }

    Column(Modifier.fillMaxSize()) {
        // top bar: close, Azzera
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "Chiudi", tint = c.onSurface)
            }
            Spacer(Modifier.weight(1f))
            FujiButton("Azzera", { set(null, null) }, kind = ButtonKind.Text, height = 40.dp)
        }

        // "Foto scattate" + range in big type
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
            Text("Foto scattate", color = c.onSurfaceVariant, style = type(14, 20, medium = true))
            Text(
                when {
                    lo == null -> "Scegli i giorni"
                    hi == lo -> short(lo)
                    else -> short(lo) + " – " + short(hi!!)
                },
                color = c.onSurface, style = TextStyle(fontSize = 32.sp, lineHeight = 40.sp),
            )
        }

        // quick ranges
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((label, range) in presets) {
                val on = lo == range.first && hi == range.second
                val shape = RoundedCornerShape(8.dp)
                Box(
                    Modifier.height(32.dp).clip(shape)
                        .then(if (on) Modifier.background(c.secondaryContainer) else Modifier.border(1.dp, c.outlineVariant, shape))
                        .clickable(role = Role.Button) { set(range.first, if (range.second == range.first) null else range.second) }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (on) c.onSecondaryContainer else c.onSurfaceVariant, style = type(14, 20, medium = true), maxLines = 1)
                }
            }
        }

        // week days, Monday first, with the divider under them
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 6.dp)) {
            for (w in listOf("L", "M", "M", "G", "V", "S", "D")) {
                Text(w, Modifier.weight(1f), color = c.onSurfaceVariant, textAlign = TextAlign.Center, style = type(12, 16, medium = true))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outlineVariant))

        // months, from the oldest photo to this month; the sheet with the button floats over the bottom
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val listState = rememberLazyListState()
            // this month in view, like the design: once the list has a size (the layer may still be hidden when it is composed)
            val viewportHeight = listState.layoutInfo.viewportSize.height
            LaunchedEffect(session.id, viewportHeight > 0) {
                if (viewportHeight > 0) listState.scrollToItem(months.lastIndex)
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 180.dp)) {
                items(months.size) { i ->
                    MonthBlock(months[i].first, months[i].second, counts, today, lo, hi) { k -> pick(k) }
                }
            }

            // bottom sheet: legend + summary, then the button
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.surfaceContainer)
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(c.primary))
                    Text(
                        "Giorni con foto sulla camera", Modifier.weight(1f).padding(start = 8.dp),
                        color = c.onSurfaceVariant, style = type(14, 20),
                    )
                    Text(
                        if (lo == null) "" else (if (count == 1) "1 foto" else "$count foto") + (if (days > 1) " in $days giorni" else ""),
                        color = c.onSurface, style = type(14, 20, medium = true),
                    )
                }
                FujiButton(
                    when {
                        lo == null -> "Mostra tutte le foto"
                        count == 0 -> "Nessuna foto in questi giorni"
                        else -> "Mostra $count foto"
                    },
                    {
                        if (lo == null || hi == null) onApply(null, "")
                        else onApply(lo to hi, if (lo == hi) short(lo) else short(lo) + " – " + short(hi))
                    },
                    Modifier.padding(top = 12.dp).fillMaxWidth(),
                    kind = ButtonKind.Filled, enabled = lo == null || count > 0,
                )
            }
        }
    }
}

@Composable
private fun MonthBlock(
    year: Int, month: Int, counts: Map<String, Int>, today: String, lo: String?, hi: String?, onPick: (String) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val cal = remember(year, month) { Calendar.getInstance().apply { clear(); set(year, month, 1) } }
    val title = remember(year, month) {
        SimpleDateFormat("LLLL yyyy", Locale.ITALIAN).format(cal.time).replaceFirstChar { it.uppercase() }
    }
    val first = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0
    val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(title, Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), color = c.onSurfaceVariant, style = type(14, 20, medium = true))
        val rows = (first + days + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val n = r * 7 + col - first + 1
                    if (n < 1 || n > days) {
                        Spacer(Modifier.weight(1f).height(46.dp))
                    } else {
                        val key = dateKey(year, month, n)
                        DayCell(key, n, counts[key] ?: 0, key == today, key > today, lo, hi, Modifier.weight(1f)) { onPick(key) }
                    }
                }
            }
        }
    }
}

/**
 * One day of the calendar, drawn like the design: a 40 dp circle (primary at the ends of the range, a ring for
 * today), a tonal band joining the days in the range, a dot under the number when the camera has photos.
 */
@Composable
private fun DayCell(
    key: String, n: Int, photos: Int, isToday: Boolean, future: Boolean, lo: String?, hi: String?, modifier: Modifier, onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val inRange = lo != null && hi != null && key >= lo && key <= hi
    val edge = key == lo || key == hi
    val ranged = lo != null && hi != null && hi > lo
    val desc = "$n" + (if (photos > 0) ", $photos foto" else ", nessuna foto") + (if (isToday) ", oggi" else "")
    Box(
        modifier.height(46.dp).semantics { contentDescription = desc }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !future, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height / 2
            val r = 20.dp.toPx()
            if (ranged && inRange) {
                if (key != lo) drawRect(c.secondaryContainer, Offset(0f, cy - r), Size(cx, 2 * r))
                if (key != hi) drawRect(c.secondaryContainer, Offset(cx, cy - r), Size(size.width - cx, 2 * r))
            }
            if (edge) {
                drawCircle(c.primary, r, Offset(cx, cy))
            } else if (isToday) {
                drawCircle(c.primary, r - 0.5.dp.toPx(), Offset(cx, cy), style = Stroke(1.dp.toPx()))
            }
            if (photos > 0) drawCircle(if (edge) c.onPrimary else c.primary, 2.dp.toPx(), Offset(cx, cy + 9.dp.toPx()))
        }
        Text(
            n.toString(),
            color = when {
                future -> c.outlineVariant
                edge -> c.onPrimary
                inRange -> c.onSecondaryContainer
                else -> c.onSurface
            },
            style = TextStyle(fontSize = 14.sp, fontWeight = if (edge || isToday) FontWeight.Medium else FontWeight.Normal),
        )
    }
}
