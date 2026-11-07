package com.bizoneceo.foldreader.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.bizoneceo.foldreader.book.BookLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class BookEntry(
    val id: String,
    var title: String,
    var author: String,
    val addedAt: Long,
    var lastOpened: Long,
    var progress: Float,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("author", author)
        .put("added", addedAt).put("opened", lastOpened).put("progress", progress.toDouble())

    companion object {
        fun fromJson(o: JSONObject) = BookEntry(
            o.getString("id"), o.optString("title"), o.optString("author"),
            o.optLong("added"), o.optLong("opened"), o.optDouble("progress", 0.0).toFloat(),
        )
    }
}

/**
 * Books are copied into app-private storage (so a link from the browser or
 * file manager keeps working later); metadata and reading positions live in
 * SharedPreferences.
 */
object Library {
    private const val PREFS = "library"
    private const val KEY_BOOKS = "books"
    private const val KEY_TREE = "sync_tree"
    private const val KEY_SEEN = "sync_seen"

    val BOOK_SUFFIXES = listOf(".fb2", ".fb2.zip", ".epub", ".zip")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dir(ctx: Context) = File(ctx.filesDir, "books").apply { mkdirs() }
    fun file(ctx: Context, id: String) = File(dir(ctx), id)

    @Synchronized
    fun list(ctx: Context): MutableList<BookEntry> {
        val arr = JSONArray(prefs(ctx).getString(KEY_BOOKS, "[]"))
        return MutableList(arr.length()) { BookEntry.fromJson(arr.getJSONObject(it)) }
            .filter { file(ctx, it.id).exists() }
            .sortedByDescending { maxOf(it.lastOpened, it.addedAt) }
            .toMutableList()
    }

    @Synchronized
    private fun saveAll(ctx: Context, list: List<BookEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY_BOOKS, arr.toString()).apply()
    }

    @Synchronized
    fun find(ctx: Context, id: String) = list(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun update(ctx: Context, entry: BookEntry) {
        val all = list(ctx)
        val i = all.indexOfFirst { it.id == entry.id }
        if (i >= 0) all[i] = entry else all.add(entry)
        saveAll(ctx, all)
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        saveAll(ctx, list(ctx).filter { it.id != id })
        file(ctx, id).delete()
        prefs(ctx).edit().remove("pos_$id").apply()
    }

    fun savePosition(ctx: Context, id: String, chapter: Int, offset: Int) {
        prefs(ctx).edit().putString("pos_$id", "$chapter:$offset").apply()
    }

    fun loadPosition(ctx: Context, id: String): Pair<Int, Int> {
        val s = prefs(ctx).getString("pos_$id", null) ?: return 0 to 0
        val parts = s.split(':')
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "book"
    }

    /** Copies a book from any content/file Uri into the library. Call off the UI thread. */
    fun import(ctx: Context, uri: Uri, name: String = displayName(ctx, uri)): BookEntry {
        val tmp = File.createTempFile("import", ".tmp", dir(ctx))
        val md = MessageDigest.getInstance("SHA-1")
        (ctx.contentResolver.openInputStream(uri) ?: error("Не удалось открыть файл")).use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        val id = md.digest().joinToString("") { "%02x".format(it) }.take(20)
        val target = file(ctx, id)
        find(ctx, id)?.let { tmp.delete(); return it }
        val fallback = name.removeSuffix(".zip").substringBeforeLast('.')
        val book = try {
            BookLoader.load(tmp, fallback)
        } catch (e: Exception) {
            tmp.delete()
            throw IllegalArgumentException("«$name»: не похоже на книгу FB2/EPUB/TXT (${e.message})", e)
        }
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        val entry = BookEntry(id, book.title.ifBlank { fallback }, book.author, System.currentTimeMillis(), 0L, 0f)
        update(ctx, entry)
        return entry
    }

    // ---- folder sync (e.g. the Download folder where litres.ru saves books) ----

    fun syncTree(ctx: Context): Uri? = prefs(ctx).getString(KEY_TREE, null)?.let(Uri::parse)
    fun setSyncTree(ctx: Context, uri: Uri) = prefs(ctx).edit().putString(KEY_TREE, uri.toString()).apply()

    class SyncResult(val imported: List<BookEntry>, val errors: List<String>)

    /** Imports every new FB2/EPUB file found in the chosen folder (two levels deep). */
    fun sync(ctx: Context): SyncResult {
        val tree = syncTree(ctx) ?: return SyncResult(emptyList(), emptyList())
        val seen = prefs(ctx).getStringSet(KEY_SEEN, emptySet())!!.toMutableSet()
        val imported = ArrayList<BookEntry>()
        val errors = ArrayList<String>()
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        fun walk(docUri: Uri, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(docUri))
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 2) walk(uri, depth + 1)
                        continue
                    }
                    val lower = name.lowercase()
                    if (BOOK_SUFFIXES.none { lower.endsWith(it) }) continue
                    val key = "$name|$size"
                    if (key in seen) continue
                    try {
                        imported.add(import(ctx, uri, name))
                    } catch (e: Exception) {
                        // plain .zip archives that are not books are expected; report others
                        if (!lower.endsWith(".zip") || lower.endsWith(".fb2.zip")) errors.add(e.message ?: name)
                    }
                    seen.add(key)
                }
            }
        }
        walk(root, 0)
        prefs(ctx).edit().putStringSet(KEY_SEEN, seen).apply()
        return SyncResult(imported, errors)
    }
}
