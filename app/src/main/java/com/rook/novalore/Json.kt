package com.rook.novalore

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

const val DATA_VERSION = 3

private fun JSONObject.strOrNull(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifBlank { null }
private fun JSONObject.intOrNull(k: String): Int? = if (!has(k) || isNull(k)) null else optInt(k)
private fun JSONObject.doubleOrNull(k: String): Double? =
    if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }
private fun Any?.orNull(): Any = this ?: JSONObject.NULL

fun StoreData.toJson(): JSONObject {
    val root = JSONObject()
    root.put("version", DATA_VERSION)
    val wa = JSONArray()
    for (w in works) {
        wa.put(
            JSONObject()
                .put("id", w.id)
                .put("title", w.title)
                .put("author", w.author)
                .put("format", w.format)
                .put("year", w.year.orNull())
                .put("imagePath", w.imagePath.orNull())
                .put("favorite", w.favorite)
                .put("state", w.state)
                .put("parentId", w.parentId.orNull())
                .put("position", w.position.orNull())
                .put("pageCount", w.pageCount.orNull())
                .put("pagesRead", w.pagesRead.orNull())
                .put("startedDate", w.startedDate)
        )
    }
    root.put("works", wa)
    val la = JSONArray()
    for (l in logs) {
        la.put(
            JSONObject()
                .put("id", l.id)
                .put("workId", l.workId)
                .put("finishedDate", l.finishedDate)
                .put("readIn", l.readIn)
                .put("verdict", l.verdict)
                .put("review", l.review)
                .put("rating", l.rating.orNull())
                .put("dnf", l.dnf)
        )
    }
    root.put("logs", la)
    return root
}

/** Reads version 1 (the original app), 2 and 3 files. Untrusted input is sanitized before use. */
fun storeDataFromJson(root: JSONObject): StoreData {
    val version = root.optInt("version", 1)
    if (version > DATA_VERSION) throw IllegalArgumentException("Made by a newer Novalore (version $version)")
    val data = StoreData()
    val wa = root.optJSONArray("works") ?: JSONArray()
    for (i in 0 until wa.length()) {
        val o = wa.optJSONObject(i) ?: continue
        data.works.add(
            Work(
                id = o.strOrNull("id") ?: UUID.randomUUID().toString(),
                title = o.optString("title", ""),
                author = o.optString("author", ""),
                format = o.strOrNull("format") ?: "Short story",
                year = o.intOrNull("year"),
                imagePath = o.strOrNull("imagePath"),
                favorite = o.optBoolean("favorite", false),
                state = States.normalize(o.strOrNull("state")),
                parentId = o.strOrNull("parentId"),
                position = o.intOrNull("position"),
                pageCount = o.intOrNull("pageCount"),
                pagesRead = o.intOrNull("pagesRead"),
                startedDate = o.strOrNull("startedDate") ?: ""
            )
        )
    }
    val la = root.optJSONArray("logs") ?: JSONArray()
    for (i in 0 until la.length()) {
        val o = la.optJSONObject(i) ?: continue
        data.logs.add(
            ReadingLog(
                id = o.strOrNull("id") ?: UUID.randomUUID().toString(),
                workId = o.optString("workId", ""),
                finishedDate = o.strOrNull("finishedDate") ?: "",
                readIn = o.strOrNull("readIn") ?: "",
                verdict = o.strOrNull("verdict") ?: "",
                review = o.strOrNull("review") ?: "",
                rating = o.doubleOrNull("rating")?.coerceIn(0.0, 10.0),
                dnf = o.optBoolean("dnf", false)
            )
        )
    }
    // Version 3 added the per-read "didn't finish" flag; work it out for older files.
    if (version < 3) data.inferDnfLogs()
    return data.sanitize()
}
