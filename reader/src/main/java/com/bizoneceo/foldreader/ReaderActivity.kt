package com.bizoneceo.foldreader

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.bizoneceo.foldreader.book.Book
import com.bizoneceo.foldreader.book.BookLoader
import com.bizoneceo.foldreader.library.BookEntry
import com.bizoneceo.foldreader.library.Library
import com.bizoneceo.foldreader.reader.CurlView
import com.bizoneceo.foldreader.reader.PagePos
import com.bizoneceo.foldreader.reader.PageRenderer
import com.bizoneceo.foldreader.reader.PageSpec
import com.bizoneceo.foldreader.reader.Paginator
import com.bizoneceo.foldreader.reader.Side
import com.bizoneceo.foldreader.reader.Themes
import com.bizoneceo.foldreader.reader.Variant
import java.util.concurrent.Executors
import kotlin.math.max

class ReaderActivity : Activity(), CurlView.Host {

    companion object {
        const val EXTRA_ID = "book_id"
        private val LAYOUT_NAMES = listOf("Авто", "1 стр.", "2 стр.")
    }

    private lateinit var entry: BookEntry
    private var book: Book? = null
    private var paginator: Paginator? = null
    private var renderer: PageRenderer? = null

    private lateinit var curl: CurlView
    private lateinit var loading: TextView
    private lateinit var menuTop: LinearLayout
    private lateinit var menuBottom: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var seekLabel: TextView
    private lateinit var layoutButton: TextView
    private lateinit var themeButton: TextView
    private lateinit var fontButton: TextView

    private val prefs by lazy { getSharedPreferences("reader", MODE_PRIVATE) }
    private var fontSp = 19f
    private var themeIdx = 0
    private var layoutMode = 0
    private var serif = true

    private var anchorChapter = 0
    private var anchorOffset = 0
    private var viewW = 0
    private var viewH = 0
    @Volatile private var generation = 0
    private val bg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        val e = id?.let { Library.find(this, it) }
        if (e == null) { finish(); return }
        entry = e
        fontSp = prefs.getFloat("font_sp", 19f)
        themeIdx = prefs.getInt("theme", 0).coerceIn(0, Themes.all.size - 1)
        layoutMode = prefs.getInt("layout", 0)
        serif = prefs.getBoolean("serif", true)
        Library.loadPosition(this, entry.id).let { (c, o) -> anchorChapter = c; anchorOffset = o }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        hideSystemUi()

        val file = Library.file(this, entry.id)
        Thread {
            try {
                val b = BookLoader.load(file, entry.title)
                main.post { if (!isFinishing) { book = b; rebuild() } }
            } catch (ex: Throwable) {
                main.post {
                    Toast.makeText(this, "Не удалось открыть книгу: ${ex.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(Themes.all[themeIdx].paper)
        curl = CurlView(this).also { it.host = this }
        root.addView(curl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        loading = TextView(this).apply {
            text = "Открываю «${entry.title}»…"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Themes.all[themeIdx].dim)
        }
        root.addView(loading, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val barColor = Color.argb(0xEE, 0x22, 0x20, 0x1D)
        menuTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(barColor)
            setPadding((8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
            visibility = View.GONE
            addView(button("‹ Книги") { finish() })
            addView(TextView(context).apply {
                text = listOf(entry.title, entry.author).filter { it.isNotBlank() }.joinToString(" — ")
                setTextColor(Color.WHITE)
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding((8 * dp).toInt(), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("Оглавление") { showToc() })
        }
        root.addView(menuTop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        seekLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) showSeekTarget(value)
                }
                override fun onStartTrackingTouch(s: SeekBar) = Unit
                override fun onStopTrackingTouch(s: SeekBar) {
                    val b = book ?: return
                    val pg = paginator ?: return
                    val global = (b.totalChars.toLong() * s.progress / 1000).toInt()
                    curl.jumpTo(pg.posForGlobal(global))
                }
            })
        }
        themeButton = button("") { themeIdx = (themeIdx + 1) % Themes.all.size; savePrefs(); applyTheme() }
        fontButton = button("") { serif = !serif; savePrefs(); rebuild() }
        layoutButton = button("") { layoutMode = (layoutMode + 1) % 3; savePrefs(); rebuild() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(button("A−") { changeFont(-1f) }, weighted())
            addView(button("A+") { changeFont(+1f) }, weighted())
            addView(themeButton, weighted())
            addView(fontButton, weighted())
            addView(layoutButton, weighted())
        }
        menuBottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(barColor)
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt())
            visibility = View.GONE
            addView(seekLabel)
            addView(seek)
            addView(row)
        }
        root.addView(menuBottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        updateButtons()
        return root
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 15f
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
        val tv = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)
        setOnClickListener { onClick() }
    }

    private fun updateButtons() {
        themeButton.text = Themes.all[themeIdx].name
        fontButton.text = if (serif) "Антиква" else "Гротеск"
        layoutButton.text = LAYOUT_NAMES[layoutMode]
    }

    private fun menuVisible() = menuBottom.visibility == View.VISIBLE

    private fun setMenu(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        menuTop.visibility = v
        menuBottom.visibility = v
        if (visible) updateSeek() else hideSystemUi()
    }

    private fun updateSeek() {
        val b = book ?: return
        val pg = paginator ?: return
        val pos = curl.base ?: return
        val g = pg.globalOffset(pos)
        seek.progress = if (b.totalChars > 0) (g.toLong() * 1000 / b.totalChars).toInt() else 0
        seekLabel.text = "${b.chapters[pos.chapter].title}  ·  ${seek.progress / 10}%"
    }

    private fun showSeekTarget(value: Int) {
        val b = book ?: return
        val ch = b.chapterForGlobalChar((b.totalChars.toLong() * value / 1000).toInt())
        seekLabel.text = "${b.chapters[ch].title}  ·  ${value / 10}%"
    }

    private fun showToc() {
        val b = book ?: return
        val idx = b.chapters.indices.filter { b.chapters[it].inToc }
        val current = curl.base?.chapter ?: 0
        val checked = idx.indexOfLast { it <= current }
        AlertDialog.Builder(this)
            .setTitle("Оглавление")
            .setSingleChoiceItems(idx.map { b.chapters[it].title }.toTypedArray(), checked) { d, which ->
                d.dismiss()
                setMenu(false)
                curl.jumpTo(PagePos(idx[which], 0))
            }
            .show()
    }

    private fun changeFont(delta: Float) {
        fontSp = (fontSp + delta).coerceIn(12f, 34f)
        savePrefs()
        rebuild()
        Toast.makeText(this, "Шрифт ${fontSp.toInt()}", Toast.LENGTH_SHORT).show()
    }

    private fun applyTheme() {
        updateButtons()
        val b = book ?: return
        val pg = paginator ?: return
        renderer = PageRenderer(b, pg, Themes.all[themeIdx], dp)
        (curl.parent as? View)?.setBackgroundColor(Themes.all[themeIdx].paper)
        curl.invalidate()
    }

    private fun savePrefs() {
        prefs.edit().putFloat("font_sp", fontSp).putInt("theme", themeIdx)
            .putInt("layout", layoutMode).putBoolean("serif", serif).apply()
        updateButtons()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !menuVisible()) hideSystemUi()
    }

    // ------------------------------------------------------------ layout

    /** Re-paginates for the current screen (folded/unfolded), font and layout mode. */
    private fun rebuild() {
        val b = book ?: return
        if (viewW == 0 || viewH == 0) return
        val wDp = viewW / dp
        val spread = when (layoutMode) {
            1 -> false
            2 -> true
            else -> wDp >= 600f // inner screen of the Fold (and landscape phones)
        }
        val pageW = if (spread) viewW / 2 else viewW
        val textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSp, resources.displayMetrics)
        val padH = max(18 * dp, pageW * 0.075f).toInt()
        val spec = PageSpec(
            pageW = pageW, pageH = viewH,
            padLeft = padH, padRight = padH,
            padTop = (30 * dp).toInt(), padBottom = (38 * dp).toInt(),
            textSizePx = textPx,
            typeface = if (serif) Typeface.SERIF else Typeface.SANS_SERIF,
            lineSpacing = 1.2f,
        )
        val pg = Paginator(b, spec)
        paginator = pg
        renderer = PageRenderer(b, pg, Themes.all[themeIdx], dp)
        val pos = pg.posForChar(anchorChapter, anchorOffset)
        curl.configure(spread, pageW, viewH, pos)
        loading.visibility = View.GONE
        prewarm(pos.chapter)
        if (menuVisible()) updateSeek()
    }

    private fun prewarm(chapter: Int) {
        val pg = paginator ?: return
        val gen = ++generation
        bg.execute {
            for (i in intArrayOf(chapter, chapter + 1, chapter - 1, chapter + 2)) {
                if (gen != generation) return@execute
                if (i in 0 until pg.chapterCount) pg.chapter(i)
            }
        }
    }

    // ------------------------------------------------------- CurlView.Host

    override fun next(p: PagePos): PagePos? = paginator?.next(p)
    override fun prev(p: PagePos): PagePos? = paginator?.prev(p)

    override fun pageBitmap(p: PagePos?, side: Side, variant: Variant): Bitmap? =
        renderer?.bitmap(p, side, variant)

    override fun onPageChanged(p: PagePos) {
        val pg = paginator ?: return
        val chapterChanged = p.chapter != anchorChapter
        anchorChapter = p.chapter
        anchorOffset = pg.charOffset(p)
        Library.savePosition(this, entry.id, anchorChapter, anchorOffset)
        if (chapterChanged) prewarm(p.chapter)
        if (menuVisible()) updateSeek()
    }

    override fun onCenterTap() = setMenu(!menuVisible())

    override fun onViewSize(w: Int, h: Int) {
        if (w == viewW && h == viewH) return
        viewW = w
        viewH = h
        rebuild()
    }

    // --------------------------------------------------------- lifecycle

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> { curl.turnForward(); return true }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT -> { curl.turnBackward(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (menuVisible()) setMenu(false) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        if (!::entry.isInitialized) return
        val b = book
        val pg = paginator
        val pos = curl.base
        if (b != null && pg != null && pos != null && b.totalChars > 0) {
            entry.progress = pg.globalOffset(pos).toFloat() / b.totalChars
        }
        entry.lastOpened = System.currentTimeMillis()
        Library.update(this, entry)
    }

    override fun onDestroy() {
        super.onDestroy()
        generation++
        bg.shutdownNow()
    }
}
