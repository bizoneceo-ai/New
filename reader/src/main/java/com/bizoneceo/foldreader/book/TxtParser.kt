package com.bizoneceo.foldreader.book

import android.text.SpannableStringBuilder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

object TxtParser {
    private val CHAPTER = Regex("^(глава|часть|chapter|part|пролог|эпилог|prologue|epilogue)\\b.{0,60}$", RegexOption.IGNORE_CASE)

    fun parse(bytes: ByteArray, fallbackTitle: String): Book {
        val text = decode(bytes)
        val chapters = ArrayList<Chapter>()
        var sb = SpannableStringBuilder()
        var title = fallbackTitle
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (CHAPTER.matches(line)) {
                if (sb.isNotBlank()) chapters.add(Chapter(title, sb))
                sb = SpannableStringBuilder()
                title = line
                val s = sb.length
                sb.append(line).append('\n')
                TextTools.heading(sb, s, sb.length, 1.3f)
                sb.append('\n')
                continue
            }
            val s = sb.length
            sb.append(line).append('\n')
            sb.setSpan(ParaIndent(), s, sb.length, TextTools.FLAGS)
        }
        if (sb.isNotBlank() || chapters.isEmpty()) chapters.add(Chapter(title, sb))
        return Book(fallbackTitle, "", TextTools.splitLong(chapters), TextTools.looksCyrillic(chapters))
    }

    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, charset("windows-1251"))
        }
    }
}
