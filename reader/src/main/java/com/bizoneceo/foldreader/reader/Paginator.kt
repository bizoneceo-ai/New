package com.bizoneceo.foldreader.reader

import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.bizoneceo.foldreader.book.Book
import com.bizoneceo.foldreader.book.Typo
import java.util.Locale

data class PagePos(val chapter: Int, val page: Int)

class PageSpec(
    val pageW: Int,
    val pageH: Int,
    val padLeft: Int,
    val padRight: Int,
    val padTop: Int,
    val padBottom: Int,
    val textSizePx: Float,
    val typeface: Typeface,
    val lineSpacing: Float,
) {
    val contentW get() = (pageW - padLeft - padRight).coerceAtLeast(50)
    val contentH get() = (pageH - padTop - padBottom).coerceAtLeast(50)
}

/**
 * Splits every chapter into pages. Each chapter gets its own StaticLayout,
 * built lazily (and pre-warmed in the background for neighbours), so even a
 * long novel opens instantly.
 */
class Paginator(private val book: Book, val spec: PageSpec) {

    class ChapterPages(val layout: StaticLayout, val pageStarts: IntArray) {
        val count get() = pageStarts.size
        fun startLine(page: Int) = pageStarts[page]
        fun endLine(page: Int) = if (page + 1 < pageStarts.size) pageStarts[page + 1] else layout.lineCount
    }

    private val basePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = spec.textSizePx
        typeface = spec.typeface
        textLocale = if (book.cyrillic) Locale("ru", "RU") else Locale.ENGLISH
    }
    private val cache = arrayOfNulls<ChapterPages>(book.chapters.size)
    private val locks = Array(book.chapters.size) { Any() }

    val chapterCount get() = book.chapters.size

    fun chapter(i: Int): ChapterPages {
        cache[i]?.let { return it }
        synchronized(locks[i]) {
            cache[i]?.let { return it }
            Typo.textSizePx = spec.textSizePx
            val text = book.chapters[i].text.let { if (it.isEmpty()) " " else it }
            val paint = TextPaint(basePaint) // own paint per layout: layouts are built on several threads
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, spec.contentW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, spec.lineSpacing)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
                .build()
            val starts = ArrayList<Int>()
            val h = spec.contentH
            var line = 0
            while (line < layout.lineCount) {
                starts.add(line)
                val top = layout.getLineTop(line)
                var end = line + 1
                while (end < layout.lineCount && layout.getLineBottom(end) - top <= h) end++
                // Don't leave a heading alone at the bottom of a page.
                if (end < layout.lineCount && end - line > 4) {
                    var k = end - 1
                    if (layout.getLineEnd(k) - layout.getLineStart(k) <= 1) k--
                    if (isHeadingLine(layout, k)) end = k
                }
                line = end
            }
            if (starts.isEmpty()) starts.add(0)
            val cp = ChapterPages(layout, starts.toIntArray())
            cache[i] = cp
            return cp
        }
    }

    private fun isHeadingLine(layout: Layout, line: Int): Boolean {
        val text = layout.text
        val s = layout.getLineStart(line)
        val e = layout.getLineEnd(line)
        if (e - s <= 1) return false // blank line: fine to end on
        if (text !is android.text.Spanned) return false
        return text.getSpans(s, e, android.text.style.RelativeSizeSpan::class.java).any { it.sizeChange > 1.05f }
    }

    fun isReady(i: Int) = cache[i] != null

    fun next(p: PagePos): PagePos? {
        if (p.page + 1 < chapter(p.chapter).count) return PagePos(p.chapter, p.page + 1)
        return if (p.chapter + 1 < chapterCount) PagePos(p.chapter + 1, 0) else null
    }

    fun prev(p: PagePos): PagePos? {
        if (p.page > 0) return PagePos(p.chapter, p.page - 1)
        if (p.chapter == 0) return null
        return PagePos(p.chapter - 1, chapter(p.chapter - 1).count - 1)
    }

    /** Char offset (within the chapter) of the first character on the page. */
    fun charOffset(p: PagePos): Int {
        val cp = chapter(p.chapter)
        return cp.layout.getLineStart(cp.startLine(p.page.coerceIn(0, cp.count - 1)))
    }

    fun globalOffset(p: PagePos) = book.chapterStarts[p.chapter] + charOffset(p)

    fun posForChar(chapter: Int, offset: Int): PagePos {
        val ch = chapter.coerceIn(0, chapterCount - 1)
        val cp = chapter(ch)
        val line = cp.layout.getLineForOffset(offset.coerceIn(0, cp.layout.text.length))
        var page = 0
        while (page + 1 < cp.count && cp.pageStarts[page + 1] <= line) page++
        return PagePos(ch, page)
    }

    fun posForGlobal(offset: Int): PagePos {
        val ch = book.chapterForGlobalChar(offset)
        return posForChar(ch, offset - book.chapterStarts[ch])
    }

    /** Draws the text of a page; the canvas origin is the page's top-left corner. */
    fun drawText(canvas: Canvas, p: PagePos, color: Int) {
        val cp = chapter(p.chapter)
        val page = p.page.coerceIn(0, cp.count - 1)
        val layout = cp.layout
        val first = cp.startLine(page)
        val last = cp.endLine(page)
        val top = layout.getLineTop(first)
        val bottom = layout.getLineTop(last)
        Typo.textSizePx = spec.textSizePx
        layout.paint.color = color
        canvas.save()
        canvas.translate(spec.padLeft.toFloat(), (spec.padTop - top).toFloat())
        canvas.clipRect(0, top, spec.contentW + spec.padRight, bottom)
        layout.draw(canvas)
        canvas.restore()
    }
}
