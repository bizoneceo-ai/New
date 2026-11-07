package com.bizoneceo.foldreader.book

/** One chapter of styled text. Paragraphs are separated by '\n'. */
class Chapter(
    val title: String,
    val text: CharSequence,
    /** False for the extra parts a long chapter is split into (hidden from the TOC). */
    val inToc: Boolean = true,
)

class Book(
    val title: String,
    val author: String,
    val chapters: List<Chapter>,
    /** Mostly Cyrillic text -> Russian hyphenation rules. */
    val cyrillic: Boolean,
) {
    /** Global char offset at which each chapter starts. */
    val chapterStarts: IntArray = IntArray(chapters.size).also { arr ->
        var acc = 0
        chapters.forEachIndexed { i, c -> arr[i] = acc; acc += c.text.length }
    }
    val totalChars: Int = chapters.sumOf { it.text.length }

    fun chapterForGlobalChar(offset: Int): Int {
        var lo = 0
        var hi = chapters.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (chapterStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo.coerceAtLeast(0)
    }
}
