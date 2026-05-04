package tech.freebacktrack.aidca

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import java.util.regex.Pattern

/**
 * 轻量 Markdown 渲染器（推送 + 历史详情共用）。
 * 支持子集：
 *   - **bold**
 *   - *italic* / _italic_
 *   - ~~strikethrough~~
 *   - `inline code`
 *   - 行首 # / ## / ### 标题
 *   - 行首 - / * 无序列表
 *   - 行首 1. / 2. 有序列表
 *   - [text](url) 链接
 * 设计原则：识别不到的 Markdown 一律按纯文本保留，绝不丢字符。
 */
object MarkdownRenderer {

  fun render(input: String): CharSequence {
    if (input.isEmpty()) return ""
    val text = input.replace("\r\n", "\n").replace("\r", "\n")
    val out = SpannableStringBuilder()
    val lines = text.split('\n')
    for ((index, raw) in lines.withIndex()) {
      renderLine(raw, out)
      if (index < lines.size - 1) out.append('\n')
    }
    return out
  }

  private fun renderLine(raw: String, out: SpannableStringBuilder) {
    val headingMatch = HEADING_PATTERN.matcher(raw)
    if (headingMatch.matches()) {
      val level = (headingMatch.group(1) ?: "#").length.coerceIn(1, 3)
      val rest = headingMatch.group(2) ?: ""
      val start = out.length
      appendInline(rest, out, emptyList())
      val end = out.length
      if (end > start) {
        val size = when (level) { 1 -> 1.30f; 2 -> 1.15f; else -> 1.05f }
        out.setSpan(RelativeSizeSpan(size), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      return
    }
    val ulMatch = UL_PATTERN.matcher(raw)
    if (ulMatch.matches()) {
      out.append("• ")
      appendInline(ulMatch.group(1) ?: "", out, emptyList())
      return
    }
    val olMatch = OL_PATTERN.matcher(raw)
    if (olMatch.matches()) {
      out.append("${olMatch.group(1)}. ")
      appendInline(olMatch.group(2) ?: "", out, emptyList())
      return
    }
    appendInline(raw, out, emptyList())
  }

  private sealed class Style {
    object Bold : Style()
    object Italic : Style()
    object Strike : Style()
    object Underline : Style()
    object Code : Style()
    data class Link(val url: String) : Style()
  }

  private fun materialize(s: Style): Any = when (s) {
    Style.Bold -> StyleSpan(Typeface.BOLD)
    Style.Italic -> StyleSpan(Typeface.ITALIC)
    Style.Strike -> StrikethroughSpan()
    Style.Underline -> UnderlineSpan()
    Style.Code -> TypefaceSpan("monospace")
    is Style.Link -> URLSpan(s.url)
  }

  private fun applyStyles(out: SpannableStringBuilder, start: Int, end: Int, styles: List<Style>) {
    if (end <= start) return
    for (style in styles) {
      out.setSpan(materialize(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
  }

  private fun appendInline(text: String, out: SpannableStringBuilder, outerStyles: List<Style>) {
    var i = 0
    val n = text.length
    val plain = StringBuilder()

    fun flushPlain() {
      if (plain.isEmpty()) return
      val start = out.length
      out.append(plain.toString())
      val end = out.length
      applyStyles(out, start, end, outerStyles)
      plain.clear()
    }

    while (i < n) {
      val c = text[i]

      if (c == '[') {
        val close = text.indexOf("](", i + 1)
        if (close > i) {
          val rparen = text.indexOf(')', close + 2)
          if (rparen > 0) {
            val linkText = text.substring(i + 1, close)
            val url = text.substring(close + 2, rparen).trim()
            if (linkText.isNotEmpty() && url.isNotEmpty()) {
              flushPlain()
              appendInline(linkText, out, outerStyles + listOf(Style.Link(url), Style.Underline))
              i = rparen + 1
              continue
            }
          }
        }
      }

      if (c == '*' && i + 1 < n && text[i + 1] == '*') {
        val close = text.indexOf("**", i + 2)
        if (close > i + 2) {
          flushPlain()
          appendInline(text.substring(i + 2, close), out, outerStyles + Style.Bold)
          i = close + 2
          continue
        }
      }

      if (c == '~' && i + 1 < n && text[i + 1] == '~') {
        val close = text.indexOf("~~", i + 2)
        if (close > i + 2) {
          flushPlain()
          appendInline(text.substring(i + 2, close), out, outerStyles + Style.Strike)
          i = close + 2
          continue
        }
      }

      if (c == '`') {
        val close = text.indexOf('`', i + 1)
        if (close > i + 1) {
          flushPlain()
          val inner = text.substring(i + 1, close)
          val start = out.length
          out.append(inner)
          val end = out.length
          applyStyles(out, start, end, outerStyles + Style.Code)
          out.setSpan(BackgroundColorSpan(0x14000000), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
          i = close + 1
          continue
        }
      }

      if ((c == '*' || c == '_') && (i == 0 || !text[i - 1].isLetterOrDigit())) {
        val closeIdx = findClosingItalic(text, i + 1, c)
        if (closeIdx > i + 1) {
          flushPlain()
          appendInline(text.substring(i + 1, closeIdx), out, outerStyles + Style.Italic)
          i = closeIdx + 1
          continue
        }
      }

      plain.append(c)
      i += 1
    }
    flushPlain()
  }

  private fun findClosingItalic(text: String, from: Int, marker: Char): Int {
    var i = from
    while (i < text.length) {
      if (text[i] == marker) {
        if (i + 1 < text.length && text[i + 1] == marker) { i += 2; continue }
        return i
      }
      i += 1
    }
    return -1
  }

  private val HEADING_PATTERN: Pattern = Pattern.compile("^(#{1,6})\\s+(.*)$")
  private val UL_PATTERN: Pattern = Pattern.compile("^[-*]\\s+(.*)$")
  private val OL_PATTERN: Pattern = Pattern.compile("^(\\d{1,3})\\.\\s+(.*)$")
}
