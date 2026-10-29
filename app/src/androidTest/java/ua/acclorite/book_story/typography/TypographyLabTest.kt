/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：中文排版自检实验室。
 *
 * 目的：在改任何 UI 之前，先用真机的 Android 文本排版引擎量出四件事：
 *   M1 避头尾：禁则字符是否出现在行首/行尾，以及四种 LineBreak 模式到底有没有差别
 *   M2 两端对齐：justify 到底把行撑宽了多少（逐行配对测量，见 M2 注释）
 *   M3 字距：letterSpacing 对 CJK 是否产生实际影响
 *   M4 分页成本：整章走真实 paginateReaderPages 的耗时
 *   M5 重排成本：改字号后重新分页的耗时下限
 *
 * 运行：
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=ua.acclorite.book_story.typography.TypographyLabTest
 * 结果写入 logcat（TAG = TypographyLab）与 stdout。
 *
 * 注意：测量结果随设备字体引擎、系统版本、屏幕尺寸而变，不跨设备比较。
 * 所有报告都带 M0 设备信息头。
 */

package ua.acclorite.book_story.typography

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ua.acclorite.book_story.domain.model.book.Node
import ua.acclorite.book_story.domain.model.book.NodeContent
import ua.acclorite.book_story.domain.model.book.NodeId
import ua.acclorite.book_story.domain.model.book.NodePosition
import ua.acclorite.book_story.ui.reader.model.ReaderNode
import ua.acclorite.book_story.ui.reader.paged.ReaderPage
import ua.acclorite.book_story.ui.reader.paged.ReaderPageConfig
import ua.acclorite.book_story.ui.reader.paged.ReaderTextMeasurement
import ua.acclorite.book_story.ui.reader.paged.paginateReaderPages
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class TypographyLabTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val displayMetrics = context.resources.displayMetrics
    private val configuration = context.resources.configuration

    // fork: fontScale 必须传进来，否则 sp→px 与设备「系统字体大小」设置不符
    private val density = Density(
        density = displayMetrics.density,
        fontScale = displayMetrics.scaledDensity / displayMetrics.density
    )
    private val measurer = TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(context),
        defaultDensity = density,
        defaultLayoutDirection = LayoutDirection.Ltr,
        cacheSize = 0
    )

    private val deviceHeader =
        "API ${android.os.Build.VERSION.SDK_INT} / ${android.os.Build.MODEL} / " +
            "density ${displayMetrics.density} / fontScale ${displayMetrics.scaledDensity / displayMetrics.density}"

    // 与运行时一致：版心宽 = 屏宽 - 左右各 18dp sidePadding（sidePadding 设置默认 6，×3 = 18dp）
    private val contentWidthPx =
        ((configuration.screenWidthDp - 36) * density.density).roundToInt()

    // 运行时版心高来自 BoxWithConstraints，测试里没有那个 Box，用屏高扣掉状态栏/导航栏/padding 近似
    private val contentHeightPx =
        ((configuration.screenHeightDp - 120) * density.density).roundToInt()

    /** 禁则字符：不允许出现在行首的标点（闭标点 + 后置标点） */
    private val closingPunctuation =
        "。，、；：？！）〕］｝〉》」』】”’！？、％‰℃…·・"

    /** 开标点：不允许出现在行尾 */
    private val openingPunctuation = "（〔［｛〈《「『【“‘"

    /** 可被拆开的成对标点：破折号与省略号不应被行边界劈成两半 */
    private val pairedMarks = "—…"

    /**
     * 样本 1：网文常见对话 + 中英数混排。
     */
    private val proseSample = buildString {
        repeat(6) {
            append("他说：“这本书我已经读完了，写得真好。”")
            append("然后他把书放回书架上（第三层），转身看向窗外——天色已经暗了下来；")
            append("隔壁房间里传来收音机的声音，正在播报 2025 年的新闻，据说有 37% 的人不再看纸质书了。")
        }
    }

    /**
     * 样本 2：对抗性样本。样本 1 只覆盖了 `：，。”）；` 这几个禁则字符，
     * 对 `！？、》」』`、破折号/省略号成对拆分、ASCII `%`、长拉丁 token、数字串都没覆盖。
     * 没有这个样本，「0 违规」只是关于样本里出现过的字符的证据，不是关于规则集的证据。
     */
    private val adversarialSample = buildString {
        repeat(3) {
            append("「你确定！？」他问。『当然、当然。』她答。")
            append("他说完就沉默了……然后补了一句——「算了。」")
            append("参见 https://example.com/a/very/long/unbreakable/token?x=1 与 3.14159265358979")
            append("增长率 37%、同比 12.5%、累计 1234567890 人次，见《统计年鉴》（2025 年版）。")
            append("书名号《活着》、引号「家」、括号（测试）连排：》』」）！？、。")
        }
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 复刻 ReaderScreen.kt 的正文 TextStyle。
     * fontSize 16 / lineHeight 20 是上游默认值（lineHeight 设置项是「相对字号的 sp 增量」）。
     * textDirection = Content 与运行时一致（ReaderLayoutPaged.kt:205-207）。
     */
    private fun readerStyle(
        fontSizeSp: Int = 16,
        lineHeightSp: Int = 20,
        align: TextAlign = TextAlign.Justify,
        lineBreak: LineBreak = LineBreak.Paragraph,
        indentSp: Int = 0,
        letterSpacingEm: Float = 0f
    ) = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = fontSizeSp.sp,
        lineHeight = lineHeightSp.sp,
        textAlign = align,
        lineBreak = lineBreak,
        textIndent = TextIndent(firstLine = indentSp.sp),
        letterSpacing = letterSpacingEm.em,
        textDirection = TextDirection.Content
    )

    private fun layout(
        text: String,
        style: TextStyle,
        widthPx: Int = contentWidthPx
    ): TextLayoutResult = measurer.measure(
        text = text,
        style = style,
        constraints = Constraints(minWidth = widthPx, maxWidth = widthPx)
    )

    private fun report(section: String, body: String) {
        val message = "[$section] $body"
        Log.i(LOG_TAG, message)
        println(message)
    }

    /** 返回该行第一个非空白字符的下标；整行空白则返回 null */
    private fun firstVisibleCharIndex(text: String, result: TextLayoutResult, line: Int): Int? {
        val start = result.getLineStart(line)
        val end = result.getLineEnd(line, visibleEnd = true)
        if (end <= start) return null
        for (i in start until end) {
            if (!text[i].isWhitespace()) return i
        }
        return null
    }

    private fun countLineStartOffenders(
        text: String,
        result: TextLayoutResult,
        examples: MutableList<String>? = null
    ): Int {
        var count = 0
        for (line in 0 until result.lineCount) {
            val index = firstVisibleCharIndex(text, result, line) ?: continue
            val char = text[index]
            if (closingPunctuation.indexOf(char) >= 0) {
                count++
                if (examples != null && examples.size < 3) {
                    val snippet = text.substring(index, (index + 12).coerceAtMost(text.length))
                    examples += "第${line + 1}行以「$char」开头 → $snippet"
                }
            }
        }
        return count
    }

    private fun countLineEndOffenders(text: String, result: TextLayoutResult): Int {
        var count = 0
        for (line in 0 until result.lineCount) {
            val end = result.getLineEnd(line, visibleEnd = true)
            if (end <= 0) continue
            if (openingPunctuation.indexOf(text[end - 1]) >= 0) count++
        }
        return count
    }

    /** 成对标点被行边界劈开：行尾是 — / … 且下一行行首是同一个字符 */
    private fun countPairSplits(text: String, result: TextLayoutResult): Int {
        var count = 0
        for (line in 0 until result.lineCount - 1) {
            val end = result.getLineEnd(line, visibleEnd = true)
            val next = result.getLineStart(line + 1)
            if (end <= 0 || next >= text.length) continue
            val last = text[end - 1]
            if (pairedMarks.indexOf(last) >= 0 && text[next] == last) count++
        }
        return count
    }

    /**
     * 像素级测量：绘制到 Bitmap，返回每行墨迹的 [左缘, 右缘] 像素列；无墨迹的行返回 null。
     *
     * 为什么要用像素而不是 getLineRight：justification 是绘制时把多余宽度分配到字间，
     * getLineRight/getPrimaryHorizontal 返回的是字形前进宽度，不包含这份分配。
     */
    private fun measureLineInk(result: TextLayoutResult): List<Pair<Int, Int>?> {
        val widthPx = result.size.width.coerceAtLeast(1)
        val heightPx = result.size.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        result.multiParagraph.paint(
            androidx.compose.ui.graphics.Canvas(android.graphics.Canvas(bitmap))
        )
        val pixels = IntArray(widthPx * heightPx)
        bitmap.getPixels(pixels, 0, widthPx, 0, 0, widthPx, heightPx)

        return (0 until result.lineCount).map { line ->
            val top = result.getLineTop(line).toInt().coerceIn(0, heightPx - 1)
            val bottom = result.getLineBottom(line).toInt().coerceIn(top + 1, heightPx)
            var left = widthPx
            var right = -1
            for (y in top until bottom) {
                val rowOffset = y * widthPx
                for (x in 0 until widthPx) {
                    // 只看蓝通道：白底 255，抗锯齿灰阶 < 200 视为墨迹
                    if ((pixels[rowOffset + x] and 0xFF) < 200) {
                        if (x < left) left = x
                        if (x > right) right = x
                    }
                }
            }
            if (right < 0) null else left to right
        }
    }

    /**
     * 把某一行单独排版（不换行、不缩进、左对齐），返回它「自然状态下」的墨迹右缘。
     * 用于与 justify 版同一行的墨迹右缘相减，得到 justification 实际插入的宽度。
     */
    private fun naturalInkRight(text: String, start: Int, end: Int, style: TextStyle): Int? {
        if (end <= start) return null
        val sub = text.substring(start, end)
        val natural = measurer.measure(
            text = sub,
            style = style.copy(
                textAlign = TextAlign.Start,
                textIndent = TextIndent.None
            ),
            softWrap = false,
            constraints = Constraints()
        )
        return measureLineInk(natural).firstOrNull()?.second
    }

    // ---------------------------------------------------------------- M0

    @Test
    fun m0_deviceInfo() {
        report(
            "M0 设备",
            "$deviceHeader\n    屏宽 ${configuration.screenWidthDp}dp / 屏高 ${configuration.screenHeightDp}dp " +
                "→ 版心 ${contentWidthPx}×${contentHeightPx}px；正文 16sp / 行高 20sp（上游默认值）。\n" +
                "    所有结论仅对当前设备/系统版本成立。"
        )
    }

    // ---------------------------------------------------------------- M1

    @Test
    fun m1_kinsoku() {
        val modes = listOf(
            "Unspecified(不设)" to LineBreak.Unspecified,
            "Simple" to LineBreak.Simple,
            "Paragraph" to LineBreak.Paragraph,
            "Heading" to LineBreak.Heading
        )
        val samples = listOf("样本1 网文混排" to proseSample, "样本2 对抗性" to adversarialSample)

        // 单一宽度下「0 违规」不能证明避头尾生效：也可能恰好没有出现断点。
        // 正确做法是扫宽度——只要断点可能落不合法位置，某个宽度就会暴露出来。
        val widths = ((contentWidthPx - 120)..(contentWidthPx + 120) step 4).toList()
        val summary = StringBuilder()

        fun lineStarts(text: String, mode: LineBreak): List<Int> =
            layout(text, readerStyle(lineBreak = mode), contentWidthPx).let { r ->
                (0 until r.lineCount).map { r.getLineStart(it) }
            }

        samples.forEach { (sampleName, sample) ->
            summary.append("\n  【$sampleName】")
            val baseline = lineStarts(sample, LineBreak.Unspecified)

            modes.forEach { (label, mode) ->
                var startOffenders = 0
                var endOffenders = 0
                var pairSplits = 0
                val examples = mutableListOf<String>()
                widths.forEach { width ->
                    val result = layout(sample, readerStyle(lineBreak = mode), width)
                    startOffenders += countLineStartOffenders(sample, result, examples)
                    endOffenders += countLineEndOffenders(sample, result)
                    pairSplits += countPairSplits(sample, result)
                }
                val starts = lineStarts(sample, mode)
                val diff = if (starts.size == baseline.size) {
                    starts.indices.count { starts[it] != baseline[it] }
                } else {
                    maxOf(starts.size, baseline.size)
                }
                summary.append(
                    "\n    $label: 扫 ${widths.size} 宽度 → 行首违规 $startOffenders、行尾违规 $endOffenders、" +
                        "成对标点被拆 $pairSplits；断行与 Unspecified 差异 $diff " +
                        "(${starts.size} vs ${baseline.size} 行)"
                )
                examples.forEach { summary.append("\n        $it") }

                // 把结论变成断言：Phase 2 换字体/改排版后它仍须成立，否则是回归。
                assertEquals("[$sampleName/$label] 行首出现禁则字符", 0, startOffenders)
                assertEquals("[$sampleName/$label] 行尾出现开标点", 0, endOffenders)
                assertEquals("[$sampleName/$label] 破折号/省略号被行边界劈开", 0, pairSplits)
            }
        }

        report("M1 避头尾", summary.toString())
    }

    // ---------------------------------------------------------------- M2

    /**
     * 逐行配对测量 justification 实际插入的宽度。
     *
     * 为什么不能像最初那样「分别统计 Justify/Start 的右边缘残留再相减」：
     * justification 会改变断行位置，两个布局的行根本不是同一批内容，
     * 把两组的平均值放在一起比较是无效的（Round 2 评审指出：记录里出现过
     * 「以标点结尾的行 justify 54px > start 46px」，而逐行看 justification
     * 只会增加宽度、残留只能变小，所以那组数据本身就是总体不同导致的伪结果）。
     *
     * 正确做法：取 justify 布局自己的行边界，把同一段文字单独排版（不换行、左对齐）
     * 得到自然墨迹右缘，两者相减 = 这一行被 justify 撑宽了多少。逐行、可配对。
     */
    @Test
    fun m2_justifyPerLineStretch() {
        val style = readerStyle(align = TextAlign.Justify)
        val justify = layout(proseSample, style)
        val ink = measureLineInk(justify)
        val widthPx = justify.size.width

        val stretches = mutableListOf<Int>()
        val residues = mutableListOf<Int>()
        var unmatched = 0

        for (line in 0 until justify.lineCount - 1) { // 末行按规范不对齐
            val start = justify.getLineStart(line)
            val end = justify.getLineEnd(line, visibleEnd = true)
            val inkRight = ink[line]?.second
            val naturalRight = naturalInkRight(proseSample, start, end, style)
            if (inkRight == null || naturalRight == null) {
                unmatched++
                continue
            }
            stretches += inkRight - naturalRight
            residues += (widthPx - inkRight).coerceAtLeast(0)
        }

        val stretchedCount = stretches.count { it > 2 }
        report(
            "M2 两端对齐",
            "中文样本 / ${justify.lineCount} 行 / 版心 ${widthPx}px\n" +
                "    justification 插入宽度: avg ${stretches.average().roundToInt()}px / " +
                "max ${stretches.maxOrNull() ?: 0}px / min ${stretches.minOrNull() ?: 0}px；" +
                "真正被撑宽的行 $stretchedCount/${stretches.size}（>2px 计），无法配对 $unmatched 行\n" +
                "    撑宽/字节: ${(stretches.average() / (16 * displayMetrics.density / 1.0)).let { "%.2f".format(it) }} 字宽" +
                "（1 字宽 = 16sp × density = ${(16 * displayMetrics.density).roundToInt()}px）\n" +
                "    剩余右边缘残留: avg ${residues.average().roundToInt()}px / max ${residues.maxOrNull() ?: 0}px\n" +
                "    不变量：justification 只能插入宽度，任何一行 stretch 都不得为负"
        )

        assertTrue("必须测到多行", stretches.size > 5)
        assertTrue(
            "justification 不可能让某一行变窄（出现负值说明测量口径不一致）",
            stretches.all { it >= -1 }
        )
    }

    /** M2b 拉丁对照：同一引擎、同一测法，拉丁应当被撑到接近填满 */
    @Test
    fun m2b_justifyLatinControl() {
        val latin = buildString {
            while (length < 800) {
                append("The quick brown fox jumps over the lazy dog while reading a book. ")
            }
        }.take(800)

        val style = readerStyle(align = TextAlign.Justify)
        val justify = layout(latin, style)
        val ink = measureLineInk(justify)
        val widthPx = justify.size.width

        val residues = mutableListOf<Int>()
        for (line in 0 until justify.lineCount - 1) {
            val inkRight = ink[line]?.second ?: continue
            residues += (widthPx - inkRight).coerceAtLeast(0)
        }

        report(
            "M2b 拉丁对照",
            "拉丁样本 / ${justify.lineCount} 行 / 版心 ${widthPx}px\n" +
                "    剩余右边缘残留: avg ${residues.average().roundToInt()}px / max ${residues.maxOrNull() ?: 0}px\n" +
                "    若此值远小于 M2 的中文残留，说明 JUSTIFICATION_MODE_INTER_WORD 只对空格生效"
        )

        assertTrue("必须测到多行", residues.size > 5)
    }

    // ---------------------------------------------------------------- M3

    @Test
    fun m3_letterSpacingOnCjk() {
        val base = layout(proseSample, readerStyle(letterSpacingEm = 0f))
        val wide = layout(proseSample, readerStyle(letterSpacingEm = 0.1f))

        val heightDelta = wide.size.height - base.size.height
        val lineDelta = wide.lineCount - base.lineCount

        report(
            "M3 字距",
            "0em → 0.1em: 高度 ${base.size.height} → ${wide.size.height} (Δ$heightDelta), " +
                "行数 ${base.lineCount} → ${wide.lineCount} (Δ$lineDelta)。" +
                if (heightDelta == 0 && lineDelta == 0) {
                    "⚠ 无变化：该字距对 CJK 不生效，面板中应对中文隐藏此项"
                } else {
                    "字距对 CJK 生效；仍需肉眼确认标点间距是否被破坏"
                }
        )
    }

    // ---------------------------------------------------------------- M4

    /** 与 ReaderLayoutPaged.kt:200-219 的运行时 config 对齐（gap 24dp、separator 3dp、textDirection） */
    private fun pageConfig(style: TextStyle) = ReaderPageConfig(
        width = contentWidthPx,
        height = contentHeightPx,
        // 运行时 gap = paragraphHeight 设置(8) × 3 = 24dp
        gap = (24 * density.density).roundToInt(),
        // 运行时 separatorHeight = 3dp
        separatorHeight = (3 * density.density).roundToInt(),
        paragraphStyle = style.copy(color = Color.Unspecified, textDirection = TextDirection.Content),
        headerStyles = List(6) {
            style.copy(color = Color.Unspecified, textDirection = TextDirection.Content)
        },
        images = true,
        imagesCaptions = true,
        imagesOriginalSize = true,
        imagesWidth = 1f,
        density = density
    )

    private val measureBlock: (AnnotatedString, TextStyle) -> ReaderTextMeasurement = { text, textStyle ->
        val result = measurer.measure(
            text = text,
            style = textStyle,
            constraints = Constraints(minWidth = contentWidthPx, maxWidth = contentWidthPx)
        )
        ReaderTextMeasurement(
            height = result.size.height,
            lineEnds = List(result.lineCount) { result.getLineEnd(it, visibleEnd = false) },
            lineBottoms = List(result.lineCount) { ceil(result.getLineBottom(it)).toInt() }
        )
    }

    @Test
    fun m4_paginationCost() = runBlocking {
        val paragraphText = buildString {
            while (length < 3000) {
                append("他停下脚步，抬头看了看天色，觉得该回去了；")
            }
        }.take(3000)

        val node = ReaderNode.Paragraph(
            Node(
                NodeId(0),
                NodePosition(paragraphText.length, 0),
                NodeContent.Paragraph(paragraphText, emptyList())
            ),
            AnnotatedString(paragraphText)
        )
        val config = pageConfig(readerStyle())

        var pages = emptyList<ReaderPage>()
        // 预热 + 取 3 次最小值：单次采样在模拟器上受 JIT 与调度干扰太大
        paginateReaderPages(listOf(node), config, measureBlock)
        var elapsed = Long.MAX_VALUE
        repeat(3) {
            val cost = measureTimeMillis {
                pages = paginateReaderPages(listOf(node), config, measureBlock)
            }
            if (cost < elapsed) elapsed = cost
        }

        // 文本完整性：分页不得丢字、不得重复
        val rebuilt = StringBuilder()
        pages.forEach { page ->
            page.items.forEach { item ->
                if (item.node is ReaderNode.Paragraph) rebuilt.append(item.text?.text ?: "")
            }
        }

        report(
            "M4 分页成本",
            "3000 字单段 / 版心 ${contentWidthPx}×${contentHeightPx}px → ${pages.size} 页, 耗时 ${elapsed}ms\n" +
                "    （目标 ≤100ms / 低端机 ≤250ms；模拟器数值仅供基线）\n" +
                "    注意：只覆盖「单段纯文本、无行内样式」，不是带标题/图片/链接的完整章节"
        )

        assertEquals("分页后文本必须与原文本完全一致", paragraphText, rebuilt.toString())
        assertTrue("3000 字必须被分成多页", pages.size > 1)
        // 耗时不做硬断言：模拟器与真机差一个量级，硬断言只会造成假失败。
    }

    // ---------------------------------------------------------------- M5

    @Test
    fun m5_reflowCost() = runBlocking {
        val paragraphText = buildString {
            while (length < 3000) append("夜深了，窗外的风把树叶吹得沙沙作响；")
        }.take(3000)

        val node = ReaderNode.Paragraph(
            Node(
                NodeId(0),
                NodePosition(paragraphText.length, 0),
                NodeContent.Paragraph(paragraphText, emptyList())
            ),
            AnnotatedString(paragraphText)
        )

        suspend fun paginate(fontSize: Int): Pair<Int, Long> {
            val config = pageConfig(readerStyle(fontSizeSp = fontSize, lineHeightSp = fontSize + 4))
            paginateReaderPages(listOf(node), config, measureBlock)
            var count = 0
            var cost = Long.MAX_VALUE
            repeat(3) {
                val one = measureTimeMillis {
                    count = paginateReaderPages(listOf(node), config, measureBlock).size
                }
                if (one < cost) cost = one
            }
            return count to cost
        }

        val (pages16, cost16) = paginate(16)
        val (pages20, cost20) = paginate(20)

        report(
            "M5 重排成本",
            "字号 16sp → ${pages16} 页 / ${cost16}ms；字号 20sp → ${pages20} 页 / ${cost20}ms\n" +
                "    这是重排成本的下限（仅 paginateReaderPages），" +
                "不含 TextMeasurer 构造、切 Dispatchers.Default、重组与 pager 重布局"
        )

        // 不断言耗时与页数：两者都随设备字体度量变化，硬断言只会造成假失败。
        assertTrue("重排必须产出页面", pages16 > 0 && pages20 > 0)
    }

    private companion object {
        const val LOG_TAG = "TypographyLab"
    }
}
