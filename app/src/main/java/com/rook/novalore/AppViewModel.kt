package com.rook.novalore

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A one-off message for the snackbar. [undo] adds an Undo action that restores a deleted work. */
class UiMessage(
    @StringRes val text: Int,
    val args: List<Any> = emptyList(),
    val undo: Removed? = null,
    val rateWorkId: String? = null // adds a "Rate" action that opens the quick rating box
) {
    val id: Long = System.nanoTime()
}

class CoverFetchProgress(val done: Int, val total: Int)

class CsvPreview(val parse: CsvParse, val counts: ImportCounts)

private const val MAX_CSV_BYTES = 32L * 1024 * 1024

/** Owns the store so it survives configuration changes, and runs work that must outlive a single screen. */
class NovaloreViewModel(app: Application) : AndroidViewModel(app) {
    val store = NovaloreStore(app)
    val looks = AppearanceStore(app)

    var message by mutableStateOf<UiMessage?>(null)
        private set
    var coverFetch by mutableStateOf<CoverFetchProgress?>(null)
        private set
    var pendingBackup by mutableStateOf<PendingImport?>(null)
        private set
    var pendingCsv by mutableStateOf<CsvPreview?>(null)
        private set

    private var coverJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            store.cleanOrphanFiles()
            // Learn each cover's shape up front, so library tiles and thumbnails don't change shape as you scroll.
            store.data.works.mapNotNull { it.imagePath }.distinct().forEach { rel ->
                store.coverFile(rel)?.takeIf { it.exists() }?.let { CoverShapes.measure(it) }
            }
        }
    }

    fun show(@StringRes text: Int, vararg args: Any) {
        message = UiMessage(text, args.toList())
    }

    fun clearMessage(m: UiMessage) {
        if (message === m) message = null
    }

    // ---------- works ----------

    fun setState(workId: String, state: String) = store.update { it.setState(workId, state) }

    fun deleteWork(workId: String) {
        val title = store.data.work(workId)?.title ?: return
        val removed = store.updateWith { it.removeWork(workId) } ?: return
        // The cover file is kept until the next cleanup so Undo can bring it back.
        message = UiMessage(R.string.msg_deleted, listOf(title), undo = removed)
    }

    fun undo(removed: Removed) = store.update { it.restore(removed) }

    /** Ticks an episode, chapter or story off without a review (or un-ticks it). */
    fun markDone(workId: String, done: Boolean) = store.update { it.markDone(workId, done) }

    /** Rates a work from a list or its page; see [StoreData.rate]. Returns the review's id. */
    fun quickRate(workId: String, rating: Double?, verdict: String? = null): String? =
        store.updateWith { it.rate(workId, rating, verdict) }

    /** Ticks off the next episode / chapter of [containerId] and offers to rate it straight away. */
    fun markNextDone(containerId: String) {
        val next = store.data.nextUp(containerId) ?: return
        store.update { it.markDone(next.id) }
        message = UiMessage(R.string.msg_marked_done, listOf(next.title), rateWorkId = next.id)
    }

    /** Ticks off everything inside a show, season, manga or collection that isn't already finished or dropped. */
    fun markAllDone(containerId: String) = store.update { d ->
        for (leaf in d.leavesOf(containerId)) {
            if (leaf.state != States.READ && leaf.state != States.DNF) d.markDone(leaf.id)
        }
    }

    /**
     * Adds planned seasons / episodes / numbered parts inside [workId], then downloads any season posters in the
     * background. Existing parts are kept and not duplicated.
     */
    fun fillIn(workId: String, plan: List<PlannedPart>) {
        val created = store.updateWith { it.addParts(workId, plan) }
        show(R.string.msg_parts_added, created.size)
        val withCovers = created.filter { it.second.coverUrl != null }
        if (withCovers.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for ((w, p) in withCovers) {
                if (!isActive) break
                val rel = downloadCover(store, w.id, p.coverUrl!!) ?: continue
                var used = false
                store.update { d -> d.work(w.id)?.let { if (it.imagePath == null) { it.imagePath = rel; used = true } } }
                if (!used) store.deleteCover(rel)
            }
        }
    }

    fun saveProgress(workId: String, pagesRead: Int?, pageCount: Int?) = store.update { d ->
        d.work(workId)?.let {
            it.pageCount = pageCount
            it.pagesRead = if (pagesRead != null && pageCount != null) pagesRead.coerceAtMost(pageCount) else pagesRead
        }
    }

    // ---------- covers ----------

    /** Looks up covers for works that have none (and don't inherit one from their collection). */
    fun fetchMissingCovers() {
        if (coverJob?.isActive == true) return
        coverJob = viewModelScope.launch(Dispatchers.IO) {
            val snapshot = store.data
            // Episodes, chapters and the like use their show's or manga's cover, so they aren't looked up.
            val targets = snapshot.works.filter {
                it.imagePath == null && snapshot.inheritedCover(it) == null && it.title.isNotBlank() && it.format !in Formats.parts
            }
            var found = 0
            coverFetch = CoverFetchProgress(0, targets.size)
            for ((i, w) in targets.withIndex()) {
                if (!isActive) break
                val outcome = searchOnline(w.title, w.author, w.format)
                if (outcome.failed && i == 0) { // offline: stop early instead of timing out on every work
                    coverFetch = null
                    withContext(Dispatchers.Main) { show(R.string.msg_offline) }
                    return@launch
                }
                val url = outcome.results.firstOrNull { it.coverUrl != null }?.coverUrl
                if (url != null) {
                    val rel = downloadCover(store, w.id, url)
                    if (rel != null) {
                        var used = false
                        store.update { d ->
                            val cur = d.work(w.id)
                            if (cur != null && cur.imagePath == null) { cur.imagePath = rel; used = true }
                        }
                        if (used) found++ else store.deleteCover(rel)
                    }
                }
                coverFetch = CoverFetchProgress(i + 1, targets.size)
            }
            coverFetch = null
            withContext(Dispatchers.Main) { show(R.string.msg_covers_found, found, targets.size) }
        }
    }

    fun stopFetchingCovers() {
        coverJob?.cancel()
        coverFetch = null
    }

    // ---------- export ----------

    fun exportBackup(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val ok = try {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { store.writeBackup(it) } ?: false
        } catch (_: Exception) {
            false
        }
        withContext(Dispatchers.Main) { show(if (ok) R.string.msg_backup_saved else R.string.msg_backup_failed) }
    }

    fun exportCsv(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val ok = try {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                it.write(Csv.build(store.data).toByteArray(Charsets.UTF_8))
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
        withContext(Dispatchers.Main) { show(if (ok) R.string.msg_csv_saved else R.string.msg_export_failed) }
    }

    // ---------- backup import (staged, then confirmed) ----------

    fun readBackup(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val parsed = try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { store.readBackup(it) }
        } catch (_: Exception) {
            null
        }
        withContext(Dispatchers.Main) {
            if (parsed != null) pendingBackup = parsed else show(R.string.msg_not_a_backup)
        }
    }

    fun confirmBackup() {
        val p = pendingBackup ?: return
        pendingBackup = null
        viewModelScope.launch(Dispatchers.IO) {
            store.commitImport(p)
            withContext(Dispatchers.Main) { show(R.string.msg_backup_imported) }
        }
    }

    fun cancelBackup() {
        val p = pendingBackup ?: return
        pendingBackup = null
        viewModelScope.launch(Dispatchers.IO) { store.discardImport(p) }
    }

    // ---------- CSV import (previewed, then merged) ----------

    fun readCsv(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val parse = try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                Csv.parseImport(readLimited(it, MAX_CSV_BYTES).toString(Charsets.UTF_8))
            }
        } catch (_: Exception) {
            null
        }
        val preview = parse?.let { CsvPreview(it, Csv.applyImport(store.data.deepCopy(), it)) }
        withContext(Dispatchers.Main) {
            when {
                preview == null -> show(R.string.msg_not_a_csv)
                preview.parse.rows.isEmpty() -> show(R.string.msg_csv_empty)
                else -> pendingCsv = preview
            }
        }
    }

    fun confirmCsv() {
        val p = pendingCsv ?: return
        pendingCsv = null
        val counts = store.updateWith { Csv.applyImport(it, p.parse) }
        show(R.string.msg_csv_imported, counts.newWorks, counts.newLogs)
    }

    fun cancelCsv() {
        pendingCsv = null
    }

    override fun onCleared() {
        store.flush()
    }
}
