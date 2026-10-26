package com.rook.novalore

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LookupCandidate(
    val title: String,
    val author: String,     // author, director, studio, network or developer
    val year: Int?,
    val pageCount: Int?,    // length in the format's unit: pages, episodes or chapters
    val coverUrl: String?,  // full-size image to download if picked
    val thumbUrl: String?,  // small preview for the results list
    val volumes: Int? = null,
    val tvmazeId: Int? = null // lets a show's seasons and episodes be filled in
)

/** [failed] is true when no service could be reached, so the UI can say "offline" instead of "no results". */
class LookupOutcome(val results: List<LookupCandidate>, val failed: Boolean)

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

private const val MAX_DOWNLOAD_BYTES = 15L * 1024 * 1024
private const val USER_AGENT = "Novalore/1.0 (Android; personal review journal)"

/** POSTs a JSON body (for AniList's GraphQL API). Returns the response text, or null on any failure. */
fun httpPostJson(url: String, body: String, timeoutMs: Int = 15000): String? {
    var conn: HttpURLConnection? = null
    return try {
        conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode in 200..299) {
            conn.inputStream.use { readLimited(it, MAX_DOWNLOAD_BYTES) }.toString(Charsets.UTF_8)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    } finally {
        conn?.disconnect()
    }
}

fun httpGetBytes(url: String, timeoutMs: Int = 15000): ByteArray? {
    var conn: HttpURLConnection? = null
    return try {
        conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "*/*")
        if (conn.responseCode in 200..299) {
            conn.inputStream.use { readLimited(it, MAX_DOWNLOAD_BYTES) }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    } finally {
        conn?.disconnect()
    }
}

fun httpGetText(url: String): String? = httpGetBytes(url)?.toString(Charsets.UTF_8)

/** Returns null when the request failed (as opposed to an empty list for "no matches"). */
fun searchOpenLibrary(title: String, author: String): List<LookupCandidate>? {
    val authorPart = if (author.isNotBlank()) "&author=${enc(author)}" else ""
    val url = "https://openlibrary.org/search.json?title=${enc(title)}$authorPart" +
        "&limit=12&fields=title,author_name,first_publish_year,cover_i,number_of_pages_median"
    val text = httpGetText(url) ?: return null
    return try {
        val docs = JSONObject(text).optJSONArray("docs") ?: return emptyList()
        val out = mutableListOf<LookupCandidate>()
        for (i in 0 until docs.length()) {
            val d = docs.optJSONObject(i) ?: continue
            val coverId = if (d.isNull("cover_i")) null else d.optInt("cover_i")
            val authors = d.optJSONArray("author_name")
            val authorStr = if (authors != null && authors.length() > 0) authors.optString(0) else author
            out.add(
                LookupCandidate(
                    title = d.optString("title", title),
                    author = authorStr,
                    year = if (d.isNull("first_publish_year")) null else d.optInt("first_publish_year"),
                    pageCount = if (d.isNull("number_of_pages_median")) null else d.optInt("number_of_pages_median").takeIf { it > 0 },
                    coverUrl = coverId?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" },
                    thumbUrl = coverId?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" }
                )
            )
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

fun searchGoogleBooks(title: String, author: String): List<LookupCandidate>? {
    val q = enc("$title $author".trim())
    val url = "https://www.googleapis.com/books/v1/volumes?q=$q&maxResults=10"
    val text = httpGetText(url) ?: return null
    return try {
        val items = JSONObject(text).optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<LookupCandidate>()
        for (i in 0 until items.length()) {
            val info = items.optJSONObject(i)?.optJSONObject("volumeInfo") ?: continue
            val authors = info.optJSONArray("authors")
            val authorStr = if (authors != null && authors.length() > 0) authors.optString(0) else author
            val year = info.optString("publishedDate", "").take(4).toIntOrNull()
            val links = info.optJSONObject("imageLinks")
            val thumb = links?.optString("thumbnail", "")?.replace("http://", "https://")?.ifBlank { null }
            out.add(
                LookupCandidate(
                    title = info.optString("title", title),
                    author = authorStr,
                    year = year,
                    pageCount = info.optInt("pageCount", 0).takeIf { it > 0 },
                    coverUrl = thumb?.replace("zoom=1", "zoom=0") ?: thumb,
                    thumbUrl = thumb
                )
            )
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/** Open Library first; Google Books is added when Open Library fails or has no covers. */
fun searchBooks(title: String, author: String): LookupOutcome {
    val ol = searchOpenLibrary(title, author)
    if (ol != null && ol.any { it.coverUrl != null }) return LookupOutcome(ol, failed = false)
    val g = searchGoogleBooks(title, author)
    val merged = (ol.orEmpty() + g.orEmpty())
    return LookupOutcome(merged, failed = ol == null && g == null)
}

/** Looks a work up with the service that suits its format. */
fun searchOnline(title: String, author: String, format: String = "Short story"): LookupOutcome {
    val info = Formats.of(format)
    val kind = if (info.lookup != LookupKind.NONE) info.lookup else when (info.group) {
        MediaGroup.READ -> LookupKind.BOOK
        MediaGroup.WATCH -> LookupKind.TV
        MediaGroup.PLAY -> LookupKind.GAME
    }
    return when (kind) {
        LookupKind.BOOK, LookupKind.NONE -> searchBooks(title, author)
        LookupKind.COMIC -> {
            val g = searchGoogleBooks(title, author)
            if (g != null && g.isNotEmpty()) LookupOutcome(g, false) else searchBooks(title, author)
        }
        LookupKind.MANGA -> outcome(searchAniList(title, "MANGA"))
        LookupKind.ANIME -> outcome(searchAniList(title, "ANIME"))
        LookupKind.TV -> outcome(searchTvMaze(title))
        LookupKind.MOVIE -> outcome(searchWikipedia(title, author, if (format == "Documentary") "documentary" else "film"))
        LookupKind.GAME -> outcome(searchWikipedia(title, author, "video game"))
    }
}

private fun outcome(list: List<LookupCandidate>?) = LookupOutcome(list.orEmpty(), failed = list == null)

private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k).ifBlank { null }

/** Anime and manga from AniList: English title when there is one, studio or author, episodes or chapters. */
fun searchAniList(title: String, type: String): List<LookupCandidate>? {
    val query = """
        query (${'$'}q: String, ${'$'}t: MediaType) { Page(perPage: 12) { media(search: ${'$'}q, type: ${'$'}t, sort: SEARCH_MATCH) {
          title { english romaji } episodes chapters volumes seasonYear startDate { year }
          coverImage { extraLarge large } studios(isMain: true) { nodes { name } } staff(perPage: 1) { nodes { name { full } } }
        } } }
    """.trimIndent()
    val body = JSONObject().put("query", query).put("variables", JSONObject().put("q", title).put("t", type)).toString()
    val text = httpPostJson("https://graphql.anilist.co", body) ?: return null
    return try {
        val media = JSONObject(text).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return emptyList()
        (0 until media.length()).mapNotNull { i ->
            val m = media.optJSONObject(i) ?: return@mapNotNull null
            val t = m.optJSONObject("title")
            val name = t?.optStringOrNull("english") ?: t?.optStringOrNull("romaji") ?: return@mapNotNull null
            val studios = m.optJSONObject("studios")?.optJSONArray("nodes")
            val staff = m.optJSONObject("staff")?.optJSONArray("nodes")
            val creator = if (type == "ANIME") studios?.optJSONObject(0)?.optStringOrNull("name")
            else staff?.optJSONObject(0)?.optJSONObject("name")?.optStringOrNull("full")
            val cover = m.optJSONObject("coverImage")
            val length = if (type == "ANIME") m.optInt("episodes", 0) else m.optInt("chapters", 0)
            LookupCandidate(
                title = name,
                author = creator ?: "",
                year = (if (m.isNull("seasonYear")) null else m.optInt("seasonYear"))
                    ?: m.optJSONObject("startDate")?.let { if (it.isNull("year")) null else it.optInt("year") },
                pageCount = length.takeIf { it > 0 },
                coverUrl = cover?.optStringOrNull("extraLarge") ?: cover?.optStringOrNull("large"),
                thumbUrl = cover?.optStringOrNull("large"),
                volumes = m.optInt("volumes", 0).takeIf { it > 0 }
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/** TV shows (and many anime) from TVmaze. The network or streaming service stands in for the creator. */
fun searchTvMaze(title: String): List<LookupCandidate>? {
    val text = httpGetText("https://api.tvmaze.com/search/shows?q=${enc(title)}") ?: return null
    return try {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { i ->
            val show = arr.optJSONObject(i)?.optJSONObject("show") ?: return@mapNotNull null
            val image = show.optJSONObject("image")
            val network = show.optJSONObject("network")?.optStringOrNull("name") ?: show.optJSONObject("webChannel")?.optStringOrNull("name")
            LookupCandidate(
                title = show.optString("name"),
                author = network ?: "",
                year = show.optStringOrNull("premiered")?.take(4)?.toIntOrNull(),
                pageCount = null,
                coverUrl = image?.optStringOrNull("original")?.replace("http://", "https://"),
                thumbUrl = image?.optStringOrNull("medium")?.replace("http://", "https://"),
                tvmazeId = show.optInt("id").takeIf { it > 0 }
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Movies, documentaries and games from Wikipedia. The short description ("2010 film by Christopher Nolan",
 * "2017 video game developed by Team Cherry") gives the year and creator; the page image is the poster or box art.
 */
fun searchWikipedia(title: String, creator: String, kind: String): List<LookupCandidate>? {
    val q = enc(listOf(title, creator, kind).filter { it.isNotBlank() }.joinToString(" "))
    val url = "https://en.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&generator=search" +
        "&gsrsearch=$q&gsrlimit=10&prop=pageimages%7Cdescription&piprop=thumbnail%7Coriginal&pithumbsize=600&pilicense=any"
    val text = httpGetText(url) ?: return null
    return try {
        val pages = JSONObject(text).optJSONObject("query")?.optJSONArray("pages") ?: return emptyList()
        val keyword = if (kind == "video game") "game" else "film"
        (0 until pages.length()).mapNotNull { pages.optJSONObject(it) }
            .sortedBy { it.optInt("index", 99) }
            .mapNotNull { p ->
                val desc = p.optString("description", "")
                // Skip soundtracks, characters, lists and the like.
                if (!desc.contains(keyword, ignoreCase = true) && !desc.contains("series", ignoreCase = true)) return@mapNotNull null
                LookupCandidate(
                    title = p.optString("title").replace(Regex("""\s*\((\d{4} )?(film|video game|documentary|TV series)\)$"""), ""),
                    author = wikiCreator(desc) ?: "",
                    year = Regex("""\b(18|19|20)\d{2}\b""").find(desc)?.value?.toIntOrNull(),
                    pageCount = null,
                    // Logos are often SVG drawings, which Android can't decode; the thumbnail is a PNG render of them.
                    coverUrl = p.optJSONObject("original")?.optStringOrNull("source")
                        ?.takeUnless { it.substringBefore('?').endsWith(".svg", ignoreCase = true) }
                        ?: p.optJSONObject("thumbnail")?.optStringOrNull("source"),
                    thumbUrl = p.optJSONObject("thumbnail")?.optStringOrNull("source")
                )
            }
    } catch (_: Exception) {
        emptyList()
    }
}

/** "2010 film by Christopher Nolan" → "Christopher Nolan"; "game developed by Team Cherry" → "Team Cherry". */
fun wikiCreator(description: String): String? =
    Regex("""(?:directed|developed|created)?\s*by ([^,;()]+)""").find(description)?.groupValues?.get(1)?.trim()?.ifBlank { null }

/**
 * A show's seasons and episodes from TVmaze, ready for [StoreData.addParts]. Specials are left out. With
 * [flattenSingleSeason] a one-season show (common for anime) gets its episodes directly, without a "Season 1".
 */
fun fetchTvMazePlan(showId: Int, seasonLabel: String, flattenSingleSeason: Boolean): List<PlannedPart>? {
    val seasonsText = httpGetText("https://api.tvmaze.com/shows/$showId/seasons") ?: return null
    val episodesText = httpGetText("https://api.tvmaze.com/shows/$showId/episodes") ?: return null
    return try {
        val episodes = JSONArray(episodesText).let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .filter { !it.isNull("number") && !it.isNull("season") }
        }
        val bySeason = episodes.groupBy { it.optInt("season") }
        val seasons = JSONArray(seasonsText).let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
            .filter { bySeason.containsKey(it.optInt("number")) }
        fun episodesOf(n: Int) = bySeason[n].orEmpty().sortedBy { it.optInt("number") }.map { e ->
            PlannedPart(
                title = e.optStringOrNull("name") ?: "Episode ${e.optInt("number")}",
                format = "Episode",
                position = e.optInt("number"),
                year = e.optStringOrNull("airdate")?.take(4)?.toIntOrNull()
            )
        }
        if (flattenSingleSeason && seasons.size == 1) return episodesOf(seasons[0].optInt("number"))
        seasons.sortedBy { it.optInt("number") }.map { season ->
            val n = season.optInt("number")
            val name = season.optStringOrNull("name")
            PlannedPart(
                title = if (name != null) "$seasonLabel $n: $name" else "$seasonLabel $n",
                format = "Season",
                position = n,
                year = season.optStringOrNull("premiereDate")?.take(4)?.toIntOrNull(),
                coverUrl = season.optJSONObject("image")?.optStringOrNull("original")?.replace("http://", "https://"),
                children = episodesOf(n)
            )
        }
    } catch (_: Exception) {
        null
    }
}

/** Downloads [url] as a cover for [workId]. Returns the relative path or null. */
fun downloadCover(store: NovaloreStore, workId: String, url: String): String? {
    val bytes = httpGetBytes(url) ?: return null
    if (bytes.isEmpty()) return null
    return store.saveCover(workId, ByteArrayInputStream(bytes))
}

object ImageCache {
    private val cache = object : LruCache<String, Bitmap>(
        // An eighth of the app's heap, in bytes.
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** The same key is used for lookups and stores, so a decoded cover is reused while scrolling. */
    fun keyFor(file: File, maxDim: Int): String = "${file.absolutePath}@${file.lastModified()}@$maxDim"

    fun get(key: String): Bitmap? = cache.get(key)

    fun load(file: File, maxDim: Int): Bitmap? {
        val key = keyFor(file, maxDim)
        cache.get(key)?.let { return it }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            if (bmp != null) cache.put(key, bmp)
            bmp
        } catch (_: Exception) {
            null
        }
    }

    fun loadRemote(url: String): Bitmap? {
        val key = "remote:$url"
        cache.get(key)?.let { return it }
        val bytes = httpGetBytes(url, timeoutMs = 10000) ?: return null
        return try {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) cache.put(key, bmp)
            bmp
        } catch (_: Exception) {
            null
        }
    }
}
