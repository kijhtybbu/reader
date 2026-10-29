# Android 本地阅读器 (TXT / EPUB) —— 开发计划 v1

> v0 版本见 `DEV_PLAN.v0.generated.md`（模型生成、未经验证，其中 epublib 坐标与 GPL 移植两条已废弃）。

## 0. 决策记录

| 议题 | 决定 | 理由 |
| --- | --- | --- |
| 富文本渲染 | **SpannableStringBuilder + StaticLayout** | 断行/避头尾由 ICU 提供；`setJustificationMode(INTER_WORD)` 给 CJK 两端对齐；块级结构有现成 Span；分页用 `getLineStart/getLineBottom` 精确切片；绘制 `layout.draw(canvas)` |
| 标点挤压 | **不做** | Android/Chrome/Compose 均无实现，只能自研 measure，成本 ×3，收益低 |
| 阅读模式 | **只做分页**（v1 不做滚动） | 分页是主路径；进度基准用章内 charOffset，日后加滚动不需重写 |
| 书签 | **多书签，精确到行** | 不做到选区标注 |
| EPUB 解析 | **不引入 epublib**，`java.util.zip` + Jsoup 自读 | 消掉坐标未验证 / 只读 NCX / jaxb 缺失三个坑；反正 XHTML→Spannable 必须过 Jsoup |
| Legado | **不复制源码**（仅参考思路），自用不发布 | 规避 GPLv3 传染；图表不作为 v1 验收项 |
| 进度基准 | 章内 **charOffset** | 严禁存 pageIndex；改字号/换字体/转屏后靠二分查找重定位 |

---

## 1. 技术选型

- **解析层**：`java.util.zip`（EPUB 容器）+ `Jsoup`（OPF/NCX/nav/XHTML）+ `juniversalchardet`（TXT 编码探测）。
- **排版层**：HTML/纯文本 → `SpannableStringBuilder` → `StaticLayout` → 按行切片成 `PageRange`。**不自己测量文本**。
- **存储层**：Room 存书籍/章节索引/书签；DataStore 存排版参数与主题。
- **minSdk 26**（`setJustificationMode` 需要 26，避开全部版本分支），compileSdk/targetSdk 取当前最新稳定版。

### 依赖（版本按 Android Studio 建议的最新稳定版填写）

```kotlin
dependencies {
    implementation(platform("androidx.compose:compose-bom:<latest>"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose")          // OpenDocument 必需
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose")
    implementation("androidx.core:core-ktx")

    implementation("androidx.room:room-runtime:<latest>")
    implementation("androidx.room:room-ktx:<latest>")
    ksp("androidx.room:room-compiler:<latest>")
    implementation("androidx.datastore:datastore-preferences:<latest>")

    implementation("org.jsoup:jsoup:<latest>")
    implementation("com.github.albfernandez:juniversalchardet:<latest>")

    testImplementation("junit:junit:4.13.2")
}
```

不引入：`epublib`、`navigation-compose`（只有书架/阅读器两屏，状态切换即可）、`coil`（封面与插图用 `BitmapFactory` + `LruCache` 自行降采样）。

---

## 2. 包结构

```text
com.example.localreader
├── data
│   ├── local
│   │   ├── AppDatabase.kt            // RoomDatabase + DAO + Migration
│   │   ├── BookEntity.kt             // 含阅读进度（currentChapterIndex / currentCharOffset）
│   │   ├── ChapterEntity.kt          // 章节索引，TXT/EPUB 字段并存但互斥使用
│   │   ├── BookmarkEntity.kt
│   │   └── ReaderPreferences.kt      // DataStore: fontSize / lineSpacing / letterSpacing /
│   │                                 //   firstLineIndentEm / paragraphSpacing / fontId / theme
│   ├── parser
│   │   ├── BookParserFactory.kt
│   │   ├── txt/TxtIndexer.kt         // 编码探测 + 流式扫描 + 断章规则择优
│   │   ├── txt/TxtChapterReader.kt   // 按 byte offset 随机读 + 解码
│   │   ├── epub/EpubContainer.kt     // container.xml / OPF / spine / cover
│   │   ├── epub/EpubToc.kt           // NCX 优先，nav.xhtml 兜底
│   │   └── epub/EpubContentReader.kt // XHTML -> Spannable
│   └── text/SpannableBuilder.kt      // HTML 标签 -> Span 映射（唯一排版入口）
├── domain
│   ├── model
│   │   ├── LayoutSpec.kt             // 排版参数快照（缓存 key，data class 值相等）
│   │   ├── ChapterLayout.kt          // StaticLayout + CharSequence + PageRange 列表
│   │   └── PageRange.kt              // (startLine, endLine)
│   └── layout
│       ├── StaticLayoutFactory.kt    // 构建 StaticLayout（唯一 setXxx 的地方）
│       └── Paginator.kt              // 行 → 页切片 + charOffset↔page 二分查找
└── ui
    ├── bookshelf/BookshelfScreen.kt, BookshelfViewModel.kt
    ├── reader
    │   ├── ReaderScreen.kt           // 容器 + 手势 + 抽屉
    │   ├── ChapterPager.kt           // 自定义跨章拖拽翻页（不用 HorizontalPager）
    │   ├── PageCanvas.kt             // canvas.clipRect + translate + layout.draw
    │   ├── ReaderControlBars.kt      // 上栏 + 底部排版面板
    │   ├── TocDrawer.kt              // 多级目录
    │   ├── BookmarkSheet.kt
    │   └── ReaderViewModel.kt        // [Prev, Cur, Next] 三章窗口 + LayoutSpec 缓存
    └── MainActivity.kt
```

---

## 3. 数据模型

```kotlin
@Entity(tableName = "book")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String? = null,
    val format: String,              // "TXT" | "EPUB"
    val filePath: String,            // filesDir/books/<uuid>.<ext>
    val fileSize: Long,
    val coverPath: String? = null,
    val charsetName: String? = null, // TXT 专用：探测结果，可被用户手动覆盖
    val tocRuleName: String? = null, // TXT 专用：本次断章命中的规则，重扫时复用
    val chapterCount: Int = 0,
    val currentChapterIndex: Int = 0,
    val currentCharOffset: Int = 0,  // 章内偏移，重排稳定
    val addedAt: Long,
    val lastReadAt: Long,
)

@Entity(
    tableName = "chapter",
    primaryKeys = ["bookId", "index"],
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = CASCADE)],
    indices = [Index("bookId")]
)
data class ChapterEntity(
    val bookId: Long,
    val index: Int,
    val title: String,
    val level: Int = 0,          // 目录层级，抽屉缩进用
    val charCount: Int = 0,
    // TXT 专用（EPUB 下为 null）
    val txtStartByte: Long? = null,
    val txtEndByte: Long? = null,
    // EPUB 专用（TXT 下为 null）
    val resourceHref: String? = null,   // 相对 OPF 目录、已 percent-decode
    val fragment: String? = null,       // 大 XHTML 内按 h1-h3 二次切分时的锚点
)

@Entity(tableName = "bookmark", indices = [Index(value = ["bookId", "chapterIndex", "charOffset"], unique = true)])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val charOffset: Int,        // 该行首字符的章内偏移
    val chapterTitle: String,   // 冗余存，避免列表查询 join
    val snippet: String,        // 该行前 40 字，直观
    val createdAt: Long,
)
```

**要点**：`charOffset` 是唯一进度坐标；`pageIndex` 任何时候都不落盘。EPUB 章节没有字节偏移概念，所以 `txtStartByte` 在 EPUB 书籍中恒为 null —— 这不是冗余，是两种格式必须分离的产物。

---

## 4. 核心实现

### 4.1 TXT 索引（Phase 1）

1. **编码探测**：先读 BOM（UTF-8/UTF-16LE/BE）→ 否则取前 100KB 交 juniversalchardet → 结果落在 `{UTF-8, GB18030, BIG5, UTF-16}` 白名单内才采纳，否则回落 GB18030。**必须提供手动切换编码入口**（见 4.5）。
2. **流式扫描**：`BufferedReader(InputStreamReader(CountingInputStream(FileInputStream)))`，一次遍历同时得到 char 序列与 byte 计数，章节边界 = 行首 charIndex + 累计 byteOffset。**不要先 `readText()` 再算偏移**，多字节字符会让二者错位。
3. **断章规则择优**：取前 30KB 用候选规则集逐个试跑，选命中数最多且间距合理的一条，再全量扫描。规则集示例：
   ```
   ^\s{0,3}第[0-9零一二三四五六七八九十百千两]{1,10}[章节回卷集部篇]
   ^\s{0,3}(楔子|序章|序言|前言|引子|后记|尾声|番外)
   ^\s{0,3}Chapter\s+\d+
   ^\s{0,3}【.{1,20}】
   ```
4. **无章节回退**：一命中都没有时按 8000 字切分，命名「第 N 节」。
5. **单章上限**：超过 200k 字强行再切（StaticLayout 构建时间与内存都会爆）。

### 4.2 EPUB 解析（Phase 1，自研，约 200 行）

```
META-INF/container.xml → rootfile@full-path
  ↓（相对 zip 根，需 percent-decode）
OPF → <manifest> id/href/media-type/properties
      <spine> itemref@idref（linear="no" 的跳过）
      <metadata> dc:title / dc:creator / dc:language
  ↓
目录：spine@toc 指向的 NCX navMap（嵌套 → level）
      缺 NCX 时用 manifest 中 properties 含 nav 的 XHTML → <nav epub:type="toc">
      两者都缺 → 按 spine 顺序生成「第 N 章」
封面：manifest 中 properties 含 cover-image
      → 否则 <meta name="cover"> 指向的 manifest id
      → 否则 spine 首项之后的第一张图片
```

必须处理的坑：
- `href` 相对 OPF 所在目录，需要规范化 `../`、`./`、percent-decode；zip entry 名区分大小写。
- EPUB3-only 书没有 NCX，只能用 nav.xhtml 兜底（这是自研相对 epublib 的直接收益）。
- 有的书把整本正文塞进一个 XHTML：按内部 `h1~h3` 二次切分，章节用 `resourceHref + "#" + fragment` 表示，读取时只取该锚点到下一个锚点之间的内容。

### 4.3 HTML / 纯文本 → Spannable（唯一排版入口）

| HTML | Span |
| --- | --- |
| `b/strong` `i/em` `u/s` | `StyleSpan(BOLD/ITALIC)`、`UnderlineSpan`、`StrikethroughSpan` |
| `h1~h3` | `RelativeSizeSpan` + `StyleSpan(BOLD)` + 段前距 `LineHeightSpan` |
| `p` `div` `br` | 段落终止符 `\n`，段间距由 `ParagraphSpacingSpan` 承担 |
| `blockquote` | `QuoteSpan` + `LeadingMarginSpan.Standard` |
| `ul/ol/li` | `BulletSpan` / 手写序号 + `LeadingMarginSpan.Standard` |
| `a` | `ClickableSpan`（`#frag` 同章跳转；跨 href 跳章或提示不支持） |
| `sup/sub` | `SuperscriptSpan` / `SubscriptSpan` |
| `img` | `ImageSpan`（v1 可先只留占位，降采样后放图） |
| `ruby` | 忽略注音，仅保留 base 文本 |
| `table` | 按行拍平为文本（v1 明确降级） |
| `script/style/head` | 丢弃 |

规范：
- 段内空白折叠为单个空格，**HTML 源码里的换行不产生新段落**（否则每行都断段）。
- 段落之间用 `\n` 连接，**不要**用 `\n\n`（空行会让分页高度计算失真，段距用 Span 表达）。
- 每个段落都要套 `LeadingMarginSpan.Standard(firstLineIndentPx, 0)` 实现首行缩进——Span 必须从段首字符开始。
- `<br>` 用 `\n`；章节末尾补一个 `\n` 保证最后一行参与分页。

### 4.4 StaticLayout 构建与分页

```kotlin
object StaticLayoutFactory {
    fun build(text: CharSequence, spec: LayoutSpec, width: Int): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = spec.fontSizePx
            color = spec.textColor
            typeface = spec.typeface          // 自定义字体见 4.6
            letterSpacing = spec.letterSpacing
            textLocale = Locale.SIMPLIFIED_CHINESE   // 让 ICU 走中文断行规则
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setLineSpacing(0f, spec.lineSpacingMultiplier)   // add, mult
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)   // 见 6 风险：HIGH_QUALITY 在 CJK 下需实测
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)  // API 26+
            .build()
    }
}
```

分页（相对 `pageStartLine` 计算，避免浮点累积误差）：

```kotlin
fun paginate(layout: StaticLayout, pageHeight: Int): List<PageRange> {
    val pages = mutableListOf<PageRange>()
    var start = 0
    while (start < layout.lineCount) {
        val top = layout.getLineTop(start)
        var end = start
        while (end + 1 < layout.lineCount &&
               layout.getLineBottom(end + 1) - top <= pageHeight) end++
        pages += PageRange(start, end)
        start = end + 1
    }
    return pages
}
```

绘制单页：

```kotlin
canvas.save()
canvas.clipRect(padL.toFloat(), padT.toFloat(), (padL + w).toFloat(), (padT + h).toFloat())
canvas.translate(padL.toFloat(), padT - layout.getLineTop(page.startLine))
layout.draw(canvas)
canvas.restore()
```

定位换算（进度恢复、书签、改字号重排后恢复位置，全部走这一对函数）：

```kotlin
fun charOffsetOfPage(layout: StaticLayout, page: PageRange) = layout.getLineStart(page.startLine)
fun pageOfCharOffset(layout: StaticLayout, pages: List<PageRange>, offset: Int): Int {
    val line = layout.getLineForOffset(offset)
    return pages.binarySearch { /* startLine <= line <= endLine */ }
}
```

**缓存**：`LayoutSpec` 必须把**所有**影响断行的参数纳入（宽、高、fontSizePx、lineSpacing、letterSpacing、firstLineIndentEm、paragraphSpacing、fontId、paddingH、justify、textLocale）。key 为 `(chapterIndex, LayoutSpec)`，LRU 容量 3。漏一项就会出现「改了字距但页面没重排」。

### 4.5 编码切换（必备，按用户要求）

阅读器菜单 →「编码」→ 列出 `UTF-8 / GB18030 / GBK / BIG5 / UTF-16`，选中后用新 charset 重读当前章并在底部给出前 3 行预览；确认后写回 `BookEntity.charsetName` 并后台重扫章节索引（重建 `ChapterEntity`）。没有这个入口，一个乱码文件就是死路。

### 4.6 自定义字体

- 字体文件放 `res/font/`，体积控制在 20MB 内（宋体/楷体全量字库很大，可先只提供 2~3 款）。
- `typeface = ResourcesCompat.getFont(context, resId)`；粗体用 `Typeface.create(base, Typeface.BOLD)` 合成，斜体用 `Typeface.create(base, Typeface.ITALIC)` 或 `paint.textSkewX = -0.25f`。
- **换字体必须重建 LayoutSpec 缓存**，并且测得 `fontMetrics` 差异导致的行高变化（需重算分页）。
- 字体加载在 Application 启动时预热，避免首屏空白。

### 4.7 跨章翻页：不用 HorizontalPager

`HorizontalPager` 的 `pageCount` 只覆盖当前章；滑到边界时它无法预渲染下一章首页，会出现空白帧或闪烁，且换章瞬间 `scrollToPage` 与 fling 打架。改为自绘拖拽：

```
三个章节的 ChapterLayout 常驻（Prev / Cur / Next）
dragOffset: Animatable<Float>   // px, 取值 [-w, w]
绘制：prevPage @ -w+drag, curPage @ drag, nextPage @ w+drag
释放：|drag| > w*0.25 或 velocity > 阈值 → 提交翻页 → dragOffset.snapTo(0) 并切换页码/章节
```

手势：单个 `pointerInput` 内的 `awaitEachGesture`，先判总位移 < touchSlop 则视为点击（左 1/3 上页、右 1/3 下页、中间呼出菜单），超过则进入拖拽。**不要**把 `detectTapGestures` 和 `detectDragGestures` 拆成两个 `pointerInput`，会互相吃事件。

### 4.8 书签（精确到行）

- 添加：`offset = layout.getLineStart(currentPage.startLine)`，`snippet = text.substring(offset, layout.getLineEnd(currentPage.startLine)).trim().take(40)`。
- 唯一索引 `(bookId, chapterIndex, charOffset)` 天然去重。
- 列表项显示：章节标题 + 行摘要 + 相对页码。
- 跳转：`pageOfCharOffset(offset)` → 直接 setPage。

---

## 5. 分阶段排期（14 天）

| 阶段 | 周期 | 目标 | 验收 |
| --- | --- | --- | --- |
| **Phase 0** | Day 1 | 脚手架、依赖、字体资源、测试语料入库 | `assembleDebug` 通过；5 份 fixture 能被 File 读到 |
| **Phase 1** | Day 2–4 | TXT 索引 + EPUB 容器/目录 + Spannable 映射 | 对每份 fixture 打印章节列表（含 level）与指定章前 3 段纯文本；GBK 文件无乱码 |
| **Phase 2** | Day 5–7 | StaticLayout 构建 + 分页 + LayoutSpec 缓存 | 单章 3000 字构建+分页 ≤ 50ms；改字号后重定位偏差 ≤ 1 行 |
| **Phase 3** | Day 8–11 | 自绘跨章翻页、点击分区、上下栏、目录抽屉、排版面板 | 左右拖拽跨章无空白帧；九项排版参数实时生效并重定位 |
| **Phase 4** | Day 12–13 | 书签、书架、导入、封面 | 多书签增删跳转；杀进程后回到原行 |
| **Phase 5** | Day 14 | 边界与压测 | 见下方指标全部达标 |

**相对 v0 的变化**：新增 Phase 0（先把依赖和语料跑通，别再对着未经验证的坐标写代码）；Phase 2 从 3 天（自研测量）降为 3 天（切片），但多出字体与两端对齐的实测工作；原本的滚动模式与全书搜索移出 v1。

### 验收指标（必须量化）

| 指标 | 目标 |
| --- | --- |
| 首次打开 5MB / 3000 章 GBK TXT 到可读 | ≤ 2s |
| 二次打开（索引命中） | ≤ 300ms |
| 单章 3000 字：Spannable 构建 + StaticLayout 构建 + 分页 | ≤ 50ms（中端机） |
| 翻页帧耗时 | < 16ms，无掉帧 |
| 改字号/字体/行距后重排 + 重定位 | ≤ 100ms |
| 位置恢复偏差 | ≤ 1 行 |
| 常驻内存 | ≤ 150MB |
| 20 张插图的 EPUB | 不 OOM（图片降采样） |
| 断章正确率（10 本真实 TXT 人工核对） | ≥ 90% |

### 测试语料（Phase 0 就要备齐）

1. GBK 无 BOM 的 3MB 网文 TXT（3000+ 章）
2. 完全无章节标记的纯文本 TXT
3. 单章 20 万字的 TXT（触发性切分）
4. BOM + UTF-16 的 TXT
5. 正常 EPUB2（有 NCX）
6. **EPUB3-only（无 NCX，只有 nav.xhtml）**
7. 3 级嵌套目录的 EPUB
8. 含 20 张插图的 EPUB
9. 单 XHTML 包含全书的 EPUB
10. 排版压测章：中英数混排 + 大量引号括号书名号（验证避头尾与两端对齐）

---

## 6. 风险与待验证清单

按优先级，Phase 0 期间就要逐条 spike 掉：

1. **CJK 两端对齐实拍**：`JUSTIFICATION_MODE_INTER_WORD` 在纯中文 + 中英混排下是否出现字间距过大/拉伸异常。若异常，退回 `ALIGN_NORMAL`（中文右边缘本来就不齐，多数阅读器可接受）或仅在纯中文段落开启。
2. **`BREAK_STRATEGY` 取值**：`HIGH_QUALITY` 在中文长段上可能产生怪异间距且构建变慢，需与 `SIMPLE` 做 1000 字实测对比后再定。
3. **StaticLayout 后台构建安全性**：官方未承诺线程安全。方案：先在 `Dispatchers.Default` 构建，若出现偶发异常则回主线程（预算 50ms 内可接受）。**不要**用 `PrecomputedText`，它不接受 `ReplacementSpan`（ImageSpan）。
4. **段间距的实现精度**：自定义 `LineHeightSpan` 挂在段末 `\n` 上会随分页边界漂移；若视觉不可接受，改为段间插入一个固定高度的 `ReplacementSpan` 空行。
5. **`getLineBottom` 含 `lineSpacingAdd`**：分页统一用 `getLineBottom(i) - getLineTop(startLine)` 的相对差，不要用 `layout.height` 累加。
6. **末页留白**：最后一页行数不足时不要强行撑满，保持首行基线对齐。
7. **进度写盘频率**：debounce 500ms + `onStop` 强制落盘，不要每次翻页写 Room。
8. **大文件内存**：正文按 byte offset 随机读，只常驻三章 `CharSequence`；导入上限 100MB 并给出提示。
9. **图片**：`inJustDecodeBounds` 量尺寸 → `inSampleSize` 降采样到页宽 → `LruCache` 复用；超出页高的图片缩放到页高。
10. **字体体积**：全量中文字库会让 APK 膨胀 10~20MB，先只内嵌 1~2 款，其余走「从系统字体列表选择」。

---

## 7. 明确不做（v1）

滚动模式、全书搜索、选区标注/笔记、标点挤压、表格与浮动图片排版、DRM/LCP、云端同步、横屏双栏、繁简转换、TTS、OPDS 书源。

以上每一项的取舍都已确认；若后续要加，`charOffset` 进度模型与 `Paginator` 的接口都不需要重写。
