package com.rook.novalore

import androidx.annotation.PluralsRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.abs
import kotlin.math.ln
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** Medium date in the user's locale, e.g. "Oct 6, 2026" in US English. */
fun fmtDate(iso: String): String {
    if (iso.isBlank()) return ""
    return try {
        LocalDate.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    } catch (_: Exception) {
        iso
    }
}

@Composable
fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    LocalContext.current.resources.getQuantityString(id, count, *args)

@Composable
fun formatLabel(format: String): String {
    val info = Formats.of(format)
    // Unknown formats from imports keep their own name rather than showing "Other".
    return if (info.id == format) stringResource(info.label) else format
}

/** "Watched", "Want to play", "Dropped" and so on, worded for the work's kind of media. */
@Composable
fun stateLabel(state: String, format: String): String = stringResource(Words.state(state, Formats.groupOf(format)))

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = Amber, fontSize = 19.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.semantics { heading() }
    )
}

@Composable
fun TitleCard(title: String, author: String, modifier: Modifier = Modifier) {
    val tints = LocalSb.current.cardTints
    val idx = ((title.hashCode() % tints.size) + tints.size) % tints.size
    BoxWithConstraints(
        modifier = modifier.background(tints[idx]),
        contentAlignment = Alignment.Center
    ) {
        if (maxWidth < 72.dp) {
            // Too small for a readable title: show its initials instead ("CO" for The Colour Out of Space).
            Text(
                initials(title),
                color = Ink,
                fontSize = (maxWidth.value * 0.36f).sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        } else {
            Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    title.ifBlank { stringResource(R.string.untitled) },
                    color = Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    author,
                    color = Amber,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private val SMALL_WORDS = setOf("the", "a", "an", "of", "and", "in", "on", "to", "for")

/** Up to two initials from the main words of a title; numbers are kept whole ("S1" for Season 1). */
fun initials(title: String): String {
    val words = title.split(Regex("""[\s:·\-–—]+""")).filter { it.isNotBlank() }
    val main = words.filter { it.lowercase() !in SMALL_WORDS }.ifEmpty { words }
    return main.take(2).joinToString("") { w -> if (w.all(Char::isDigit)) w else w.first().uppercase() }.ifEmpty { "?" }
}

/**
 * Shows the work's cover, falling back to [fallbackPath] (usually the parent collection's cover) and then to a
 * generated title card. [maxDim] keeps list thumbnails small; the detail screen asks for a larger decode.
 * The cover is decorative unless [contentDescription] is given, because the title is always shown next to it.
 */
@Composable
fun CoverArt(
    work: Work,
    modifier: Modifier = Modifier,
    fallbackPath: String? = null,
    maxDim: Int = 360,
    contentDescription: String? = null
) {
    val context = LocalContext.current
    val path = work.imagePath ?: fallbackPath
    val file = remember(path) { path?.let { resolveCover(context.filesDir, it) } }
    var bmp by remember(file, maxDim) {
        mutableStateOf(file?.let { ImageCache.get(ImageCache.keyFor(it, maxDim)) })
    }
    LaunchedEffect(file, maxDim) {
        if (file != null && bmp == null) {
            bmp = withContext(Dispatchers.IO) { ImageCache.load(file, maxDim) }
        }
    }
    val b = bmp
    if (file != null && b != null) {
        FittedImage(b.asImageBitmap(), contentDescription, modifier)
    } else {
        TitleCard(work.title, work.author, if (contentDescription == null) modifier.clearAndSetSemantics { } else modifier)
    }
}

/**
 * True when an image is close enough to its frame's shape to fill it by cropping a little. A square game box or a
 * wide poster in a tall frame is not, and is shown whole instead (see [FittedImage]).
 */
fun fillsFrame(imageAspect: Float, frameAspect: Float): Boolean =
    imageAspect > 0f && frameAspect > 0f && abs(ln(imageAspect / frameAspect)) < 0.18f

/**
 * Fills the frame when the shapes nearly match. Otherwise shows the whole image, on a dimmed, blurred copy of itself
 * so there are no empty bars (the blur needs Android 12; older phones just get the dimmed copy).
 */
@Composable
fun FittedImage(bitmap: ImageBitmap, contentDescription: String?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(Panel), contentAlignment = Alignment.Center) {
        val bounded = constraints.hasBoundedWidth && constraints.hasBoundedHeight && constraints.maxHeight > 0
        val frame = if (bounded) constraints.maxWidth.toFloat() / constraints.maxHeight else 0f
        if (!bounded || fillsFrame(bitmap.width.toFloat() / bitmap.height, frame)) {
            Image(bitmap, contentDescription, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Image(
                bitmap, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(14.dp).alpha(0.35f)
            )
            Image(bitmap, contentDescription, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun RemoteThumb(url: String?, height: Dp, modifier: Modifier = Modifier) {
    var bmp by remember(url) { mutableStateOf(if (url != null) ImageCache.get("remote:$url") else null) }
    LaunchedEffect(url) {
        if (url != null && bmp == null) {
            bmp = withContext(Dispatchers.IO) { ImageCache.loadRemote(url) }
        }
    }
    val b = bmp
    // Results take their image's shape, so you can see whether a cover is tall, square or wide before picking it.
    val shape = b?.let { CoverShape.of(it.width.toFloat() / it.height) } ?: CoverShape.TALL
    val framed = modifier.height(height).width(height * shape.aspect).clip(RoundedCornerShape(4.dp))
    if (b != null) {
        FittedImage(b.asImageBitmap(), null, framed)
    } else {
        Box(framed.background(Panel2), contentAlignment = Alignment.Center) {
            Text("?", color = Faint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ProgressLine(fraction: Float, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        LinearProgressIndicator(
            progress = { fraction },
            color = Amber,
            trackColor = Panel2,
            modifier = Modifier.fillMaxWidth().height(6.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(label, color = Faint, fontSize = 12.sp)
    }
}

/** Non-interactive star row for a rating out of 10 (supports halves). Unchanged look; now readable by TalkBack. */
@Composable
fun StarsRow(rating: Double?, starSize: Dp = 14.dp) {
    if (rating == null) {
        Text(stringResource(R.string.not_rated), color = Faint, fontSize = 12.sp)
        return
    }
    val description = stringResource(R.string.rating_description, formatRating(rating))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics { contentDescription = description }
    ) {
        for (i in 1..10) {
            val frac = when {
                rating >= i -> 1f
                rating >= i - 0.5 -> 0.5f
                else -> 0f
            }
            StarGlyph(frac, starSize)
        }
        Spacer(Modifier.width(6.dp))
        Text(formatRating(rating), color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

fun formatRating(r: Double): String =
    if (r == r.toLong().toDouble()) r.toLong().toString() else String.format("%.1f", r)

@Composable
fun StarGlyph(frac: Float, size: Dp) {
    Box(Modifier.size(size)) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = LocalSb.current.starTrack, modifier = Modifier.fillMaxSize())
        if (frac > 0f) {
            Box(
                Modifier
                    .width(size * frac)
                    .height(size)
                    .clip(RectangleShape)
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = Amber, modifier = Modifier.size(size))
            }
        }
    }
}

/**
 * Interactive 10-star input: tap the left half of a star for .5, the right half for the full star.
 * Same look and behaviour as before. For TalkBack the row is exposed as one adjustable control (swipe up/down)
 * instead of twenty unlabelled tap targets.
 */
@Composable
fun RatingInput(rating: Double?, onChange: (Double?) -> Unit) {
    val label = stringResource(R.string.rating_label)
    val state = if (rating == null) stringResource(R.string.no_rating_yet) else stringResource(R.string.rating_out_of_10, formatRating(rating))
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo((rating ?: 0.0).toFloat(), 0f..10f, steps = 19)
                setProgress { target ->
                    val snapped = ((target * 2).roundToInt() / 2.0).coerceIn(0.0, 10.0)
                    onChange(if (snapped <= 0.0) null else snapped)
                    true
                }
            }
        ) {
            for (i in 1..10) {
                val frac = when {
                    rating == null -> 0f
                    rating >= i -> 1f
                    rating >= i - 0.5 -> 0.5f
                    else -> 0f
                }
                Box(Modifier.size(30.dp)) {
                    StarGlyph(frac, 30.dp)
                    Row(Modifier.fillMaxSize().clearAndSetSemantics { }) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clickable { onChange(i - 0.5) }
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clickable { onChange(i.toDouble()) }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state,
                color = if (rating == null) Faint else Amber,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (rating != null) {
                TextButton(onClick = { onChange(null) }) {
                    Text(stringResource(R.string.clear_rating), color = Dim, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * A row for something inside a collection, show, season or manga on the detail screen. The circle ticks it off
 * as done; the button on the right rates it (showing its stars once rated); tapping the row opens its page.
 */
@Composable
fun ChildRow(data: StoreData, child: Work, onOpen: () -> Unit, onToggleDone: (Boolean) -> Unit, onRate: () -> Unit) {
    val latest = data.latestLog(child.id)
    val done = child.state == States.READ
    val group = Formats.groupOf(child.format)
    val kids = data.childrenOf(child.id)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.open)) { onOpen() }
            .padding(vertical = 4.dp)
    ) {
        if (kids.isEmpty()) {
            val label = stringResource(if (done) R.string.mark_not_done else R.string.mark_done, child.title)
            IconButton(onClick = { onToggleDone(!done) }, modifier = Modifier.size(40.dp).semantics { contentDescription = label }) {
                if (done) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Amber, modifier = Modifier.size(24.dp))
                } else {
                    Box(Modifier.size(20.dp).border(2.dp, Faint, CircleShape))
                }
            }
        } else {
            CoverThumb(data, child, 46.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                numberedTitle(child),
                color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            val sub = if (kids.isNotEmpty()) {
                val r = data.rollupFor(child.id)
                stringResource(Words.doneCount(group), r.read, r.total)
            } else {
                stateLabel(child.state, child.format)
            }
            Text(sub, color = Faint, fontSize = 12.sp)
        }
        val rating = latest?.rating
        val rateLabel = if (rating != null) stringResource(R.string.change_rating_of, child.title, formatRating(rating))
        else stringResource(R.string.rate_this, child.title)
        TextButton(onClick = onRate, modifier = Modifier.semantics { contentDescription = rateLabel }) {
            if (rating != null) {
                Text("★ ${formatRating(rating)}", color = Amber, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            } else {
                Text(stringResource(R.string.rate), color = Amber, fontSize = 13.sp)
            }
        }
    }
}

/** "Episodes", "Seasons", "Stories": the plural used in rating summaries. */
@Composable
fun pluralLabel(format: String): String = when (format) {
    "Short story", "Novelette", "Novella" -> stringResource(R.string.plural_stories)
    "Season" -> stringResource(R.string.plural_seasons)
    "Episode" -> stringResource(R.string.plural_episodes)
    "Volume" -> stringResource(R.string.plural_volumes)
    "Chapter" -> stringResource(R.string.plural_chapters)
    "Issue" -> stringResource(R.string.plural_issues)
    "Movie", "Anime movie", "Documentary" -> stringResource(R.string.plural_movies)
    else -> formatLabel(format)
}

/** "320 pages", "24 episodes" and so on. */
@Composable
fun lengthLabel(unit: ProgressUnit, n: Int): String = when (unit) {
    ProgressUnit.CHAPTERS -> plural(R.plurals.chapters, n, n)
    ProgressUnit.ISSUES -> plural(R.plurals.issues, n, n)
    ProgressUnit.EPISODES -> plural(R.plurals.episodes, n, n)
    else -> plural(R.plurals.pages, n, n)
}

/** A review's date, marked "didn't finish" or "dropped" when it was abandoned. */
@Composable
fun logDate(log: ReadingLog, group: MediaGroup): String {
    val d = fmtDate(log.finishedDate)
    if (!log.dnf) return d
    return stringResource(if (group == MediaGroup.READ) R.string.date_dnf else R.string.date_dropped, d)
}

/** Where [leaf] sits under [root], e.g. "Season 2 › 4. The Hunt". */
fun partName(data: StoreData, root: Work, leaf: Work): String {
    val chain = ArrayList<Work>()
    var cur: Work? = leaf
    var guard = 0
    while (cur != null && cur.id != root.id && guard++ < 8) {
        chain.add(0, cur)
        cur = data.parentOf(cur)
    }
    return chain.joinToString(" › ") { numberedTitle(it) }
}

/** "3. In Perpetuity", but just "Season 1" when the title already carries its number. */
fun numberedTitle(w: Work): String {
    val p = w.position ?: return w.title
    return if (Regex("""\b$p\b""").containsMatchIn(w.title)) w.title else "$p. ${w.title}"
}

/** A title that makes sense out of context: an episode or season also names its show ("Severance · Season 1"). */
fun displayTitle(data: StoreData, w: Work): String {
    if (w.format !in Formats.parts) return w.title
    var top: Work? = data.parentOf(w)
    var guard = 0
    while (top != null && top.format in Formats.parts && guard++ < 6) top = data.parentOf(top)
    return if (top != null) "${top.title} · ${w.title}" else w.title
}
