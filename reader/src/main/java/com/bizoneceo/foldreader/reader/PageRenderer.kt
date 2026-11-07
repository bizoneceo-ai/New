package com.bizoneceo.foldreader.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import com.bizoneceo.foldreader.book.Book

enum class Side { LEFT, RIGHT, SINGLE }

/** How a page bitmap is used by the curl: the back of a sheet is mirrored. */
enum class Variant { NORMAL, MIRRORED, SHOW_THROUGH }

class ReaderTheme(val name: String, val paper: Int, val ink: Int, val dim: Int, val spineShadow: Int)

object Themes {
    val all = listOf(
        ReaderTheme("День", Color.rgb(0xFB, 0xF8, 0xF1), Color.rgb(0x1E, 0x1C, 0x1A), Color.rgb(0x8C, 0x86, 0x7C), 0x30000000),
        ReaderTheme("Сепия", Color.rgb(0xF2, 0xE6, 0xCE), Color.rgb(0x4A, 0x3A, 0x28), Color.rgb(0x9A, 0x84, 0x66), 0x38000000),
        ReaderTheme("Ночь", Color.rgb(0x1E, 0x1D, 0x1B), Color.rgb(0xC9, 0xC3, 0xB8), Color.rgb(0x6E, 0x69, 0x60), 0x60000000),
    )
}

/** Renders pages into bitmaps (with a small LRU cache) for the curl view. */
class PageRenderer(
    private val book: Book,
    private val paginator: Paginator,
    private val theme: ReaderTheme,
    private val density: Float,
) {
    val pageW = paginator.spec.pageW
    val pageH = paginator.spec.pageH

    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 10
    }

    private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = paginator.spec.textSizePx * 0.62f
        color = theme.dim
        typeface = android.graphics.Typeface.SANS_SERIF
    }
    private val shadePaint = Paint()

    fun bitmap(pos: PagePos?, side: Side, variant: Variant): Bitmap {
        val key = "${pos?.chapter}:${pos?.page}:$side:$variant"
        cache[key]?.let { return it }
        val bmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when (variant) {
            Variant.NORMAL -> drawPage(c, pos, side)
            Variant.MIRRORED -> {
                c.save()
                c.scale(-1f, 1f, pageW / 2f, 0f)
                drawPage(c, pos, side)
                c.restore()
            }
            Variant.SHOW_THROUGH -> {
                // Back of a single sheet: blank paper with the front's ink faintly showing through.
                c.drawColor(theme.paper)
                if (pos != null) {
                    c.saveLayerAlpha(0f, 0f, pageW.toFloat(), pageH.toFloat(), 26)
                    paginator.drawText(c, pos, theme.ink)
                    c.restore()
                }
                c.drawColor(0x10000000)
            }
        }
        cache[key] = bmp
        return bmp
    }

    private fun drawPage(c: Canvas, pos: PagePos?, side: Side) {
        c.drawColor(theme.paper)
        if (pos != null) {
            paginator.drawText(c, pos, theme.ink)
            drawFooter(c, pos, side)
        }
        drawSpine(c, side)
    }

    private fun drawFooter(c: Canvas, pos: PagePos, side: Side) {
        val spec = paginator.spec
        val y = pageH - spec.padBottom / 2f + footerPaint.textSize / 3f
        val cp = paginator.chapter(pos.chapter)
        val pct = if (book.totalChars > 0) paginator.globalOffset(pos) * 100 / book.totalChars else 0
        val right = "${pos.page + 1}/${cp.count}  ·  $pct%"
        val title = book.chapters[pos.chapter].title
        val rw = footerPaint.measureText(right)
        val avail = spec.contentW - rw - 24 * density
        val left = TextUtils.ellipsize(title, footerPaint, avail.coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()
        if (side == Side.LEFT) {
            // mirror layout on left pages of a spread: page number on the outer edge
            c.drawText(right, spec.padLeft.toFloat(), y, footerPaint)
            c.drawText(left, pageW - spec.padRight - footerPaint.measureText(left), y, footerPaint)
        } else {
            c.drawText(left, spec.padLeft.toFloat(), y, footerPaint)
            c.drawText(right, pageW - spec.padRight - rw, y, footerPaint)
        }
    }

    /** Soft gutter shadow so a spread looks like a bound book. */
    private fun drawSpine(c: Canvas, side: Side) {
        val w = pageW * if (side == Side.SINGLE) 0.035f else 0.07f
        val strong = if (side == Side.SINGLE) theme.spineShadow and 0x00FFFFFF or ((theme.spineShadow ushr 24) / 2 shl 24) else theme.spineShadow
        when (side) {
            Side.LEFT -> {
                shadePaint.shader = LinearGradient(pageW - w, 0f, pageW.toFloat(), 0f, 0, strong, Shader.TileMode.CLAMP)
                c.drawRect(pageW - w, 0f, pageW.toFloat(), pageH.toFloat(), shadePaint)
            }
            Side.RIGHT, Side.SINGLE -> {
                shadePaint.shader = LinearGradient(0f, 0f, w, 0f, strong, 0, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, pageH.toFloat(), shadePaint)
            }
        }
    }

    fun clear() = cache.clear()
}
