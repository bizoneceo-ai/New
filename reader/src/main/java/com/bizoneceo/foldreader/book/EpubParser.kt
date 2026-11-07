package com.bizoneceo.foldreader.book

import android.text.Html
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/** Minimal EPUB 2/3 reader: OPF spine order, NCX titles, XHTML via Html.fromHtml. */
object EpubParser {

    private val BODY = Regex("<body[^>]*>(.*)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val JUNK = Regex("<(script|style|svg|head)\\b[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val IMG = Regex("<(img|image)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ENCODING = Regex("encoding\\s*=\\s*[\"']([A-Za-z0-9_\\-]+)[\"']")

    fun parse(file: File): Book = ZipFile(file).use { zip ->
        val container = zip.text("META-INF/container.xml")
        val opfPath = container?.let { Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            ?: zip.entries().asSequence().map { it.name }.firstOrNull { it.endsWith(".opf", true) }
            ?: error("EPUB без OPF")
        val opf = zip.text(opfPath) ?: error("EPUB: не найден $opfPath")
        val base = opfPath.substringBeforeLast('/', "")

        val manifest = HashMap<String, Pair<String, String>>() // id -> (href, media-type)
        val spine = ArrayList<String>()
        var tocId: String? = null
        var title = ""
        var author = ""
        run {
            val p = parser(opf)
            var field: String? = null
            val buf = StringBuilder()
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (p.name.substringAfter(':')) {
                        "item" -> {
                            val id = p.getAttributeValue(null, "id")
                            val href = p.getAttributeValue(null, "href")
                            if (id != null && href != null) {
                                manifest[id] = resolve(base, href) to (p.getAttributeValue(null, "media-type") ?: "")
                            }
                        }
                        "itemref" -> p.getAttributeValue(null, "idref")?.let { spine.add(it) }
                        "spine" -> tocId = p.getAttributeValue(null, "toc")
                        "title" -> if (title.isEmpty()) { field = "title"; buf.setLength(0) }
                        "creator" -> if (author.isEmpty()) { field = "creator"; buf.setLength(0) }
                    }
                    XmlPullParser.TEXT -> if (field != null) buf.append(p.text)
                    XmlPullParser.END_TAG -> {
                        val n = p.name.substringAfter(':')
                        if (field != null && n == field) {
                            if (n == "title") title = buf.toString().trim() else author = buf.toString().trim()
                            field = null
                        }
                    }
                }
                ev = p.next()
            }
        }

        val navTitles = HashMap<String, String>()
        try {
            val ncx = tocId?.let { manifest[it]?.first }
                ?: manifest.values.firstOrNull { it.second == "application/x-dtbncx+xml" }?.first
            if (ncx != null) readNcx(zip, ncx, navTitles)
        } catch (_: Exception) {
        }

        val chapters = ArrayList<Chapter>()
        for (idref in spine) {
            val (href, type) = manifest[idref] ?: continue
            if (type.isNotEmpty() && !type.contains("html", true) && !type.contains("xml", true)) continue
            val bytes = zip.bytes(href) ?: continue
            val head = String(bytes, 0, minOf(bytes.size, 300), Charsets.ISO_8859_1)
            val cs = ENCODING.find(head)?.groupValues?.get(1)?.let {
                try { charset(it) } catch (_: Exception) { Charsets.UTF_8 }
            } ?: Charsets.UTF_8
            var html = String(bytes, cs)
            html = BODY.find(html)?.groupValues?.get(1) ?: html
            html = JUNK.replace(html, "")
            html = IMG.replace(html, "")
            val spanned = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
            val text = TextTools.normalizeHtml(spanned)
            if (text.isBlank()) continue
            val firstLine = text.substring(0, text.indexOf('\n').let { if (it < 0) text.length else it }).trim()
            val chTitle = navTitles[href]
                ?: firstLine.takeIf { it.length in 1..80 }
                ?: "Глава ${chapters.size + 1}"
            chapters.add(Chapter(chTitle, text))
        }
        if (chapters.isEmpty()) chapters.add(Chapter(title, ""))
        Book(title, author, TextTools.splitLong(chapters), TextTools.looksCyrillic(chapters))
    }

    private fun readNcx(zip: ZipFile, path: String, out: MutableMap<String, String>) {
        val xml = zip.text(path) ?: return
        val base = path.substringBeforeLast('/', "")
        val p = parser(xml)
        var inText = false
        var label: String? = null
        val buf = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "navPoint" -> label = null
                    "text" -> { inText = true; buf.setLength(0) }
                    "content" -> {
                        val src = p.getAttributeValue(null, "src")
                        val l = label
                        if (src != null && !l.isNullOrBlank()) {
                            val key = resolve(base, src.substringBefore('#'))
                            if (!out.containsKey(key)) out[key] = l
                        }
                    }
                }
                XmlPullParser.TEXT -> if (inText) buf.append(p.text)
                XmlPullParser.END_TAG -> if (p.name == "text") {
                    inText = false
                    label = buf.toString().replace(Regex("\\s+"), " ").trim()
                }
            }
            ev = p.next()
        }
    }

    private fun parser(xml: String): XmlPullParser = XmlPullParserFactory.newInstance().newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        try { setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) } catch (_: Exception) {}
        setInput(java.io.StringReader(xml))
    }

    private fun resolve(base: String, href: String): String {
        val decoded = try { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { href }
        val parts = ArrayList<String>()
        if (base.isNotEmpty()) parts.addAll(base.split('/'))
        for (seg in decoded.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun ZipFile.bytes(path: String): ByteArray? {
        val e = getEntry(path)
            ?: entries().asSequence().firstOrNull { it.name.equals(path, ignoreCase = true) }
            ?: return null
        return getInputStream(e).use { it.readBytes() }
    }

    private fun ZipFile.text(path: String): String? = bytes(path)?.toString(Charsets.UTF_8)
}
