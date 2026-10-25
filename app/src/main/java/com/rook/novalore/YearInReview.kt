package com.rook.novalore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** Year in review: what was finished, how it was rated, the best of the year, and a picture to share it. */
@Composable
fun YearInReviewScreen(data: StoreData, onOpen: (String) -> Unit) {
    val years = remember(data) { data.reviewYears() }
    if (years.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.year_empty), color = Faint, fontSize = 16.sp, textAlign = TextAlign.Center)
        }
        return
    }
    var year by rememberSaveable { mutableStateOf(years.firstOrNull { it <= LocalDate.now().year } ?: years.first()) }
    val stats = remember(data, year) { data.yearStats(year) }
    val context = LocalContext.current
    val sb = LocalSb.current
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            years.forEach { y ->
                FilterChip(
                    selected = y == year, onClick = { year = y },
                    label = { Text(y.toString(), fontSize = 14.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Panel, labelColor = Dim, selectedContainerColor = Panel2, selectedLabelColor = Amber
                    )
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            BigNumber(stats.finished.toString(), stringResource(R.string.year_finished), Modifier.weight(1f))
            BigNumber(stats.avgRating?.let { "★ " + formatRating(Math.round(it * 10) / 10.0) } ?: "–", stringResource(R.string.year_avg_rating), Modifier.weight(1f))
            BigNumber(stats.rereviews.toString(), stringResource(R.string.year_rereviews), Modifier.weight(1f))
        }
        if (stats.dropped > 0) {
            Spacer(Modifier.height(6.dp))
            Text(plural(R.plurals.year_dropped, stats.dropped, stats.dropped), color = Faint, fontSize = 13.sp)
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle(stringResource(R.string.year_by_type))
        Spacer(Modifier.height(6.dp))
        val maxGroup = stats.byGroup.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        MediaGroup.entries.forEach { g ->
            BarRow(stringResource(Words.group(g)), stats.byGroup[g] ?: 0, maxGroup)
        }
        if (stats.byFormat.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                stats.byFormat.joinToString(" · ") { (f, n) -> "$n ${formatLabelPlain(context, f)}" },
                color = Dim, fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle(stringResource(R.string.year_by_month))
        Spacer(Modifier.height(8.dp))
        MonthChart(stats.months)

        if (stats.topRated.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionTitle(stringResource(R.string.year_top_rated))
            Spacer(Modifier.height(6.dp))
            stats.topRated.forEachIndexed { i, (w, l) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.open)) { onOpen(w.id) }.padding(vertical = 5.dp)
                ) {
                    Text("${i + 1}", color = Amber, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
                    CoverThumb(data, w, 56.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(displayTitle(data, w), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(formatLabel(w.format), w.author).filter { it.isNotBlank() }.joinToString(" · "), color = Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("★ ${formatRating(l.rating!!)}", color = Amber, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (stats.topCreators.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionTitle(stringResource(R.string.year_top_creators))
            Spacer(Modifier.height(6.dp))
            stats.topCreators.forEach { (name, n) ->
                Text(plural(R.plurals.year_creator_count, n, name, n), color = Dim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                scope.launch {
                    val bmp = withContext(Dispatchers.IO) { runCatching { yearCardBitmap(context, data, stats, sb) }.getOrNull() }
                    shareTo(context, context.getString(R.string.year_title, stats.year), yearText(context, data, stats), bmp)
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = AmberFill, contentColor = OnAmber),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.year_share, stats.year))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BigNumber(value: String, label: String, modifier: Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Column(Modifier.padding(12.dp).fillMaxWidth().semantics(mergeDescendants = true) { }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = Amber, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, color = Dim, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun BarRow(label: String, value: Int, max: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clearAndSetSemantics { contentDescription = "$label: $value" }
    ) {
        Text(label, color = Ink, fontSize = 14.sp, modifier = Modifier.width(64.dp))
        Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(Panel2)) {
            if (value > 0) Box(Modifier.fillMaxWidth(value.toFloat() / max).fillMaxHeight().background(AmberFill))
        }
        Text(value.toString(), color = Dim, fontSize = 14.sp, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
    }
}

@Composable
private fun MonthChart(months: IntArray) {
    val max = months.maxOrNull()?.coerceAtLeast(1) ?: 1
    val locale = Locale.getDefault()
    Row(
        Modifier.fillMaxWidth().height(130.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        months.forEachIndexed { i, n ->
            val name = Month.of(i + 1).getDisplayName(TextStyle.SHORT, locale)
            Column(
                Modifier.weight(1f).fillMaxHeight().clearAndSetSemantics { contentDescription = "$name: $n" },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                if (n > 0) Text(n.toString(), color = Dim, fontSize = 10.sp)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.75f * n / max + 0.01f)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (n > 0) AmberFill else Panel2)
                )
                Text(name.take(1), color = Faint, fontSize = 11.sp)
            }
        }
    }
}

private fun formatLabelPlain(context: Context, format: String): String {
    val info = Formats.of(format)
    return if (info.id == format) context.getString(info.label).lowercase() else format
}

/** The post text for a year: totals by kind and the top five. */
fun yearText(context: Context, data: StoreData, s: YearStats): String {
    val r = context.resources
    val lines = mutableListOf(r.getString(R.string.year_title, s.year))
    lines.add(r.getString(R.string.year_text_total, s.finished) + s.avgRating?.let { " · " + r.getString(R.string.year_text_avg, formatRating(Math.round(it * 10) / 10.0)) }.orEmpty())
    val groups = MediaGroup.entries.filter { (s.byGroup[it] ?: 0) > 0 }.joinToString(" · ") { "${r.getString(Words.group(it))}: ${s.byGroup[it]}" }
    if (groups.isNotEmpty()) lines.add(groups)
    if (s.topRated.isNotEmpty()) {
        lines.add("")
        lines.add(r.getString(R.string.year_top_rated))
        s.topRated.forEachIndexed { i, (w, l) -> lines.add("${i + 1}. ${displayTitle(data, w)} ★ ${formatRating(l.rating!!)}") }
    }
    return lines.joinToString("\n")
}

/** A 4:5 summary picture: the year, big totals, a bar per kind of media and the top five. */
fun yearCardBitmap(context: Context, data: StoreData, s: YearStats, sb: SbColors): Bitmap {
    val r = context.resources
    val w = 1080
    val h = 1350
    val pad = 80f
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(sb.bg.toArgb())
    c.drawRoundRect(RectF(36f, 36f, w - 36f, h - 36f), 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sb.panel.toArgb() })

    fun paint(color: androidx.compose.ui.graphics.Color, size: Float, bold: Boolean = false, serif: Boolean = false) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb(); textSize = size
            typeface = Typeface.create(if (serif) Typeface.SERIF else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    var y = pad + 80
    c.drawText(r.getString(R.string.year_title, s.year), pad, y, paint(sb.ink, 76f, bold = true, serif = true))
    y += 110
    // Three big numbers.
    val colW = (w - 2 * pad) / 3
    listOf(
        s.finished.toString() to r.getString(R.string.year_finished),
        (s.avgRating?.let { "★" + formatRating(Math.round(it * 10) / 10.0) } ?: "–") to r.getString(R.string.year_avg_rating),
        s.rereviews.toString() to r.getString(R.string.year_rereviews)
    ).forEachIndexed { i, (value, label) ->
        val x = pad + i * colW
        c.drawText(value, x, y, paint(sb.accent, 84f, bold = true))
        c.drawText(label, x, y + 50, paint(sb.dim, 32f))
    }
    y += 130
    // A bar per kind of media.
    val max = s.byGroup.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sb.panel2.toArgb() }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sb.accentFill.toArgb() }
    val labelPaint = paint(sb.ink, 36f)
    val valuePaint = paint(sb.dim, 36f)
    for (g in MediaGroup.entries) {
        val n = s.byGroup[g] ?: 0
        c.drawText(r.getString(Words.group(g)), pad, y + 30, labelPaint)
        val bx = pad + 150
        val bw = w - pad - 90 - bx
        c.drawRoundRect(RectF(bx, y + 4, bx + bw, y + 36), 10f, 10f, track)
        if (n > 0) c.drawRoundRect(RectF(bx, y + 4, bx + bw * n / max, y + 36), 10f, 10f, fill)
        c.drawText(n.toString(), w - pad - 70, y + 30, valuePaint)
        y += 62
    }
    // Top five.
    if (s.topRated.isNotEmpty()) {
        y += 40
        c.drawText(r.getString(R.string.year_top_rated), pad, y, paint(sb.accent, 40f, bold = true))
        y += 24
        val titlePaint = paint(sb.ink, 38f, bold = true)
        val metaPaint = paint(sb.faint, 30f)
        val starPaint = paint(sb.accent, 38f, bold = true)
        for ((i, pair) in s.topRated.withIndex()) {
            val (work, log) = pair
            y += 70
            val stars = "★ " + formatRating(log.rating!!)
            val starsW = starPaint.measureText(stars)
            c.drawText("${i + 1}", pad, y, starPaint)
            val maxTitleW = w - 2 * pad - 60 - starsW - 20
            val title = android.text.TextUtils.ellipsize(displayTitle(data, work), titlePaint, maxTitleW, android.text.TextUtils.TruncateAt.END).toString()
            c.drawText(title, pad + 60, y, titlePaint)
            c.drawText(stars, w - pad - starsW, y, starPaint)
            val meta = listOf(r.getString(Formats.of(work.format).label), work.author).filter { it.isNotBlank() }.joinToString(" · ")
            c.drawText(android.text.TextUtils.ellipsize(meta, metaPaint, maxTitleW, android.text.TextUtils.TruncateAt.END).toString(), pad + 60, y + 38, metaPaint)
            y += 30
        }
    }
    val brand = paint(sb.accent, 32f, bold = true)
    val brandText = r.getString(R.string.app_name)
    c.drawText(brandText, w - pad - brand.measureText(brandText), h - pad, brand)
    return bmp
}
