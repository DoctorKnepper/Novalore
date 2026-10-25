package com.rook.novalore

import java.time.LocalDate
import java.util.UUID

// Pure-Kotlin data model. Nothing in this file touches Android, so it is covered by local unit tests.

object States {
    const val TO_READ = "TO_READ"
    const val CURRENTLY = "CURRENTLY_READING"
    const val PAUSED = "PAUSED"
    const val DNF = "DID_NOT_FINISH"
    const val READ = "READ"
    val all = listOf(TO_READ, CURRENTLY, PAUSED, DNF, READ)

    fun normalize(s: String?): String = if (s != null && s in all) s else TO_READ
}

data class Work(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "",
    var author: String = "",
    var format: String = "Short story",
    var year: Int? = null,
    var imagePath: String? = null, // relative to filesDir, always "covers/<file>"
    var favorite: Boolean = false,
    var state: String = States.TO_READ,
    var parentId: String? = null,   // the collection / anthology / book this work appears in
    var position: Int? = null,      // optional order inside the parent
    var pageCount: Int? = null,
    var pagesRead: Int? = null,
    var startedDate: String = ""    // ISO yyyy-MM-dd
)

data class ReadingLog(
    val id: String = UUID.randomUUID().toString(),
    val workId: String,
    var finishedDate: String = "", // ISO yyyy-MM-dd
    var readIn: String = "",
    var verdict: String = "",
    var review: String = "",
    var rating: Double? = null, // 0.5 steps, max 10
    var dnf: Boolean = false    // this read was abandoned rather than finished
)

/** Collection roll-up: how many stories are inside, how many are read, and the average of their latest ratings. */
data class Rollup(val total: Int, val read: Int, val rated: Int, val avgRating: Double?)

/** Everything needed to undo a deletion. */
class Removed(val work: Work, val logs: List<ReadingLog>, val childIds: List<String>, val index: Int)

fun todayIso(): String = LocalDate.now().toString()

/**
 * The whole library. A published snapshot is never mutated: [NovaloreStore.update] works on a [deepCopy]
 * and then calls [freeze], which builds lookup indexes so reads stay fast for large libraries.
 */
class StoreData(
    val works: MutableList<Work> = mutableListOf(),
    val logs: MutableList<ReadingLog> = mutableListOf()
) {
    private class Index(
        val byId: Map<String, Work>,
        val logsByWork: Map<String, List<ReadingLog>>,
        val childrenOf: Map<String, List<Work>>
    )

    private var index: Index? = null

    fun deepCopy(): StoreData =
        StoreData(works.map { it.copy() }.toMutableList(), logs.map { it.copy() }.toMutableList())

    /** Builds the lookup indexes. Call only once the data will no longer be mutated. */
    fun freeze(): StoreData {
        val byId = HashMap<String, Work>(works.size * 2 + 1)
        for (w in works) byId[w.id] = w
        val logsBy = HashMap<String, MutableList<ReadingLog>>()
        for (l in logs) logsBy.getOrPut(l.workId) { mutableListOf() }.add(l)
        for (v in logsBy.values) v.sortWith(compareByDescending { it.finishedDate })
        val kids = HashMap<String, MutableList<Work>>()
        for (w in works) {
            val p = w.parentId ?: continue
            if (byId.containsKey(p)) kids.getOrPut(p) { mutableListOf() }.add(w)
        }
        for (v in kids.values) v.sortWith(compareBy { it.position ?: Int.MAX_VALUE })
        index = Index(byId, logsBy, kids)
        return this
    }

    fun work(id: String): Work? {
        val ix = index
        return if (ix != null) ix.byId[id] else works.firstOrNull { it.id == id }
    }

    fun logsFor(workId: String): List<ReadingLog> {
        val ix = index
        return if (ix != null) ix.logsByWork[workId] ?: emptyList()
        else logs.filter { it.workId == workId }.sortedByDescending { it.finishedDate }
    }

    fun latestLog(workId: String): ReadingLog? = logsFor(workId).firstOrNull()

    fun childrenOf(parentId: String): List<Work> {
        val ix = index
        return if (ix != null) ix.childrenOf[parentId] ?: emptyList()
        else works.filter { it.parentId == parentId }.sortedBy { it.position ?: Int.MAX_VALUE }
    }

    fun isContainer(w: Work): Boolean = w.format in Formats.containers || childrenOf(w.id).isNotEmpty()

    fun parentOf(w: Work): Work? = w.parentId?.let { work(it) }

    /** All works nested (at any depth) under [id]. Cycle-safe. */
    fun descendantIds(id: String): Set<String> {
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        queue.add(id)
        while (queue.isNotEmpty()) {
            for (c in childrenOf(queue.removeFirst())) {
                if (c.id != id && out.add(c.id)) queue.add(c.id)
            }
        }
        return out
    }

    /** Works that [forWorkId] may be placed inside without creating a loop. */
    fun parentOptions(forWorkId: String?): List<Work> {
        val banned = if (forWorkId != null) descendantIds(forWorkId) + forWorkId else emptySet()
        return works.filter { it.id !in banned && isContainer(it) }.sortedBy { it.title.lowercase() }
    }

    /** Everything at the bottom of [id]'s tree (episodes of a show's seasons, stories of an anthology), in order. */
    fun leavesOf(id: String): List<Work> {
        val out = ArrayList<Work>()
        val seen = HashSet<String>().apply { add(id) }
        fun walk(pid: String) {
            for (c in childrenOf(pid)) {
                if (!seen.add(c.id)) continue
                if (childrenOf(c.id).isEmpty()) out.add(c) else walk(c.id)
            }
        }
        walk(id)
        return out
    }

    /** The first episode / chapter / story not yet finished or dropped, or null. */
    fun nextUp(id: String): Work? = leavesOf(id).firstOrNull { it.state != States.READ && it.state != States.DNF }

    /**
     * Counts are over the leaves (so a show reports episodes, not seasons). The average uses the direct children's
     * latest ratings, falling back to the leaves when none of the children are rated.
     */
    fun rollupFor(containerId: String): Rollup {
        val leaves = leavesOf(containerId)
        val direct = childrenOf(containerId).mapNotNull { latestLog(it.id)?.rating }
        val ratings = direct.ifEmpty { leaves.mapNotNull { latestLog(it.id)?.rating } }
        return Rollup(
            total = leaves.size,
            read = leaves.count { it.state == States.READ },
            rated = ratings.size,
            avgRating = if (ratings.isEmpty()) null else ratings.average()
        )
    }

    /**
     * Quick tick-off without writing a review. When every part of a season or volume is done, that season or
     * volume is marked done too (shows, manga and games are left for the user to finish and review).
     */
    fun markDone(id: String, done: Boolean = true) {
        val w = work(id) ?: return
        w.state = if (done) States.READ else States.TO_READ
        if (!done) return
        var parent = parentOf(w)
        var guard = 0
        while (parent != null && Formats.of(parent.format).part && guard++ < 8) {
            if (childrenOf(parent.id).any { it.state != States.READ && it.state != States.DNF }) break
            parent.state = States.READ
            parent = parentOf(parent)
        }
    }

    /** The work's own cover, or the nearest parent's cover (stories rarely have covers of their own). */
    fun inheritedCover(w: Work): String? {
        var cur: Work = w
        repeat(6) {
            cur.imagePath?.let { return it }
            cur = parentOf(cur) ?: return null
        }
        return null
    }

    /** 0..1 progress: parts finished for anything with parts, else pages / episodes / percent, else null. */
    fun progressFraction(w: Work): Float? {
        val leaves = leavesOf(w.id)
        if (leaves.isNotEmpty()) return leaves.count { it.state == States.READ }.toFloat() / leaves.size
        if (Formats.of(w.format).unit == ProgressUnit.PERCENT) return w.pagesRead?.let { (it / 100f).coerceIn(0f, 1f) }
        val total = w.pageCount ?: return null
        val read = w.pagesRead ?: return null
        if (total <= 0) return null
        return (read.toFloat() / total).coerceIn(0f, 1f)
    }

    fun setState(id: String, state: String, today: String = todayIso()) {
        val w = work(id) ?: return
        w.state = States.normalize(state)
        if (w.state == States.CURRENTLY && w.startedDate.isBlank()) w.startedDate = today
    }

    /** Removes a work and its logs. Stories inside it are kept and simply un-nested. */
    fun removeWork(id: String): Removed? {
        val i = works.indexOfFirst { it.id == id }
        if (i < 0) return null
        val w = works.removeAt(i)
        val removedLogs = logs.filter { it.workId == id }
        logs.removeAll { it.workId == id }
        val kids = works.filter { it.parentId == id }
        kids.forEach { it.parentId = null }
        return Removed(w.copy(), removedLogs.map { it.copy() }, kids.map { it.id }, i)
    }

    fun restore(r: Removed) {
        if (works.any { it.id == r.work.id }) return
        works.add(r.index.coerceIn(0, works.size), r.work.copy())
        logs.addAll(r.logs.map { it.copy() })
        for (cid in r.childIds) works.firstOrNull { it.id == cid }?.parentId = r.work.id
    }

    /** Repairs references after loading untrusted data: unknown parents, self-parents and loops are cleared. */
    fun sanitize(): StoreData {
        // Duplicate ids would break list keys (and lookups), so keep the first of each.
        val seenWorks = HashSet<String>()
        works.retainAll { seenWorks.add(it.id) }
        val seenLogs = HashSet<String>()
        logs.retainAll { seenLogs.add(it.id) }
        val ids = works.mapTo(HashSet()) { it.id }
        for (w in works) {
            w.state = States.normalize(w.state)
            if (w.parentId != null && (w.parentId == w.id || w.parentId !in ids)) w.parentId = null
            if (!isSafeCoverPath(w.imagePath)) w.imagePath = null
        }
        val byId = works.associateBy { it.id }
        for (w in works) {
            val seen = HashSet<String>()
            var cur: Work? = w
            while (cur?.parentId != null) {
                if (!seen.add(cur.id)) { cur.parentId = null; break } // cut the loop where it closes
                cur = byId[cur.parentId]
            }
        }
        return this
    }

    /**
     * Older data only recorded "didn't finish" on the work. For each DNF work in [workIds] (default: all) whose logs
     * carry no flag, the latest log is marked, since that is the read that was abandoned.
     */
    fun inferDnfLogs(workIds: Collection<String>? = null) {
        val byWork = logs.groupBy { it.workId }
        for (w in works) {
            if (w.state != States.DNF || (workIds != null && w.id !in workIds)) continue
            val wl = byWork[w.id] ?: continue
            if (wl.none { it.dnf }) wl.maxByOrNull { it.finishedDate }?.dnf = true
        }
    }

    /** After a log is edited or removed, a finished/abandoned work takes its state from its latest remaining log. */
    fun syncStateWithLatestLog(workId: String) {
        val w = work(workId) ?: return
        if (w.state != States.READ && w.state != States.DNF) return
        val latest = logs.filter { it.workId == workId }.maxByOrNull { it.finishedDate } ?: return
        w.state = if (latest.dnf) States.DNF else States.READ
    }

    fun findDuplicate(title: String, author: String, excludeId: String? = null): Work? {
        val t = title.trim().lowercase()
        val a = author.trim().lowercase()
        return works.firstOrNull { it.id != excludeId && it.title.trim().lowercase() == t && it.author.trim().lowercase() == a }
    }
}

/** Case-insensitive match against title, author, review text, verdict, "read in" and the parent's title. */
fun StoreData.matches(w: Work, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    if (w.title.lowercase().contains(q) || w.author.lowercase().contains(q)) return true
    if (parentOf(w)?.title?.lowercase()?.contains(q) == true) return true
    return logsFor(w.id).any {
        it.verdict.lowercase().contains(q) || it.review.lowercase().contains(q) || it.readIn.lowercase().contains(q)
    }
}

/** Optional review prompts. The first matches the structure of the reviews in the original reading tracker. */
object ReviewTemplates {
    val storyNotes = listOf("What happened:", "Sticking image / scene / line:", "How it made me feel:")
    val prosCons = listOf("What worked:", "What didn't:", "Who would enjoy it:")
    val spoilerFree = listOf("Premise (no spoilers):", "Style and voice:", "Verdict:")

    /** Appends the prompts to an existing review, separated by a blank line. */
    fun apply(review: String, prompts: List<String>): String {
        val block = prompts.joinToString("\n\n") { "$it " }.trimEnd()
        return if (review.isBlank()) block else review.trimEnd() + "\n\n" + block
    }
}

// ---------- Filling in parts (seasons, episodes, volumes, chapters) ----------

/** Something to add inside a work: a season with its episodes, or a single numbered part. */
class PlannedPart(
    val title: String,
    val format: String,
    val position: Int?,
    val year: Int? = null,
    val coverUrl: String? = null,
    val children: List<PlannedPart> = emptyList()
)

/** "Episode 1" … "Episode 12" (or volumes, chapters, issues) numbered [from]..[to]. */
fun numberedParts(format: String, label: String, from: Int, to: Int): List<PlannedPart> =
    if (to < from) emptyList() else (from..to).map { PlannedPart("$label $it", format, it) }

/** Total number of parts in a plan, including nested ones. */
fun List<PlannedPart>.countAll(): Int = sumOf { 1 + it.children.countAll() }

/**
 * Adds [parts] inside [parentId]. Parts already there (same number, or same title when unnumbered) are reused rather
 * than duplicated, so filling in a show twice only adds the new episodes. New parts take the parent's creator.
 * Returns the works that were created, paired with their plan (for downloading season covers afterwards).
 */
fun StoreData.addParts(parentId: String, parts: List<PlannedPart>): List<Pair<Work, PlannedPart>> {
    val parent = work(parentId) ?: return emptyList()
    val created = ArrayList<Pair<Work, PlannedPart>>()
    fun add(into: Work, plan: List<PlannedPart>, depth: Int) {
        if (depth > 4) return
        val existing = works.filter { it.parentId == into.id }
        for (p in plan) {
            val match = existing.firstOrNull { e ->
                if (p.position != null) e.position == p.position && e.format == p.format
                else e.title.equals(p.title, ignoreCase = true)
            }
            val w = match ?: Work(
                title = p.title, author = into.author, format = p.format, year = p.year,
                parentId = into.id, position = p.position
            ).also {
                works.add(it)
                created.add(it to p)
            }
            if (p.children.isNotEmpty()) add(w, p.children, depth + 1)
        }
    }
    add(parent, parts, 0)
    return created
}

// ---------- Year in review ----------

class YearStats(
    val year: Int,
    val finished: Int,                          // reviews logged as finished this year
    val dropped: Int,                           // reviews logged as didn't finish / dropped
    val byGroup: Map<MediaGroup, Int>,          // finished, per kind of media
    val byFormat: List<Pair<String, Int>>,      // finished, per format, most first
    val months: IntArray,                       // finished per month, January first
    val avgRating: Double?,
    val rated: Int,
    val rereviews: Int,                         // reviews of things already reviewed in an earlier year or earlier this year
    val topRated: List<Pair<Work, ReadingLog>>, // best five, highest rating first
    val topCreators: List<Pair<String, Int>>    // most reviewed authors / directors / studios / developers
)

/** Years that have at least one dated review, newest first. */
fun StoreData.reviewYears(): List<Int> =
    logs.mapNotNull { it.finishedDate.take(4).toIntOrNull() }.distinct().sortedDescending()

fun StoreData.yearStats(year: Int): YearStats {
    val prefix = "$year-"
    val inYear = logs.filter { it.finishedDate.startsWith(prefix) }.mapNotNull { l -> work(l.workId)?.let { it to l } }
    val done = inYear.filter { !it.second.dnf }
    val ratings = inYear.mapNotNull { it.second.rating }
    val months = IntArray(12)
    for ((_, l) in done) l.finishedDate.substring(5, 7).toIntOrNull()?.let { if (it in 1..12) months[it - 1]++ }
    val firstSeen = HashMap<String, String>()
    for (l in logs.sortedBy { it.finishedDate }) firstSeen.putIfAbsent(l.workId, l.id)
    return YearStats(
        year = year,
        finished = done.size,
        dropped = inYear.size - done.size,
        byGroup = MediaGroup.entries.associateWith { g -> done.count { Formats.groupOf(it.first.format) == g } },
        byFormat = done.groupingBy { it.first.format }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value },
        months = months,
        avgRating = if (ratings.isEmpty()) null else ratings.average(),
        rated = ratings.size,
        rereviews = inYear.count { (w, l) -> firstSeen[w.id] != l.id },
        topRated = inYear.filter { it.second.rating != null }
            .sortedWith(compareByDescending<Pair<Work, ReadingLog>> { it.second.rating }.thenByDescending { it.second.finishedDate })
            .distinctBy { it.first.id }
            .take(5),
        topCreators = inYear.map { it.first.author.trim() }.filter { it.isNotEmpty() && it != Csv.UNKNOWN_AUTHOR }
            .groupingBy { it.lowercase() to it }.eachCount()
            .entries.groupBy { it.key.first }
            .map { (_, e) -> e.first().key.second to e.sumOf { it.value } }
            .sortedByDescending { it.second }
            .take(3)
    )
}

// ---------- Quick rating ----------

/** How one level inside a show / collection is rated, e.g. Episodes: 3 of 8 rated, average 8.3. */
class LevelRating(val format: String, val total: Int, val rated: Int, val avg: Double?)

/** One entry per kind of part inside [id] (seasons, then episodes), in the order they nest. */
fun StoreData.ratingsByLevel(id: String): List<LevelRating> {
    val byFormat = LinkedHashMap<String, MutableList<Work>>()
    val seen = HashSet<String>().apply { add(id) }
    var level = childrenOf(id)
    var guard = 0
    while (level.isNotEmpty() && guard++ < 6) {
        val next = ArrayList<Work>()
        for (w in level) {
            if (!seen.add(w.id)) continue
            byFormat.getOrPut(w.format) { mutableListOf() }.add(w)
            next.addAll(childrenOf(w.id))
        }
        level = next
    }
    return byFormat.map { (format, ws) ->
        val ratings = ws.mapNotNull { latestLog(it.id)?.rating }
        LevelRating(format, ws.size, ratings.size, if (ratings.isEmpty()) null else ratings.average())
    }
}

/**
 * Rates a work in one step. Changes the latest review's stars (and verdict, when given), or starts a review dated
 * [today] if there is none yet. A first rating of an episode, chapter or story also ticks it off as done; shows and
 * collections keep their state. Returns the review's id, or null when there was nothing to save.
 */
fun StoreData.rate(workId: String, rating: Double?, verdict: String? = null, today: String = todayIso()): String? {
    val w = work(workId) ?: return null
    val latest = logs.filter { it.workId == workId }.maxByOrNull { it.finishedDate }
    if (latest == null && rating == null && verdict.isNullOrBlank()) return null
    val log = latest ?: ReadingLog(workId = workId, finishedDate = today).also { logs.add(it) }
    log.rating = rating
    if (verdict != null) log.verdict = verdict.trim()
    val isLeaf = works.none { it.parentId == workId }
    if (latest == null && isLeaf && w.state != States.READ && w.state != States.DNF) markDone(workId)
    return log.id
}

// ---------- "Part of", typed freely ----------

/**
 * Finds what [typed] refers to for work [forWorkId]: something already in the library with that title that can hold
 * it (and wouldn't create a loop), or else a new collection / show / series created for it, in the format that suits
 * [childFormat] and with the child's creator. A new parent starts in progress once the child is finished, since part
 * of it has been read or watched. Returns null for a blank entry.
 */
fun StoreData.resolveParent(typed: String, forWorkId: String, childFormat: String, author: String, childState: String): String? {
    val t = typed.trim()
    if (t.isEmpty()) return null
    val banned = descendantIds(forWorkId) + forWorkId
    works.firstOrNull { it.id !in banned && it.title.trim().equals(t, ignoreCase = true) && isContainer(it) }?.let { return it.id }
    val state = when (childState) {
        States.READ, States.DNF -> States.CURRENTLY
        else -> childState
    }
    val parent = Work(title = t, author = author.trim(), format = Formats.parentFor(childFormat), state = state)
    works.add(parent)
    return parent.id
}
