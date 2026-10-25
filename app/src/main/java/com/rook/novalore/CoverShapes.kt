package com.rook.novalore

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The three frame shapes a cover can get: tall (books, posters, most game cases), square (CD-case games, albums,
 * some manga) and wide (landscape game boxes, banners). Each image snaps to the nearest one.
 */
enum class CoverShape(val aspect: Float) {
    TALL(2f / 3f), SQUARE(1f), WIDE(1.4f);

    companion object {
        /** Width ÷ height → shape. The cut-offs sit halfway (by ratio) between the three shapes. */
        fun of(aspect: Float): CoverShape = when {
            aspect <= 0f -> TALL
            aspect < 0.82f -> TALL
            aspect <= 1.18f -> SQUARE
            else -> WIDE
        }
    }
}

/**
 * Remembers each cover file's shape. Measuring only reads the image header, so it's quick, and it's done in the
 * background; screens show the tall shape until a cover has been measured, then settle.
 */
object CoverShapes {
    private val shapes = mutableStateMapOf<String, CoverShape>()

    private fun key(file: File) = "${file.absolutePath}@${file.lastModified()}"

    operator fun get(file: File): CoverShape? = shapes[key(file)]

    /** Reads the image size from disk. Call off the main thread. */
    fun measure(file: File): CoverShape? {
        shapes[key(file)]?.let { return it }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        return CoverShape.of(o.outWidth.toFloat() / o.outHeight).also { shapes[key(file)] = it }
    }
}

/** The frame shape for a work's cover (its own, or the one it borrows from what it's part of). */
@Composable
fun coverShape(data: StoreData, work: Work): CoverShape {
    val context = LocalContext.current
    val path = work.imagePath ?: data.inheritedCover(work)
    val file = remember(path) { path?.let { resolveCover(context.filesDir, it) } }
    LaunchedEffect(file) {
        if (file != null && CoverShapes[file] == null) withContext(Dispatchers.IO) { CoverShapes.measure(file) }
    }
    return file?.let { CoverShapes[it] } ?: CoverShape.TALL
}

/** A small cover in a list: always [height] tall, as wide as its shape needs. */
@Composable
fun CoverThumb(data: StoreData, work: Work, height: Dp, modifier: Modifier = Modifier) {
    val shape = coverShape(data, work)
    Box(modifier.height(height).width(height * shape.aspect).clip(RoundedCornerShape(4.dp))) {
        CoverArt(work, Modifier.fillMaxSize(), fallbackPath = data.inheritedCover(work))
    }
}
