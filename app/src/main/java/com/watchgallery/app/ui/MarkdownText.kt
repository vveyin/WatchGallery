package com.watchgallery.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration

/**
 * 轻量 Markdown 文本：把 Release body（GitHub/Gitee 的更新说明）里常用的语法
 * 转成 Compose 的 AnnotatedString，交给一个 Text 渲染。
 *
 * 不引入第三方库，离线可编译。支持：
 *  - 标题 `#` ~ `####`（加粗 + 强调色）
 *  - 粗体 `**x**` / `__x__`、斜体 `*x*` / `_x_`、删除线 `~~x~~`
 *  - 行内代码 `` `x` `` 与围栏代码块 ```
 *  - 链接 `[x](url)`（主色下划线）、图片 `![x](url)`（退化为斜体替代文字）
 *  - 无序列表 `- x` / `* x`、有序列表 `1. x`、引用 `> x`、分隔线 `---`
 *
 * 普通文字保持传入的 [style] 与 [color]；标记部分用 SpanStyle 覆盖。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val colors = MdColors(
        strong = MaterialTheme.colorScheme.onSurface,
        link = MaterialTheme.colorScheme.primary,
        code = MaterialTheme.colorScheme.primary,
        codeBg = MaterialTheme.colorScheme.surfaceVariant,
        quote = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val annotated = remember(markdown, colors) { markdown.toAnnotated(colors) }
    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color,
        textAlign = TextAlign.Start
    )
}

private data class MdColors(
    val strong: Color,
    val link: Color,
    val code: Color,
    val codeBg: Color,
    val quote: Color
)

private enum class InlineKind { IMAGE, LINK, BOLD_ITALIC, BOLD, STRIKE, CODE, ITALIC }

private data class InlineRule(val regex: Regex, val kind: InlineKind)

// 顺序决定优先级：先匹配到「起始位置更靠前」的规则；同一位置由这里的先后决定。
private val INLINE_RULES = listOf(
    InlineRule(Regex("""!\[([^\]]*)\]\([^)]+\)"""), InlineKind.IMAGE),
    InlineRule(Regex("""\[([^\]]+)\]\(([^)]+)\)"""), InlineKind.LINK),
    InlineRule(Regex("""\*\*\*([^*]+)\*\*\*"""), InlineKind.BOLD_ITALIC),
    InlineRule(Regex("""\*\*([^*]+)\*\*"""), InlineKind.BOLD),
    InlineRule(Regex("""__([^_]+)__"""), InlineKind.BOLD),
    InlineRule(Regex("""~~([^~]+)~~"""), InlineKind.STRIKE),
    InlineRule(Regex("""`([^`]+)`"""), InlineKind.CODE),
    InlineRule(Regex("""\*([^*]+)\*"""), InlineKind.ITALIC),
    InlineRule(Regex("""_([^_]+)_"""), InlineKind.ITALIC)
)

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^\s*[-*+]\s+(.*)$""")
private val ORDERED = Regex("""^\s*(\d+)[.)]\s+(.*)$""")
private val QUOTE = Regex("""^\s*>\s?(.*)$""")
private val RULE = Regex("""^\s*(-{3,}|\*{3,}|_{3,})\s*$""")

private fun String.toAnnotated(colors: MdColors): AnnotatedString = buildAnnotatedString {
    val lines = replace("\r\n", "\n").replace('\r', '\n').split('\n')
    var inCodeBlock = false
    for (raw in lines) {
        val trimmed = raw.trim()
        // 围栏代码块：整段用等宽 + 底色，不解析行内标记
        if (trimmed.startsWith("```")) {
            inCodeBlock = !inCodeBlock
            continue
        }
        if (inCodeBlock) {
            if (trimmed.isNotEmpty()) {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = colors.code, background = colors.codeBg))
                append(raw)
                pop()
            }
            append("\n")
            continue
        }
        when {
            trimmed.isEmpty() -> append("\n")

            HEADING.matches(trimmed) -> {
                val m = HEADING.find(trimmed)!!
                val level = m.groupValues[1].length
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.strong))
                appendInline(m.groupValues[2], colors)
                pop()
                append("\n")
            }

            BULLET.matches(trimmed) -> {
                val m = BULLET.find(trimmed)!!
                append("  • ")
                appendInline(m.groupValues[1], colors)
                append("\n")
            }

            ORDERED.matches(trimmed) -> {
                val m = ORDERED.find(trimmed)!!
                append("  ${m.groupValues[1]}. ")
                appendInline(m.groupValues[2], colors)
                append("\n")
            }

            QUOTE.matches(trimmed) -> {
                val m = QUOTE.find(trimmed)!!
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = colors.quote))
                append("  ")
                appendInline(m.groupValues[1], colors)
                pop()
                append("\n")
            }

            RULE.matches(trimmed) -> append("  ──────\n")

            else -> {
                appendInline(trimmed, colors)
                append("\n")
            }
        }
    }
}

private fun AnnotatedString.Builder.appendInline(text: String, colors: MdColors) {
    var remaining = text
    while (remaining.isNotEmpty()) {
        var bestMatch: MatchResult? = null
        var bestRule: InlineRule? = null
        for (rule in INLINE_RULES) {
            val m = rule.regex.find(remaining)
            if (m != null && (bestMatch == null || m.range.first < bestMatch.range.first)) {
                bestMatch = m
                bestRule = rule
            }
        }
        if (bestMatch == null || bestRule == null) {
            append(remaining)
            return
        }
        append(remaining.substring(0, bestMatch.range.first))
        val body = bestMatch.groupValues.getOrNull(1).orEmpty()
        when (bestRule.kind) {
            InlineKind.IMAGE -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = colors.quote))
                append(body)
                pop()
            }
            InlineKind.LINK -> {
                // 当前 Compose 版本无 LinkAnnotation；链接渲染为主色下划线条目，不做点击跳转
                pushStyle(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline))
                append(body)
                pop()
            }
            InlineKind.BOLD_ITALIC -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = colors.strong))
                append(body)
                pop()
            }
            InlineKind.BOLD -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.strong))
                append(body)
                pop()
            }
            InlineKind.STRIKE -> {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(body)
                pop()
            }
            InlineKind.CODE -> {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = colors.code, background = colors.codeBg))
                append(body)
                pop()
            }
            InlineKind.ITALIC -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(body)
                pop()
            }
        }
        remaining = remaining.substring(bestMatch.range.last + 1)
    }
}
