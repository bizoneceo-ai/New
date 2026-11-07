package com.bizoneceo.foldreader.book

import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipFile

object BookLoader {
    enum class Format { EPUB, FB2, FB2_ZIP, TXT }

    fun detect(file: File): Format {
        val head = ByteArray(1024)
        val n = file.inputStream().use { it.read(head) }.coerceAtLeast(0)
        if (n >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            ZipFile(file).use { zip ->
                if (zip.getEntry("META-INF/container.xml") != null) return Format.EPUB
                if (zip.entries().asSequence().any { it.name.endsWith(".fb2", true) }) return Format.FB2_ZIP
                if (zip.entries().asSequence().any { it.name.endsWith(".opf", true) }) return Format.EPUB
            }
            error("Архив не содержит книгу FB2/EPUB")
        }
        val s = String(head, 0, n, Charsets.ISO_8859_1)
        return if (s.contains("<FictionBook") || s.trimStart('\uFEFF', ' ', '\n', '\r').startsWith("<?xml")) Format.FB2 else Format.TXT
    }

    fun load(file: File, fallbackTitle: String = file.nameWithoutExtension): Book {
        val book = when (detect(file)) {
            Format.EPUB -> EpubParser.parse(file)
            Format.FB2 -> BufferedInputStream(file.inputStream()).use { Fb2Parser.parse(it) }
            Format.FB2_ZIP -> ZipFile(file).use { zip ->
                val e = zip.entries().asSequence().first { it.name.endsWith(".fb2", true) }
                BufferedInputStream(zip.getInputStream(e)).use { Fb2Parser.parse(it) }
            }
            Format.TXT -> TxtParser.parse(file.readBytes(), fallbackTitle)
        }
        return if (book.title.isBlank()) Book(fallbackTitle, book.author, book.chapters, book.cyrillic) else book
    }
}
