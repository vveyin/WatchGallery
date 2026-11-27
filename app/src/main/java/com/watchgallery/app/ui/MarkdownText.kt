package com.watchgallery.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit

/**
 * 轻量 Markdown 文本：把 Release body（GitHub/Gitee 的更新说明）转成 AnnotatedString，
 * 用 ClickableText 渲染。不引入第三方库，离线可编译。
 *
 * 支持的块级元素：
 *  - 标题：ATX `#` ~ `######`（按级别缩放字号）、Setext（`===` / `---` 下划线）
 *  - 无序列表 `-`/`*`/`+`、有序列表 `1.`、任务列表 `- [ ]`/`- [x]`（按前导空格分级缩进）
 *  - 引用 `>`、分隔线 `---`/`***`/`___`、围栏代码块 ```
 *  - GFM 表格（表头加粗、单元格分栏）
 *
 * 支持的行内元素：
 *  - 粗体 `**x**`/`__x__`、斜体 `*x*`/`_x_`、粗斜体 `***x***`、删除线 `~~x~~`
 *  - 行内代码 `` `x` ``、链接 `[x](url)`（可点击）、自动链接 `<url>`、图片 `![x](url)`（替代文字）
 *  - 转义 `\x`
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val context = LocalContext.current
    val colors = MdColors(
        strong = MaterialTheme.colorScheme.onSurface,
        link = MaterialTheme.colorScheme.primary,
        code = MaterialTheme.colorScheme.primary,
        codeBg = MaterialTheme.colorScheme.surfaceVariant,
        quote = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val annotated = remember(markdown, colors, style.fontSize) {
        markdown.toAnnotated(colors, style.fontSize)
    }
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = style.copy(color = color),
        onClick = { offset ->
            annotated.getStringAnnotations(URL_TAG, offset, offset)
                .firstOrNull()?.item?.let { url ->
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                }
        }
    )
}

private const val URL_TAG = "URL"
private val NBSP = "\u00A0"

private data class MdColors(
    val strong: Color,
    val link: Color,
    val code: Color,
    val codeBg: Color,
    val quote: Color
)

private enum class InlineKind { IMAGE, LINK, AUTOLINK, BOLD_ITALIC, BOLD, STRIKE, CODE, ITALIC }

private data class InlineRule(val regex: Regex, val kind: InlineKind)

// 同一位置由这里的先后决定优先级；更靠前出现的匹配优先被处理。
private val INLINE_RULES = listOf(
    InlineRule(Regex("""!\[([^\]]*)\]\([^)]*\)"""), InlineKind.IMAGE),
    InlineRule(Regex("""\[([^\]]+)\]\(([^)]+)\)"""), InlineKind.LINK),
    InlineRule(Regex("""<(https?://[^>]+)>"""), InlineKind.AUTOLINK),
    InlineRule(Regex("""\*\*\*([^*]+)\*\*\*"""), InlineKind.BOLD_ITALIC),
    InlineRule(Regex("""___([^_]+)___"""), InlineKind.BOLD_ITALIC),
    InlineRule(Regex("""\*\*([^*]+)\*\*"""), InlineKind.BOLD),
    InlineRule(Regex("""__([^_]+)__"""), InlineKind.BOLD),
    InlineRule(Regex("""~~([^~]+)~~"""), InlineKind.STRIKE),
    InlineRule(Regex("""`([^`]+)`"""), InlineKind.CODE),
    InlineRule(Regex("""\*([^*\n]+)\*"""), InlineKind.ITALIC),
    InlineRule(Regex("""_([^_\n]+)_"""), InlineKind.ITALIC)
)

// 转义只处理 Markdown 特殊字符，避免出现 \a 这类被误去掉反斜杠
private val ESCAPE = Regex("""\\([\\`*_{}\[\]()#+\-.!>~])""")

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val SETEXT = Regex("""^(=+|-{2,})$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val TASK = Regex("""^(\s*)[-*+]\s+\[([ xX])\]\s*(.*)$""")
private val ORDERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val QUOTE = Regex("""^(\s*)>\s?(.*)$""")
private val RULE = Regex("""^\s*(-{3,}|\*{3,}|_{3,})\s*$""")
private val TABLE_DELIM = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)+\|?\s*$""")

private fun String.toAnnotated(colors: MdColors, baseSize: TextUnit): AnnotatedString = buildAnnotatedString {
    val lines = replace("\r\n", "\n").replace('\r', '\n').split('\n')
    var i = 0
    var inCodeBlock = false
    while (i < lines.size) {
        val raw = lines[i]
        val trimmed = raw.trim()

        // 围栏代码块：整段等宽 + 底色，不解析任何行内标记
        if (trimmed.startsWith("```")) {
            inCodeBlock = !inCodeBlock
            i++
            continue
        }
        if (inCodeBlock) {
            pushStyle(codeSpan(colors))
            append(raw)
            pop()
            append("\n")
            i++
            continue
        }

        // GFM 表格：当前行含 |，下一行是分隔行
        if (trimmed.contains('|') && i + 1 < lines.size && TABLE_DELIM.matches(lines[i + 1])) {
            val header = splitRow(trimmed)
            header.forEachIndexed { idx, cell ->
                if (idx > 0) append("$NBSP│$NBSP")
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.strong))
                appendInline(cell, colors)
                pop()
            }
            append("\n")
            pushStyle(SpanStyle(color = colors.quote))
            append(header.joinToString("$NBSP┼$NBSP") { "─".repeat(it.length.coerceIn(3, 12)) })
            pop()
            append("\n")
            i += 2
            while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                val cells = splitRow(lines[i])
                cells.forEachIndexed { idx, cell ->
                    if (idx > 0) append("$NBSP│$NBSP")
                    appendInline(cell, colors)
                }
                append("\n")
                i++
            }
            continue
        }

        // Setext 标题：文本行 + `===`(H1) 或 `---`(H2) 下划线
        if (trimmed.isNotEmpty() && i + 1 < lines.size && SETEXT.matches(lines[i + 1].trim())) {
            val h1 = lines[i + 1].trim().startsWith('=')
            appendHeading(trimmed, if (h1) 1 else 2, colors, baseSize)
            i += 2
            continue
        }

        when {
            trimmed.isEmpty() -> append("\n")

            HEADING.matches(trimmed) -> {
                val m = HEADING.find(trimmed)!!
                appendHeading(m.groupValues[2], m.groupValues[1].length, colors, baseSize)
            }

            RULE.matches(trimmed) -> append("  ──────\n")

            TASK.matches(raw) -> {
                val m = TASK.find(raw)!!
                val indent = indentOf(m.groupValues[1])
                val mark = if (m.groupValues[2].equals("x", true)) "☑" else "☐"
                append("$indent$mark$NBSP")
                appendInline(m.groupValues[3], colors)
                append("\n")
            }

            BULLET.matches(raw) -> {
                val m = BULLET.find(raw)!!
                val indent = indentOf(m.groupValues[1])
                append("$indent•$NBSP")
                appendInline(m.groupValues[2], colors)
                append("\n")
            }

            ORDERED.matches(raw) -> {
                val m = ORDERED.find(raw)!!
                val indent = indentOf(m.groupValues[1])
                append("$indent${m.groupValues[2]}.$NBSP")
                appendInline(m.groupValues[3], colors)
                append("\n")
            }

            QUOTE.matches(raw) -> {
                val m = QUOTE.find(raw)!!
                val indent = indentOf(m.groupValues[1])
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = colors.quote))
                append("${indent}│$NBSP")
                appendInline(m.groupValues[2], colors)
                pop()
                append("\n")
            }

            else -> {
                appendInline(trimmed, colors)
                append("\n")
            }
        }
        i++
    }
}

private fun AnnotatedString.Builder.appendHeading(
    text: String,
    level: Int,
    colors: MdColors,
    baseSize: TextUnit
) {
    val factor = when (level) {
        1 -> 1.5f
        2 -> 1.3f
        3 -> 1.15f
        4 -> 1.05f
        else -> 1f
    }
    pushStyle(
        SpanStyle(
            fontWeight = FontWeight.Bold,
            color = colors.strong,
            fontSize = baseSize * factor
        )
    )
    appendInline(text, colors)
    pop()
    append("\n")
}

private fun codeSpan(colors: MdColors) = SpanStyle(
    fontFamily = FontFamily.Monospace,
    color = colors.code,
    background = colors.codeBg
)

private fun indentOf(leading: String): String {
    val level = leading.length / 2
    return if (level <= 0) "" else NBSP.repeat(level * 2)
}

private fun splitRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.drop(1)
    if (s.endsWith("|")) s = s.dropLast(1)
    return s.split('|').map { it.trim() }
}

private fun AnnotatedString.Builder.appendInline(text: String, colors: MdColors) {
    var remaining = text
    while (remaining.isNotEmpty()) {
        // 转义优先：若 `\*` 出现在任何标记之前，先把它原样输出，避免被当斜体等解析
        val esc = ESCAPE.find(remaining)
        var bestMatch: MatchResult? = null
        var bestRule: InlineRule? = null
        for (rule in INLINE_RULES) {
            val m = rule.regex.find(remaining)
            if (m != null && (bestMatch == null || m.range.first < bestMatch.range.first)) {
                bestMatch = m
                bestRule = rule
            }
        }
        if (esc != null && (bestMatch == null || esc.range.first <= bestMatch.range.first)) {
            append(remaining.substring(0, esc.range.first))
            append(esc.groupValues[1])
            remaining = remaining.substring(esc.range.last + 1)
            continue
        }
        if (bestMatch == null || bestRule == null) {
            append(remaining)
            return
        }
        append(remaining.substring(0, bestMatch.range.first))
        val groups = bestMatch.groupValues
        when (bestRule.kind) {
            InlineKind.IMAGE -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = colors.quote))
                append(groups.getOrNull(1).orEmpty())
                pop()
            }
            InlineKind.LINK -> appendLink(groups.getOrNull(1).orEmpty(), groups.getOrNull(2).orEmpty(), colors)
            InlineKind.AUTOLINK -> {
                val url = groups.getOrNull(1).orEmpty()
                appendLink(url, url, colors)
            }
            InlineKind.BOLD_ITALIC -> styled(groups.getOrNull(1).orEmpty(), colors) {
                SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = colors.strong)
            }
            InlineKind.BOLD -> styled(groups.getOrNull(1).orEmpty(), colors) {
                SpanStyle(fontWeight = FontWeight.Bold, color = colors.strong)
            }
            InlineKind.STRIKE -> styled(groups.getOrNull(1).orEmpty(), colors) {
                SpanStyle(textDecoration = TextDecoration.LineThrough)
            }
            InlineKind.CODE -> styled(groups.getOrNull(1).orEmpty(), colors) { codeSpan(colors) }
            InlineKind.ITALIC -> styled(groups.getOrNull(1).orEmpty(), colors) {
                SpanStyle(fontStyle = FontStyle.Italic)
            }
        }
        remaining = remaining.substring(bestMatch.range.last + 1)
    }
}

private fun AnnotatedString.Builder.appendLink(label: String, url: String, colors: MdColors) {
    pushStyle(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline))
    pushStringAnnotation(URL_TAG, url)
    append(label)
    pop() // URL annotation
    pop() // link style
}

private inline fun AnnotatedString.Builder.styled(
    body: String,
    colors: MdColors,
    crossinline style: () -> SpanStyle
) {
    pushStyle(style())
    appendInline(body, colors) // 支持样式嵌套，如 **粗体里含 `代码`**
    pop()
}
