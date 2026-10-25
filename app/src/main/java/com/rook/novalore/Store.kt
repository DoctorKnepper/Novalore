package com.rook.novalore

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Things the UI should tell the user about storage. */
enum class StoreNotice { RECOVERED_FROM_BACKUP, LOAD_FAILED, SAVE_FAILED }

/** A backup that has been read and staged, waiting for the user to confirm the replace. */
class PendingImport(val data: StoreData, internal val stagingDir: File)

private const val MAX_BACKUP_ENTRIES = 50_000
private const val MAX_JSON_BYTES = 64L * 1024 * 1024
private const val MAX_IMAGE_BYTES = 25L * 1024 * 1024

class NovaloreStore(context: Context) {
    private val appContext = context.applicationContext
    private val filesDir: File = appContext.filesDir
    private val dataFile = File(filesDir, "data.json")
    private val bakFile = File(filesDir, "data.json.bak")
    private val tmpFile = File(filesDir, "data.json.tmp")
    private val stagingRoot = File(filesDir, "import_staging")
    private val coversDir = File(filesDir, COVERS_DIR)

    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "novalore-writer").apply { isDaemon = true } }
    private val pending = AtomicReference<StoreData?>(null)

    /** Latest published snapshot. Never mutate it directly: use [update]. */
    var data: StoreData by mutableStateOf(StoreData().freeze())
        private set

    var notice: StoreNotice? by mutableStateOf(null)

    init {
        data = loadInitial()
    }

    // ---------- loading ----------

    private fun tryRead(f: File): StoreData? = try {
        if (f.exists()) storeDataFromJson(JSONObject(f.readText(Charsets.UTF_8))) else null
    } catch (_: Exception) {
        null
    }

    private fun loadInitial(): StoreData {
        if (!dataFile.exists()) {
            // A crash between writes could leave only the backup copy.
            tryRead(bakFile)?.let { return it.freeze() }
            return StoreData().freeze() // fresh install: an empty library
        }
        tryRead(dataFile)?.let { return it.freeze() }

        // data.json exists but can't be read. Never overwrite it: keep the bad file aside for recovery.
        try {
            dataFile.renameTo(File(filesDir, "data.corrupt-${System.currentTimeMillis()}.json"))
        } catch (_: Exception) {
        }
        val fromBak = tryRead(bakFile)
        return if (fromBak != null) {
            notice = StoreNotice.RECOVERED_FROM_BACKUP
            schedulePersist(fromBak)
            fromBak.freeze()
        } else {
            notice = StoreNotice.LOAD_FAILED
            StoreData().freeze()
        }
    }

    // ---------- writing ----------

    /** Apply a mutation and publish a fresh snapshot. Safe to call from any thread; writes never overlap. */
    fun update(block: (StoreData) -> Unit) {
        synchronized(lock) {
            val copy = data.deepCopy()
            block(copy)
            copy.freeze()
            data = copy
            schedulePersist(copy)
        }
    }

    /** Like [update], but returns a value computed inside the block (e.g. what was removed). */
    fun <T> updateWith(block: (StoreData) -> T): T {
        synchronized(lock) {
            val copy = data.deepCopy()
            val result = block(copy)
            copy.freeze()
            data = copy
            schedulePersist(copy)
            return result
        }
    }

    fun replaceAll(newData: StoreData) {
        synchronized(lock) {
            newData.freeze()
            data = newData
            schedulePersist(newData)
        }
    }

    private fun schedulePersist(d: StoreData) {
        pending.set(d)
        writer.execute {
            val next = pending.getAndSet(null) ?: return@execute
            if (!writeToDisk(next)) notice = StoreNotice.SAVE_FAILED
        }
    }

    /** Blocks briefly until queued writes are on disk. Called when the app goes to the background. */
    fun flush() {
        try {
            writer.submit(Runnable { }).get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
    }

    private fun writeToDisk(d: StoreData): Boolean = try {
        val bytes = d.toJson().toString().toByteArray(Charsets.UTF_8)
        FileOutputStream(tmpFile).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (dataFile.exists()) {
            try {
                dataFile.copyTo(bakFile, overwrite = true)
            } catch (_: IOException) {
            }
        }
        try {
            Files.move(tmpFile.toPath(), dataFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmpFile.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        true
    } catch (_: Exception) {
        false
    }

    // ---------- covers ----------

    fun coverFile(rel: String?): File? = resolveCover(filesDir, rel)

    fun deleteCover(rel: String?) {
        try {
            coverFile(rel)?.delete()
        } catch (_: Exception) {
        }
    }

    /** Saves image bytes as a new cover. Returns the relative path, or null if it isn't a usable image. */
    fun saveCover(workId: String, input: InputStream, minSize: Int = 10): String? {
        coversDir.mkdirs()
        val safeId = workId.filter { it.isLetterOrDigit() || it == '-' }.take(40)
        val name = "${safeId}_${System.currentTimeMillis()}.jpg"
        val dest = File(coversDir, name)
        return try {
            FileOutputStream(dest).use { copyLimited(input, it, MAX_IMAGE_BYTES) }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(dest.absolutePath, opts)
            // Open Library returns a 1x1 blank for missing covers.
            if (opts.outWidth < minSize || opts.outHeight < minSize) {
                dest.delete()
                null
            } else {
                "$COVERS_DIR/$name"
            }
        } catch (_: Exception) {
            dest.delete()
            null
        }
    }

    fun copyPickedImage(uri: Uri, workId: String): String? = try {
        appContext.contentResolver.openInputStream(uri)?.use { saveCover(workId, it, minSize = 1) }
    } catch (_: Exception) {
        null
    }

    /**
     * Deletes cover files nothing refers to, plus leftover import staging. Run at startup and after imports.
     * The default age keeps a cover picked in a form that is still open (e.g. restored after process death).
     */
    fun cleanOrphanFiles(minAgeMs: Long = 24L * 60 * 60 * 1000) {
        try {
            stagingRoot.deleteRecursively()
            val referenced = data.works.mapNotNullTo(HashSet()) { it.imagePath?.substringAfter('/') }
            val cutoff = System.currentTimeMillis() - minAgeMs
            coversDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name !in referenced && f.lastModified() < cutoff) f.delete()
            }
        } catch (_: Exception) {
        }
    }

    // ---------- backup ----------

    /** Writes data.json plus every referenced cover. */
    fun writeBackup(out: OutputStream): Boolean = try {
        val snapshot = data
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(snapshot.toJson().toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            val seen = HashSet<String>()
            for (w in snapshot.works) {
                val rel = w.imagePath ?: continue
                if (!seen.add(rel)) continue
                val f = coverFile(rel) ?: continue
                if (!f.exists()) continue
                zip.putNextEntry(ZipEntry(rel))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Reads a backup into a staging folder without touching the live library.
     * Only "data.json" and "covers/<file>" entries are accepted; sizes and entry counts are capped.
     */
    fun readBackup(input: InputStream): PendingImport? {
        val staging = File(stagingRoot, System.currentTimeMillis().toString())
        return try {
            staging.mkdirs()
            val stagedCovers = File(staging, COVERS_DIR).apply { mkdirs() }
            var jsonText: String? = null
            var count = 0
            ZipInputStream(input.buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (++count > MAX_BACKUP_ENTRIES) throw IOException("Too many entries")
                    val name = entry.name
                    if (!entry.isDirectory) {
                        if (name == "data.json") {
                            jsonText = readLimited(zip, MAX_JSON_BYTES).toString(Charsets.UTF_8)
                        } else {
                            val dest = resolveCover(staging, name)
                            if (dest != null && dest.parentFile == stagedCovers.canonicalFile) {
                                FileOutputStream(dest).use { copyLimited(zip, it, MAX_IMAGE_BYTES) }
                            }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
            val parsed = storeDataFromJson(JSONObject(jsonText ?: throw IOException("No data.json")))
            // Drop cover references that weren't in the zip.
            for (w in parsed.works) {
                if (w.imagePath != null && resolveCover(staging, w.imagePath)?.exists() != true) w.imagePath = null
            }
            PendingImport(parsed, staging)
        } catch (_: Exception) {
            staging.deleteRecursively()
            null
        }
    }

    /** Moves staged covers into place, replaces the library, then removes covers that are no longer used. */
    fun commitImport(p: PendingImport) {
        coversDir.mkdirs()
        File(p.stagingDir, COVERS_DIR).listFiles()?.forEach { f ->
            val dest = File(coversDir, f.name)
            try {
                Files.move(f.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                try { f.copyTo(dest, overwrite = true) } catch (_: Exception) { }
            }
        }
        replaceAll(p.data)
        p.stagingDir.deleteRecursively()
        cleanOrphanFiles(minAgeMs = 0)
    }

    fun discardImport(p: PendingImport) {
        p.stagingDir.deleteRecursively()
    }
}
