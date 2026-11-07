package com.bizoneceo.foldreader.book

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/** Current body text size; read by indent spans so indents scale with the font. */
object Typo {
    @Volatile
    var textSizePx: Float = 48f
}

/** Red-line (first line) indent of a normal paragraph. */
class ParaIndent : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int =
        if (first) (Typo.textSizePx * 1.4f).toInt() else 0

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout,
    ) = Unit
}

/** Whole-block left indent (epigraphs, poems, citations). */
class BlockIndent(private val em: Float) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int = (Typo.textSizePx * em).toInt()

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout,
    ) = Unit
}

object TextTools {
    const val FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    fun center(sb: Spannable, start: Int, end: Int) =
        sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, end, FLAGS)

    fun heading(sb: Spannable, start: Int, end: Int, size: Float) {
        sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, FLAGS)
        sb.setSpan(RelativeSizeSpan(size), start, end, FLAGS)
        center(sb, start, end)
    }

    /**
     * Normalises HTML-derived text: drops spans that would break theming
     * (link colours, fixed colours/sizes, images), removes blank lines,
     * trims every paragraph, centres headings and adds red-line indents.
     */
    fun normalizeHtml(src: Spanned): SpannableStringBuilder {
        val text = SpannableStringBuilder(src)
        for (cls in listOf(
            ClickableSpan::class.java, ForegroundColorSpan::class.java,
            BackgroundColorSpan::class.java, AbsoluteSizeSpan::class.java, ImageSpan::class.java,
        )) {
            for (s in text.getSpans(0, text.length, cls)) text.removeSpan(s)
        }
        val out = SpannableStringBuilder()
        var i = 0
        val n = text.length
        while (i < n) {
            var e = i
            while (e < n && text[e] != '\n') e++
            var s = i
            var t = e
            while (s < t && isBlank(text[s])) s++
            while (t > s && isBlank(text[t - 1])) t--
            if (t > s) {
                val start = out.length
                out.append(text.subSequence(s, t))
                // strip object-replacement chars of removed images
                var k = out.length - 1
                while (k >= start) {
                    if (out[k] == '￼') out.delete(k, k + 1)
                    k--
                }
                if (out.length > start) {
                    out.append('\n')
                    val end = out.length
                    val sizes = out.getSpans(start, end, RelativeSizeSpan::class.java)
                    val isHeading = sizes.any { it.sizeChange > 1.05f }
                    val hasAlign = out.getSpans(start, end, AlignmentSpan::class.java).isNotEmpty()
                    val hasMargin = out.getSpans(start, end, LeadingMarginSpan::class.java).isNotEmpty()
                    if (isHeading) {
                        if (!hasAlign) center(out, start, end)
                        out.append('\n') // breathing room after a heading
                    } else if (!hasAlign && !hasMargin) {
                        out.setSpan(ParaIndent(), start, end, FLAGS)
                    }
                }
            }
            i = e + 1
        }
        return out
    }

    private fun isBlank(c: Char) = c == ' ' || c == '\t' || c == ' ' || c == '\r' || c == '￼'

    fun looksCyrillic(chapters: List<Chapter>): Boolean {
        var cyr = 0
        var lat = 0
        loop@ for (c in chapters) {
            for (ch in c.text) {
                if (ch in 'Ѐ'..'ӿ') cyr++ else if (ch in 'a'..'z' || ch in 'A'..'Z') lat++
                if (cyr + lat > 4000) break@loop
            }
        }
        return cyr >= lat
    }

    /** Splits very long chapters so each StaticLayout stays fast to build. */
    fun splitLong(chapters: List<Chapter>, maxChars: Int = 40_000): List<Chapter> {
        val out = ArrayList<Chapter>()
        for (c in chapters) {
            val t = c.text
            if (t.length <= maxChars * 3 / 2) { out.add(c); continue }
            var start = 0
            var first = true
            while (start < t.length) {
                var end = (start + maxChars).coerceAtMost(t.length)
                if (end < t.length) {
                    var k = end
                    while (k < t.length && t[k] != '\n') k++
                    end = (k + 1).coerceAtMost(t.length)
                    if (t.length - end < maxChars / 3) end = t.length
                }
                out.add(Chapter(c.title, t.subSequence(start, end), inToc = first && c.inToc))
                first = false
                start = end
            }
        }
        return out
    }
}
