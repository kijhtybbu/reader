当前对话环境不支持直接生成二进制下载链接，但以下已将完整的技术方案、依赖配置与开发计划整理为**单文件标准 Markdown 源码**。

你可以直接点击代码块右上角的**复制按钮**，或可以在终端执行一键命令直接生成本地 `DEV_PLAN.md` 文件（macOS 下复制代码块后在终端运行 `pbpaste > DEV_PLAN.md` 即可）。

```markdown
# Android 本地阅读器 (TXT/EPUB) MVP 开发计划与架构设计

## 1. 技术选型与核心思路
- **解析层（免造轮子）**：直接引入 `me.ag2s.epublib:epublib-core` + `Jsoup` + `juniversalchardet`，并移植 Legado（阅读 3.0）的 `TxtFile` 与 `EpubFile` 核心容错逻辑。
- **排版与渲染层（纯原生统一管线）**：将 TXT 与 EPUB 章节统一解析为抽象块节点 `List<BlockNode>`，通过 Jetpack Compose 的 `TextMeasurer` 动态计算分页切片，生成 `List<RenderedPage>` 交由 `Canvas` 直接绘制。
- **状态与存储层**：Room 存储书籍元数据与章节字节偏移量（Byte Offset），DataStore 存储全局排版配置（字号、行距、背景主题）。

---

## 2. Gradle 依赖配置 (`app/build.gradle.kts`)

```kotlin
dependencies {
    // 1. Compose 核心与导航
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // 2. 本地持久化 (Room 数据库 + DataStore 配置存储)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 3. 底层文件解析库 (与 Legado 同款)
    implementation("me.ag2s.epublib:epublib-core:3.1") {
        exclude(group = "org.slf4j")
        exclude(group = "xmlpull")
    }
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("com.github.albfernandez:juniversalchardet:2.4.0")
}

```

---

## 3. 项目工程包结构

```text
com.example.localreader
├── data
│   ├── local
│   │   ├── AppDatabase.kt          // Room 数据库定义与 DAO
│   │   ├── BookEntity.kt           // 书籍与进度表 (绑定 currentChapterIndex + currentCharOffset)
│   │   ├── ChapterEntity.kt        // 章节偏移量/路径表 (startByteOffset / endByteOffset / resourceHref)
│   │   └── ReaderPreferences.kt    // DataStore 存储字号、行距、背景主题
│   └── parser
│       ├── BookParserFactory.kt    // 根据后缀分发 TXT 或 EPUB 解析器
│       ├── TxtParser.kt            // 剥离自 Legado TxtFile（含编码探测与断章正则）
│       └── EpubParser.kt           // 剥离自 Legado EpubFile（封装 epublib + Jsoup）
├── domain
│   ├── model
│   │   ├── BlockNode.kt            // 统一段落/图片抽象节点 (TextBlock / ImageBlock / DividerBlock)
│   │   └── RenderedPage.kt         // 单页排版渲染快照 (PageElement 绝对坐标指令集)
│   └── layouter
│       └── UnifiedPageLayouter.kt  // TextMeasurer 分页切片引擎
└── ui
    ├── bookshelf
    │   ├── BookshelfScreen.kt      // 书架页面 + 文件导入入口
    │   └── BookshelfViewModel.kt
    ├── reader
    │   ├── ReaderScreen.kt         // Pager 容器 + 九宫格手势拦截 + 抽屉目录
    │   ├── ReaderPageCanvas.kt     // 单页 Canvas 渲染器
    │   ├── ReaderControlBars.kt    // 顶部返回栏 + 底部字号/主题调节面板
    │   └── ReaderViewModel.kt      // 三章滑动窗口状态机 [Prev, Cur, Next]
    └── MainActivity.kt             // NavHost 路由配置 (Bookshelf -> Reader)

```

---

## 4. 分阶段排期表 (10~14 天)

| 阶段 | 周期 | 核心目标 | 关键产出物 | 验收标准 |
| --- | --- | --- | --- | --- |
| **Phase 1：数据与解析层** | Day 1 - 3 | 移植 Legado 解析器并打通 Room 存储 | `TxtParser.kt`, `EpubParser.kt`, `AppDatabase.kt` | 选择本地 TXT/EPUB 文件后，能打印完整的章节列表与指定章节的段落节点树 |
| **Phase 2：排版与分页核** | Day 4 - 6 | 实现 `TextMeasurer` 动态切片与三章缓存 | `UnifiedPageLayouter.kt`, `ReaderViewModel.kt` | 给定任意章节与屏幕宽高，能正确切分为多页 `RenderedPage` 且翻页不卡顿 |
| **Phase 3：阅读器交互 UI** | Day 7 - 10 | 完成画布绘制、点击翻页、菜单与目录抽屉 | `ReaderScreen.kt`, `ReaderControlBars.kt` | 支持左右滑动/点击翻页、呼出上下菜单、跳章、实时修改字号/主题并自动重排 |
| **Phase 4：书架与收尾** | Day 11 - 12 | 书架网格展示、文件导入与阅读进度恢复 | `BookshelfScreen.kt` | 杀进程重启后，点击书籍能精确回到上次退出时的章节与所在页 |

---

## 5. 任务跟踪清单 (Checklist)

* [ ] **Phase 1.1**：定义 Room 数据库实体（`BookEntity` 书籍表、`ChapterEntity` 章节索引表）
* [ ] **Phase 1.2**：移植 Legado 的编码探测与 `TxtFile` 正则断章逻辑，实现流式扫描写入章节表
* [ ] **Phase 1.3**：基于 `epublib-core` 与 `Jsoup` 移植 `EpubFile` 解析逻辑，提取封面、目录树与章节正文
* [ ] **Phase 2.1**：编写统一排版模型（`BlockNode` 与 `RenderedPage`）及 `TextMeasurer` 分页测量算法
* [ ] **Phase 2.2**：在 `ReaderViewModel` 中实现 `[上一章, 当前章, 下一章]` 滑动窗口内存缓存与异步预加载
* [ ] **Phase 3.1**：开发 `ReaderPageCanvas` 组件，遍历绘制测量好的文本块与 EPUB 内嵌插图
* [ ] **Phase 3.2**：集成 Compose `HorizontalPager`，结合 `pointerInput` 实现左/右 1/3 点击秒切翻页、中间 1/3 呼出菜单
* [ ] **Phase 3.3**：实现上下控制栏（章节进度条、`ModalNavigationDrawer` 目录抽屉、字号/行距/主题色实时调节）
* [ ] **Phase 3.4**：实现字号或屏幕尺寸变更时，根据 `startCharOffset` 重新定位当前页下标
* [ ] **Phase 4.1**：使用 `ActivityResultContracts.OpenDocument` 导入文件并拷贝至 App 私有目录（`filesDir/books`）
* [ ] **Phase 4.2**：开发 `BookshelfScreen` 书架网格视图，展示封面、书名、阅读进度百分比与长按删除

---

## 6. 核心实现要点与避坑备忘

1. **文件 I/O 策略**：导入文件时直接拷贝至 `context.filesDir/books/`，避免直接对 SAF `content://` URI 进行高频 `RandomAccessFile` 随机 Seek 导致的性能劣化与权限失效。
2. **进度持久化基准**：严禁保存页码（`pageIndex`）。必须保存当前页首字符在当前章节中的偏移量（`startCharOffset`），保证字号调整或旋转屏幕重排后，通过二分查找 `startCharOffset <= savedOffset` 即可精准还原阅读位置。
3. **测量时机控制**：`TextMeasurer` 分页计算必须等待外层 `Box` 的 `Modifier.onSizeChanged` 回调拿到精确可视区域 `IntSize` 后再触发。

```

<ElicitationsGroup message="保存文档后，如果你准备开始搭建工程，可以直接生成第一阶段的源码：">
  <Elicitation label="生成 Phase 1 完整代码：Room 数据库与剥离好的 TXT/EPUB 解析器" query="请直接提供 Phase 1 的完整代码，包括 BookEntity、ChapterEntity、AppDatabase 以及剥离了 Legado 依赖的 TxtParser.kt 和 EpubParser.kt。"/>
  <Elicitation label="生成一键初始化项目包结构的 Shell 脚本" query="请提供一段在项目根目录下执行的 Shell 脚本，自动创建上述包结构目录与对应的空白 Kotlin 源码文件。"/>
</ElicitationsGroup>


