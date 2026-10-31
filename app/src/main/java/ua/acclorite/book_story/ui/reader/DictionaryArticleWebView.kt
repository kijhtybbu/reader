/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：富媒体词条的 WebView 正文（设计 §3.6）。
 *
 * 只画**一份词条**的 WebView，所以按「封闭渲染盒」来配置，而不是按浏览器（安全基线见设计 §2.7）：
 *  - 脚本一律不执行（`javaScriptEnabled = false`），DOM storage / database / 文件与 content://
 *    访问全部关闭，外部网络整体封锁（`blockNetworkLoads = true`）；
 *  - 唯一的资源来源是 `shouldInterceptRequest`：只认 `https://dict.local/<dictId>/…`
 *    （base URL 由 `loadDataWithBaseURL` 给定）。**根路径必须放行（返回 null）**：真机实测
 *    （Lenovo TB322FC / Android 16）`loadDataWithBaseURL` 会把 base URL 本身也送进
 *    `shouldInterceptRequest`，给它一个 404 会让整篇词条变成 Chromium 的
 *    `net::ERR_HTTP_RESPONSE_CODE_FAILED` 错误页；`null` 才是「用我给你的内联数据」。
 *    其它 host 一律返回 `null`（网络已被 `blockNetworkLoads` 封死，不值得为此伪造响应）；
 *  - 导航除了「同词条锚点」之外全部拦下：`juice://lookup/…` 交给卡片继续查，`dict-audio://…`
 *    交给音频播放器（`<audio>` 标签同样不播，系统播放器负责发音）。
 *
 * 两个路径容错都要保留（真实语料里两种写法都出现）：
 *  - 词条里的相对路径 → `https://dict.local/<dictId>/…`，前缀是词典 id；
 *  - 词典 CSS 里的根相对路径（OALD9 的 `url('/font/Optima_LT_Medium.ttf')`）没有词典前缀，
 *    当作**本 WebView 自己那部词典**的路径处理。
 *
 * 字符集固定 UTF-8：`loadDataWithBaseURL(…, "text/html", "utf-8", null)`。encoding 传 null 会让
 * WebView 按文档里的 meta 或启发式猜编码，中文词条直接乱码（与 `LookupHtmlRewriter` 的约定一致）。
 *
 * 纯字符串的部分（base URL、路径前缀剥离、MIME、主题样式、字号换算、`dict-audio://` 解码）都是
 * 无 `android.*` 的纯函数，JVM 单测可直接覆盖；WebView 本身必须真机验证（嵌套滚动、暗色、字号）。
 */

package ua.acclorite.book_story.ui.reader

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import ua.acclorite.book_story.data.dictionary.DictionaryResourceStore
import ua.acclorite.book_story.ui.theme.LocalReaderPalette
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

/**
 * 量正文高度的时刻（ms，相对 `onPageFinished`）。取这几次里的最大值、只在最后一次上报：
 * 一次测量太早（排版/图片还没落），反复上报又会让卡片来回长。
 */
private val MEDIA_CONTENT_HEIGHT_CHECKS_MS = longArrayOf(0L, 90L, 260L)

/** 词条资源的虚拟站点。除它之外的 host 一律不服务（§2.7）。 */
internal const val DICT_RESOURCE_HOST = "dict.local"

/** WebView 的默认字号（px）：`textZoom` 的换算基准，见 [articleTextZoom]。 */
private const val WEBVIEW_BASE_FONT_PX = 16f

/**
 * 卡片正文的目标字号（sp）。`ReaderLookupPopup` 里义项用的是
 * `MaterialTheme.typography.bodyMedium`（14sp），而卡片排版**不跟阅读正文字号**走；
 * 所以 WebView 也只对齐这一个固定值，否则阅读字号调到「大字」时同一张卡片里
 * WebView 正文会比 ECDICT 卡片的释义大一圈（真机实测：22pt 阅读字号 → 137%，明显偏大）。
 */
private const val CARD_BODY_FONT_SP = 14f

/** `textZoom` 的兜底范围：再小读不清，再大排版全断。 */
private const val MIN_TEXT_ZOOM = 50
private const val MAX_TEXT_ZOOM = 300

/**
 * `loadDataWithBaseURL` 的基准：CSS / 图片 / 字体的相对路径都从这里解析，同时它也是
 * `shouldInterceptRequest` 里「这个 WebView 属于哪部词典」的唯一凭据。
 */
internal fun dictionaryBaseUrl(dictId: String): String = "https://$DICT_RESOURCE_HOST/$dictId/"

/**
 * 请求路径 → `.mdd` 键。
 *
 * **不在这里解码**：`DictionaryResourceStore.read` 会解一次，提前解会变成双重解码
 * （`%2520` → `%20` → 空格，读错键）。
 *
 * 两种形状都要支持：
 *  - 带词典前缀：`/<dictId>/oalecd9.css` → `oalecd9.css`
 *  - 根相对（CSS 里的 `url(/font/x.ttf)`）：`/font/x.ttf` → `font/x.ttf`
 */
internal fun dictionaryResourcePath(path: String, dictId: String): String? {
    val withoutLeading = path.trimStart('/')
    if (withoutLeading.isEmpty()) return null
    val prefix = "$dictId/"
    val resource = if (withoutLeading.startsWith(prefix)) {
        withoutLeading.removePrefix(prefix)
    } else {
        withoutLeading
    }
    return resource.takeIf { it.isNotEmpty() }
}

/** 资源后缀 → MIME。认不出的按 `application/octet-stream` 给（WebView 会当二进制）。 */
private val RESOURCE_MIME_TYPES = mapOf(
    "css" to "text/css",
    "js" to "application/javascript",
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "svg" to "image/svg+xml",
    "webp" to "image/webp",
    "ttf" to "font/ttf",
    "otf" to "font/otf",
    "woff" to "font/woff",
    "woff2" to "font/woff2",
    "mp3" to "audio/mpeg",
    "wav" to "audio/wav",
    "ogg" to "audio/ogg"
)

/**
 * `MdxResourcePack` 的键用 `\` 当分隔符、还混用 `/`，所以取文件名时两种都要切；
 * `?` / `#` 在规范化时已经被 `DictionaryResourceStore` 截掉，这里不再处理。
 */
internal fun dictionaryResourceMimeType(path: String): String {
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    val extension = name.substringAfterLast('.', "").lowercase()
    return RESOURCE_MIME_TYPES[extension] ?: "application/octet-stream"
}

/**
 * 注入的 `<style>`：透明底 + 跟随卡片字色 + 图片/表格不溢出。
 *
 * 为什么每条规则都带 `!important`：词典 CSS 经常自己写 `body{background:#fff}`，
 * 阅读页是羊皮纸/夜黑预设时，卡片里就会贴出一张白色便签；`!important` 才盖得住同选择器规则。
 * 只覆盖 `html`/`body`，表格斑马纹、强调色这类元素级样式不动。
 *
 * `-webkit-text-size-adjust:100%` 关掉 WebView 的字体放大启发式，字号只由
 * `textZoom`（卡片正文目标字号）决定，否则两套缩放会叠乘。
 */
internal fun articleThemeStyle(cssColor: String): String =
    "<style>" +
        "html{-webkit-text-size-adjust:100%}" +
        "html,body{background:transparent!important;margin:0!important;padding:0!important;" +
        "color:$cssColor!important}" +
        "img{max-width:100%!important;height:auto!important}" +
        "table{max-width:100%!important}" +
        "</style>"

/** 改写后的正文 → 交给 WebView 的完整文档。样式在最前面，词典样式表仍能覆盖具体元素。 */
internal fun themedArticleHtml(html: String, cssColor: String): String =
    articleThemeStyle(cssColor) + html

/**
 * `WebSettings.textZoom` 百分比：把词典 CSS 的基准字号（WebView 默认 16px）缩到卡片
 * 正文的 [CARD_BODY_FONT_SP]（＝Compose 的 `bodyMedium`），于是同一张卡片里 ECDICT 的
 * 释义与 MDX 词条的正文看起来一样大。
 *
 * `textZoom = 14 × fontScale × 100 / 16`：系统字号 1.0 时 88%，1.3 时 114%。
 * `fontScale` 是系统字号缩放：Compose 的 `sp` 吃它，WebView 的 CSS px 不吃，不乘的话
 * 系统字号调大后 WebView 正文会比卡片标题小。
 *
 * 刻意**不**跟阅读正文字号（`settings.fontSize`）走：卡片本来就用固定排版，跟过去会让
 * 「大字」预设下的词条正文比 ECDICT 卡片大一圈（真机反馈）。
 *
 * 用它而不是往 CSS 里写 `font-size`：词典 CSS 大量使用 `px` / `em` / 百分比，只改 `body`
 * 的字号反而会让标题与正文的比例失真；`textZoom` 是整体缩放，比例保持不变。
 */
internal fun articleTextZoom(fontScale: Float = 1f): Int =
    (CARD_BODY_FONT_SP * fontScale * 100f / WEBVIEW_BASE_FONT_PX).roundToInt()
        .coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM)

/**
 * `dict-audio://<dictId>/<encoded path>` → 词条里的原始音频路径，与 [dictAudioUrl] 对称。
 * 解码失败（畸形百分号）返回 null：宁可这次不播，也不要拿一串 `%5C` 当路径去读资源。
 */
internal fun dictAudioPathFromUrl(url: String): String? {
    if (!url.startsWith(DICT_AUDIO_SCHEME, ignoreCase = true)) return null
    val rest = url.substring(DICT_AUDIO_SCHEME.length)
    val encoded = rest.substringAfter('/', missingDelimiterValue = "")
    if (encoded.isEmpty()) return null
    return try {
        URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()).takeIf { it.isNotEmpty() }
    } catch (e: IllegalArgumentException) {
        null
    }
}

/**
 * 同词条内的锚点：目标只是 base URL 加一个 `#fragment`（`https://dict.local/<id>/#frag`）。
 * 这种导航要放行，否则词条里的「跳到义项 3」全都没反应。
 */
internal fun isSameDocumentFragment(url: String, baseUrl: String): Boolean =
    url.startsWith(baseUrl) && url.substring(baseUrl.length).startsWith("#")

/** `Color` → CSS `rgb(…)`。不用 `#AARRGGBB`：CSS 的 8 位十六进制是 `#RRGGBBAA`，alpha 会读错。 */
private fun Color.toCssColor(): String {
    val argb = toArgb()
    return "rgb(${(argb shr 16) and 0xFF},${(argb shr 8) and 0xFF},${argb and 0xFF})"
}

/**
 * 富媒体词条正文（设计 §3.6）。
 *
 * 生命周期：WebView 由 `AndroidView` 的 factory 创建**一次**，重组只通过 [update] 换内容；
 * 离开组合时先 `stopLoading()`、换回默认 `WebViewClient`，再 `about:blank`、最后 `destroy()` ——
 * 直接 destroy 一个还在解码的
 * WebView 会在后台线程里抛 NPE（Android 的已知行为）。[dictId] 变化时不复用：base URL 与
 * `shouldInterceptRequest` 的归属都跟着它，重建比就地改更省事（代价与换词条的一次加载相当）。
 *
 * @param html 已改写的词条正文（[LookupBody.Media.html]）。
 * @param dictId 当前词条所在词典的 id。
 * @param onLookupLink 点了正文里的 `juice://lookup/…` 链接，参数是解出来的词。
 * @param onPlayAudio 点了正文里的 `dict-audio://…` 发音链接，参数是 `.mdd` 里的路径。
 * @param onContentHeight 加载完成后量到的正文高度（View 像素，`contentHeight × scale`）。
 *   卡片拿它当正文高度，好让卡片是「内容多高就多高」而不是一上来撑满上限。**只回调一次**：
 *   之后的图片懒加载/异步字体不再重测 —— 反复重测会让卡片一直抖。
 */
@Composable
internal fun DictionaryArticleWebView(
    html: String,
    dictId: String,
    onLookupLink: (String) -> Unit,
    onPlayAudio: (String) -> Unit,
    modifier: Modifier = Modifier,
    onContentHeight: (Int) -> Unit = {}
) {
    val palette = LocalReaderPalette.current
    // 系统字号缩放：Compose 的 sp 自动吃它，WebView 的 textZoom 不会，得自己乘上去。
    val fontScale = LocalDensity.current.fontScale
    // 暗色预设：算法暗化只在词典 CSS 自带浅底时才有作用，浅色预设下必须关掉，否则正常配色会被压暗。
    val darkTheme = palette.bar.luminance() < 0.5f
    val document = remember(html, palette.onBar) {
        themedArticleHtml(html, palette.onBar.toCssColor())
    }
    val textZoom = articleTextZoom(fontScale)
    val currentOnLookupLink = rememberUpdatedState(onLookupLink)
    val currentOnPlayAudio = rememberUpdatedState(onPlayAudio)
    val currentOnContentHeight = rememberUpdatedState(onContentHeight)

    key(dictId) {
        // 非 State 的容器：View 不需要参与快照，只有 onDispose 读它。
        val holder = remember { WebViewHolder() }
        AndroidView(
            modifier = modifier,
            factory = { context ->
                WebView(context).apply {
                    holder.webView = this
                    configureForArticle()
                    val client = DictionaryArticleClient(
                        dictId = dictId,
                        onLookupLink = { word -> currentOnLookupLink.value(word) },
                        onPlayAudio = { path -> currentOnPlayAudio.value(path) },
                        onContentHeight = { px -> currentOnContentHeight.value(px) }
                    )
                    holder.client = client
                    webViewClient = client
                }
            },
            update = { webView ->
                webView.settings.textZoom = textZoom
                // 暗色预设下允许算法暗化（词典 CSS 自带浅底时由 WebView 压暗），浅色预设显式关掉。
                webView.settings.applyAlgorithmicDarkening(darkTheme)
                // 重组时不重载：每帧 loadDataWithBaseURL 会让图片闪烁、滚动位置归零。
                if (webView.tag != document) {
                    webView.tag = document
                    // 同一个 WebView 换了词条：内容高度要重新量（上一次的值属于上一篇词条）。
                    holder.client?.resetMeasurement()
                    webView.loadDataWithBaseURL(
                        dictionaryBaseUrl(dictId),
                        document,
                        "text/html",
                        "utf-8",
                        null
                    )
                }
            }
        )
        DisposableEffect(Unit) {
            onDispose {
                holder.client?.cancelPendingMeasurements()
                holder.client = null
                holder.webView?.let(::destroyWebView)
                holder.webView = null
            }
        }
    }
}

private class WebViewHolder {
    var webView: WebView? = null

    /** 复用同一个 WebView 换词条时，让下一次加载重新量一遍内容高度。 */
    var client: DictionaryArticleClient? = null
}

/**
 * 安全与渲染设置（设计 §2.7 逐条对应，改动前先读那一节）。全部是「能关就关」：
 * 这个 WebView 的职责只有画一份词条。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureForArticle() {
    // 透明底：WebView 自身背景默认是白色，注入的 `background:transparent` 只作用在文档上，
    // 下面那层还是白的 —— 夜黑/羊皮纸预设下卡片会闪一块白。
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    val settings = settings
    // 脚本：不执行。词典正文里的脚本已在 HTML 层剥掉，这里再关一层（设计 §1.2 的非目标）。
    settings.javaScriptEnabled = false
    settings.javaScriptCanOpenWindowsAutomatically = false
    // 多窗口 / window.open：封闭渲染盒里没有意义。
    settings.setSupportMultipleWindows(false)
    // 本地文件与 content:// 一律不开：词条只需要虚拟站点上的资源。
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    // 存储：DOM storage 不用，关掉可以少一份可被写坏的状态（WebSQL/database 早已移除，不再设置）。
    settings.domStorageEnabled = false
    // 网络：整体封锁，资源只能经 shouldInterceptRequest。mixed content 因此也不会有，
    // 但仍然显式设成 NEVER —— 这是 §2.7 的硬要求，不依赖别的开关兜底。
    settings.blockNetworkLoads = true
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    // 缩放：由卡片正文目标字号（textZoom，见 articleTextZoom）决定，不给手势缩放与缩放控件。
    settings.setSupportZoom(false)
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
    // 图片要显示；定位与自动播放媒体都不需要。
    settings.loadsImagesAutomatically = true
    settings.setGeolocationEnabled(false)
    settings.mediaPlaybackRequiresUserGesture = true
}

/**
 * 算法暗化：只在暗色预设下允许。词典 CSS 自带浅底（`body{background:#fff}`）时，WebView 会
 * 把整页按暗色重绘；注入的 `html,body{background:transparent}` 保证它不会变成「卡片里的白便签」。
 * 不支持该特性的旧 WebView（< 1.5 / Chromium 缺特性）上什么也不做，由透明底兜底。
 */
private fun WebSettings.applyAlgorithmicDarkening(allowed: Boolean) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) return
    WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, allowed)
}

/**
 * 资源与导航的唯一出口。
 *
 * 资源：只服务 `https://dict.local/<dictId>/…` 下的具体路径，**其余一律返回 `null`**。
 *  - 根路径（`path == "/"`）必须放行：真机实测 `loadDataWithBaseURL` 会把 base URL 本身
 *    送进这里，回 404 会把整篇词条换成 `net::ERR_HTTP_RESPONSE_CODE_FAILED` 错误页（`null`
 *    才是「用内联数据」）。同一个回调里分不出「主文档」与「子资源」，所以这一条得靠路径判断；
 *  - 其它 host / 其它 scheme（`<audio src="dict-audio:…">` 这类）也返回 `null`：网络已由
 *    `blockNetworkLoads` 封死，伪造一个 404 只会把真正的失败藏起来；
 *  - 路径存在但包里没有 → 404。这是子资源（图片/CSS/字体）的正常缺失，响应码不会影响主文档。
 *
 * 导航：`juice://` 与 `dict-audio://` 拦下并回调，同词条锚点放行，其余（`https` / `http` /
 * `file` / `javascript` / 未知 scheme）一律返回 true 封死。
 */
private class DictionaryArticleClient(
    private val dictId: String,
    private val onLookupLink: (String) -> Unit,
    private val onPlayAudio: (String) -> Unit,
    private val onContentHeight: (Int) -> Unit
) : WebViewClient() {

    /** 内容高度只报一次：图片异步到达时再报会让卡片反复长高（用户要的是「一次性长到最终高度」）。 */
    private var measured = false

    /**
     * 代次：换文档时自增。已经排进消息队列的测量回调带着旧代次，会被丢掉 —— 否则上一次的词条
     * 会把高度报到新词条上，新文档再也不会被测量（卡片停在一个错误的高度）。
     */
    private var generation = 0

    /** 换词条复用同一个 WebView 时调用，让新文档重新量一次高度。 */
    fun resetMeasurement() {
        generation++
        measured = false
    }

    /** WebView 要销毁了：丢掉还没跑完的测量回调（不能再碰一个已 destroy 的 WebView）。 */
    fun cancelPendingMeasurements() {
        generation++
        measured = true
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        if (measured) return
        val expected = generation
        // 量三次取最大值、只在最后一次上报：`onPageFinished` 之后排版/图片/字体还可能继续落，
        // 只量一次容易偏小（正文被裁）。取最大值 + 单次上报 = 卡片只长一次。
        var best = 0
        MEDIA_CONTENT_HEIGHT_CHECKS_MS.forEachIndexed { index, delayMillis ->
            view.postDelayed({
                if (expected != generation || measured) return@postDelayed
                // `contentHeight` 是 CSS 像素，乘 `scale` 才是 View 像素（Compose 的 px 就是 View 像素）。
                // `scale` 异常时退回屏幕密度，量出来的高度只会偏小（正文可滚），不会撑坏卡片。
                val scale = view.scale.takeIf { it > 0f } ?: view.resources.displayMetrics.density
                val height = (view.contentHeight * scale).roundToInt()
                if (height > best) best = height
                if (index == MEDIA_CONTENT_HEIGHT_CHECKS_MS.lastIndex && best > 0) {
                    measured = true
                    onContentHeight(best)
                }
            }, delayMillis)
        }
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? = intercept(request.url)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        handleNavigation(request.url)

    private fun intercept(url: Uri): WebResourceResponse? {
        val scheme = url.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        if (!url.host.equals(DICT_RESOURCE_HOST, ignoreCase = true)) return null
        // encodedPath 保留百分号编码：DictionaryResourceStore 会解一次，提前解就成双重解码。
        val resource = dictionaryResourcePath(url.encodedPath.orEmpty(), dictId) ?: return null
        val bytes = DictionaryResourceStore.shared.read(dictId, resource) ?: return notFound()
        val mimeType = dictionaryResourceMimeType(resource)
        return WebResourceResponse(
            mimeType,
            if (mimeType.startsWith("text/")) "utf-8" else null,
            200,
            "OK",
            mapOf("Content-Length" to bytes.size.toString()),
            ByteArrayInputStream(bytes)
        )
    }

    private fun handleNavigation(uri: Uri): Boolean {
        val url = uri.toString()
        if (url.startsWith(DICT_AUDIO_SCHEME, ignoreCase = true)) {
            dictAudioPathFromUrl(url)?.let(onPlayAudio)
            return true
        }
        if (url.startsWith(LOOKUP_LINK_SCHEME, ignoreCase = true)) {
            lookupWordFromLink(url)?.let(onLookupLink)
            return true
        }
        // 同词条锚点：交给 WebView 自己滚过去。
        if (isSameDocumentFragment(url, dictionaryBaseUrl(dictId))) return false
        // 其余一律封死：外链、file://、javascript: 都不该把用户带出卡片。
        return true
    }

    private fun notFound(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        404,
        "Not Found",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0))
    )
}

/**
 * 先离开文档再销毁：直接 `destroy()` 一个还在解码/加载的 WebView 会在内部线程里抛 NPE。
 * `stopLoading` 之后 `destroy` 是 Android 官方推荐的顺序。
 */
private fun destroyWebView(webView: WebView) {
    // 顺序很重要：先把 client 换成默认值再导航。留着 [DictionaryArticleClient] 时
    // `about:blank` 会被 `handleNavigation` 当外部导航拦下（返回 true），于是「先离开文档」
    // 这一步等于没做，`destroy()` 时文档仍在加载 —— 正是想避开的那个 NPE 场景。
    webView.stopLoading()
    webView.webViewClient = WebViewClient()
    webView.loadUrl("about:blank")
    webView.destroy()
}
