package org.linuxdo.android.ui.html

import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.linuxdo.android.Config

data class InlineText(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val href: String? = null,
    val emojiUrl: String? = null,
    val isAttachment: Boolean = false,
)

sealed interface CookedBlock {
    data class Paragraph(val runs: List<InlineText>) : CookedBlock
    data class Code(val text: String) : CookedBlock
    data class Quote(val blocks: List<CookedBlock>) : CookedBlock
    data class Picture(val url: String, val description: String, val originalUrl: String = url) : CookedBlock
    data class ListBlock(val entries: List<List<CookedBlock>>, val start: Int?) : CookedBlock
    data object Divider : CookedBlock
}

/** Only HTTP(S) links are handed to the image loader or external browser. */
fun contentUrl(value: String): String? = runCatching {
    if (value.isBlank()) return null
    URI(Config.BASE_URL + "/").resolve(value.trim()).takeIf {
        it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null
    }?.toASCIIString()
}.getOrNull()

private val ATTACHMENT_EXTENSIONS = setOf(
    "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz",
    "apk", "apks", "xapk", "ipa", "exe", "msi", "dmg", "pkg", "deb", "rpm",
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "txt",
    "json", "xml", "yaml", "yml", "toml", "ini", "conf", "log", "sql",
    "sh", "bat", "ps1", "py", "jar", "iso", "mp3", "flac", "wav", "mp4", "mkv", "mov",
)

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "avif", "heic")

fun isDownloadableAttachmentUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val path = uri.path?.lowercase().orEmpty()
    if (path.startsWith("/uploads/short-url/") || path.startsWith("/uploads/default/")) {
        val ext = path.substringAfterLast('.', "")
        if (ext !in IMAGE_EXTENSIONS) return true
    }
    val ext = path.substringAfterLast('.', "")
    return ext in ATTACHMENT_EXTENSIONS
}

class CookedParser(private val onUnknown: (String) -> Unit = {}) {
    private val reported = mutableSetOf<String>()

    fun parse(html: String): List<CookedBlock> = blocks(Jsoup.parseBodyFragment(html).body().childNodes())

    private fun isLightboxMeta(element: Element): Boolean =
        element.hasClass("meta") ||
            element.hasClass("filename") ||
            element.hasClass("informations")

    private fun resolvePictureUrls(img: Element): Pair<String, String>? {
        val rawSrc = contentUrl(img.attr("src"))
        val srcsetBest = img.attr("srcset")
            .split(",")
            .mapNotNull { entry ->
                val candidate = entry.trim().substringBefore(" ").trim()
                contentUrl(candidate)
            }
            .lastOrNull()
        val lightboxHref = img.parents()
            .firstOrNull { it.normalName() == "a" && (it.hasClass("lightbox") || it.hasAttr("data-download-href")) }
            ?.let { anchor ->
                contentUrl(anchor.attr("href")) ?: contentUrl(anchor.attr("data-download-href"))
            }
        val originalUrl = lightboxHref ?: srcsetBest ?: rawSrc ?: return null
        val displayUrl = srcsetBest ?: lightboxHref ?: rawSrc ?: originalUrl
        return displayUrl to originalUrl
    }

    private fun pictureBlockOf(img: Element): CookedBlock.Picture? {
        val urls = resolvePictureUrls(img) ?: return null
        return CookedBlock.Picture(
            url = urls.first,
            description = img.attr("alt").ifBlank { "帖子图片" },
            originalUrl = urls.second,
        )
    }

    private fun blocks(nodes: List<Node>): List<CookedBlock> {
        val output = mutableListOf<CookedBlock>()
        val pending = mutableListOf<InlineText>()
        fun flush() {
            if (pending.any { it.text.isNotBlank() }) output += CookedBlock.Paragraph(pending.toList())
            pending.clear()
        }
        for (node in nodes) {
            val element = node as? Element
            if (element != null && isLightboxMeta(element)) continue
            if (element != null && (element.hasClass("lightbox-wrapper") || element.hasClass("lightbox"))) {
                flush()
                element.select("img:not(.emoji)").mapNotNullTo(output, ::pictureBlockOf)
                continue
            }
            when (element?.normalName()) {
                "script", "style", "noscript" -> Unit
                "pre" -> { flush(); output += CookedBlock.Code(element.wholeText().trimEnd()) }
                "blockquote" -> { flush(); output += CookedBlock.Quote(blocks(element.childNodes())) }
                "aside" -> {
                    flush()
                    val children = blocks(element.childNodes())
                    output += if (element.hasClass("quote")) listOf(CookedBlock.Quote(children)) else children
                }
                "ul", "ol" -> {
                    flush()
                    output += CookedBlock.ListBlock(
                        element.children().filter { it.normalName() == "li" }.map { blocks(it.childNodes()) },
                        if (element.normalName() == "ol") element.attr("start").toIntOrNull() ?: 1 else null,
                    )
                }
                "hr" -> { flush(); output += CookedBlock.Divider }
                "table" -> {
                    flush()
                    output += CookedBlock.Code(element.select("tr").joinToString("\n") { row ->
                        row.children().joinToString(" | ") { it.text() }
                    })
                }
                "img" -> {
                    if (element.hasClass("emoji")) pending += inline(element)
                    else {
                        flush()
                        val pic = pictureBlockOf(element)
                        if (pic != null) output += pic
                        else pending += InlineText(element.attr("alt"))
                    }
                }
                "p", "div", "section", "article", "details", "summary", "figure", "figcaption", "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    flush()
                    output += blocks(element.childNodes())
                }
                else -> {
                    if (element != null && element.select("img:not(.emoji)").isNotEmpty()) {
                        flush()
                        output += blocks(element.childNodes())
                    } else pending += inline(node)
                }
            }
        }
        flush()
        return output
    }

    private fun inline(node: Node, style: InlineText = InlineText("")): List<InlineText> {
        if (node is TextNode) return listOf(style.copy(text = node.text()))
        if (node !is Element) return emptyList()
        if (isLightboxMeta(node)) return emptyList()
        val updated = when (node.normalName()) {
            "script", "style", "noscript" -> return emptyList()
            "br" -> return listOf(style.copy(text = "\n"))
            "strong", "b" -> style.copy(bold = true)
            "em", "i" -> style.copy(italic = true)
            "code" -> style.copy(code = true)
            "a" -> {
                val resolvedHref = contentUrl(node.attr("href"))
                val attachment = node.hasClass("attachment") ||
                    node.hasAttr("download") ||
                    (resolvedHref != null && isDownloadableAttachmentUrl(resolvedHref))
                style.copy(href = resolvedHref, isAttachment = style.isAttachment || attachment)
            }
            "img" -> return listOf(style.copy(text = node.attr("alt"), emojiUrl = contentUrl(node.attr("src"))))
            "span", "s", "del", "u", "small", "sup", "sub", "mark", "kbd" -> style
            else -> {
                if (reported.add(node.normalName())) onUnknown(node.normalName())
                style
            }
        }
        return node.childNodes().flatMap { inline(it, updated) }
    }
}
