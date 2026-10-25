package com.rook.novalore

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

// Path and size guards for anything that comes from outside the app (backups, downloads, picked images).

const val COVERS_DIR = "covers"

/** True only for "covers/<plain file name>": no "..", no sub-folders, no absolute paths. */
fun isSafeCoverPath(rel: String?): Boolean {
    if (rel.isNullOrBlank()) return false
    if (rel.contains('\\') || rel.contains('\u0000') || rel.startsWith("/")) return false
    val parts = rel.split('/')
    if (parts.size != 2 || parts[0] != COVERS_DIR) return false
    val name = parts[1]
    return name.isNotBlank() && name != "." && name != ".."
}

/** Resolves a cover path inside [root], or null if it is unsafe or would escape the covers folder. */
fun resolveCover(root: File, rel: String?): File? {
    if (!isSafeCoverPath(rel)) return null
    return try {
        val coversDir = File(root, COVERS_DIR).canonicalFile
        val f = File(root, rel!!).canonicalFile
        if (f.parentFile == coversDir) f else null
    } catch (_: IOException) {
        null
    }
}

/** Copies at most [maxBytes]; throws if the input is larger (guards against zip bombs and huge downloads). */
fun copyLimited(input: InputStream, out: OutputStream, maxBytes: Long): Long {
    val buf = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) throw IOException("Too large")
        out.write(buf, 0, n)
    }
    return total
}

fun readLimited(input: InputStream, maxBytes: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    copyLimited(input, out, maxBytes)
    return out.toByteArray()
}
