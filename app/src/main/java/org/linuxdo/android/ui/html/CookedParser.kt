package org.linuxdo.android.ui.html

import android.util.LruCache
import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.linuxdo.android.Config
import org.linuxdo.android.data.Post
import org.linuxdo.android.data.DiscoursePoll

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
    data class Code(val text: String, val copyText: String? = null) : CookedBlock
    data class Quote(val blocks: List<CookedBlock>) : CookedBlock
    data class Picture(
        val url: String,
        val description: String,
        val originalUrl: String = url,
        val width: Int = 0,
        val height: Int = 0,
    ) : CookedBlock
    data class Video(val url: String, val posterUrl: String? = null, val title: String = "视频", val mimeType: String? = null) : CookedBlock
    data class Audio(val url: String, val title: String = "音频", val mimeType: String? = null) : CookedBlock
    data class Details(val id: String, val summary: List<InlineText>, val blocks: List<CookedBlock>, val initiallyOpen: Boolean = false) : CookedBlock
    data class ImageGrid(val pictures: List<Picture>, val columns: Int = 2) : CookedBlock
    data class Table(val rows: List<List<TableCell>>, val columns: Int, val caption: List<InlineText> = emptyList(), val headerRows: Int = 0) : CookedBlock
    data class TableCell(
        val blocks: List<CookedBlock>,
        val column: Int,
        val columnSpan: Int = 1,
        val rowSpan: Int = 1,
        val header: Boolean = false,
    )
    data class Heading(val level: Int, val title: String, val anchor: String) : CookedBlock
    data class Onebox(val url: String, val title: String, val description: String? = null, val posterUrl: String? = null) : CookedBlock
    data class Poll(
        val name: String,
        val title: String,
        val type: String,
        val status: String,
        val results: String,
        val options: List<Option>,
        val userVotes: Set<String>,
        val voters: Int,
    ) : CookedBlock {
        data class Option(val id: String, val title: String, val votes: Int)
    }
    data class ListBlock(val entries: List<List<CookedBlock>>, val start: Int?, val continuation: Boolean = false) : CookedBlock
    data object Divider : CookedBlock
}

/** 分段与屏幕宽度无关，重进页面时可用同一个段号恢复位置。 */
internal fun segmentCookedBlocks(blocks: List<CookedBlock>, budget: Int = 1400): List<List<CookedBlock>> {
    require(budget >= 64)
    fun textCost(text: String): Int = text.length + text.count { it == '\n' } * 30
    fun cut(text: String): List<String> {
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = start
            var weight = 0
            while (end < text.length) {
                val count = Character.charCount(Character.codePointAt(text, end))
                val nextCost = count + if (text[end] == '\n') 30 else 0
                if (end > start && weight + nextCost > budget) break
                weight += nextCost
                end += count
            }
            if (end < text.length) {
                val newline = text.lastIndexOf('\n', end - 1)
                if (newline >= start + (end - start) / 2) end = newline + 1
            }
            pieces += text.substring(start, end)
            start = end
        }
        return pieces.ifEmpty { listOf("") }
    }
    fun cost(block: CookedBlock): Int = when (block) {
        is CookedBlock.Paragraph -> block.runs.sumOf { textCost(it.text).coerceAtLeast(1) }
        is CookedBlock.Code -> textCost(block.text)
        is CookedBlock.Quote -> block.blocks.sumOf { cost(it) }
        is CookedBlock.ListBlock -> block.entries.sumOf { entry -> entry.sumOf { cost(it) } }
        is CookedBlock.Picture, is CookedBlock.Video, is CookedBlock.Audio,
        is CookedBlock.ImageGrid, is CookedBlock.Table, is CookedBlock.Poll -> budget / 2
        else -> 120
    }
    fun split(block: CookedBlock): List<CookedBlock> = when (block) {
        is CookedBlock.Paragraph -> {
            val parts = mutableListOf<CookedBlock>()
            var runs = mutableListOf<InlineText>()
            var length = 0
            for (run in block.runs) {
                val pieces = if (run.emojiUrl == null) cut(run.text) else listOf(run.text)
                for (piece in pieces) {
                    val weight = textCost(piece)
                    if (length + weight > budget && runs.isNotEmpty()) {
                        parts += CookedBlock.Paragraph(runs.toList())
                        runs = mutableListOf()
                        length = 0
                    }
                    runs += run.copy(text = piece)
                    length += weight
                }
            }
            if (runs.isNotEmpty()) parts += CookedBlock.Paragraph(runs.toList())
            parts.ifEmpty { listOf(block) }
        }
        is CookedBlock.Code -> {
            val pieces = cut(block.text)
            pieces.mapIndexed { index, text ->
                CookedBlock.Code(text, if (index == 0 && pieces.size > 1) block.copyText ?: block.text else block.copyText)
            }
        }
        is CookedBlock.Quote -> segmentCookedBlocks(block.blocks, budget).map { CookedBlock.Quote(it) }
        is CookedBlock.ImageGrid -> block.pictures.chunked(block.columns * 3).map { block.copy(pictures = it) }
        is CookedBlock.Table -> {
            val parts = mutableListOf<CookedBlock>()
            val headers = block.rows.take(block.headerRows).takeIf { rows ->
                rows.withIndex().all { (rowIndex, row) -> row.all { rowIndex + it.rowSpan <= rows.size } }
            }.orEmpty()
            var start = 0
            var spanEnd = 0
            block.rows.forEachIndexed { rowIndex, cells ->
                spanEnd = maxOf(spanEnd, cells.maxOfOrNull { rowIndex + it.rowSpan } ?: rowIndex + 1)
                if (rowIndex + 1 >= maxOf(spanEnd, block.headerRows) && (rowIndex + 1 - start >= 12 || rowIndex == block.rows.lastIndex)) {
                    val rows = block.rows.subList(start, rowIndex + 1)
                    parts += block.copy(
                        rows = if (start == 0) rows else headers + rows,
                        caption = if (start == 0) block.caption else emptyList(),
                        headerRows = if (start == 0) block.headerRows else headers.size,
                    )
                    start = rowIndex + 1
                }
            }
            parts.ifEmpty { listOf(block) }
        }
        is CookedBlock.ListBlock -> {
            val parts = mutableListOf<CookedBlock>()
            var entries = mutableListOf<List<CookedBlock>>()
            var length = 0
            var firstEntry = 0
            block.entries.forEachIndexed { index, entry ->
                val size = entry.sumOf { cost(it) }
                if (size > budget) {
                    if (entries.isNotEmpty()) {
                        parts += CookedBlock.ListBlock(entries.toList(), block.start?.plus(firstEntry))
                        entries = mutableListOf()
                        length = 0
                    }
                    segmentCookedBlocks(entry, budget).forEachIndexed { part, children ->
                        parts += CookedBlock.ListBlock(listOf(children), block.start?.plus(index), continuation = part > 0)
                    }
                    firstEntry = index + 1
                    return@forEachIndexed
                }
                if (entries.isNotEmpty() && (length + size > budget || entries.size >= 6)) {
                    parts += CookedBlock.ListBlock(entries.toList(), block.start?.plus(firstEntry))
                    entries = mutableListOf()
                    length = 0
                    firstEntry = index
                }
                entries += entry
                length += size
            }
            if (entries.isNotEmpty()) parts += CookedBlock.ListBlock(entries, block.start?.plus(firstEntry))
            parts.ifEmpty { listOf(block) }
        }
        else -> listOf(block)
    }
    val result = mutableListOf<List<CookedBlock>>()
    var pending = mutableListOf<CookedBlock>()
    var length = 0
    for (block in blocks.flatMap(::split)) {
        val size = cost(block)
        if (pending.isNotEmpty() && (length + size > budget || pending.size >= 6)) {
            result += pending.toList()
            pending = mutableListOf()
            length = 0
        }
        pending += block
        length += size
    }
    if (pending.isNotEmpty()) result += pending.toList()
    return result.ifEmpty { listOf(emptyList()) }
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
private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "m3u8")
private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")

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
    private var pollModels: List<DiscoursePoll> = emptyList()
    private var pollVotes: Map<String, List<String>> = emptyMap()
    private var headingPrefix = "post"
    private var headingOrdinal = 0
    private var detailsOrdinal = 0

    fun parse(html: String): List<CookedBlock> = parse(html, emptyList(), emptyMap(), "post")

    private fun parse(
        html: String,
        polls: List<DiscoursePoll>,
        votes: Map<String, List<String>>,
        prefix: String,
    ): List<CookedBlock> {
        pollModels = polls
        pollVotes = votes
        headingPrefix = prefix
        headingOrdinal = 0
        detailsOrdinal = 0
        return blocks(Jsoup.parseBodyFragment(html).body().childNodes())
    }

    companion object {
        private const val CONTENT_IMAGES = "img:not(.emoji):not(.avatar)"
        private val blockCache = LruCache<String, List<CookedBlock>>(600)

        fun parseCached(html: String, onUnknown: (String) -> Unit = {}): List<CookedBlock> {
            if (html.isBlank()) return emptyList()
            synchronized(blockCache) { blockCache.get(html) }?.let { return it }
            val parsed = CookedParser(onUnknown).parse(html)
            synchronized(blockCache) { blockCache.put(html, parsed) }
            return parsed
        }

        fun parsePostCached(post: Post, onUnknown: (String) -> Unit = {}): List<CookedBlock> {
            if (post.cooked.isBlank()) return emptyList()
            val key = "${post.id}\u0000${post.cooked}\u0000${post.polls}\u0000${post.pollsVotes}"
            synchronized(blockCache) { blockCache.get(key) }?.let { return it }
            val parsed = CookedParser(onUnknown).parse(
                post.cooked,
                post.polls,
                post.pollsVotes,
                "post-${post.id}",
            )
            synchronized(blockCache) { blockCache.put(key, parsed) }
            return parsed
        }

    }

    private fun isLightboxMeta(element: Element): Boolean =
        element.hasClass("meta") ||
            element.hasClass("filename") ||
            element.hasClass("informations")

    /** Emoji and quote-header avatars are small icons that belong inline with the surrounding text. */
    private fun isInlineImage(element: Element): Boolean =
        element.hasClass("emoji") || element.hasClass("avatar")

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
        val displayUrl = rawSrc ?: srcsetBest ?: lightboxHref ?: originalUrl
        return displayUrl to originalUrl
    }

    private fun pictureBlockOf(img: Element): CookedBlock.Picture? {
        val urls = resolvePictureUrls(img) ?: return null
        val w = img.attr("width").toIntOrNull()?.takeIf { it > 0 } ?: 0
        val h = img.attr("height").toIntOrNull()?.takeIf { it > 0 } ?: 0
        return CookedBlock.Picture(
            url = urls.first,
            description = img.attr("alt").ifBlank { "帖子图片" },
            originalUrl = urls.second,
            width = w,
            height = h,
        )
    }

    private fun pollBlockOf(element: Element): CookedBlock.Poll? {
        val name = element.attr("data-poll-name").ifBlank { "poll" }
        val poll = pollModels.firstOrNull { it.name == name }
        val apiOptions = poll?.options.orEmpty()
        val options = if (apiOptions.isNotEmpty()) {
            apiOptions.map { option ->
                CookedBlock.Poll.Option(option.id, Jsoup.parseBodyFragment(option.html).text(), option.votes)
            }
        } else {
            element.select("[data-poll-option-id]").mapNotNull { option ->
                option.attr("data-poll-option-id").takeIf { it.isNotBlank() }?.let { id ->
                    CookedBlock.Poll.Option(id, option.text(), option.attr("data-votes").toIntOrNull() ?: 0)
                }
            }
        }
        if (options.isEmpty()) return null
        val userVotes = pollVotes[name].orEmpty().toSet()
        val title = element.selectFirst(".poll-title")?.text()?.takeIf { it.isNotBlank() }
            ?: poll?.title?.takeIf { it.isNotBlank() }
            ?: element.attr("data-poll-question").ifBlank { "投票" }
        return CookedBlock.Poll(
            name = name,
            title = title,
            type = poll?.type ?: element.attr("data-poll-type").ifBlank { "regular" },
            status = poll?.status ?: element.attr("data-poll-status").ifBlank { "open" },
            results = poll?.results ?: element.attr("data-poll-results").ifBlank { "always" },
            options = options,
            userVotes = userVotes,
            voters = poll?.voters ?: options.sumOf { it.votes },
        )
    }

    private fun oneboxBlockOf(element: Element): CookedBlock.Onebox? {
        val rawUrl = element.attr("data-onebox-url").ifBlank { element.selectFirst("a[href]")?.attr("href").orEmpty() }
        val url = contentUrl(rawUrl) ?: return null
        val title = element.selectFirst(".title, .onebox-body h1, .onebox-body h2, .onebox-body h3")
            ?.text()?.takeIf { it.isNotBlank() } ?: runCatching { URI(url).host }.getOrNull().orEmpty()
        val description = element.selectFirst(".description, .onebox-body p")?.text()?.takeIf { it.isNotBlank() }
        return CookedBlock.Onebox(url, title, description)
    }

    private fun mediaLink(value: String, extensions: Set<String>): String? {
        val url = contentUrl(value) ?: return null
        val extension = runCatching { URI(url).path.substringAfterLast('.', "").lowercase() }.getOrDefault("")
        return url.takeIf { extension in extensions }
    }

    private fun lazyVideoOf(element: Element): CookedBlock.Onebox? {
        val provider = element.attr("data-provider-name").lowercase()
        val videoId = element.attr("data-video-id").takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
        val fallback = videoId?.let {
            when (provider) {
                "youtube" -> "https://www.youtube.com/watch?v=$it"
                "bilibili" -> "https://www.bilibili.com/video/$it"
                "vimeo" -> "https://vimeo.com/$it"
                else -> null
            }
        }
        val url = element.selectFirst("a.title-link[href], a[href]")?.attr("href")?.let(::contentUrl)
            ?: fallback ?: return null
        return CookedBlock.Onebox(
            url,
            element.attr("data-video-title").ifBlank { element.selectFirst("a")?.text().orEmpty().ifBlank { "外链视频" } },
            provider.takeIf { it.isNotBlank() }?.let { "$it · 打开视频" },
            element.selectFirst("img")?.attr("src")?.let(::contentUrl),
        )
    }

    private fun tableOf(element: Element): CookedBlock.Table {
        val htmlRows = element.select("tr").filter { row ->
            row.parents().firstOrNull { it.normalName() == "table" } === element
        }
        val occupiedUntil = mutableListOf<Int>()
        val rows = htmlRows.mapIndexed { rowIndex, row ->
            var column = 0
            row.children().filter { it.normalName() in setOf("td", "th") }.map { cell ->
                val columnSpan = (cell.attr("colspan").toIntOrNull() ?: 1).coerceIn(1, 32)
                while ((column until column + columnSpan).any { (occupiedUntil.getOrNull(it) ?: 0) > rowIndex }) column++
                val rowSpan = (cell.attr("rowspan").toIntOrNull() ?: 1).let {
                    if (it == 0) htmlRows.size - rowIndex else it.coerceIn(1, htmlRows.size - rowIndex)
                }
                while (occupiedUntil.size < column + columnSpan) occupiedUntil += 0
                for (index in column until column + columnSpan) occupiedUntil[index] = rowIndex + rowSpan
                CookedBlock.TableCell(blocks(cell.childNodes()), column, columnSpan, rowSpan, cell.normalName() == "th")
                    .also { column += columnSpan }
            }
        }
        val caption = element.children().firstOrNull { it.normalName() == "caption" }
            ?.childNodes()?.flatMap { inline(it) }.orEmpty()
        val explicitHeaders = htmlRows.takeWhile { it.parent()?.normalName() == "thead" }.size
        val headerRows = if (explicitHeaders > 0) explicitHeaders
            else if (rows.firstOrNull()?.let { row -> row.isNotEmpty() && row.all { it.header } } == true) 1 else 0
        return CookedBlock.Table(rows, occupiedUntil.size.coerceAtLeast(1), caption, headerRows)
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
            if (element?.hasClass("d-image-grid") == true) {
                flush()
                val pictures = element.select(CONTENT_IMAGES).mapNotNull(::pictureBlockOf)
                if (pictures.isNotEmpty()) {
                    val columns = (element.attr("data-columns").toIntOrNull() ?: if (pictures.size >= 6) 3 else 2).coerceIn(1, 3)
                    output += CookedBlock.ImageGrid(pictures, columns)
                }
                continue
            }
            if (element?.hasClass("lazy-video-container") == true) {
                flush()
                lazyVideoOf(element)?.let(output::add)
                continue
            }
            if (element?.hasClass("poll") == true) {
                flush()
                pollBlockOf(element)?.let(output::add)
                continue
            }
            if (element?.hasClass("onebox") == true) {
                flush()
                oneboxBlockOf(element)?.let(output::add)
                continue
            }
            if (element != null && (element.hasClass("lightbox-wrapper") || element.hasClass("lightbox"))) {
                flush()
                element.select(CONTENT_IMAGES).mapNotNullTo(output, ::pictureBlockOf)
                continue
            }
            when (element?.normalName()) {
                "script", "style", "noscript" -> Unit
                "video", "audio" -> {
                    flush()
                    val source = if (contentUrl(element.attr("src")) != null) element
                        else element.select("source[src]").firstOrNull { contentUrl(it.attr("src")) != null }
                    val url = source?.attr("src")?.let(::contentUrl)
                    if (url != null) {
                        val mimeType = source.attr("type").takeIf { it.startsWith("audio/") || it.startsWith("video/") || it == "application/x-mpegURL" || it == "application/vnd.apple.mpegurl" }
                        output += if (element.normalName() == "audio") CookedBlock.Audio(
                            url, element.attr("title").ifBlank { "音频" }, mimeType,
                        ) else CookedBlock.Video(
                            url = url,
                            posterUrl = contentUrl(element.attr("poster")),
                            title = element.attr("title").ifBlank { "视频" },
                            mimeType = mimeType,
                        )
                    }
                }
                "a" -> {
                    val href = mediaLink(element.attr("href"), VIDEO_EXTENSIONS)
                    val audio = mediaLink(element.attr("href"), AUDIO_EXTENSIONS)
                    if (href != null) {
                        flush()
                        output += CookedBlock.Video(href, title = element.text().ifBlank { "播放视频" })
                    } else if (audio != null) {
                        flush()
                        output += CookedBlock.Audio(audio, element.text().ifBlank { "音频" })
                    } else pending += inline(element)
                }
                "details" -> {
                    flush()
                    val id = "$headingPrefix-details-${detailsOrdinal++}"
                    val summary = element.children().firstOrNull { it.normalName() == "summary" }
                    output += CookedBlock.Details(
                        id,
                        summary?.childNodes()?.flatMap { inline(it) }?.takeIf { it.any { run -> run.text.isNotBlank() } }
                            ?: listOf(InlineText("展开详情")),
                        blocks(element.childNodes().filterNot { it === summary }),
                        element.hasAttr("open"),
                    )
                }
                "pre" -> { flush(); output += CookedBlock.Code(element.wholeText().trimEnd()) }
                "blockquote" -> { flush(); output += CookedBlock.Quote(blocks(element.childNodes())) }
                "aside" -> {
                    flush()
                    if (element.hasClass("onebox")) {
                        oneboxBlockOf(element)?.let(output::add)
                    } else {
                        val children = blocks(element.childNodes())
                        output += if (element.hasClass("quote")) listOf(CookedBlock.Quote(children)) else children
                    }
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
                    output += tableOf(element)
                }
                "img" -> {
                    if (isInlineImage(element)) pending += inline(element)
                    else {
                        flush()
                        val pic = pictureBlockOf(element)
                        if (pic != null) output += pic
                        else pending += InlineText(element.attr("alt"))
                    }
                }
                "h1", "h2", "h3", "h4" -> {
                    flush()
                    val title = element.text().trim()
                    if (title.isNotBlank()) {
                        val anchor = element.id().ifBlank { "$headingPrefix-${headingOrdinal++}" }
                        output += CookedBlock.Heading(element.normalName().last().digitToInt(), title, anchor)
                    }
                }
                "p", "div", "section", "article", "summary", "figure", "figcaption", "h5", "h6" -> {
                    flush()
                    output += blocks(element.childNodes())
                }
                else -> {
                    if (element != null && element.select("$CONTENT_IMAGES, video, audio, details, table, .lazy-video-container, .d-image-grid").isNotEmpty()) {
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
