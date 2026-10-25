package com.rook.novalore

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// CSV export and import. Pure Kotlin, covered by local unit tests.
// Import understands Novalore's own export plus the common Goodreads / StoryGraph column names.

data class ImportRow(
    val title: String,
    val author: String,
    val format: String,
    val year: Int?,
    val favorite: Boolean,
    val state: String?,      // null when the file doesn't say
    val finishedDate: String,
    val readIn: String,
    val rating: Double?,
    val verdict: String,
    val review: String,
    val partOf: String,
    val pageCount: Int?,
    val pagesRead: Int?,
    val startedDate: String,
    val position: Int?,
    val dnf: Boolean? = null // null when the file has no "Did Not Finish" column
) {
    val hasLog: Boolean get() = finishedDate.isNotBlank() || rating != null || verdict.isNotBlank() || review.isNotBlank()
}

class CsvParse(val rows: List<ImportRow>, val ignoredRows: Int)

data class ImportCounts(val newWorks: Int, val newLogs: Int, val matchedWorks: Int, val linked: Int, val ignoredRows: Int)

object Csv {
    const val UNKNOWN_AUTHOR = "Unknown"

    val HEADER = listOf(
        "Title", "Author", "Format", "Publication Year", "Favorite", "State", "Finished Date", "Read In",
        "Rating", "Verdict", "Review", "Part Of", "Pages", "Pages Read", "Started Date", "Position", "Did Not Finish"
    )

    fun cell(s: String): String {
        val needsQuotes = s.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || s != s.trim()
        return if (needsQuotes) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun num(d: Double): String =
        if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

    fun build(data: StoreData): String {
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(",")).append('\n')
        val sorted = data.works.sortedWith(compareBy({ it.author.lowercase() }, { it.title.lowercase() }))
        for (w in sorted) {
            val head = listOf(
                w.title, w.author, w.format, w.year?.toString() ?: "", if (w.favorite) "yes" else "no", w.state
            )
            val tail = listOf(
                data.parentOf(w)?.title ?: "", w.pageCount?.toString() ?: "", w.pagesRead?.toString() ?: "",
                w.startedDate, w.position?.toString() ?: ""
            )
            val logs = data.logsFor(w.id)
            if (logs.isEmpty()) {
                appendRow(sb, head + listOf("", "", "", "", "") + tail + "")
            } else {
                for (l in logs) {
                    appendRow(sb, head + listOf(l.finishedDate, l.readIn, l.rating?.let(::num) ?: "", l.verdict, l.review) + tail + (if (l.dnf) "yes" else "no"))
                }
            }
        }
        return sb.toString()
    }

    private fun appendRow(sb: StringBuilder, cells: List<String>) {
        sb.append(cells.joinToString(",") { cell(it) }).append('\n')
    }

    /** RFC 4180 parser: quoted fields, doubled quotes, embedded newlines, CRLF, UTF-8 BOM. Blank lines are skipped. */
    fun parse(input: String): List<List<String>> {
        val text = input.removePrefix("\uFEFF")
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        fun endCell() {
            row.add(cur.toString()); cur.setLength(0)
        }
        fun endRow() {
            endCell()
            if (!(row.size == 1 && row[0].isEmpty())) rows.add(row)
            row = ArrayList()
        }
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cur.append('"'); i++ } else inQuotes = false
                } else {
                    cur.append(c)
                }
            } else {
                when (c) {
                    '"' -> if (cur.isEmpty()) inQuotes = true else cur.append(c)
                    ',' -> endCell()
                    '\r' -> { if (i + 1 < text.length && text[i + 1] == '\n') i++; endRow() }
                    '\n' -> endRow()
                    else -> cur.append(c)
                }
            }
            i++
        }
        if (cur.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    private fun norm(h: String) = h.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** Returns null when the file has no Title column (i.e. it isn't a reading list we understand). */
    fun parseImport(text: String): CsvParse? {
        val table = parse(text)
        if (table.isEmpty()) return null
        val header = table[0].map(::norm)
        fun idx(vararg names: String): Int {
            for (n in names) { val i = header.indexOf(n); if (i >= 0) return i }
            return -1
        }
        val iTitle = idx("title")
        if (iTitle < 0) return null
        val iAuthor = idx("author", "authors")
        val iFormat = idx("format")
        val iYear = idx("publicationyear", "originalpublicationyear", "yearpublished", "year")
        val iFav = idx("favorite", "favourite")
        val iState = idx("state", "status", "readstatus", "exclusiveshelf")
        val iFinished = idx("finisheddate", "dateread", "datefinished", "lastdateread")
        val iReadIn = idx("readin")
        val iRating = idx("rating")
        val iScaled = idx("myrating", "starrating") // Goodreads / StoryGraph: out of 5
        val iVerdict = idx("verdict")
        val iReview = idx("review", "myreview")
        val iPartOf = idx("partof", "parent")
        val iPages = idx("pages", "numberofpages", "pagecount")
        val iPagesRead = idx("pagesread")
        val iStarted = idx("starteddate", "datestarted")
        val iPosition = idx("position")
        val iDnf = idx("didnotfinish", "dnf")

        val rows = ArrayList<ImportRow>()
        var ignored = 0
        for (r in table.drop(1)) {
            fun get(i: Int) = if (i in r.indices) r[i].trim() else ""
            val title = get(iTitle)
            if (title.isBlank()) { ignored++; continue }
            val rating = when {
                iRating >= 0 -> parseRating(get(iRating), 1.0)
                iScaled >= 0 -> parseRating(get(iScaled), 2.0)
                else -> null
            }
            rows.add(
                ImportRow(
                    title = title,
                    author = get(iAuthor).ifBlank { UNKNOWN_AUTHOR },
                    format = parseFormat(get(iFormat)),
                    year = parseInt(get(iYear)),
                    favorite = get(iFav).lowercase(Locale.ROOT) in setOf("yes", "y", "true", "1", "x", "favorite", "favourite"),
                    state = parseState(get(iState)),
                    finishedDate = parseDate(get(iFinished)),
                    readIn = get(iReadIn),
                    rating = rating,
                    verdict = get(iVerdict),
                    review = get(iReview),
                    partOf = get(iPartOf),
                    pageCount = parseInt(get(iPages))?.takeIf { it > 0 },
                    pagesRead = parseInt(get(iPagesRead))?.takeIf { it >= 0 },
                    startedDate = parseDate(get(iStarted)),
                    position = parseInt(get(iPosition)),
                    dnf = if (iDnf < 0) null else get(iDnf).lowercase(Locale.ROOT) in setOf("yes", "y", "true", "1", "x", "dnf")
                )
            )
        }
        return CsvParse(rows, ignored)
    }

    fun parseInt(s: String): Int? = s.trim().toIntOrNull()

    fun parseFormat(s: String): String {
        if (s.isBlank()) return "Other"
        return Formats.all.firstOrNull { it.equals(s.trim(), ignoreCase = true) } ?: "Other"
    }

    /** Converts to the app's 0.5-step, out-of-10 scale. Zero / blank means "not rated". */
    fun parseRating(raw: String, scale: Double): Double? {
        val v = raw.trim().replace(',', '.').toDoubleOrNull() ?: return null
        val x = v * scale
        if (x <= 0.0 || x.isNaN()) return null
        return (Math.round(x * 2) / 2.0).coerceAtMost(10.0)
    }

    fun parseState(raw: String): String? = when (raw.lowercase(Locale.ROOT).filter { it.isLetter() }) {
        "toread", "wanttoread", "tbr", "unread" -> States.TO_READ
        "currentlyreading", "reading", "inprogress" -> States.CURRENTLY
        "paused", "onhold" -> States.PAUSED
        "dnf", "didnotfinish", "didntfinish", "abandoned" -> States.DNF
        "read", "finished", "done", "completed" -> States.READ
        else -> null
    }

    private val datePatterns = listOf(
        DateTimeFormatter.ofPattern("yyyy-M-d", Locale.ROOT),
        DateTimeFormatter.ofPattern("yyyy/M/d", Locale.ROOT),
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
    )

    /** Accepts ISO, yyyy/MM/dd (Goodreads) and "Oct 6, 2026" (Novalore's display format). Unknown → "". */
    fun parseDate(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        for (p in datePatterns) {
            try { return LocalDate.parse(s, p).toString() } catch (_: Exception) { }
        }
        return ""
    }

    /** Merges rows into [d]. Existing works (same title + author) are reused; identical logs are not duplicated. */
    fun applyImport(d: StoreData, parsed: CsvParse): ImportCounts {
        fun key(t: String, a: String) = t.trim().lowercase() + "\u0001" + a.trim().lowercase()
        val byKey = HashMap<String, Work>()
        for (w in d.works) byKey.putIfAbsent(key(w.title, w.author), w)
        fun sig(workId: String, l: ImportRow) = listOf(workId, l.finishedDate, l.verdict, l.review, l.rating?.toString() ?: "").joinToString("\u0001")
        val logSigs = HashSet<String>()
        for (l in d.logs) logSigs.add(listOf(l.workId, l.finishedDate, l.verdict, l.review, l.rating?.toString() ?: "").joinToString("\u0001"))

        var newWorks = 0; var newLogs = 0; var matched = 0; var linked = 0
        val rowWork = ArrayList<Pair<ImportRow, Work>>()
        val createdLogs = HashMap<String, MutableList<ReadingLog>>() // keyed by work id
        val createdIds = HashSet<String>()
        val matchedIds = HashSet<String>()

        for (row in parsed.rows) {
            val state = row.state ?: if (row.hasLog) States.READ else States.TO_READ
            var w = byKey[key(row.title, row.author)]
            if (w == null) {
                w = Work(
                    title = row.title, author = row.author, format = row.format, year = row.year,
                    favorite = row.favorite, state = state, pageCount = row.pageCount, pagesRead = row.pagesRead,
                    startedDate = row.startedDate, position = row.position
                )
                d.works.add(w)
                byKey[key(row.title, row.author)] = w
                createdIds.add(w.id)
                newWorks++
            } else {
                if (w.id !in createdIds && matchedIds.add(w.id)) matched++
                if (w.year == null) w.year = row.year
                if (w.pageCount == null) w.pageCount = row.pageCount
                if (w.pagesRead == null) w.pagesRead = row.pagesRead
                if (w.startedDate.isBlank()) w.startedDate = row.startedDate
                if (w.position == null) w.position = row.position
                if (row.favorite) w.favorite = true
                if (rank(state) > rank(w.state)) w.state = state
            }
            if (row.hasLog) {
                val s = sig(w.id, row)
                if (logSigs.add(s)) {
                    val log = ReadingLog(
                        workId = w.id, finishedDate = row.finishedDate, readIn = row.readIn,
                        verdict = row.verdict, review = row.review, rating = row.rating, dnf = row.dnf == true
                    )
                    d.logs.add(log)
                    createdLogs.getOrPut(w.id) { mutableListOf() }.add(log)
                    newLogs++
                }
            }
            rowWork.add(row to w)
        }

        // Files without a "Did Not Finish" column (older Novalore exports, Goodreads) only say it on the work.
        d.inferDnfLogs(rowWork.filter { it.first.dnf == null }.mapTo(HashSet()) { it.second.id })

        // Second pass: link "Part Of" to a work with that title (which may have been created by this same file).
        for ((row, w) in rowWork) {
            if (row.partOf.isBlank() || w.parentId != null) continue
            val candidates = d.works.filter { it.id != w.id && it.title.trim().equals(row.partOf.trim(), ignoreCase = true) }
            val parent = candidates.firstOrNull { it.format in Formats.containers } ?: candidates.firstOrNull()
            if (parent != null && parent.id !in d.descendantIds(w.id)) {
                w.parentId = parent.id
                linked++
            } else {
                createdLogs[w.id]?.forEach { if (it.readIn.isBlank()) it.readIn = row.partOf }
            }
        }
        return ImportCounts(newWorks, newLogs, matched, linked, parsed.ignoredRows)
    }

    /** Import only ever moves a work forward (to-read → reading → finished), never backwards. */
    private fun rank(state: String) = when (state) {
        States.TO_READ -> 0
        States.PAUSED -> 1
        States.CURRENTLY -> 2
        States.DNF -> 3
        States.READ -> 4
        else -> 0
    }
}
