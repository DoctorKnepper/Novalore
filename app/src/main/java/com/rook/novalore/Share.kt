package com.rook.novalore

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.sin

// Sharing a review to other apps. The review goes in the post text; the picture is the cover, an optional
// review card (cover, stars and verdict drawn together), or nothing. A spoiler switch leaves the full review out.

private const val SHARE_DIR = "shared"
private const val SHARE_MAX_DIM = 1600

enum class SharePicture { COVER, CARD, NONE }

/** The last choices made in the share dialog, remembered for next time. */
data class ShareOptions(val fullReview: Boolean = true, val picture: SharePicture = SharePicture.COVER) {
    fun save(context: Context) {
        context.getSharedPreferences("share", Context.MODE_PRIVATE).edit()
            .putBoolean("fullReview", fullReview).putString("picture", picture.name).apply()
    }

    companion object {
        fun load(context: Context): ShareOptions {
            val p = context.getSharedPreferences("share", Context.MODE_PRIVATE)
            return ShareOptions(
                fullReview = p.getBoolean("fullReview", true),
                picture = SharePicture.entries.firstOrNull { it.name == p.getString("picture", null) } ?: SharePicture.COVER
            )
        }
    }
}

/** "Season 1 › Severance" style path of what a work is part of, nearest first; empty when standalone. */
private fun ancestry(data: StoreData, work: Work): String {
    val names = ArrayList<String>()
    var cur = data.parentOf(work)
    var guard = 0
    while (cur != null && guard++ < 6) {
        names.add(cur.title)
        cur = data.parentOf(cur)
    }
    return names.reversed().joinToString(" › ")
}

/** The post body: title, creator, what it's part of, rating, verdict, (review) and when. */
fun shareText(context: Context, data: StoreData, work: Work, log: ReadingLog, fullReview: Boolean = true): String {
    val r = context.resources
    val group = Formats.groupOf(work.format)
    val lines = mutableListOf<String>()
    lines.add(if (work.author.isBlank()) work.title else r.getString(R.string.share_title_by, work.title, work.author))
    ancestry(data, work).ifBlank { null }?.let { lines.add(r.getString(R.string.in_parent, it)) }
    log.rating?.let { lines.add(starString(it) + "  " + r.getString(R.string.rating_out_of_10, formatRating(it))) }
    val body = mutableListOf<String>()
    if (log.verdict.isNotBlank()) body.add(log.verdict.trim())
    if (fullReview && log.review.isNotBlank()) body.add(log.review.trim())
    val date = fmtDate(log.finishedDate)
    val footer = when {
        date.isBlank() -> null
        log.dnf -> r.getString(Words.sharedStopped(group), date)
        else -> r.getString(Words.sharedDone(group), date)
    }
    return (listOf(lines.joinToString("\n")) + body + listOfNotNull(footer)).joinToString("\n\n")
}

/** Ten-star line using whole and half stars, e.g. ★★★★★★★½☆☆ for 7.5. */
private fun starString(rating: Double): String {
    val full = rating.toInt().coerceIn(0, 10)
    val half = rating - full >= 0.5 && full < 10
    return "★".repeat(full) + (if (half) "½" else "") + "☆".repeat(10 - full - (if (half) 1 else 0))
}

/** Builds the picture for [options], or null for "no picture". Call off the main thread. */
fun sharePicture(context: Context, data: StoreData, work: Work, log: ReadingLog, sb: SbColors, options: ShareOptions): Bitmap? =
    when (options.picture) {
        SharePicture.NONE -> null
        SharePicture.COVER -> coverBitmap(context, data, work) ?: titleCardBitmap(work, sb)
        SharePicture.CARD -> reviewCardBitmap(context, data, work, log, sb, options.fullReview)
    }

/**
 * Opens the system share sheet. The text is also copied to the clipboard, because some apps (Instagram,
 * Facebook) ignore text handed to them and only take the picture.
 */
suspend fun shareTo(context: Context, subject: String, text: String, picture: Bitmap?) {
    val image = picture?.let {
        withContext(Dispatchers.IO) {
            try {
                writeShareImage(context, it)
            } catch (_: Exception) {
                null
            }
        }
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        if (image != null) {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, image)
            clipData = ClipData.newRawUri(null, image)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            type = "text/plain"
        }
    }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
        ?.setPrimaryClip(ClipData.newPlainText(subject, text))
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_review)))
}

/** Writes the picture into the cache folder and returns a content:// Uri other apps can read. */
private fun writeShareImage(context: Context, bmp: Bitmap): Uri {
    val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() } // only the latest shared picture is kept
    val out = File(dir, "novalore-${System.currentTimeMillis()}.jpg")
    FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
}

private fun coverBitmap(context: Context, data: StoreData, work: Work): Bitmap? =
    data.inheritedCover(work)
        ?.let { resolveCover(context.filesDir, it) }
        ?.takeIf { it.exists() }
        ?.let { decodeForShare(it) }

private fun decodeForShare(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= SHARE_MAX_DIM || bounds.outHeight / (sample * 2) >= SHARE_MAX_DIM) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setAlignment(align).setMaxLines(maxLines.coerceAtLeast(1))
        .setEllipsize(TextUtils.TruncateAt.END).build()

private fun Canvas.drawAt(l: StaticLayout, x: Float, y: Float) {
    save(); translate(x, y); l.draw(this); restore()
}

/** The same generated card the app shows for works without a cover, in the current colours, drawn at share size. */
private fun titleCardBitmap(work: Work, sb: SbColors, w: Int = 1000, h: Int = 1470): Bitmap {
    val tints = sb.cardTints
    val tint = tints[((work.title.hashCode() % tints.size) + tints.size) % tints.size]
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(tint.toArgb())

    val pad = w * 9 / 100
    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = sb.ink.toArgb()
        textSize = w * 0.096f
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = sb.accent.toArgb()
        textSize = w * 0.056f
    }
    val title = layout(work.title, titlePaint, w - 2 * pad, 7, Layout.Alignment.ALIGN_CENTER)
    val author = layout(work.author, authorPaint, w - 2 * pad, 2, Layout.Alignment.ALIGN_CENTER)
    val gap = w * 4 / 100
    var y = ((h - title.height - gap - author.height) / 2).toFloat()
    canvas.drawAt(title, pad.toFloat(), y)
    y += title.height + gap
    canvas.drawAt(author, pad.toFloat(), y)
    return bmp
}

/** A five-pointed star centred on (cx, cy). */
private fun starPath(cx: Float, cy: Float, r: Float): Path = Path().apply {
    for (i in 0 until 10) {
        val radius = if (i % 2 == 0) r else r * 0.45f
        val a = Math.toRadians(-90.0 + i * 36.0)
        val x = cx + (radius * cos(a)).toFloat()
        val y = cy + (radius * sin(a)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** Ten stars with half steps, the same look as the app's rating display. Returns the row's width. */
private fun Canvas.drawStars(rating: Double, x: Float, y: Float, size: Float, sb: SbColors): Float {
    val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sb.starTrack.toArgb() }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sb.accent.toArgb() }
    val step = size * 1.08f
    for (i in 0 until 10) {
        val cx = x + i * step + size / 2
        val cy = y + size / 2
        val p = starPath(cx, cy, size / 2)
        drawPath(p, track)
        val frac = when {
            rating >= i + 1 -> 1f
            rating >= i + 0.5 -> 0.5f
            else -> 0f
        }
        if (frac > 0f) {
            save()
            clipRect(cx - size / 2, cy - size / 2, cx - size / 2 + size * frac, cy + size / 2)
            drawPath(p, fill)
            restore()
        }
    }
    return 10 * step
}

/**
 * A 4:5 picture (the size Instagram and Facebook show best) with the cover, title, creator, stars and verdict, plus
 * as much of the review as fits when [fullReview] is on. Drawn in the app's current colours.
 */
fun reviewCardBitmap(context: Context, data: StoreData, work: Work, log: ReadingLog, sb: SbColors, fullReview: Boolean): Bitmap {
    val r = context.resources
    val w = 1080
    val h = 1350
    val pad = 72f
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(sb.bg.toArgb())
    val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sb.panel.toArgb() }
    c.drawRoundRect(RectF(36f, 36f, w - 36f, h - 36f), 36f, 36f, panel)

    // Cover, top left.
    // The cover frame takes the image's shape: tall, square or wide, the same as in the app.
    val image = coverBitmap(context, data, work)
    val (coverW, coverH) = when (image?.let { CoverShape.of(it.width.toFloat() / it.height) } ?: CoverShape.TALL) {
        CoverShape.TALL -> 330 to 495
        CoverShape.SQUARE -> 400 to 400
        CoverShape.WIDE -> 480 to 343
    }
    val cover = image ?: titleCardBitmap(work, sb, coverW * 2, coverH * 2)
    val dst = RectF(pad, pad, pad + coverW, pad + coverH)
    // Same rule as in the app: crop to fill when the shapes nearly match, else show it whole on a dimmed copy.
    val scale = maxOf(coverW.toFloat() / cover.width, coverH.toFloat() / cover.height)
    val sw = coverW / scale
    val sh = coverH / scale
    val src = android.graphics.Rect(
        ((cover.width - sw) / 2).toInt(), ((cover.height - sh) / 2).toInt(),
        ((cover.width + sw) / 2).toInt(), ((cover.height + sh) / 2).toInt()
    )
    val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
    c.save()
    c.clipPath(Path().apply { addRoundRect(dst, 18f, 18f, Path.Direction.CW) })
    if (fillsFrame(cover.width.toFloat() / cover.height, coverW.toFloat() / coverH)) {
        c.drawBitmap(cover, src, dst, smooth)
    } else {
        c.drawBitmap(cover, src, dst, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 80 })
        val fit = minOf(coverW.toFloat() / cover.width, coverH.toFloat() / cover.height)
        val fw = cover.width * fit
        val fh = cover.height * fit
        val fx = dst.left + (coverW - fw) / 2
        val fy = dst.top + (coverH - fh) / 2
        c.drawBitmap(cover, null, RectF(fx, fy, fx + fw, fy + fh), smooth)
    }
    c.restore()

    // Title block, right of the cover.
    val textX = pad + coverW + 44
    val textW = (w - pad - textX).toInt()
    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = sb.ink.toArgb(); textSize = 58f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    val creatorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = sb.accent.toArgb(); textSize = 38f }
    val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = sb.faint.toArgb(); textSize = 32f }
    var y = pad
    val title = layout(work.title, titlePaint, textW, 5)
    c.drawAt(title, textX, y); y += title.height + 16
    if (work.author.isNotBlank()) {
        val creator = layout(work.author, creatorPaint, textW, 2)
        c.drawAt(creator, textX, y); y += creator.height + 14
    }
    ancestry(data, work).ifBlank { null }?.let {
        val inParent = layout(r.getString(R.string.in_parent, it), metaPaint, textW, 3)
        c.drawAt(inParent, textX, y); y += inParent.height + 10
    }
    val formatText = r.getString(Formats.of(work.format).label) + (work.year?.let { " · $it" } ?: "")
    c.drawAt(layout(formatText, metaPaint, textW, 1), textX, y)

    // Stars and verdict, under the cover.
    y = pad + coverH + 56
    val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = sb.ink.toArgb(); textSize = 44f; typeface = Typeface.DEFAULT_BOLD }
    if (log.rating != null) {
        val starsW = c.drawStars(log.rating!!, pad, y, 58f, sb)
        c.drawText(r.getString(R.string.rating_out_of_10, formatRating(log.rating!!)), pad + starsW + 20, y + 46f, ink)
        y += 100
    }
    val contentW = (w - 2 * pad).toInt()
    if (log.verdict.isNotBlank()) {
        val verdictPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = sb.ink.toArgb(); textSize = 48f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        }
        val verdict = layout("“${log.verdict.trim()}”", verdictPaint, contentW, 3)
        c.drawAt(verdict, pad, y); y += verdict.height + 28
    }

    // Footer first, so the review knows how much room it has.
    val group = Formats.groupOf(work.format)
    val date = fmtDate(log.finishedDate)
    val footerY = h - pad - 34
    if (date.isNotBlank()) {
        val dateText = r.getString(if (log.dnf) Words.sharedStopped(group) else Words.sharedDone(group), date)
        c.drawText(dateText, pad, footerY + 30, metaPaint)
    }
    val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = sb.accent.toArgb(); textSize = 32f; typeface = Typeface.DEFAULT_BOLD }
    val brandText = r.getString(R.string.app_name)
    c.drawText(brandText, w - pad - brand.measureText(brandText), footerY + 30, brand)

    if (fullReview && log.review.isNotBlank()) {
        val reviewPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = sb.dim.toArgb(); textSize = 34f }
        val lineH = reviewPaint.fontSpacing
        val lines = ((footerY - 24 - y) / lineH).toInt()
        if (lines > 0) c.drawAt(layout(log.review.trim(), reviewPaint, contentW, lines), pad, y)
    }
    return bmp
}

/**
 * Choose what to share: the full review or only the rating and verdict (the spoiler switch), and the picture:
 * the cover, a review card, or none. Shows a preview of the picture and remembers the choices.
 */
@Composable
fun ShareReviewDialog(data: StoreData, work: Work, log: ReadingLog, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sb = LocalSb.current
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf(ShareOptions.load(context)) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(options) {
        preview = withContext(Dispatchers.IO) {
            try {
                sharePicture(context, data, work, log, sb, options)
            } catch (_: Exception) {
                null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(stringResource(R.string.share_review), color = Ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().toggleable(
                        value = options.fullReview, role = Role.Switch,
                        onValueChange = { options = options.copy(fullReview = it) }
                    )
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.share_full_review), color = Ink, fontSize = 15.sp)
                        Text(stringResource(R.string.share_full_review_hint), color = Faint, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = options.fullReview, onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedThumbColor = OnAmber, checkedTrackColor = AmberFill)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.share_picture), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                listOf(
                    SharePicture.COVER to R.string.share_picture_cover,
                    SharePicture.CARD to R.string.share_picture_card,
                    SharePicture.NONE to R.string.share_picture_none
                ).forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().selectable(selected = options.picture == value, role = Role.RadioButton) {
                            options = options.copy(picture = value)
                        }
                    ) {
                        RadioButton(
                            selected = options.picture == value, onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = Amber, unselectedColor = Faint)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(label), color = Ink, fontSize = 15.sp, modifier = Modifier.padding(vertical = 10.dp))
                    }
                }
                val p = preview
                if (p != null && options.picture != SharePicture.NONE) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Image(
                            p.asImageBitmap(),
                            contentDescription = stringResource(R.string.share_preview),
                            modifier = Modifier.height(240.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                options.save(context)
                val text = shareText(context, data, work, log, options.fullReview)
                scope.launch {
                    val picture = withContext(Dispatchers.IO) { runCatching { sharePicture(context, data, work, log, sb, options) }.getOrNull() }
                    shareTo(context, work.title, text, picture)
                    onDismiss()
                }
            }) { Text(stringResource(R.string.share_action), color = Amber) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Dim) } }
    )
}
