/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：中文排版自检实验室。
 *
 * 目的：在改任何 UI 之前，先用真机的 Android 文本排版引擎量出四件事：
 *   M1 避头尾：LineBreak.Paragraph 到底有没有生效，禁则字符是否出现在行首
 *   M2 两端对齐：TextAlign.Justify 在纯中文下每行右边缘的差多少
 *   M3 字距：letterSpacing 对 CJK 是否产生实际影响
 *   M4 分页成本：3000 字章节走真实 paginateReaderPages 耗时多久
 *
 * 这些结论决定 Phase 2/3 是「只改 UI」还是「必须动排版代码」，因此必须在
 * Phase 0 拿数据，不能靠肉眼判断。
 *
 * 运行方式：
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=ua.acclorite.book_story.typography.TypographyLabTest
 * 结果同时输出到 logcat（TAG = TypographyLab）。
 *
 * 注意：测量结果随设备字体引擎、系统版本不同而不同，不跨设备比较。
 */

package ua.acclorite.book_story.typography

import android.util.Log
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
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

    /** 所有报告都带设备信息：不同设备/系统版本的排版行为不可直接比较 */
    private val deviceHeader =
        "API ${android.os.Build.VERSION.SDK_INT} / ${android.os.Build.MODEL} / " +
            "density ${displayMetrics.density} / fontScale ${displayMetrics.scaledDensity / displayMetrics.density}"

    // 视口按 360dp 宽、左右各 18dp sidePadding 推算
    private val contentWidthPx =
        ((360 - 18 * 2) * density.density).roundToInt()

    /** 禁则字符：不允许出现在行首的标点（闭标点 + 后置标点） */
    private val closingPunctuation =
        "。，、；：？！）〕］｝〉》」』】”’％‰℃…·・"

    /** 开标点：不允许出现在行尾（行尾禁则），否则与行首禁则对称失守 */
    private val openingPunctuation = "（〔［｛〈《「『【“‘"

    /** 混排样本：中文标点密集 + 中英数字混排，足够在一屏内制造大量断行点 */
    private val cjkSample = buildString {
        repeat(6) {
            append("他说：“这本书我已经读完了，写得真好。”")
            append("然后他把书放回书架上（第三层），转身看向窗外——天色已经暗了下来；")
            append("隔壁房间里传来收音机的声音，正在播报 2025 年的新闻，据说有 37% 的人不再看纸质书了。")
        }
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 复刻 ReaderScreen.kt 的正文 TextStyle。
     * fontSize 16 / lineHeight 20 是上游默认值（lineHeight 设置项是「相对字号的 sp 增量」）。
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
        letterSpacing = letterSpacingEm.em
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

    @Test
    fun m0_deviceInfo() {
        report(
            "M0 设备",
            deviceHeader + "\n    版心宽 ${contentWidthPx}px，正文字号 16sp / 行高 20sp " +
                "（上游默认值）。所有结论仅对当前设备/系统版本成立。"
        )
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
            val char = text[end - 1]
            if (openingPunctuation.indexOf(char) >= 0) count++
        }
        return count
    }

    /**
     * 像素级测量：绘制到 Bitmap，返回每行墨迹的 [左缘, 右缘] 像素列。
     *
     * 为什么要用像素而不是 getLineRight：justification 是绘制时把多余宽度分配到字间，
     * getLineRight/getPrimaryHorizontal 返回的是字形前进宽度，不包含这份分配，
     * 因此用它算“右边缘不足”会得到与对齐无关的数。
     */
    private fun measureLineInk(result: TextLayoutResult): List<Pair<Int, Int>> {
        val widthPx = result.size.width.coerceAtLeast(1)
        val heightPx = result.size.height.coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(
            widthPx, heightPx, android.graphics.Bitmap.Config.ARGB_8888
        )
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
                    // 只看蓝通道：白底为 255，抗锯齿灰阶 < 200 视为墨迹
                    if ((pixels[rowOffset + x] and 0xFF) < 200) {
                        if (x < left) left = x
                        if (x > right) right = x
                    }
                }
            }
            left to right
        }
    }

    // ---------------------------------------------------------------- M1

    @Test
    fun m1_kinsoku_lineBreakComparison() {
        val modes = listOf(
            "Unspecified(不设 lineBreak)" to LineBreak.Unspecified,
            "Simple" to LineBreak.Simple,
            "Paragraph" to LineBreak.Paragraph,
            "Heading" to LineBreak.Heading
        )

        // 单一宽度下「违规 0 处」不能证明避头尾生效：也可能恰好没有出现断点。
        // 正确做法是扫宽度——只要断点可能落不合法位置，某个宽度就会暴露出来。
        // 宽度必须相对真实版心（不能用绝对 px，mdpi 设备上会跑到 2~3 倍视口）。
        val widths = ((contentWidthPx - 120)..(contentWidthPx + 120) step 4).toList()
        val summary = StringBuilder()

        // 额外回答一个正交问题：四种 LineBreak 模式到底有没有差别？
        fun lineStarts(mode: LineBreak): List<Int> =
            layout(cjkSample, readerStyle(lineBreak = mode), contentWidthPx).let { r ->
                (0 until r.lineCount).map { r.getLineStart(it) }
            }

        val baseline = lineStarts(LineBreak.Unspecified)

        modes.forEach { (label, mode) ->
            var startOffenders = 0
            var endOffenders = 0
            val examples = mutableListOf<String>()
            widths.forEach { width ->
                val result = layout(cjkSample, readerStyle(lineBreak = mode), width)
                startOffenders += countLineStartOffenders(cjkSample, result, examples)
                endOffenders += countLineEndOffenders(cjkSample, result)
            }
            val starts = lineStarts(mode)
            val diff = if (starts.size == baseline.size) {
                starts.indices.count { starts[it] != baseline[it] }
            } else {
                maxOf(starts.size, baseline.size)
            }
            summary.append(
                "\n  $label: 扫 ${widths.size} 个宽度，行首禁则违规 $startOffenders 处、" +
                    "行尾禁则违规 $endOffenders 处；与 Unspecified 断行差异 $diff/${baseline.size} 行"
            )
            examples.forEach { summary.append("\n      $it") }
        }

        report("M1 避头尾", summary.toString())
        assertTrue("样本必须能排多行", layout(cjkSample, readerStyle()).lineCount > 5)
    }

    // ---------------------------------------------------------------- M2

    private data class InkStats(
        val max: Int,
        val avg: Int,
        val flush: Int,
        val total: Int,
        val tailsWithPunctAvg: Int?,
        val tailsWithPunctCount: Int,
        val tailsPlainAvg: Int?,
        val tailsPlainCount: Int
    )

    /**
     * 右边缘间距统计。
     *
     * 必须按「行尾字符类别」分组：全角闭标点（。，））的字形只占字身左侧一半，
     * 所以一行以 。 结尾时即使完美对齐，墨迹也会天然离右缘 0.3~0.5em。
     * 不分组的话，中文样本（行尾几乎都是标点）会系统性地被高估褫齿，
     * 而拉丁样本（行尾是字母）不会被高估 —— 两边不可比，会直接导错结论。
     */
    private fun inkStats(text: String, result: TextLayoutResult): InkStats {
        val ink = measureLineInk(result)
        val widthPx = result.size.width
        // 末行按规范不对齐，剔除
        val nonLast = ink.dropLast(1)
        if (nonLast.isEmpty()) {
            return InkStats(0, 0, 0, 0, null, 0, null, 0)
        }
        val gaps = nonLast.map { (_, right) -> (widthPx - right).coerceAtLeast(0) }

        val withPunct = mutableListOf<Int>()
        val plain = mutableListOf<Int>()
        nonLast.forEachIndexed { index, _ ->
            val end = result.getLineEnd(index, visibleEnd = true)
            val lastChar = if (end > 0) text[end - 1] else ' '
            val isClosing = closingPunctuation.indexOf(lastChar) >= 0
            if (isClosing) withPunct += gaps[index] else plain += gaps[index]
        }

        return InkStats(
            max = gaps.maxOrNull() ?: 0,
            avg = gaps.average().roundToInt(),
            flush = nonLast.count { it.second >= widthPx - 2 },
            total = nonLast.size,
            tailsWithPunctAvg = if (withPunct.isEmpty()) null else withPunct.average().roundToInt(),
            tailsWithPunctCount = withPunct.size,
            tailsPlainAvg = if (plain.isEmpty()) null else plain.average().roundToInt(),
            tailsPlainCount = plain.size
        )
    }

    private fun InkStats.render(): String =
        "右边缘不足 max ${max}px / avg ${avg}px，触边行 $flush/$total" +
            "｜行尾为标点 $tailsWithPunctCount 行 avg ${tailsWithPunctAvg ?: "-"}px" +
            "｜行尾非标点 $tailsPlainCount 行 avg ${tailsPlainAvg ?: "-"}px"

    @Test
    fun m2_justify_pixelRightEdge() {
        val justify = layout(cjkSample, readerStyle(align = TextAlign.Justify))
        val start = layout(cjkSample, readerStyle(align = TextAlign.Start))

        report(
            "M2 两端对齐",
            "中文样本 / 版心宽 ${justify.size.width}px, ${justify.lineCount} 行\n" +
                "    Justify:       ${inkStats(cjkSample, justify).render()}\n" +
                "    Start(对照):  ${inkStats(cjkSample, start).render()}\n" +
                "    判定：看「行尾非标点」那一组的 avg。若 Justify 在该组明显小于 Start，则对中文部分生效"
        )

        assertTrue("版心宽度必须大于 0", justify.size.width > 0)
    }

    /**
     * M2b 关键对照组：同一引擎上跑纯拉丁样本。
     *
     * Android 只有 `JUSTIFICATION_MODE_INTER_WORD` 一种justification 模式（Compose 的
     * TextAlign.Justify 就映射到它）。如果拉丁样本能触边、中文样本不能，就能证明
     * 中文两端对齐在当前技术栈上不成立。
     */
    @Test
    fun m2b_justify_latinControl() {
        val latin = buildString {
            while (length < 800) {
                append("The quick brown fox jumps over the lazy dog while reading a book. ")
            }
        }.take(800)

        val justify = layout(latin, readerStyle(align = TextAlign.Justify))
        val start = layout(latin, readerStyle(align = TextAlign.Start))

        report(
            "M2b 两端对齐(拉丁对照)",
            "拉丁样本 / ${justify.lineCount} 行\n" +
                "    Justify:       ${inkStats(latin, justify).render()}\n" +
                "    Start(对照):  ${inkStats(latin, start).render()}"
        )
    }

    // ---------------------------------------------------------------- M3

    @Test
    fun m3_letterSpacingOnCjk() {
        val base = layout(cjkSample, readerStyle(letterSpacingEm = 0f))
        val wide = layout(cjkSample, readerStyle(letterSpacingEm = 0.1f))

        val heightDelta = wide.size.height - base.size.height
        val lineDelta = wide.lineCount - base.lineCount

        report(
            "M3 字距",
            "0em → 0.1em: 高度 ${base.size.height} → ${wide.size.height} (Δ$heightDelta), " +
                "行数 ${base.lineCount} → ${wide.lineCount} (Δ$lineDelta)。" +
                if (heightDelta == 0 && lineDelta == 0) {
                    "⚠ 无变化：该字距对 CJK 不生效，面板中应对中文隐藏此项"
                } else {
                    "字距生效；需肉眼确认标点间距是否被破坏"
                }
        )
    }

    // ---------------------------------------------------------------- M4

    @Test
    fun m4_paginationCostOn3000Chars() = runBlocking {
        // 3000 字单章，接近网文单章上限
        val paragraphText = buildString {
            while (length < 3000) {
                append("他停下脚步，抬头看了看天色，觉得该回去了；")
            }
        }.take(3000)

        val node = Node(
            NodeId(0),
            NodePosition(paragraphText.length, 0),
            NodeContent.Paragraph(paragraphText, emptyList())
        )
        val readerNode = ReaderNode.Paragraph(node, AnnotatedString(paragraphText))

        val style = readerStyle()
        val pageHeightPx = (600 * density.density).roundToInt()
        val config = ReaderPageConfig(
            width = contentWidthPx,
            height = pageHeightPx,
            gap = (8 * density.density).roundToInt(),
            separatorHeight = 1,
            paragraphStyle = style,
            headerStyles = List(6) { style },
            images = true,
            imagesCaptions = true,
            imagesOriginalSize = true,
            imagesWidth = 1f,
            density = density
        )

        var pages = emptyList<ReaderPage>()
        val measureBlock: (AnnotatedString, TextStyle) -> ReaderTextMeasurement = { text, textStyle ->
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

        // 预热 + 取 3 次最小值：单次采样在模拟器上受 JIT 与调度干扰太大
        paginateReaderPages(listOf(readerNode), config, measureBlock)
        var elapsed = Long.MAX_VALUE
        repeat(3) {
            val cost = measureTimeMillis {
                pages = paginateReaderPages(listOf(readerNode), config, measureBlock)
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
            "3000 字 / 版心 ${contentWidthPx}×${pageHeightPx}px → ${pages.size} 页, 耗时 ${elapsed}ms" +
                "（目标 ≤100ms / 低端机 ≤250ms；模拟器数值仅供基线，不代表真机）"
        )

        assertEquals("分页后文本必须与原文本完全一致", paragraphText, rebuilt.toString())
        assertTrue("3000 字必须被分成多页", pages.size > 1)
        // 耗时不做硬断言：模拟器与真机差一个量级，硬断言只会造成假失败。
        // 是否达标由人根据 report 输出判定，并记录到 DEV_PLAN 附录 B。
    }

    // ---------------------------------------------------------------- M5

    @Test
    fun m5_reflowCostOnFontSizeChange() = runBlocking {
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

        val measureBlock: (AnnotatedString, TextStyle) -> ReaderTextMeasurement = { text, textStyle ->
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

        suspend fun paginate(fontSize: Int): Pair<Int, Long> {
            val style = readerStyle(fontSizeSp = fontSize, lineHeightSp = fontSize + 4)
            val config = ReaderPageConfig(
                width = contentWidthPx,
                height = (600 * density.density).roundToInt(),
                gap = (8 * density.density).roundToInt(),
                separatorHeight = 1,
                paragraphStyle = style,
                headerStyles = List(6) { style },
                images = true,
                imagesCaptions = true,
                imagesOriginalSize = true,
                imagesWidth = 1f,
                density = density
            )
            // 预热 + 取 3 次最小值
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
            "字号 16sp → ${pages16} 页 / ${cost16}ms；字号 20sp → ${pages20} 页 / ${cost20}ms。" +
                "注意：这只是重排成本的下限（仅 paginateReaderPages），" +
                "不含 TextMeasurer 构造、切 Dispatchers.Default、重组与 pager 重布局"
        )

        // 不断言耗时与页数：两者都随设备字体度量变化，硬断言只会造成假失败。
        assertTrue("重排必须产出页面", pages16 > 0 && pages20 > 0)
    }

    private companion object {
        const val LOG_TAG = "TypographyLab"
    }
}
