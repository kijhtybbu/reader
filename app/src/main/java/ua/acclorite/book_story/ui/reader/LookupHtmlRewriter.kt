/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：富媒体词条 HTML 的安全改写（设计 §3.5）。
 *
 * 词条 HTML 来自第三方词典，含脚本、外链、`sound://` 发音引用和 `file:///` 绝对路径。
 * WebView 只负责渲染（JS 关闭、网络全封，见 §2.7），但脚本节点与事件属性仍要在 HTML 层
 * 剥掉：`loadDataWithBaseURL` 下即使 JS 关闭，`javascript:` 链接也可能被当导航处理。
 *
 * 改写的边界（与设计一致，改动前先读 §3.5）：
 *  - `sound://X` 是 MDict 的发音引用，浏览器不认识。提取成 [AudioCandidate]，
 *    并把属性值换成 `dict-audio://<dictId>/<encoded X>`，由 `shouldOverrideUrlLoading`
 *    （或卡片里的时长按）接住后交给音频播放器。
 *  - `file:///…` 是词典作者机器上的绝对路径，浏览器同样不认识；改写成相对路径，交给
 *    base URL `https://dict.local/<dictId>/` 解析。开头的分隔符必须去掉：按 URL 规则
 *    `\pic\x.png` 会被解析成站点根，丢掉 `<dictId>/` 前缀，图片就取不到了。路径内部的
 *    反斜杠原样保留，`MdxResourcePack` 自己会做 `/`↔`\` 与大小写的容错。
 *  - `sound://` 与 `file://` 有意作用于**所有**属性（含 `data-sound` 这类 `data-*`）。
 *  - `img` / `link` / `source` 的普通相对路径与 `srcset`、内联 `style` 里的 `url()`
 *    不改写：它们本来就该由 base URL 解析，重新编码只会多一层出错空间。
 *  - `<style>` 保留（词条排版靠它），只删掉 `@import`：外链样式表在封闭的 WebView
 *    里必然失败，还会拖慢首屏。`@font-face` 的 `url()` 保留。
 *  - `<script>` 之外还有第二类导航入口要堵：`<base href>` 会改掉
 *    `loadDataWithBaseURL` 的相对解析基准（CSS/图片全丢或者指到外网），
 *    `<meta http-equiv="refresh">` 在 JS 关闭下**仍会导航**，`iframe`/`object`/`embed`
 *    是可导航嵌入。词典正文里这些元素本就不该出现，整块移除比逐个清 `src`/`data` 更稳
 *    （不会漏掉 `srcdoc` 这类新入口）。
 *  - `javascript:` / `vbscript:` 按**属性名**（[SCRIPT_URL_ATTRIBUTES]）而不是元素名清洗：
 *    真实语料里的命名空间标签会把元素名变成 `xhtml:a`（OALD9 有 158 个），按
 *    `tag == "a"` 白名单会整片漏掉。
 *  - 引擎已改写好的 `juice://lookup/…` 与同词条 `#frag` 锚点原样保留。
 *
 * 字符集约定：输出固定 UTF-8（`parseBodyFragment` 的输入、`outputSettings().charset`、
 * 序列化都按 UTF-8）。调用方必须显式写成
 * `loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)` —— encoding 传 `null`
 * 会让 WebView 按文档里的 meta 或启发式去猜编码，中文词条直接乱码。
 *
 * 纯函数、无 `android.*`：JVM 单测覆盖（LookupHtmlRewriterTest）。
 */

package ua.acclorite.book_story.ui.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** 词条里的一个发音候选。 */
internal data class AudioCandidate(
    /** MDD 里的资源路径（HTML 实体已解码，分隔符与大小写保持原样）。 */
    val path: String,
    /** 写进 HTML 的 `dict-audio://<dictId>/<encoded path>`，点击时用它回查 [path]。 */
    val url: String
)

/** [rewriteArticleHtml] 的结果：可交给 WebView 的 HTML + 词条自带的发音候选。 */
internal data class RewrittenArticle(
    val html: String,
    /** 按 HTML 里出现的顺序去重（同一条发音常被词头与例句重复引用）。 */
    val audio: List<AudioCandidate>
)

/** 发音链接的自定义 scheme。 */
internal const val DICT_AUDIO_SCHEME = "dict-audio://"

/** MDict 正文里的发音引用 scheme。 */
private const val SOUND_SCHEME = "sound://"

/**
 * 生成一条发音链接。路径整体百分号编码：`\oald9\apple__gb_1.mp3` →
 * `dict-audio://<dictId>/%5Coald9%5Capple__gb_1.mp3`。编码后路径是一个不透明的
 * URL 段，不会因为反斜杠/斜杠混用而被拆成多段；消费方解码即得回 [AudioCandidate.path]。
 */
internal fun dictAudioUrl(dictId: String, path: String): String =
    DICT_AUDIO_SCHEME + dictId + "/" + encodePath(path)

private fun encodePath(path: String): String =
    URLEncoder.encode(path, StandardCharsets.UTF_8.name()).replace("+", "%20")

/**
 * 可能承载「导航 / 加载」目标的属性。`javascript:` / `vbscript:` 出现在这些属性上时，
 * 浏览器即使 JS 关闭也可能把它当导航处理，一律删掉。
 *
 * 用属性名而不是元素名判断：命名空间标签（`xhtml:a`）会让元素名比较失效，而属性名仍是
 * 普通的 `href` / `src`。这也是 `area[href]`、`form[action]`、`button[formaction]`、
 * `object[data]` 一起被覆盖到的原因。
 */
private val SCRIPT_URL_ATTRIBUTES = setOf("href", "src", "action", "formaction", "data")

/** 可执行的 URL scheme（归一化后按前缀比较）。 */
private val SCRIPT_URL_SCHEMES = listOf("javascript:", "vbscript:")

/** CSS 注释（斜杠-星号与星号-斜杠定界），可以跨行。 */
private val CSS_COMMENT_REGEX = Regex("""/\*.*?\*/""", setOf(RegexOption.DOT_MATCHES_ALL))

/**
 * `@import` 规则：到分号为止；没有分号的畸形写法吃到行尾。
 *
 * `url(...)` 必须整体吃掉：data URI 里自带分号（`data:text/css;base64,…`），只按「第一个
 * 分号」截断会把 `base64,…);` 残骸留进样式表。
 */
private val CSS_IMPORT_REGEX = Regex(
    """@import\b(?:url\([^)]*\)|[^;{}"']|"[^"]*"|'[^']*')*(?:;|$)""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
)

/**
 * 词条 HTML → 安全可渲染的 HTML + 发音候选。
 *
 * @param html 引擎输出的词条正文（`entry://` 已改写成 `juice://lookup/…`）。
 * @param dictId 当前词典的 id，用来拼 `dict-audio://<dictId>/…` 与资源 base URL。
 */
internal fun rewriteArticleHtml(html: String, dictId: String): RewrittenArticle {
    if (html.isBlank()) return RewrittenArticle("", emptyList())

    val document = Jsoup.parseBodyFragment(html)
    // prettyPrint=false：词典排版靠 `<br>` 与空白，重新缩进会插进不该有的空白节点。
    document.outputSettings()
        .prettyPrint(false)
        .charset(StandardCharsets.UTF_8)
    val body = document.body()

    body.select("script").remove()
    // `<base>` 改掉相对解析基准（CSS/图片全丢或指到外网），`meta refresh` 在 JS 关闭下
    // 仍会导航；`<head>` 里的这些标签在 body fragment 解析后同样落在 body 上，一并清掉。
    body.select("base, meta[http-equiv~=(?i)refresh]").remove()
    // iframe/object/embed 是可导航嵌入，正文里用不到，连元素一起移除。
    body.select("iframe, object, embed").remove()
    body.select("style").forEach(::stripCssImports)

    val audio = LinkedHashMap<String, AudioCandidate>()
    for (element in body.allElements) {
        rewriteAttributes(element, dictId, audio)
    }

    return RewrittenArticle(body.html(), audio.values.toList())
}

private fun rewriteAttributes(
    element: Element,
    dictId: String,
    audio: MutableMap<String, AudioCandidate>
) {
    // 边遍历边改：先把键拷出来，避免遍历过程中删属性导致迭代器失效。
    for (name in element.attributes().asList().map { it.key }) {
        val lowerName = name.lowercase()
        if (lowerName.startsWith("on")) {
            element.removeAttr(name)
            continue
        }
        // srcset 与内联 style 里的 url() 交给 base URL / WebView，不做改写。
        if (lowerName == "srcset" || lowerName == "style") continue

        val value = element.attr(name).trim()
        if (value.isEmpty()) continue

        when {
            // 有意对所有属性生效：OALD9 的发音写在 `data-sound` 上，按属性名猜会漏。
            // `sound://` / `file://` 不会作为普通文本值出现，误伤的代价只是属性被改写。
            value.startsWith(SOUND_SCHEME, ignoreCase = true) -> {
                val path = value.substring(SOUND_SCHEME.length)
                if (path.isEmpty()) {
                    element.removeAttr(name)
                } else {
                    val url = dictAudioUrl(dictId, path)
                    element.attr(name, url)
                    audio.getOrPut(path) { AudioCandidate(path, url) }
                }
            }
            lowerName in SCRIPT_URL_ATTRIBUTES && isScriptUrl(value) -> element.removeAttr(name)
            else -> relativeResourcePath(value)?.let { element.attr(name, it) }
        }
    }
}

/**
 * 浏览器执行 `javascript:` 时不看大小写，也会忽略其中的空白与控制字符
 * （`java\tscript:`、`java&#x0A;script:`）。先按同样的规则归一化再比较前缀。
 * `vbscript:` 是同一个洞（IE/旧 WebView 里有效），一起挡掉。
 */
private fun isScriptUrl(value: String): Boolean {
    val normalized = value.filterNot { it.isWhitespace() || it.isISOControl() }
    return SCRIPT_URL_SCHEMES.any { normalized.startsWith(it, ignoreCase = true) }
}

/**
 * `file:///…` → base URL 可解析的相对路径。`file:///` 与 `file://` 都见过；
 * 开头的 `/`、`\` 必须去掉（否则 URL 解析会把它当成站点根）。去掉后为空说明
 * 这条路径没有内容，保留原值让浏览器自己失败，别把属性改成空串。
 */
private fun relativeResourcePath(value: String): String? {
    val prefix = when {
        value.startsWith("file:///", ignoreCase = true) -> "file:///"
        value.startsWith("file://", ignoreCase = true) -> "file://"
        else -> return null
    }
    val rest = value.substring(prefix.length).trimStart('/', '\\')
    return rest.takeIf { it.isNotEmpty() }
}

/**
 * 去掉 `<style>` 里的 `@import` 规则。
 *
 * 顺序固定：先去 CSS 注释，再删 `@import`（注释先走，被注释掉的 `@import` 才不会被单独
 * 抠出来留下半个注释）。
 *
 * 已知限制：只按文本匹配，不认字符串上下文 —— `content: "@import x;"` 这种写在字符串里的
 * `@import` 也会被删掉。只发生在 `<style>` 里，且词典排版没有这种写法，接受。
 *
 * 用 [DataNode] 而不是 `Element.text(...)`：`text` 生成的是普通文本节点，序列化时
 * `>`、`&` 会被转义成 `&gt;`、`&amp;`，`a > b` 这样的选择器就坏了。
 */
private fun stripCssImports(style: Element) {
    val css = style.data()
    if (!css.contains("@import", ignoreCase = true)) return
    val cleaned = CSS_IMPORT_REGEX.replace(CSS_COMMENT_REGEX.replace(css, ""), "")
    if (cleaned == css) return
    style.empty()
    style.appendChild(DataNode(cleaned))
}
