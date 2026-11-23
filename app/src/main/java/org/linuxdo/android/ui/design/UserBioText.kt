package org.linuxdo.android.ui.design

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import coil.compose.AsyncImage
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.linuxdo.android.ui.html.InlineText
import org.linuxdo.android.ui.html.contentUrl

/** 资料卡和详细主页共用简介渲染，保留普通及自定义表情图片。 */
@Composable
internal fun UserBioText(
    html: String?,
    emptyText: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    emptyColor: Color = color,
    maxLines: Int = 2,
) {
    val runs = remember(html) { bioInlineText(html.orEmpty()) }
    val text = remember(runs) {
        buildAnnotatedString {
            runs.forEach { run ->
                val url = run.emojiUrl
                if (url == null) append(run.text) else appendInlineContent(url, run.text.ifBlank { "表情" })
            }
        }
    }
    val emojis = remember(runs) {
        runs.mapNotNull { it.emojiUrl }.distinct().associateWith { url ->
            InlineTextContent(Placeholder(1.15.em, 1.15.em, PlaceholderVerticalAlign.TextCenter)) { alt ->
                AsyncImage(
                    model = url,
                    contentDescription = alt,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
    Text(
        text = if (text.isBlank()) AnnotatedString(emptyText) else text,
        inlineContent = emojis,
        style = style,
        color = if (text.isBlank()) emptyColor else color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 保留表情图片和段落换行，其余图片降级为 alt 文本。 */
private fun bioInlineText(html: String): List<InlineText> {
    val document = Jsoup.parse(html)
    val emojis = mutableListOf<InlineText>()
    document.select("br").forEach { it.replaceWith(TextNode(NEWLINE_MARKER)) }
    document.select("p").forEach { it.append(NEWLINE_MARKER) }
    document.select("img").forEach { image ->
        val url = image.attr("src").takeIf { it.isNotBlank() }?.let(::contentUrl)
        val alt = image.attr("alt")
        if (image.hasClass("emoji") && url != null) {
            // 非空白占位符避免简介首尾的表情被 Jsoup.text() 的 trim 移除。
            val marker = "\uE000${emojis.size}\uE001"
            emojis += InlineText(text = alt, emojiUrl = url)
            image.replaceWith(TextNode(marker))
        } else {
            image.replaceWith(TextNode(alt))
        }
    }
    val text = document.text()
        .replace(NEWLINE_MARKER, "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
    return buildList {
        var offset = 0
        BIO_EMOJI_MARKER.findAll(text).forEach { match ->
            if (match.range.first > offset) add(InlineText(text.substring(offset, match.range.first)))
            add(emojis.getOrNull(match.groupValues[1].toIntOrNull() ?: -1) ?: InlineText(match.value))
            offset = match.range.last + 1
        }
        if (offset < text.length) add(InlineText(text.substring(offset)))
    }
}

private const val NEWLINE_MARKER = "\u0001"
private val BIO_EMOJI_MARKER = Regex("\uE000(\\d+)\uE001")
