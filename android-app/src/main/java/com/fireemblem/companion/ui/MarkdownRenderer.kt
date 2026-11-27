package com.fireemblem.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.ui.theme.tabularNumbers

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class BulletItem(val text: String) : MdBlock
    data class OrderedItem(val number: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class CodeBlock(val code: String) : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

/**
 * Lightweight, zero-dependency native Compose GFM Markdown renderer supporting:
 * - GFM pipe tables (`| ... |` with horizontal scroll and styled headers)
 * - Headings (`#`..`####`), bullet & ordered lists, blockquotes, fenced code blocks, rules
 * - Inline `**bold**`, `*italic*`, `` `code` ``, `~~strike~~` (CJK-punctuation safe)
 */
@Composable
internal fun MarkdownText(
    markdown: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    baseStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val codeFg = MaterialTheme.colorScheme.primary
    val subtleBg = MaterialTheme.colorScheme.surfaceContainerLow
    val headerBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    val headerFg = MaterialTheme.colorScheme.onPrimaryContainer
    val outline = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }.copy(fontWeight = FontWeight.Bold)
                    Text(
                        text = parseInlineMarkdown(block.text, codeBg, codeFg),
                        style = style,
                        color = if (block.level <= 2) MaterialTheme.colorScheme.primary else color,
                    )
                }
                is MdBlock.Paragraph -> {
                    Text(
                        text = parseInlineMarkdown(block.text, codeBg, codeFg),
                        style = baseStyle,
                        color = color,
                    )
                }
                is MdBlock.BulletItem -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("•", style = baseStyle, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = parseInlineMarkdown(block.text, codeBg, codeFg),
                            style = baseStyle,
                            color = color,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                is MdBlock.OrderedItem -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "${block.number}.",
                            style = baseStyle.tabularNumbers(),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = parseInlineMarkdown(block.text, codeBg, codeFg),
                            style = baseStyle,
                            color = color,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                is MdBlock.Quote -> {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                        )
                        Text(
                            text = parseInlineMarkdown(block.text, codeBg, codeFg),
                            style = baseStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                        )
                    }
                }
                is MdBlock.CodeBlock -> {
                    Surface(
                        color = subtleBg,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, outline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = block.code,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = color,
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(10.dp),
                        )
                    }
                }
                is MdBlock.Table -> {
                    val colCount = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
                    val colWidths = remember(block) {
                        List(colCount) { c ->
                            val headerUnits = estimateVisualUnits(stripMdMarkers(block.headers.getOrElse(c) { "" }))
                            val maxRowUnits = block.rows.maxOfOrNull { row ->
                                estimateVisualUnits(stripMdMarkers(row.getOrElse(c) { "" }))
                            } ?: 0f
                            val units = maxOf(headerUnits, maxRowUnits)
                            (units * 8.5f + 24f).coerceIn(78f, 230f).dp
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, outline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier
                                .horizontalScroll(rememberScrollState())
                                .width(IntrinsicSize.Max),
                        ) {
                            // Header Row
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(headerBg)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                for (c in 0 until colCount) {
                                    Text(
                                        text = parseInlineMarkdown(block.headers.getOrElse(c) { "" }, codeBg, codeFg),
                                        style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                                        fontWeight = FontWeight.Bold,
                                        color = headerFg,
                                        modifier = Modifier.width(colWidths[c]),
                                    )
                                }
                            }
                            block.rows.forEachIndexed { rIdx, rowCells ->
                                HorizontalDivider(color = outline.copy(alpha = 0.6f))
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (rIdx % 2 == 1) subtleBg else Color.Transparent)
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    for (c in 0 until colCount) {
                                        Text(
                                            text = parseInlineMarkdown(rowCells.getOrElse(c) { "" }, codeBg, codeFg),
                                            style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                                            color = color,
                                            modifier = Modifier.width(colWidths[c]),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                MdBlock.Rule -> HorizontalDivider(color = outline)
            }
        }
    }
}

private fun parseMarkdownBlocks(raw: String): List<MdBlock> {
    val lines = raw.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MdBlock>()
    val paraBuf = mutableListOf<String>()

    fun flushPara() {
        if (paraBuf.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paraBuf.joinToString("\n").trim())
            paraBuf.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.isEmpty()) {
            flushPara()
            i++
            continue
        }

        // Fenced code block
        if (trimmed.startsWith("```")) {
            flushPara()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines += lines[i]
                i++
            }
            if (i < lines.size) i++
            blocks += MdBlock.CodeBlock(codeLines.joinToString("\n"))
            continue
        }

        // GFM Table: line contains '|' and next line is separator like |---|---|
        if (trimmed.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1].trim())) {
            flushPara()
            val headers = splitTableRow(trimmed)
            i += 2 // skip header + separator
            val rows = mutableListOf<List<String>>()
            while (i < lines.size) {
                val rTrim = lines[i].trim()
                if (rTrim.isEmpty() || !rTrim.contains('|')) break
                if (isTableSeparator(rTrim)) {
                    i++
                    continue
                }
                rows += splitTableRow(rTrim)
                i++
            }
            blocks += MdBlock.Table(headers, rows)
            continue
        }

        // Horizontal rule
        if (trimmed.matches(Regex("^[-*_]{3,}$"))) {
            flushPara()
            blocks += MdBlock.Rule
            i++
            continue
        }

        // Heading
        val headingMatch = Regex("^(#{1,4})\\s+(.+)$").matchEntire(trimmed)
        if (headingMatch != null) {
            flushPara()
            blocks += MdBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2].trim())
            i++
            continue
        }

        // Blockquote
        if (trimmed.startsWith(">")) {
            flushPara()
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quoteLines += lines[i].trim().removePrefix(">").trim()
                i++
            }
            blocks += MdBlock.Quote(quoteLines.joinToString("\n"))
            continue
        }

        // Bullet list
        val bulletMatch = Regex("^[-*•]\\s+(.+)$").matchEntire(trimmed)
        if (bulletMatch != null) {
            flushPara()
            blocks += MdBlock.BulletItem(bulletMatch.groupValues[1])
            i++
            continue
        }

        // Ordered list
        val orderedMatch = Regex("^(\\d+)[.)、]\\s*(.+)$").matchEntire(trimmed)
        if (orderedMatch != null) {
            flushPara()
            blocks += MdBlock.OrderedItem(orderedMatch.groupValues[1], orderedMatch.groupValues[2])
            i++
            continue
        }

        paraBuf += line
        i++
    }
    flushPara()
    return blocks
}

private fun isTableSeparator(line: String): Boolean {
    if (!line.contains('-')) return false
    val stripped = line.trim().trim('|')
    if (stripped.isEmpty()) return false
    return stripped.split('|').all { cell ->
        cell.trim().matches(Regex("^:?-{2,}:?$"))
    }
}

private fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

private fun stripMdMarkers(text: String): String =
    text.replace("**", "").replace("~~", "").replace("`", "").trim()

private fun estimateVisualUnits(text: String): Float {
    var total = 0f
    for (ch in text) {
        total += if (ch.code > 0x7F) 1.75f else 0.95f
    }
    return total
}

internal fun parseInlineMarkdown(
    text: String,
    codeBg: Color,
    codeFg: Color,
): AnnotatedString = buildAnnotatedString {
    appendInlineMarkdown(text, codeBg, codeFg)
}

private fun AnnotatedString.Builder.appendInlineMarkdown(
    text: String,
    codeBg: Color,
    codeFg: Color,
) {
    var i = 0
    val n = text.length
    while (i < n) {
        // Inline code: `...`
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBg,
                        color = codeFg,
                    ),
                )
                append(text.substring(i + 1, end))
                pop()
                i = end + 1
                continue
            }
        }
        // Bold: **...** (recursively handles nested `code` / *italic*)
        if (i + 1 < n && text[i] == '*' && text[i + 1] == '*') {
            val end = text.indexOf("**", i + 2)
            if (end > i + 2) {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                appendInlineMarkdown(text.substring(i + 2, end), codeBg, codeFg)
                pop()
                i = end + 2
                continue
            }
        }
        // Strikethrough: ~~...~~
        if (i + 1 < n && text[i] == '~' && text[i + 1] == '~') {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 2) {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                appendInlineMarkdown(text.substring(i + 2, end), codeBg, codeFg)
                pop()
                i = end + 2
                continue
            }
        }
        // Italic: *...* (single asterisk not followed by space)
        if (text[i] == '*' && (i + 1 < n && text[i + 1] != '*' && text[i + 1] != ' ')) {
            val end = text.indexOf('*', i + 1)
            if (end > i + 1) {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                appendInlineMarkdown(text.substring(i + 1, end), codeBg, codeFg)
                pop()
                i = end + 1
                continue
            }
        }
        append(text[i])
        i++
    }
}
