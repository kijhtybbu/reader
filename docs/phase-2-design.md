# 第二阶段设计：取词词典与 AI 翻译

> 状态：S1–S9 已实施完成（最终整体 review 按要求取消）
> 基线：`ui-redesign` 分支（fork of Book's Story）
> 目标分支：`phase-2`
> 相关文档：`DEV_PLAN.md`、`UPSTREAM_DIFF.md`、`PROGRESS.md`、`REBASE.md`

## 0. 已确认的产品决策

| 问题 | 决策 |
| --- | --- |
| 取词手势 | 单击与长按都支持，设置项 `SINGLE_TAP / LONG_PRESS / OFF`，默认 `SINGLE_TAP` |
| 内置词库 | 完整 ECDICT，官方 StarDict 数据（MIT） |
| 词库分发 | 仓库提交官方 `stardict.7z`（约 51MB），首次启动解压到应用私有目录 |
| 自定义词库 | 支持导入 `.mdx`，复用同一引擎（`.mdd` 资源包随资源渲染一起接，见 §4.3） |
| AI 接口 | OpenAI 兼容：Base URL + Model + Key，兼容 OpenAI / DeepSeek / Gemini(OpenAI 兼容) / OpenRouter / Ollama |
| 取词框 | 浮动卡片贴词，自动避让屏幕边缘 |
| 翻译框 | 居中大对话框，标签页：翻译 / 语法分析 |
| 双击段落 | 翻译**整段文本**，替换原外部翻译；默认开启，保留开关 |
| 语法页 | 逐句分析：整段多句时先列出句子、点哪句分析哪句（单句自动分析），结果用 Markdown 渲染，没有「开始分析」按钮 |
| 结果保存 | 翻译、语法、AI 单词解释全部持久化；第二次点击直接显示缓存 |
| 终审 | 每阶段两轮 `reviewer`；最终整体 review 按要求取消 |

## 1. 目标与非目标

### 1.1 目标

1. 阅读页单击/长按单词，弹出贴词卡片，查内置 ECDICT 或导入的 MDX。
2. 双击段落，打开居中 AI 翻译对话框，流式翻译整段。
3. 语法标签逐句分析：点中哪句就请求哪句，结果按 Markdown 渲染。
4. 所有 AI 结果持久化，二次打开零网络请求、立即显示。
5. 词典解析、AI 调用、加密存储全部复用社区组件，不自研底层格式/协议。

### 1.2 非目标（本期）

- 自研 MDX / StarDict / dictzip / LZO / RIPEMD128 解析器。
- 自研 OpenAI HTTP / SSE / JSON 客户端。
- 自研 Keystore 加密存储。
- 整本书翻译、翻译历史浏览页、云同步、生词本。
- `.mdd` 图片/音频在正文中的渲染（引擎可读，UI 后续接）。
- 加密词典的密码输入。
- 在 WebView 中执行词典 HTML/JS。

## 2. 社区组件选型

| 能力 | 组件 | 版本/来源 | 许可 | 说明 |
| --- | --- | --- | --- | --- |
| MDX/MDD、StarDict、dictzip、LZO、RIPEMD128 | juicedict 纯 Kotlin 引擎 | `qiuminal/juicedict` commit `7bbcb87404183c24cb102eede0aa9551eeb8241f` | GPL-3.0 | 上游未发 Maven；作为 `:dict-engine` 源码模块引入，只加 public 工厂 |
| 内置词库 | ECDICT 官方 CSV → 自建 StarDict/7z | `skywind3000/ECDICT` `ecdict.csv` | MIT | 由 `tools/dict/prepare_ecdict.py` 转换，不生成 MDX |
| OpenAI 兼容调用 | `com.aallam.openai:openai-client` | `4.1.0` | MIT | 自定义 `OpenAIHost(baseUrl)`、headers、流式 Chat Completions |
| HTTP 引擎 | `io.ktor:ktor-client-okhttp` | Ktor 3.x（与 openai-client 对齐） | Apache-2.0 | Android 上使用 OkHttp |
| API Key 加密 | `com.google.crypto.tink:tink-android` | `1.18.0` | Apache-2.0 | `AndroidKeysetManager` + DataStore |
| 7z 解压 | `org.apache.commons:commons-compress` + `org.tukaani:xz` | 最新稳定版 | Apache-2.0 / Public Domain | 首次启动解压 ECDICT |
| 词典 HTML | 引擎 `Article.toHtml()` + Compose `AnnotatedString.fromHtml` | 随引擎 / Compose 1.9.3 | GPL-3.0 / Apache-2.0 | 不写自定义 HTML 解析器 |
| 词形/模糊 | 引擎 `Morphology` / `EditDistance` | 随引擎 | GPL-3.0 | 不手写后缀规则 |
| 分词边界 | `android.icu.text.BreakIterator` | Android 平台 | 平台 | 取词定位 |
| 持久化 | Room（项目已有） | 2.7.2 | Apache-2.0 | 翻译缓存表 |
| UI | Compose / Material3 / 现有 Dialog、Popup | 项目已有 | Apache-2.0 | 取词卡片、翻译对话框 |

明确不引入：`mdict-java`（无可用 Maven 构件、依赖杂乱）、`stardict4j`（整包解压内存不可控）、已废弃的 `security-crypto`。

### 2.1 传递依赖（S1 实测版本）

上表四个新依赖会带进以下传递依赖，全部保留、不做 exclude（AboutLibraries 会自动列出，
Credits 无需手写条目）：

| 传递依赖 | 版本 | 来自 |
| --- | --- | --- |
| `commons-io:commons-io` | 2.20.0 | commons-compress |
| `org.apache.commons:commons-lang3` | 3.18.0 | commons-compress |
| `commons-codec:commons-codec` | 1.19.0 | commons-compress |
| `com.google.code.gson:gson` | 2.10.1 | tink-android |
| `org.slf4j:slf4j-api` | 2.0.16 | ktor-client-okhttp |

体积/方法数收益不明确之前不写 speculative 的 exclude —— S3 记录安装包体积后再决定是否裁剪。

## 3. 总体架构

```
ReaderLayout (Paged/Continuous)
  └─ 单击/长按命中 → TranslationModel.onWordLookup(word, anchor, sentence)
  └─ 双击整段    → TranslationModel.onTranslateParagraph(text, nodeId, bookId)
                        │
        ┌───────────────┴────────────────┐
        │                                │
  DictionaryRepository             TranslationCacheRepository (Room)
  （:dict-engine）                  （cache-first）
        │                                │
  ECDICT StarDict / 导入 MDX        AiTranslationRepository
                                    （openai-kotlin + Ktor）
                                         │
                                   OpenAI 兼容服务
```

- 业务层只面向 `DictionaryEngine` 与 `AiTranslationRepository` 接口。
- `TranslationModel` 负责缓存优先、请求去重、流式状态、取消与“重新生成”。
- UI 只消费 UiState，不直接访问引擎或网络。

## 4. 词典引擎模块

### 4.1 引入方式

- 新建 `third_party/juicedict-engine/`，复制上游 `engine/` 源码与测试，保留包名
  `com.qiuminal.juicedict.engine`。
- 同目录保留 `LICENSE`（GPL-3.0）与 `PROVENANCE.md`：仓库、commit、复制范围、本地改动。
- 新建 `:dict-engine` 模块（`kotlin("jvm")`，JVM 17，纯 JVM 测试，不依赖 Android）。
- 只新增 `DictionaryEngines` 适配层（public，实现名；早期草稿叫 `EngineFactory`），用于访问上游
  `internal` 的 `MdxDictionary` / `MdxProbe` / `MdxResourceFiles`，并提供
  `openMdx` / `openStarDict` / `probeMdx` / `probeMdxHead` / `mddFilesFor` 与
  `logSink`（诊断出口）。
- 解析、LZO、RIPEMD128、dictzip、链接改写、词形/模糊逻辑一律不修改。

### 4.2 内置 ECDICT

- 仓库提交 `app/src/main/assets/dictionaries/ecdict/stardict.7z`（约 21.7MB，LZMA2）。
  内容是从官方 ECDICT `ecdict.csv`（MIT）转出的 StarDict 三件套（`ecdict.ifo` / `.idx` /
  `.dict`，77 万词条），转换与打包脚本是构建期工具 `tools/dict/prepare_ecdict.py`；
  §0 里「仓库提交官方 stardict.7z（约 51MB）」落地时改成自建 7z：官方 7z 里是同一份 CSV
  派生的数据，而自己的转换能固定词条渲染格式（`<b>词头</b>` + 音标 + 释义）与可复现的 SHA-256：
  脚本给三个输入设固定 mtime（`SOURCE_DATE_EPOCH`，默认 2024-01-01T00:00:00Z）并先打成 ustar
  再转 7z（7z 还会存 atime/ctime，而 ctime 无法从用户态固定），所以同一份 CSV 重建出的字节恒定，
  SHA-256 与大小写在同目录 `NOTICE.txt` 并由 `BuiltInDictionaryAssetTest` 钉住。
  中间产物（66MB CSV + 90MB StarDict）只落在 `build/ecdict/`，只有 `stardict.7z` 进 assets。
  资产版本 2 起，词条里还带两个**不可见**标记（卡片据此分栏）：
  `<!--ecdict|c=柯林斯星级|o=牛津|t=考试标签|b=BNC 词频|f=当代语料库词频-->`
  与 `<!--def-->`（英文释义起始）。换资产必须递增 `ECDICT_ASSET_VERSION`，否则旧目录会被跳过。
  数据来源与许可见同目录 `NOTICE.txt` / `ECDICT-LICENSE.txt`。
- `DictionaryRegistry.ensureBuiltIn()`：
  1. 首次启动/版本变化时，在 IO 线程用 `SevenZFile` 解压 `.ifo` / `.idx` / `.dict`（或 dictzip 的
     `.dict.dz`，可选 `.syn`）；随包资产是明文 `.dict`。
  2. 解压到 `filesDir/dictionaries/builtin/`，临时目录 + 原子 rename，写入 `version`（版本 + 词条数）。
     暂存目录是同级的 `builtin.tmp` / `builtin.old`；进程死在两次 rename 之间时，下次启动先把
     `builtin.old` 搬回来再判断标记（不重解），`.old` 的体积也计入空间检查。
  3. 失败保留旧版本；设置页显示进度与错误。
- 启动不阻塞；查询时若未就绪，卡片显示“词典准备中”，并保留 AI 解释入口。
- 内存：ECDICT 约 76 万词条，StarDict 索引常驻内存；项目已启用 `largeHeap`，实测后记录峰值。
  S3 实测：解压出 90MB，引擎另写 34MB `.jidx`，目录合计约 120MiB（见 `PROGRESS.md` §3.3）。

### 4.3 导入与查询

- 导入 `.mdx`：先读前 1 MiB 用 `DictionaryEngines.probeMdxHead`（内部 `MdxProbe.readFromHead`）预检
  是否为 MDict，不是就立即拒绝（不把误选的大文件完整复制一遍）；通过后流式复制到暂存目录，
  再用 `probeMdx` + `openMdx` 校验版本/加密位/资源包/截断与条目数。
- **`.mdd` 资源包本期不导入（已确认的欠账，S4 实施时记录）**：MDX 入口是单文件
  `OpenDocument`，拿不到同目录兄弟文件的授权；`DictionaryEngines.mddFilesFor`
  （`<base>.mdd` / `<base>.1.mdd` …）的调用点已留在 `openDictionaryEngine`，等资源渲染
  阶段把 `.mdd` 复制进同一目录即可自动配对。在此之前，引用 `entry://` 图片/音频的词条
  会缺图 —— 这是本期的已知限制，不是缺陷。
- StarDict 导入：`OpenDocumentTree` 选目录，校验 `.ifo/.idx/.dict(.dz)` 后复制。
- 元数据 `index.json`：id、type、baseName、name、directory、wordCount、sizeBytes、enabled、importedAt
  （只存元数据：词典文件名由 `type` + `baseName` 拼出，不再抄一份文件列表；`directory` 是相对
  `filesDir/dictionaries/` 的路径，不存绝对路径 —— 绝对路径会随重装/迁移失效）。
- 引擎实例 LRU（内置 + 最近 2 个导入词典），删除/禁用时 `close()`。
- 查询：`lookupExact` → 无结果 `lookupSmart(limit=5)`；多词典按“精确 > 前缀 > 模糊”排序，
  同级内先查「优先词典」（`activeDictionaryId`，单选只影响先后，不禁用其它词典），再按目录顺序。
- 正文：`engine.article(hit).toHtml()` → Compose `AnnotatedString.fromHtml`；
  拦截 `juice://lookup/` 做二次查询。
- 无结果：卡片显示“未找到”，提供“用 AI 解释这个词”（结果可缓存）与“去导入词典”。

## 5. 阅读页取词

### 5.1 手势

- 设置项 `lookupGesture`：
  - `SINGLE_TAP`（默认）：命中单词即取词；空白区仍走原翻页/菜单。
  - `LONG_PRESS`：保留单击翻页；长按选中后自动取词。
  - `OFF`：关闭取词。
- 分页布局：沿用 Pager 上**唯一**的 `detectTapGestures`；`readerPageItemAtY` 找到页项后，
  用 item 内坐标 `TextLayoutResult.getOffsetForPosition` + `getWordBoundary` 取词。
- 连续布局：取词模式下用一个手势处理器做同样命中；不新增第二套翻页检测器。
- 长按：保留 `SelectionContainer` 选区与工具栏；自定义 `SelectionToolbar` 增加
  `onSelectionSettled(rect, hideToolbar)`，防抖 350ms：用文本布局注册表按选区**首行**反查单词
  → 取词卡片；跨行选区本期忽略（S8 接翻译对话框）。
  - 反查点用「选区顶边 + 4px」而不是几何中心：Compose 的 `getContentRect()` 在底部加了
    `4 × HandleHeight`（给系统工具栏留位），几何中心会落到下一行，长按「apple」查出隔壁的词。
  - 弹出卡片前调用 `hideToolbar()`：系统 ActionMode 悬浮工具栏是触摸模态窗口，不收起它，
    卡片外的点击全被它吞掉。
  - 同一选区矩形 1.5s 内只取一次词（`showMenu` 会被重复调用）。
  - `showMenu` 交来的 rect 本身就是窗口坐标（同一个 rect 原样交给 ActionMode 的
    `onGetContentRect`），**不再叠加 `localToWindow` 二次换算**。
  - **不取选中文本本身**：`showMenu` 只给矩形，拿文本只能读剪贴板，而 Android 13+ 每次写
    剪贴板都会弹系统提示 —— 拖动选择手柄时等于连续弹窗（S5 实施时的结论）。
  - 已知缺口：Compose 1.9 默认启用**新上下文菜单**，它会绕过项目自定义的 `LocalTextToolbar`，
    所以当前构建里「长按选后自动取词」实际不会触发（工具栏也只剩系统默认项）。代码路径、
    锚点与防重逻辑都已经就位，等迁移到新上下文菜单 API 或显式关掉该 flag 后再启用。
- 工具栏「词典」：改为应用内取词。多词选区只查选区里的**第一个词**（整串短语 `lookupExact`
  必然 miss），真 miss 时卡片上的「用其他应用查询」回退到外部词典应用。
- 命中的文本块由 `ui/reader/ReaderTextLayoutRegistry` 按「节点 id + 页项起点」登记
  `TextLayoutResult` 与窗口矩形；两种布局共用这张表算锚点，卡片才能贴住词。
  矩形用 `positionInWindow()` + 节点尺寸拼出，**不用** `boundsInWindow()`：后者会被祖先裁剪
  （LazyColumn 视口）并夹到根节点范围内，段落有一半滚出屏幕时原点会被钉在视口边上，
  取词与高亮就会随段落位置偏移。
- 长按取词的锚点用注册表给出的**精确词矩形**，不是 `showMenu` 的选区矩形：后者是选区包围盒，
  底部还额外加了 `4 × HandleHeight`（给系统工具栏留位）并被裁剪到可见区，带标点的词、
  靠近屏幕边缘的词会明显偏移。选区矩形只用于「是否跨行」的判断。

### 5.2 取词卡片

- `Popup` 贴词，自动判断上/下与左右边界；最大宽 360dp、最小宽 248dp、最大高 45%
  **安全区高度**（窗口扣掉系统栏、刘海/挖孔），定位与夹紧都在安全区内。
- 弹窗窗口本身是**全屏**的：卡片用 `Modifier.offset` 摆在算好的位置，不需要
  `PopupPositionProvider` 的 `popupContentSize`（自己 `onSizeChanged` 量）。原因：原先的
  wrap-content 窗口靠 `ACTION_OUTSIDE` 接收外部点击，而选区手柄/系统工具栏都是带
  `WATCH_OUTSIDE_TOUCH` 的独立窗口且叠在上面，会把外部点击截走（真机复现：长按取词后
  卡片关不掉）。全屏窗口把「外部点击」变成窗口内事件：一张透明遮罩接点击关闭。
  弹窗刻意**非 focusable**：focusable 弹窗开/关会抢/还焦点，SelectionManager 每次拿回焦点
  都会把选区工具栏重报一遍，造成「关掉 → 自己弹回来」；返回键交给弹窗内的 `BackHandler`。
- 卡片分三层，**只有正文滚动**：
  1. 固定头部：词头、音标、来源词典、词典元信息标签、多词典 chip；
  2. 可滚动正文（`weight(1f, fill = false)`，内容短时卡片跟着收缩）；
  3. 固定按钮区（未找到、错误两种状态）—— 以前按钮和正文在同一个滚动容器里，
     词条一长按钮就被滚出可见区。
- **按下即高亮**：单击取词要等双击判定（`detectTapGestures` 的 `onDoubleTap` 非 null 时
  `onTap` 延迟 ~300ms），没有反馈用户会以为没点中。`onPress` 里先命词并把矩形写进
  `DictionaryLookupModel.previewAnchor`（只画高亮、不查词），`onTap` 落地后才查；
  手势取消（拖动翻页/滚动）或双击翻译时撤掉预览。
- 正文高亮量的是**词的墨迹范围**：注册表在布局时用同字号、同字体的 `Paint.getTextBounds`
  取该词的 tight bbox，再按 `getLineBaseline` 贴到词上。为什么不用 `getBoundingBox` 的行框：
  行框高度 = 字号 × 用户行高设置，行距调大时高亮会跟着变高；为什么不用「基线 ± 固定 em 比例」：
  无下伸字母的词（`crack`）底部会留一条空白、上伸字母也可能被切。拿不到 Paint/字号时依次
  退回「基线 ± 0.72/0.22em」与「行框 + 对称内缩」。
- **两端对齐的横向校正**：Android 的 `getPrimaryHorizontal`/`getBoundingBox` 返回的是**未拉伸**
  坐标，而绘制时词间空格被拉宽到行宽（真机实测：20pt、行宽 1804px 时行尾的 `through` 布局
  坐标比实际绘制偏左 ~35px，表现为「选到上一个词的末尾、漏掉最后一个字母」）。校正量 =
  词前空格数 × 每间隔拉伸量（拉伸总量 = 行右边界 − 最后一个非空白字符的布局右边界）；
  命中点也先按同量往回挪再取词。非两端对齐的行/段落最后一行该差值为 0，天然不校正。
- 多词条候选 chip **换行**（`FlowRow` + 紧凑胶囊 ~24dp），不做横向滚动；Material3
  `FilterChip` 固定 32dp 高，5 个前缀候选换行会把固定头部撑掉半个卡片。候选组与上方的
  词典元信息胶囊之间加 10dp + 发丝分隔线 + 10dp：两者都是胶囊，挤在一起会混成一片。
- 使用 `LocalReaderPalette` 配色，夜间不闪白。
- 外部点击、返回、翻页、滚动关闭。
- 词条正文由 `ui/reader/ReaderLookupArticle` 解析后分栏渲染：
  - 词头行 `<b>词</b>` 与卡片标题重复，丢弃；音标从正文提到头部；
  - 中文义项按「词性/领域标签 + 释义」两列排（`n.` / `vt.` / `[医]` 左对齐）；
  - 英文 WordNet 释义放在分隔线下的次要小节（小字号、次要色）；每行开头的词性
    （`n.` / `v.` / `a.` / `s.` / `r.`）单独解析成**浅灰底 + 粗体**的小徽标，避免被当成释义正文；
  - `<small>exchange</small>` 转成的词形说明作为文末附注。
- 内置 ECDICT 词条带两个不可见标记（见 `tools/dict/prepare_ecdict.py`）：
  `<!--ecdict|c=…|o=…|t=…|b=…|f=…-->` 提供柯林斯星级/牛津/考试标签/词频，
  `<!--def-->` 标出英文释义起始。没有标记的导入词典按启发式拆（整行 `<small>`/`<i>`、
  `n. …` 这类纯英文行）；带块级标签的词条整段交给 `fromHtml`，不拆。
- 多词典结果用来源标签区分（来源标签做成可切换的 chip）。
- 支持 `juice://lookup/` 链接点击继续查询。

## 6. AI 配置与调用

### 6.1 设置项

- `aiBaseUrl`：默认 `https://api.openai.com/v1/`；规范化补尾部 `/`，没写协议时补 `https://`
  （`api.openai.com/v1` 直接可用，不再是一个被映射成 UNKNOWN 的相对路径）。
- `aiModel`：自由文本 + 常用预设。
- `aiTargetLanguage`：跟随界面 / 简体中文 / 繁體中文 / English / 日本語 / 한국어 / 自定义。
  S6 已实现前六项；「自定义」未做（枚举无法同时表达预设与自由文本，留到有需求时再评估）。
- `aiGrammarEnabled`：默认开启（只影响是否显示语法标签）。
- API Key：不进入普通 DataStore 明文。

### 6.2 Key 存储

- `com.google.crypto.tink:tink-android` 的 `AndroidKeysetManager` 管理 AES256-GCM keyset。
- 密文 base64 存 DataStore（`stringPreferencesKey("ai_api_key_enc")`）；解密失败清空存量并
  提示重填；日志绝不打印 Key 与正文。Tink 初始化/加解密放到 `Dispatchers.IO`，不占主线程。
- `allowBackup=false` 已关闭备份。

### 6.3 客户端

- `openai-kotlin`：`OpenAIConfig(token, host = OpenAIHost(baseUrl), timeout, headers,
  logging = LoggingConfig(logLevel = None), retry = RetryStrategy(maxRetries = 0/1))`。
  引擎显式传 OkHttp（`ktor-client-okhttp`），**不**依赖 Ktor 的 ServiceLoader 运行时发现——
  那条路 R8 之后编译期看不见、单测也盖不到。
- 翻译：`ChatCompletionRequest(stream = true)`，`chatCompletions()` 收集流。
- 语法：`responseFormat = ChatResponseFormat.JsonObject`，非流式或流式聚合均可。
- 错误映射：401 Key 无效、403、404 模型/路径不存在、429 限流、5xx、超时、断网。
  5xx 的状态码不在 `OpenAIServerException` 上，要从它的 cause（Ktor 的 `ResponseException`）
  捞回来，UI 才能显示「(HTTP 503)」。
- 超时：非流式请求（`/models`、语法）整次调用 60s；**流式**请求用 per-request
  `RequestOptions(timeout = Timeout(request = INFINITE))` 覆盖掉整次调用上限，否则一个慢但正常的
  长 SSE 会在第 60 秒被掐成 TIMEOUT；卡死由 socket 超时（60s 无数据）兜住。
- 长段落按句子边界切块（≤3000 字符）顺序输出；关闭对话框取消 Job。
- 明文 HTTP 只放行 `localhost` / `127.0.0.1` / `10.0.2.2`（`res/xml/network_security_config.xml`），
  其余流量强制 HTTPS。IPv6 回环 `::1` 与局域网 HTTP 本期**不**放行。
  S6 决定保持该策略：Base URL 可以指向 `localhost`/`127.0.0.1`/`10.0.2.2` 上的本地服务，
  但局域网明文 HTTP 需要用户自己用反向代理/HTTPS 解决。
- **本机地址免 Key**：与上面放行明文的正是同一组主机。这类地址上的本地服务（Ollama、LM Studio、
  llama.cpp server 等）通常不校验鉴权，所以 Base URL 的 host 是 `localhost`/`127.0.0.1`/`10.0.2.2`
  时允许不配 Key；公网地址没有 Key 仍然在建连之前抛 `NO_KEY`，不会把空凭据发出去。
  空 Key 时 `OpenAIConfig.token` 传空串：openai-kotlin 4.1.0 无条件安装 Bearer 鉴权（没有
  「跳过鉴权」的开关），所以请求上会带一个空的 `Authorization: Bearer`，本机服务不校验它。
  设置页对这类 Base URL 会说明「可以留空」，并且允许在没存过 Key 时直接保存空值。

### 6.4 提示词

- 翻译系统提示：只输出译文、保留段落、目标语言 X。
- 语法系统提示（v2）：只分析**用户给的那一句**，直接写 Markdown 讲解 —— 主句结构、从句/短语
  如何嵌进去、容易漏的语法点、重要搭配在本句里的意思；短标题 + 要点。不再要求 JSON schema：
  JSON 一漏字段整段就退化成原始文本，Markdown 是模型直接写给人看的东西。
- 语法输出不做解析：Markdown 原文进缓存，客户端用 `org.commonmark` 渲染；空 content =
  `EMPTY_RESPONSE`。
- `PROMPT_VERSION` 常量随提示词变更递增（v1 = JSON 语法提示，v2 = Markdown 语法提示；
  升到 v2 时 v1 的 JSON 缓存整批失效）。

## 7. 持久化缓存（核心）

### 7.1 表结构

```kotlin
@Entity(
    tableName = "translationcacheentity",
    indices = [Index("lastAccessedAt"), Index("bookId")]
)
data class TranslationCacheEntity(
    @PrimaryKey val cacheKey: String,
    val bookId: Int,
    val nodeId: Int,
    val kind: String,          // TRANSLATION / GRAMMAR / WORD
    val model: String,
    val baseUrlHost: String,
    val targetLanguage: String,
    val promptVersion: Int,
    val payload: String,       // 译文或语法原始 JSON
    val complete: Boolean,
    val createdAt: Long,
    val lastAccessedAt: Long
)
```

- `AppDatabase` version 21，`entities` 增加该表，`AutoMigration(20, 21)`，提交 `21.json`。
- 不存正文全文，只存哈希键；正文参与键计算。

### 7.2 缓存键

```
cacheKey = SHA-256(
  "v$PROMPT_VERSION|$kind|$targetLanguage|$model|$baseUrlHost|${normalize(text)}"
)
```

- `normalize`：trim + 统一换行。
- 换模型、换服务商、换目标语言、改提示词都会自动 miss。
- 同一段文字出现在不同书/章节时共享缓存。

### 7.3 读写策略

- 双击/选中翻译：先读 translation 缓存；命中直接展示，不建网络请求。
- 对话框打开时并行预取 grammar 缓存；切到语法标签时：
  - 命中 → 立即展示；
  - 未命中 → 立即请求（无“开始分析”按钮）。
- 同一 `cacheKey` 的并发请求用 `Map<String, Job/Deferred>` 去重。
- 只在完整完成后写入；流式中途取消/失败不写半截。
- 写入使用 `NonCancellable`；读取时 `touch(lastAccessedAt)`。
- “重新生成”：重新请求 → 用 `put` 覆盖同一键（`REPLACE`），不需要先删；仓库没有按单键删除的 API，`clear()` 只给设置页全清用。
- 容量：默认全部保留；超过 20,000 条或 200MB 时按 `lastAccessedAt` LRU 清理；
  设置页显示条数/占用并提供“清除翻译缓存”。

### 7.4 S7 实施结论（已落地）

- 落点：`data/storage/room/entity/TranslationCacheEntity`、`dao/TranslationCacheDao`
  （`@Dao abstract class`，`trimToBytes` 是事务方法）、`AppDatabase` 版本 21 +
  `AutoMigration(20, 21)` + 导出的 `app/schemas/…AppDatabase/21.json`；
  键与纯逻辑在 `data/ai/cache/TranslationCacheKey`，仓库在 `data/ai/cache/TranslationCacheRepository`
  （接口 + `Impl`，`RepositoryModule` 里 `@Binds @Singleton`）。
- 占用按 **UTF-8 字节**算，不是 SQLite `LENGTH(text)` 的字符数：后者按字符计，一段中文只有
  实际体积的 1/3，200MB 的预算会被撑成 600MB。DAO 用 `LENGTH(CAST(payload AS BLOB))`。
- `trimToBytes` 不用窗口函数（不同 Android 版本 SQLite 版本不一致），也不做 O(n²) 自连接：
  先读 `(cacheKey, 字节数)` 投影算出切点，再分批删（每批 500 键，避开 `IN (...)` 的参数上限）。
  **至少保留最新一行**：单行就超预算时删掉它只会让下次访问重新请求、写回、再被删。
- `trimToEntries` / `deleteOldest` 的 `limit` 按至少 1 处理（`coerceAtLeast(1)`）：
  `LIMIT 0` 的保留集是空集，等于把整个缓存删光；统一按至少 1 处理。
- `stats()` 用一次查询取回条数与字节数：分两次查时，并发的 `put`/`trim` 会让两个数字
  来自不同快照，设置页会显示条数变了而占用没变的组合。
- 键只含 Base URL 的 host（§7.2），因此同一主机不同路径的两个网关共用缓存；畸形 Base URL 的
  host 解析为空串而不是抛异常（`aiBaseUrlHost` 复用，不另写一份解析）。
- `touch` 用系统时钟（`System.currentTimeMillis()`），只有 `put` 的 `now` 由调用方传入
  （S8 有完成时刻）；没有为测试注入时钟。
- S8 的调用方式：
  `key = translationCacheKey(text, TranslationKind.TRANSLATION, targetLanguage.promptName(uiLocale), model, baseUrl)`；
  `repo.get(key)` → 命中直接展示（`CachedAiResult.fromCache == true`）；未命中请求完成后
  `repo.put(key, kind, bookId, nodeId, payload, model, baseUrl, targetLanguage, AI_PROMPT_VERSION, now)`。
  「重新生成」= 重新请求后用 `put` 覆盖同一键（`REPLACE`），不需要先删；仓库只提供 `clear()` 全清，
  没有按单键删除的 API。
- 仪器测试：`TranslationCacheRepositoryTest`（内存 Room，11 例）+
  `TranslationCacheMigrationTest`（`MigrationTestHelper` 跑 20→21 并校验表结构）。
  `MigrationTestHelper` 在测试进程里按 assets 读 schema，`app/build.gradle.kts` 的 `androidTest`
  sourceSet 加了 `assets.srcDir("$projectDir/schemas")`，否则运行时找不到 `20.json`。

## 8. 双击与语法页行为

- 双击段落：取 `NodeContent.Paragraph.text` 的整段文本（不是分页切片），
  打开翻译对话框；翻译标签自动开始（缓存命中则直接显示）。
- `doubleClickTranslation` 保留，默认 `true`；开启时单击翻页/菜单会等待双击判定。
  阅读页把 `LocalViewConfiguration.doubleTapTimeoutMillis` 从平台默认 300ms 降到 **200ms**
  （`ReaderScaffold`），配合翻页动画从默认 spring（实测 ~416ms 落定）改成 200ms tween：
  真机实测「按下 → onTap」300→208ms、「onTap → 落定」416→约 250ms。设置页说明取舍，
  可关闭双击翻译恢复完全即时。
- 语法标签：逐句分析（§8.2）。整段只有一句时切到标签自动分析；多句时先列出句子，
  点哪句分析哪句、再点一次收起；结果用 Markdown 渲染。没有「开始分析」按钮；
  错误态提供「重试」，成功后提供「重新分析」。
- 对话框尺寸随状态变化：翻译标签 Idle/Loading（或单句语法还在请求）时是一张 360dp 宽的
  紧凑卡片，只显示标题/标签/居中的加载行；内容（首段流式文字或结果）到达后
  `animateContentSize` 平滑展开到最大 600dp 宽、80% 高，超过后正文滚动。模型与目标语言
  从滚动正文挪到标题下的固定副标题，标题/标签与正文之间加分隔线。
- 关闭对话框取消请求；已完成结果保留；进程被杀后再次双击从 Room 读取。
- 外部翻译 Intent 仅保留为**翻译标签**错误态的“用其他应用”回退：它只能把原文送去翻译，
  给不了语法分析，所以语法标签的错误态只有「重试」。

### 8.1 S8 实施结论（已落地）

- 落点：`presentation/reader/TranslationModel`（`@HiltViewModel`）、
  `presentation/reader/model/{TranslationState,GrammarAnalysis}`、
  `ui/reader/{ReaderTranslationActions,ReaderTranslationDialog}`、
  `data/ai/AiRequestConfig`（`AiRequestConfigProvider` 配置快照）。
  对话框挂在 `ReaderContent`（`ReaderScreen` 的渲染层，与取词卡片同级，同在
  `LocalReaderPalette` 提供范围内），通过 `ReaderScaffold` → 两种布局传递同一个
  `ReaderTranslationActions`。
- 一次 `open` 固定一份 `Session`（配置快照 + 两个 cacheKey + 正文 + bookId/nodeId）：
  切标签、重新生成都用同一份，缓存键不会因为设置中途变化而与本次 open 的键错位。
  快照里**只有目标语言**同时进了请求（`translate(text, targetLanguage)`）：model/baseUrl 由
  `AiTranslationRepository` 在请求开始时现取设置，所以「键与请求同一份配置」只对目标语言成立，
  model/baseUrl 只在设置没变时一致（对话框开着时改不了设置，app 内碰不到这条缝隙）。
- 缓存优先：`open` 先读 translation 缓存（命中直接展示、零请求）；grammar 缓存并行**预取**
  （只读不请求），切到语法标签时才请求。`Map<cacheKey, Job>` 去重，重复点标签不会发第二个请求。
- 只写完整结果：流式结束后 `put`（仓库内部 `NonCancellable`）；写库失败只记日志，不藏结果；
  空译文按失败处理（不写缓存）。落库前再复查一次「还是不是当前这次 open、跑它的协程有没有被
  取消」，被取消的旧请求不能把结果写进缓存盖掉重新生成的新结果。
  `dismiss()` 取消全部 Job 并递增 `requestId`，迟到响应被丢弃。
- 双击取 `NodeContent.Paragraph.text`（`ReaderNode.Paragraph.wholeParagraphText()`），
  两种布局都传节点 id；选区工具栏「翻译」把**整段选中文字**（可以跨多行）交给对话框、
  `nodeId = null`（落库记 `-1`，`-1` 不参与缓存键）。仍然只认单行的是 **S5 的「选后自动取词」**：
  选区稳定后自动查词只取单行内的一个词，跨行选区不会自动弹卡片（要翻译请用工具栏）。
- `doubleClickTranslation` 默认值从 `false` 改为 **`true`**（§0/§8 的既定决策；S5 时期
  双击只是拉起外部 Intent，才保持关闭），设置项说明里写明单击会等约 300ms。
- 偏差：
  1. 语法 JSON 用 kotlinx.serialization 的 `JsonElement` 手写宽容解析（围栏/前后说明文字/
     多字段/缺字段/`points` 写成字符串都能处理），解析失败返回 null → UI 展示原始文本。
     解析放在 `Dispatchers.Default` 上（不在主线程解 JSON）。
  2. 语法/译文错误只带 `AiError` + HTTP 状态码，文案复用 S6 的 `ai_error_*` 资源。
  3. 对话框文案与取词卡片的「AI 解释」按钮：后者仍禁用，文案从 `(S8)` 改为 `(S9)` ——
     S8 只做段落翻译与语法，单词 AI 解释留到 S9。
  4. 关闭对话框后不主动 `trim()` 缓存：容量清理仍由设置页触发（S7 结论不变）。
  5. 两个标签的成功态共用同一个「重新生成」文案（本节原文对语法写的是「重新分析」），
     错误态共用「重试」；`regenerate(tab)` 只重置当前标签。
  6. 语法标签的错误态只有「重试」，没有「用其他应用翻译」：外部 Intent 只能翻译原文，
     给不了语法分析，放在这里会承诺一个它做不到的动作。
  7. 语法 JSON 合法但一个字段都没有（例如 `{}`）时不再回显 `{}`，改提示
     `translation_dialog_empty_analysis`；只有 JSON 不合法才退化成展示原始回复。

### 8.2 语法逐句分析（本次 UX 改动，已落地）

- 切句：`data/ai/SentenceSplitter.splitIntoSentences`，纯 JVM，用
  `java.text.BreakIterator.getSentenceInstance(Locale.ENGLISH)`（Android 上是 ICU 实现），
  `trim` + 丢弃空条目。已知限制：UAX#29 没有缩写词典，`Dr. Smith` 仍会在 `Dr.` 后断开；
  不做后处理（把两句误并成一句会让一次分析里塞两个主句）。
- 状态：`TranslationState` 新增 `sentences` 与 `selectedSentenceIndex`；`grammar` 只描述
  **当前展开那一句**。`AsyncGrammar.Success(text: String, fromCache: Boolean)` 直接就是 Markdown
  原文，JSON 解析器（`GrammarAnalysis`、`parseGrammarAnalysis`）与 `response_format` 全部删除。
- 模型：`open` 只切句不请求；`selectTab(GRAMMAR)` 多句时**不**自动请求，单句自动选第 0 句并分析；
  `selectSentence(index)` 同句 = 收起（不清缓存），异句 = 展开并分析（已有结果直接显示）。
  结果按 `translationCacheKey(sentence, GRAMMAR, …)` 逐句缓存，本次打开期间另有一份内存 map，
  收起再展开零请求。
- 迟到响应：切换句子**不**取消上一句在途请求（跑完进缓存，切回去直接显示），
  发布前校验「还是不是这次 open」+「展开的还是不是这一句」，旧结果只能进缓存。
  `regenerate(GRAMMAR)` 取消当前句的在途请求后重发，`put` 覆盖同一键。
- UI：`ui/reader/MarkdownText`（commonmark AST → `AnnotatedString`：标题加粗放大、
  列表前缀与缩进、粗体/斜体、行内代码、围栏代码块、引用竖线、块间空行；颜色来自
  `LocalReaderPalette`，纯 JVM 可测）。语法标签 = 句子卡片列表（编号、最多两行、选中高亮，
  点句子行展开/收起）；结果区不在点击范围内，复制/分享用 Markdown 原文。
- 文案：`translation_dialog_sentence_pick`、`translation_dialog_reanalyze`（en/zh）；
  删除 JSON 时代的 `translation_dialog_summary/sentences/vocabulary/raw_note/empty_analysis`。

## 9. 设置与文案

- `TranslatorSubcategory`：双击翻译开关、取词手势、进入“翻译与词典设置”。
- `TranslationSettingsScreen`：
  - 词典：内置 ECDICT 版本/状态、导入/启用/删除、导入进度与错误。
  - AI：Base URL、Model、Key（密码框/显示切换）、目标语言、测试连接。
  - 缓存：条数/占用、清除。
  - 隐私与费用说明：正文会发送给配置的第三方。S6 的设置页只说明这一点；
    「切到语法标签即请求」是 S8 对话框的行为，S6 的用户可见文案里不许诺。
- 新文案进 `values/strings.xml` 与 `values-zh-rCN/strings.xml`，其他语言回退英文。

## 10. 分阶段实施与验收

每个阶段由 `worker` 实现，完成后 `reviewer` 第 1 轮、修复后第 2 轮；最终整体 review 按要求取消。
两轮通过后提交并进入下一阶段。

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| S1 | 设计文档、`phase-2` 分支、依赖与 Spike（openai-kotlin/Ktor、Tink、7z） | Debug/Release-debug 可编译；MockEngine 流式测试；Tink 存取测试；7z 解压测试；R8 通过 |
| S2 | `:dict-engine` 模块 + `DictionaryEngines` + 上游测试 | 引擎单测通过；MDX/StarDict fixture 查询/渲染通过（fixture 在测试运行时合成，不依赖外部语料） |
| S3 | ECDICT `stardict.7z` 资产 + 首次解压 + 内置词典注册 | 首次启动解压成功；`apple`/`perceive` 可查；重启不重复解压 |
| S4 | 词典仓库、MDX/StarDict 导入、设置页词典管理 | 导入/启用/删除；损坏/加密/空间不足错误明确 |
| S5 | 取词手势、文本布局命中、取词卡片 | 两种布局单击/长按取词；空白区翻页/菜单不回归；RTL/夜间正常 |
| S6 | AI 设置、Tink、openai 客户端、MockEngine 测试 | Key 加密存取；流式/错误/取消；测试连接 |
| S7 | Room 缓存表/DAO/迁移/仓库/缓存键 | ✅ 迁移 20→21（`MigrationTestHelper` 实跑）；读写/touch/LRU/clear 通过 |
| S8 | `TranslationModel`、双击整段、翻译/语法对话框、缓存优先、语法点击即分析 | ✅ JVM：二次双击 0 请求、语法缓存命中 0 请求、取消不写半截、重新生成覆盖同键（34 个新用例）；杀进程后仍命中待真机/仪器验证 |
| S9 | 文案/许可/文档、全量回归、release-debug、终审 | ✅ JVM 379 / 引擎 102 / 仪器 22 通过；体积/内存记录；每阶段两轮 `reviewer`，最终整体 review 按要求取消 |

> S1–S9 已完成。S8 的对话框只在 JVM 上验证过状态机与解析，UI 行为（居中对话框、
> 标签切换、缓存标记、错误态回退）**未**在真机/模拟器上走过，见 `PROGRESS.md` §3.8。
> S3 的验收只在 JVM 上做过（真实资产解压 + `apple` 查询 + 二次安装跳过），
> 真机首启耗时/内存峰值与 minified 构建下的 7z 解压仍待验证；实测数据见 `PROGRESS.md` §3.3。
> S6 的 `AiKeyStore` 只在 JVM 上用假 cipher 测过；真实 Tink/AndroidKeysetManager 仪器测试已写但**未执行**；
> 真实服务商的测试连接/流式/401/429 也未在设备上验证，见 `PROGRESS.md` §3.6。
> S7 的 Room DAO/迁移测试在**模拟器**（`reader34` / API 34）上跑过，**没有**在真机上跑；
> 缓存跨进程重建（杀进程后再读）要到 S8 有调用方才能验证，见 `PROGRESS.md` §3.7。

## 11. 测试矩阵

### JVM / 单元

- 引擎：上游 `Lzo1xTest`、`MdxHeaderTest`、`MdxKeyOrderTest`、`MdxLinkRewriteTest`、
  `MdxBytesTest`、`Ripemd128Test`、`StarDict*Test`；本地适配层 `DictionaryEnginesTest`
  （StarDict `.idx` / `.idx.gz` + `.syn`、`.mdd` 配对、`logSink`）与 `MdxFixtureTest`
  （运行时合成的合法 MDX 2.0，覆盖 probe → open → lookup → 渲染）。
- 缓存键：模型/语言/提示词/空白变化；SHA-256 稳定。
- `TranslationModel`：缓存命中、miss、并发去重、取消、重新生成（fake repository）；
  语法逐句：多句零请求、点哪句分析哪句、逐句缓存键、收起/切换、迟到响应不覆盖、单句自动分析。
- `SentenceSplitter`：单句/多句、段落空行、无终止标点、CJK 全角标点、缩写（已知限制）。
- `MarkdownText`：标题加粗放大、有序/无序列表与嵌套缩进、粗体/斜体、行内代码、代码块、
  块间空行、引用标记、空输入。
- AI：Ktor `MockEngine` 的流式拼接、`[DONE]`、401/404/429/5xx（带状态码）/超时映射、
  JSON 请求、取消、按句切块与 Base URL 规范化（补 `/`、补 `https://`、本机/远程判定）、
  本机地址免 Key 与公网地址无 Key 的 `NO_KEY`。生产引擎用 loopback 的最小 HTTP 服务验证
  （`AiOkHttpEngineTest`），不走 ServiceLoader。
- 取词候选：word boundary 纯函数、CJK 限长、标点剥离。

### Android / 仪器

- Room DAO、迁移 20→21（S7）；缓存跨进程重建（S8/S9，S7 未写）。
- 内置 ECDICT 解压、查询、内存峰值。
- Tink Key 存取。
- 取词卡片/翻译对话框 Compose 行为（可用现有测试 runner，不引入 Compose UI 测试库）。

### 手工 / 真机

- 分页/连续、单击/长按/双击、空白翻页/菜单、长按选择复制。
- 夜间预设、RTL、超大字号、横竖屏、进程重建。
- 无网、401/429、流式中途关闭、超长段落。
- 双击整段（两种布局）→ 译文流式；切语法标签即分析；再次双击/再次点标签 0 请求；
  关掉对话框后重开命中缓存；错误态的「用其他应用翻译」。
- `release-debug` minified：打开书 → 取词 → 双击翻译 → 语法 → 二次命中。
- APK/安装体积、首次解压耗时、ECDICT 查询内存。

## 12. 风险与缓解

1. **缓存误用旧结果**：键含 `promptVersion/model/baseUrlHost/targetLanguage`；提供“重新生成”。
2. **双击默认开启导致单击延迟**：保留开关；实测不可接受时再评估默认值/仅中区双击。
3. **语法点击即请求产生费用**：设置页明确说明；可取消；“重新生成”才重复计费。
4. **缓存膨胀**：LRU 上限 + 占用展示 + 一键清除；正文不入库。
5. **7z 解压失败/空间不足**：原子目录、保留旧版本、明确错误；解压前检查剩余空间。
6. **GPL 兼容**：应用与引擎均为 GPL-3.0；保留 `LICENSE`/`PROVENANCE` 并随源码分发。
7. **库兼容/R8**：S1 Spike + `release-debug` 验证；失败时回退官方 OpenAI Java SDK，不手写 SSE。
8. **词典 HTML 复杂**：Compose `fromHtml` 只保证基础标签；复杂 CSS/表格退化为文本，后续可评估 WebView。

## 13. 许可与归属

- juicedict 引擎：GPL-3.0，来源与 commit 写入 `third_party/juicedict-engine/PROVENANCE.md`。
- ECDICT：MIT，附 `ECDICT-LICENSE.txt`，记录数据版本/SHA。
- openai-kotlin：MIT；Ktor：Apache-2.0；Tink：Apache-2.0；commons-compress：Apache-2.0；xz：Public Domain。
- 所有第三方依赖进入 AboutLibraries/Credits；ECDICT 与引擎手工补条目。

## 14. 文档更新

- 本文档：`docs/phase-2-design.md`。
- `DEV_PLAN.md`：新增“第二阶段”链接与关键决策摘要。
- `UPSTREAM_DIFF.md`：新增功能、设置项、缓存与许可说明。
- `PROGRESS.md`：阶段状态、已验证/未验证项、体积/内存数据。
- `README.md`：功能列表与内置词库说明。

## 15. 最终状态与已知限制

- 阶段 S1–S9 全部完成；`phase-2` 分支按阶段提交。
- 最终校验：`:app:testDebugUnitTest` 379 / 55 类、`:dict-engine:test` 102、`:app:connectedDebugAndroidTest` 22（模拟器 API 34）、`:app:assembleRelease-debug` 通过；APK debug 155,192,122 B / release-debug 56,025,366 B。
- 首次安装模拟器实测约 24.8s，词典目录约 122,800 KB（`.dict`/`.idx`/`.jidx`/`version`）。
- 语法逐句分析（§8.2）只做过 JVM 状态机与渲染测试：句子列表的点击/收起、长列表滚动、Markdown 排版在设备上仍未走过；切句依赖平台 `BreakIterator`，缩写（`Dr.`）会多切一刀。
- 已知限制：单词级 AI 解释未实现；`.mdd` 不导入/不渲染；需要密码的 MDX 不支持；长按自动取词只处理单行（工具栏翻译可翻多行选区）；缓存跨进程重建没有仪器测试；release-debug 上真实 AI/Tink/Room 路径只做构建级验证；默认双击翻译带来约 200ms 单击延迟（见附录 A.6）；S5/S8 手工 UI 矩阵未执行（项目无 Compose UI 测试依赖）。
- 最终整体 review 按用户要求取消；S1–S9 每阶段均完成两轮 `reviewer`。

## 附录 A. 设备反馈迭代（S5/S8 后续，已落地）

> 记录 S1–S9 完成后，按真机使用反馈追加的改动。实现细节与文件见括号内路径；
> 每项都在 Lenovo TB322FC（Android 16 / 1904×3040 / 440dpi）上验证。

### A.1 内置 ECDICT 资产 v2（`tools/dict/prepare_ecdict.py`）

- `ECDICT_ASSET_VERSION` 1 → **2**；新资产 21,691,246 B，SHA-256 `c93fec78…7ad3`
  （`NOTICE.txt` 与 `BuiltInDictionaryAssetTest` 同步）。
- 词条里新增两个不可见标记：
  - `<!--ecdict|c=柯林斯星级|o=牛津|t=考试标签|b=BNC 词频|f=当代语料库词频-->`
  - `<!--def-->`：英文 WordNet 释义的起始位置
- 卡片据此显示「牛津 3000 / 中考 / 高考 / 四级 / 六级 / 考研 / 托福 / 雅思 / GRE / 词频 / ★」，
  并把英文释义单独成段。老用户升级后会自动重新解压一次（约 90MB，几十秒）。

### A.2 取词卡片排版（`ui/reader/ReaderLookupPopup.kt`、`ReaderLookupArticle.kt`）

- 卡片分三层，**只有正文滚动**：固定头部（词头/音标/来源/元信息/多词条 chip）、
  可滚动正文（`weight(1f, fill = false)`）、固定按钮区（未找到/错误）。
- 正文结构化：丢弃与标题重复的 `<b>词</b>`、音标提到头部、中文义项按「词性/领域标签 + 释义」
  两列排、英文释义降为次要小节并把行首词性渲染成浅灰底粗体徽标、词形说明放文末。
  只拆「仅含行内标签」的词条，块级标签/未知标签退回 `fromHtml`。
- 多词条候选 **换行**（`FlowRow` + ~24dp 紧凑胶囊），不做横向滚动；与上方元信息之间
  用 10dp + 发丝线 + 10dp 分组。
- 按下即高亮：`onPress` 先命词并写 `DictionaryLookupModel.previewAnchor`（只画高亮、不查词），
  `onTap` 落地后才查词；手势取消/双击翻译时撤掉预览。

### A.3 高亮锚点修复（`ui/reader/ReaderTextLayoutRegistry.kt`、`ReaderScaffold.kt`）

三轮真机反馈分别暴露了三个独立原因，现全部修复：

1. **窗口矩形被裁剪/夹紧**：原先用 `boundsInWindow()`，会被 LazyColumn 视口裁剪并夹到根节点内，
   段落滚出屏幕一半时原点被钉在视口边。改为 `positionInWindow()` + 节点尺寸，注册表存未裁剪矩形。
2. **长按用选区矩形当锚点**：`getContentRect()` 是「选区包围盒 + 底部 `4 × HandleHeight` + 裁剪到
   可见区」，带标点/靠边的词必偏。改为用注册表命中的精确词矩形；探测点取选区顶边而非几何中心；
   跨行判定改用「选区宽度≈行宽」。
3. **两端对齐的横向偏移**：Android 的 `getPrimaryHorizontal` 返回未拉伸坐标，绘制时词间空格被拉宽
   （实测 20pt/行宽 1804px 时行尾词偏左 ~35px）。按「词前空格数 × 每间隔拉伸量」校正词矩形，
   命中点先按同量左移再取词。
- 高亮竖向用同字号、同字体的 `Paint.getTextBounds` 量词本身墨迹（不再用行框/固定 em 比例），
  四周再留 1.5dp；实测 `crack` 上 3/下 4px、`through` 左 3/右 6px。

### A.4 翻译对话框（`ui/reader/ReaderTranslationDialog.kt`）

- 刚打开/等待首个 token 时是一张 **360dp 宽紧凑卡片**（标题 + 固定副标题 + 标签 + 居中加载行），
  `AsyncText.Idle` 也按 loading 画；内容到达后 `animateContentSize` 平滑展开到最大 600dp 宽、
  80% 高，超出后正文滚动。
- 模型与目标语言从滚动正文挪到标题下的固定副标题；标题/标签与正文之间加分隔线。

### A.5 点击分区：Kindle 式（`presentation/reader/model/ReaderTapZones.kt`）

- 默认 `KINDLE`：顶部 **12%**（正文区内）呼出菜单、左侧 **25%** 上一页、其余 **75%** 下一页。
- 旧的「左/中/右」被替换，存量 `SIDES` 值按名字找不到时回落默认（完成迁移）；
  「上/中/下」「仅中间」保留。分区仍是位置性的，反向阅读方向只翻转 `pageDeltaFor`。

### A.6 翻页延迟（`ReaderScaffold.kt`、`ReaderLayoutPaged.kt`）

- 调查结论：与文件大小无关。延迟 = 双击翻译判定 300ms + Pager 默认 spring 动画实测 416ms。
- 修复：阅读页 `LocalViewConfiguration.doubleTapTimeoutMillis` 300 → **200ms**；
  `animateScrollToPage` 改用 **200ms tween**（`Motion.Base` + `Motion.Standard`）。
- 真机日志实测：按下 → onTap 300→**207ms**；onTap → 落定 416→**234ms**；合计 ~716→**441ms**。
  剩余 200ms 是双击窗口本身，设置里关掉双击翻译可完全即时（文案同步为 200ms）。

### A.7 遗留与未验证

- Compose 1.9 默认启用新上下文菜单，绕过项目自定义的 `LocalTextToolbar`：长按选后自动取词
  当前不会触发，选区工具栏只剩系统默认项。锚点/防抖/防重逻辑已就位，等迁移到新上下文菜单 API
  或上游提供关闭开关后再启用。
- 翻译对话框的最终视觉复测曾因设备锁屏中断，恢复后已补测紧凑加载 → 展开；句子列表的点击/收起、
  长列表滚动仍未在设备上走过（项目无 Compose UI 测试依赖）。
- 测试基线更新：`:app:testDebugUnitTest` **395 个用例 / 57 类 / 0 失败**；三重构建门禁通过。
