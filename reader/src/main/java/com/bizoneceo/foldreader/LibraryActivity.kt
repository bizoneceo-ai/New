package com.bizoneceo.foldreader

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.bizoneceo.foldreader.library.BookEntry
import com.bizoneceo.foldreader.library.Library
import java.util.concurrent.Executors

class LibraryActivity : Activity() {

    companion object {
        private const val REQ_OPEN = 11
        private const val REQ_TREE = 12
        private const val LITRES_PACKAGE = "ru.litres.android"
        private const val LITRES_URL = "https://www.litres.ru/"
        private val BG = Color.rgb(0xF4, 0xEC, 0xDD)
        private val INK = Color.rgb(0x2B, 0x22, 0x18)
        private val DIM = Color.rgb(0x8A, 0x7A, 0x66)
        private val ACCENT = Color.rgb(0x6B, 0x4F, 0x2E)
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var books: List<BookEntry> = emptyList()
    private lateinit var listView: ListView
    private lateinit var status: TextView
    private lateinit var help: View
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (!handleIncoming(intent) && savedInstanceState == null) syncFolder(quiet = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val pad = (16 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(pad, pad, pad, 0)
        }
        root.addView(TextView(this).apply {
            text = "FoldReader"
            textSize = 28f
            setTypeface(Typeface.SERIF, Typeface.BOLD)
            setTextColor(INK)
        })
        root.addView(TextView(this).apply {
            text = "Читалка с живым перелистыванием для Galaxy Fold"
            textSize = 14f
            setTextColor(DIM)
            setPadding(0, 0, 0, (12 * dp).toInt())
        })

        root.addView(row(
            pill("Открыть файл") { openFile() },
            pill(if (Library.syncTree(this) == null) "Папка с книгами" else "Обновить папку") { onFolderClick() },
        ))
        root.addView(row(
            pill("ЛитРес") { openLitres() },
            pill("Как перенести?") { showHelp() },
        ))

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(DIM)
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
            visibility = View.GONE
        }
        root.addView(status)

        listView = ListView(this).apply {
            divider = GradientDrawable().apply { setColor(Color.rgb(0xE2, 0xD6, 0xC0)) }
            dividerHeight = 1
            setOnItemClickListener { _, _, pos, _ -> openBook(books[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> confirmDelete(books[pos]); true }
            adapter = BookAdapter()
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        help = ScrollView(this).apply {
            addView(TextView(context).apply {
                text = helpText()
                textSize = 15f
                setTextColor(INK)
                setLineSpacing(0f, 1.25f)
                setPadding(0, (12 * dp).toInt(), 0, pad)
            })
        }
        root.addView(help, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach {
            addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                val m = (4 * dp).toInt(); setMargins(m, m, m, m)
            })
        }
    }

    private fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        maxLines = 1
        setPadding((10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt())
        background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = 22 * dp }
        setOnClickListener { onClick() }
    }

    private fun helpText() = """
        Как читать книги из ЛитРес

        Приложение ЛитРес хранит купленные книги в зашифрованном виде у себя внутри — другие программы до них добраться не могут. Но ЛитРес разрешает скачать купленную книгу файлом:

        1. Откройте litres.ru в браузере (кнопка «ЛитРес» вверху откроет приложение или сайт), войдите в свой аккаунт.
        2. «Мои книги» → нужная книга → «Скачать» → формат FB2 или EPUB.
        3. Файл попадёт в папку «Загрузки» (Download).

        Дальше любой из вариантов:
        • Нажмите «Папка с книгами» и выберите папку Download — FoldReader будет сам подтягивать оттуда новые книги при каждом запуске.
        • Или просто нажмите на скачанный файл — Android предложит открыть его в FoldReader.
        • Или «Открыть файл» и выберите книгу вручную.

        Управление в книге
        • Тяните страницу пальцем от правого края — она загибается как настоящая; отпустите — долистается или вернётся.
        • Тап по правому/левому краю — следующая/предыдущая страница, клавиши громкости тоже листают.
        • Тап по центру — меню: размер шрифта, тема, шрифт, оглавление, режим 1/2 страницы.
        • На внутреннем экране Fold книга открывается разворотом из двух страниц, на внешнем — одна страница.

        Долгое нажатие на книгу в списке — удалить её из библиотеки.
    """.trimIndent()

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setMessage(helpText())
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun refresh() {
        books = Library.list(this)
        (listView.adapter as BaseAdapter).notifyDataSetChanged()
        listView.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
        help.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setStatus(text: String?) {
        status.text = text ?: ""
        status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private inner class BookAdapter : BaseAdapter() {
        override fun getCount() = books.size
        override fun getItem(position: Int) = books[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val b = books[position]
            val v = (convertView as? LinearLayout) ?: LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
                addView(TextView(context).apply {
                    textSize = 17f
                    setTypeface(Typeface.SERIF, Typeface.BOLD)
                    setTextColor(INK)
                })
                addView(TextView(context).apply {
                    textSize = 13f
                    setTextColor(DIM)
                })
            }
            (v.getChildAt(0) as TextView).text = b.title
            val pct = (b.progress * 100).toInt()
            val meta = listOfNotNull(
                b.author.takeIf { it.isNotBlank() },
                if (b.lastOpened == 0L) "новая" else "прочитано $pct%",
            )
            (v.getChildAt(1) as TextView).text = meta.joinToString("  ·  ")
            return v
        }
    }

    // ------------------------------------------------------------ actions

    private fun openBook(b: BookEntry) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ID, b.id))
    }

    private fun confirmDelete(b: BookEntry) {
        AlertDialog.Builder(this)
            .setTitle("Удалить книгу?")
            .setMessage("«${b.title}» будет удалена из библиотеки FoldReader (на ЛитРес она останется).")
            .setPositiveButton("Удалить") { _, _ -> Library.delete(this, b.id); refresh() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(i, REQ_OPEN)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Нет приложения для выбора файлов", Toast.LENGTH_LONG).show()
        }
    }

    private fun onFolderClick() {
        if (Library.syncTree(this) == null) pickFolder() else {
            AlertDialog.Builder(this)
                .setTitle("Папка с книгами")
                .setItems(arrayOf("Подтянуть новые книги", "Выбрать другую папку")) { _, which ->
                    if (which == 0) syncFolder(quiet = false) else pickFolder()
                }
                .show()
        }
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        if (Build.VERSION.SDK_INT >= 26) {
            // Suggest the Download folder, where browsers save LitRes files.
            val download = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, download)
        }
        try {
            startActivityForResult(i, REQ_TREE)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Выбор папки недоступен", Toast.LENGTH_LONG).show()
        }
    }

    private fun openLitres() {
        val app = packageManager.getLaunchIntentForPackage(LITRES_PACKAGE)
        try {
            startActivity(app ?: Intent(Intent.ACTION_VIEW, Uri.parse(LITRES_URL)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Не удалось открыть ЛитРес", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_OPEN -> importAndOpen(uri)
            REQ_TREE -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                }
                Library.setSyncTree(this, uri)
                setContentView(buildUi())
                refresh()
                syncFolder(quiet = false)
            }
        }
    }

    private fun handleIncoming(intent: Intent?): Boolean {
        intent ?: return false
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        uri ?: return false
        intent.action = null // don't import again on rotation
        importAndOpen(uri)
        return true
    }

    private fun importAndOpen(uri: Uri) {
        setStatus("Добавляю книгу…")
        io.execute {
            try {
                val e = Library.import(this, uri)
                main.post { setStatus(null); refresh(); openBook(e) }
            } catch (ex: Exception) {
                main.post {
                    setStatus(null)
                    Toast.makeText(this, ex.message ?: "Ошибка импорта", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun syncFolder(quiet: Boolean) {
        if (Library.syncTree(this) == null) return
        if (!quiet) setStatus("Ищу новые книги в папке…")
        io.execute {
            val result = try {
                Library.sync(this)
            } catch (ex: Exception) {
                Library.SyncResult(emptyList(), listOf("Нет доступа к папке — выберите её заново (${ex.message})"))
            }
            main.post {
                refresh()
                val n = result.imported.size
                val msg = when {
                    n > 0 -> "Добавлено книг: $n"
                    result.errors.isNotEmpty() -> result.errors.first()
                    quiet -> null
                    else -> "Новых книг не найдено"
                }
                setStatus(msg)
            }
        }
    }
}
