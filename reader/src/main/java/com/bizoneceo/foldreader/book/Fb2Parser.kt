package com.bizoneceo.foldreader.book

import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.style.AlignmentSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/** FictionBook 2 parser (the main LitRes download format). */
object Fb2Parser {

    fun parse(input: InputStream): Book = State().run(input)

    private class State {
        val chapters = ArrayList<Chapter>()
        var sb = SpannableStringBuilder()
        val chapterTitles = ArrayList<String>()
        var contentChars = 0

        // description
        var inDesc = false
        var inTitleInfo = false
        var inAuthor = false
        var authorDone = false
        var descField: String? = null
        val descText = StringBuilder()
        var bookTitle = ""
        val authorParts = ArrayList<String>()

        // body
        var inBody = false
        var bodyCount = 0
        var notesBody = false
        var sectionDepth = 0
        var titleDepth = 0
        var epigraphDepth = 0
        var poemDepth = 0
        var citeDepth = 0
        var inPara = false
        var paraTag = ""
        var paraStart = 0
        val titleText = StringBuilder()
        var skipText = false
        val inline = ArrayList<Pair<String, Int>>()

        fun run(input: InputStream): Book {
            val p = XmlPullParserFactory.newInstance().newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            try {
                p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true)
            } catch (_: Exception) {
            }
            p.setInput(input, null)
            var ev = p.eventType
            try {
                while (ev != XmlPullParser.END_DOCUMENT) {
                    when (ev) {
                        XmlPullParser.START_TAG -> start(p)
                        XmlPullParser.END_TAG -> end(localName(p.name))
                        XmlPullParser.TEXT, XmlPullParser.ENTITY_REF -> text(p.text ?: "")
                    }
                    ev = p.nextToken()
                }
            } catch (e: Exception) {
                // Broken tail of a file: keep what was parsed so far.
                if (chapters.isEmpty() && sb.isEmpty()) throw e
            }
            if (inPara) endPara()
            flush(force = true)
            val author = authorParts.joinToString(" ").trim()
            val list = if (chapters.isEmpty()) listOf(Chapter(bookTitle, "")) else chapters
            return Book(bookTitle.trim(), author, TextTools.splitLong(list), TextTools.looksCyrillic(list))
        }

        fun localName(n: String) = n.substringAfter(':')

        fun start(p: XmlPullParser) {
            val name = localName(p.name)
            if (inDesc) {
                when (name) {
                    "title-info" -> inTitleInfo = true
                    "author" -> if (inTitleInfo && !authorDone) inAuthor = true
                    "book-title" -> if (inTitleInfo) beginDesc(name)
                    "first-name", "middle-name", "last-name" -> if (inAuthor) beginDesc(name)
                }
                return
            }
            when (name) {
                "description" -> inDesc = true
                "binary" -> skipText = true
                "body" -> {
                    if (inPara) endPara()
                    val bodyName = p.getAttributeValue(null, "name") ?: ""
                    notesBody = bodyCount > 0 && (bodyName == "notes" || bodyName == "comments" || bodyName.isNotEmpty())
                    if (bodyCount > 0) flush(force = true)
                    if (notesBody) chapterTitles.add(if (bodyName == "comments") "Комментарии" else "Примечания")
                    bodyCount++
                    inBody = true
                }
                "section" -> {
                    if (inPara) endPara()
                    sectionDepth++
                    if (inBody && !notesBody && contentChars > 0) flush(force = false)
                }
                "title" -> {
                    if (inPara) endPara()
                    titleDepth++
                    if (titleDepth == 1) {
                        titleText.setLength(0)
                        blankLine()
                    }
                }
                "epigraph" -> epigraphDepth++
                "poem" -> poemDepth++
                "cite" -> citeDepth++
                "p", "v", "subtitle", "text-author" -> if (inBody) startPara(name)
                "empty-line" -> if (inBody && !inPara) blankLine()
                "emphasis" -> push("i")
                "strong" -> push("b")
                "strikethrough" -> push("s")
                "sup" -> push("sup")
                "sub" -> push("sub")
                "a" -> if (p.getAttributeValue(null, "type") == "note") push("note") else push("a")
            }
        }

        fun end(name: String) {
            if (inDesc) {
                when (name) {
                    "description" -> inDesc = false
                    "title-info" -> inTitleInfo = false
                    "author" -> if (inAuthor) { inAuthor = false; authorDone = authorParts.isNotEmpty() }
                    "book-title", "first-name", "middle-name", "last-name" -> endDesc(name)
                }
                return
            }
            when (name) {
                "binary" -> skipText = false
                "body" -> { if (inPara) endPara(); inBody = false }
                "section" -> { if (inPara) endPara(); sectionDepth-- }
                "title" -> {
                    if (inPara) endPara()
                    titleDepth--
                    if (titleDepth == 0) {
                        val t = titleText.toString().replace(Regex("\\s+"), " ").trim()
                        if (t.isNotEmpty() && inBody && !notesBody) chapterTitles.add(t)
                        blankLine()
                    }
                }
                "epigraph" -> { if (inPara) endPara(); epigraphDepth-- }
                "poem" -> { if (inPara) endPara(); poemDepth--; blankLine() }
                "cite" -> { if (inPara) endPara(); citeDepth-- }
                "p", "v", "subtitle", "text-author" -> if (inPara && paraTag == name) endPara()
                "emphasis" -> pop("i")
                "strong" -> pop("b")
                "strikethrough" -> pop("s")
                "sup" -> pop("sup")
                "sub" -> pop("sub")
                "a" -> { pop("note"); pop("a") }
            }
        }

        fun beginDesc(name: String) { descField = name; descText.setLength(0) }

        fun endDesc(name: String) {
            if (descField != name) return
            val v = descText.toString().replace(Regex("\\s+"), " ").trim()
            if (name == "book-title") {
                if (bookTitle.isEmpty()) bookTitle = v
            } else if (v.isNotEmpty()) {
                authorParts.add(v) // first-, middle-, last-name come in document order
            }
            descField = null
        }

        fun text(raw: String) {
            if (skipText) return
            if (inDesc) { if (descField != null) descText.append(raw); return }
            if (!inBody) return
            if (!inPara) {
                // Bare text directly inside <title>/<section>: treat as a paragraph.
                if (raw.isBlank()) return
                startPara("p")
            }
            var t = raw.replace(Regex("[\\s\\u00A0]+"), " ")
            if (sb.length == paraStart) t = t.trimStart()
            else if (sb[sb.length - 1] == ' ' && t.startsWith(' ')) t = t.substring(1)
            if (t.isEmpty()) return
            sb.append(t)
            if (titleDepth > 0) titleText.append(t)
        }

        fun startPara(tag: String) {
            if (inPara) endPara()
            inPara = true
            paraTag = tag
            paraStart = sb.length
        }

        fun endPara() {
            inPara = false
            while (sb.length > paraStart && sb[sb.length - 1] == ' ') sb.delete(sb.length - 1, sb.length)
            // close inline styles left open inside this paragraph
            while (inline.isNotEmpty() && inline.last().second >= paraStart) applyInline(inline.removeAt(inline.size - 1))
            if (sb.length == paraStart) return
            if (titleDepth > 0) titleText.append(' ')
            sb.append('\n')
            val s = paraStart
            val e = sb.length
            val f = TextTools.FLAGS
            when {
                titleDepth > 0 -> TextTools.heading(sb, s, e, if (sectionDepth <= 1) 1.35f else 1.2f)
                paraTag == "subtitle" -> {
                    sb.setSpan(StyleSpan(Typeface.BOLD), s, e, f)
                    TextTools.center(sb, s, e)
                }
                paraTag == "text-author" -> {
                    sb.setSpan(StyleSpan(Typeface.ITALIC), s, e, f)
                    sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), s, e, f)
                }
                epigraphDepth > 0 -> {
                    sb.setSpan(StyleSpan(Typeface.ITALIC), s, e, f)
                    sb.setSpan(BlockIndent(4f), s, e, f)
                }
                poemDepth > 0 -> sb.setSpan(BlockIndent(2f), s, e, f)
                citeDepth > 0 -> sb.setSpan(BlockIndent(1.5f), s, e, f)
                else -> sb.setSpan(ParaIndent(), s, e, f)
            }
            if (titleDepth == 0) contentChars += e - s
        }

        fun blankLine() {
            if (sb.isEmpty()) return
            if (sb.length >= 2 && sb[sb.length - 1] == '\n' && sb[sb.length - 2] == '\n') return
            if (sb[sb.length - 1] != '\n') sb.append('\n')
            sb.append('\n')
        }

        fun push(kind: String) { if (inPara) inline.add(kind to sb.length) }

        fun pop(kind: String) {
            val idx = inline.indexOfLast { it.first == kind }
            if (idx < 0) return
            applyInline(inline.removeAt(idx))
        }

        fun applyInline(item: Pair<String, Int>) {
            val (kind, start) = item
            val end = sb.length
            if (end <= start) return
            val f = TextTools.FLAGS
            when (kind) {
                "i" -> sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, f)
                "b" -> sb.setSpan(StyleSpan(Typeface.BOLD), start, end, f)
                "s" -> sb.setSpan(StrikethroughSpan(), start, end, f)
                "sup", "note" -> {
                    sb.setSpan(SuperscriptSpan(), start, end, f)
                    sb.setSpan(RelativeSizeSpan(0.7f), start, end, f)
                }
                "sub" -> {
                    sb.setSpan(SubscriptSpan(), start, end, f)
                    sb.setSpan(RelativeSizeSpan(0.7f), start, end, f)
                }
            }
        }

        fun flush(force: Boolean) {
            if (!force && contentChars == 0) return
            // trim trailing blank lines
            while (sb.length >= 2 && sb[sb.length - 1] == '\n' && sb[sb.length - 2] == '\n') sb.delete(sb.length - 1, sb.length)
            if (sb.isNotBlank()) {
                val title = chapterTitles.joinToString(". ").ifBlank { "Глава ${chapters.size + 1}" }
                chapters.add(Chapter(title, sb))
                sb = SpannableStringBuilder()
            } else {
                sb.clear()
                if (!force) return
            }
            chapterTitles.clear()
            contentChars = 0
        }
    }
}
