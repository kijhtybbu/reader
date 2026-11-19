# 与上游的差异说明

本文档记录本 fork（`dev.jia.reader`）相对上游 **Acclorite/book-story**（`ua.acclorite.book_story`，GPL-3.0-only）的全部差异。

- 基线：tag `upstream-base` = `464891ec`（feat: update translations，即 Acclorite/book-story 的 `master`）
- 本仓库：独立的 `kijhtybbu/reader`（2026-10-31 起独立发展，上游只作只读参考，不再 rebase）
- 相对基线：51 个提交，295 个文件，+35045 / −488 行
- 单元测试：`app` 61 个文件 / 402 个用例 / 59 个类；`:dict-engine` 16 个文件 / 102 个用例
- 仪器测试：4 个文件

因为本 fork 派生自 GPL-3.0 项目，**整个 fork 同样是 GPL-3.0-only**，不能闭源分发。
来源、修改与授权的正式声明另见 [`NOTICE.md`](NOTICE.md)，应用内见「关于 → 上游来源 / 授权协议 / 源码」。

---

## 1. 构建与身份

| 项 | 上游 | 本 fork | 原因 |
| --- | --- | --- | --- |
| `applicationId` | `ua.acclorite.book_story` | `dev.jia.reader` | 与上游共存，不覆盖已安装的原版 |
| `namespace` | `ua.acclorite.book_story` | **不变** | 与 Kotlin 包路径、`R` 引用绑定，改名要触及 29k 行的全部 import；如需彻底改名另开一次纯机械提交 |
| `minSdk` | 26 | **29** | `Typeface.CustomFallbackBuilder` 是 API 29 才有，是用户导入字体时唯一能串中文字形回退链的 API |
| `testInstrumentationRunner` | 未配置（默认是已废弃的 `android.test.InstrumentationTestRunner`） | `androidx.test.runner.AndroidJUnitRunner` | 上游的配置下 `AndroidJUnit4` 根本跑不起来 |
| 测试依赖 | `ui-test-junit4:1.10.0` / `ui-test-manifest:1.10.0` / `ui-tooling:1.10.0` | 删除前两个；`ui-tooling` 钉回 `1.9.3`；新增 `androidx.test:runner` + `androidx.test.ext:junit` | Compose 是 atomic group：上游把测试件钉在 1.10.0 会把 debug 变体的 `ui` 拉到 1.10.0，导致**调试包和发布包跑的不是同一套文本引擎** |
| ProGuard | 无 protobuf 规则 | `-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }` | 见 §2.1 |
| 应用名 | Book's Story | 麦麦阅读器（v2.0 起；v1.8.0 及以前为「本地阅读器」） | 与上游区分；显示名为硬编码中文，随 `app_name` 单一来源生效 |
| `versionName` | `1.8.0` | `2.0`（`versionCode` 14 → 15） | 改名后一次大版本号，同时保证覆盖安装的单调递增 |

`gradlew` 的文件模式从 644 修正为可执行（上游在 git 里记录的是 644，macOS 上直接 `./gradlew` 会 `Permission denied`）。

---

## 2. 上游缺陷修复（本 fork 修的是上游的 bug）

这两条不是"我们的新功能"，而是**上游 `master` 上已经存在、但尚未发布出去的缺陷**。

准确范围（已用官方发布包 + 未改动的上游源码双向验证）：

| | 官方发布版 **v1.8.0** | `upstream-base` = `464891ec`（分叉时的上游 `master`） |
| --- | --- | --- |
| `.proto` 文件 | 0 个 | 1 个 |
| `protobuf-javalite` | 无 | 4.33.2 |
| minified 构建能否打开书籍 | ✅ 正常 | ❌ **失败** |

protobuf 是在分叉点那唯一一个超出 v1.8.0 的上游提交（`464891ec`，提交信息写的是 "feat: update translations"）里引入的。
也就是说：**这是一个会随上游下一个版本发布出去的回归**，而本 fork 因为基于 `master` 才踩到它。

### 2.1 minified 构建完全无法打开书籍

**现象**：任何书（TXT/EPUB）都报「文件似乎为空，请检查文件内容」。debug 构建正常。

**根因**：`protobuf-javalite` 通过**反射按字段名**读写消息，且自身不带 consumer proguard 规则；而 `release` 构建类型开启 R8。改名后 `ParagraphProto.text_` 变成 `e`，于是：

```
RuntimeException: Field text_ for ...ParagraphProto not found.
Known fields are [... e, ... f, ... g, ... h]
```

**对照实验**：
- 官方发布的 **v1.8.0** APK（26.6MB，minified）——打开真实 EPUB 正常（它没有 protobuf）
- 未改动的上游基线（`upstream-base` = `464891ec`）构建 `release-debug`——同一台设备、同一个文件，失败（`Could not parse book content.`）
- 同一份 master 的 **debug** 构建——正常（不开 R8）
- 上游 `proguard-rules.pro` 里没有任何 protobuf 规则，而 `protobuf-javalite` 自身不带 consumer 规则

唯一变量是 R8 与 protobuf 的组合。

**修复**：加上 keep 规则后，minified 构建在真机上正常渲染。

### 2.2 错误信息撒谎，把排查引向错误方向

`NodeHandler.cacheNodes` 的失败被 `.getOrNull()` 静默吞掉，于是**缓存写失败**被报成「文件似乎为空」。用户会去检查文件，而问题在序列化。

**修复**：仓库层记录异常；`TxtContentParser` 区分「文件真的没有节点」与「节点写不进缓存」（后者报 `error_could_not_save_file`）。这条改动是定位 §2.1 的前提。

### 2.3 大屏上「屏幕方向」设置静默失效（Android 16 / targetSdk 36）

**现象**：平板上阅读页「屏幕方向」六个选项（默认 / 自由 / 竖屏 / 横屏 / 锁定竖屏 / 锁定横屏）
点了全部没反应；手机正常。不崩溃、不报错、日志干净。

**根因**：上游 `1772d9e7` 把 `targetSdk` 35 → **36**。Android 16 起，targetSdk 36 的应用在
最小宽度 ≥ `600dp` 的屏幕上被忽略 `screenOrientation` / `setRequestedOrientation()` /
`getRequestedOrientation()` / `resizeableActivity` / 宽高比限制（**全屏与多窗口都一样**）。
测试机 Lenovo TB322FC：1904×3040 @440dpi → sw ≈ **692dp**，正好踩中；官方例外只有游戏
（`appCategory`）、用户在系统「应用宽高比」里手动选按应用默认行为、以及 `sw < 600dp` 的屏幕。

代码链路本身是通的（`SettingsManager` 持久化 → `ReaderScreen.kt:445`
`activity.requestedOrientation = …code` → `lastValue` 不是滞后值），所以只能从 manifest 复现，
纯 JVM 单测与 debug 构建都看不出来。

**上游状态**：`upstream-base`（`464891ec`）的 `AndroidManifest.xml` 里没有任何 `<property>`，
所以这不是 fork 引入的问题，上游同样有这个缺陷。

**修复**：`MainActivity` 声明
`<property android:name="android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY" android:value="true" />`。

**时限**：Android 17（API 37）**删除了这个 opt-out**（"removes the temporary developer opt-out"），
`sw≥600dp` 上永久失效。抬 `targetSdk` 到 37 时必须换成别的形态（大屏隐藏这组 chip /
改成应用内提示），不能指望这行继续有效。

### 2.4 标题内嵌粗体时，格式标记被原样显示

**现象**：EPUB 第 20 章标题显示成 `{{B-START}}20{{B-END}}`（而不是「20」）。只有标题里
含 `<b>`/`<i>`/`<a>` 的章节会中招，纯文本标题正常。

**根因**：`JsoupDocumentParser.processDocument` 对 `strong, b, emphasis / em, i, cite, dfn, address / a`
统一插入段落格式标记（`{{B-START}}` 等），但 `processMarkedDocument` 的 Header 分支
直接取 `getHeaderTitle(line)`，没有像 Paragraph 分支那样调用 `clearMarkers`。
`NodeContent.Header` 只存纯文本、不解析样式，于是标记被当成标题正文渲染。

**上游状态**：`upstream-base`（`464891ec`）的 `JsoupDocumentParser` 是上游代码，同样存在。

**修复**：Header 标题先 `Paragraph.clearMarkers(...)` 再 `trim()`；`JSOUP_PARSER` 2 → 3，
让 EPUB/HTML/XML 的已缓存内容失效，用户重开书时自动重建（已读位置保留）。

---

## 3. 新增功能

### 3.1 翻页动画（新设置：`pagedAnimation`）

| 模式 | 实现 |
| --- | --- |
| 平移（默认） | 保持 Pager 原生行为，不做任何额外变换 |
| 淡入淡出 | 两页抵消 Pager 位移后按 `1 - |offsetDistance|` 交叉淡变 |

数学抽成纯函数 `pagerPageTransform(animation, offsetDistance, pageSize, reversed)`，因为算错不会崩、只会"翻页看着怪"。支持横向/纵向与反向布局。

**不做"覆盖"模式**：它要求离场页静止而新页滑入，但 Pager 的 `currentPage` 在拖动过中点时翻转，单凭 `(index, currentPage, offsetFraction)` 无法可靠判断手势起点；试写版本在翻转点会跳半页或露出背景色。**不做"无动画"**：跟手拖拽本身就是动画。

### 3.2 点击分区（新设置：`tapZones`）

上游正文点击只有一个行为：切换菜单。

| 分区 | 顶部 | 左侧 | 其余 |
| --- | --- | --- | --- |
| Kindle 式（默认） | 12% 高度：呼出菜单 | 25% 宽度：上一页 | 75% 宽度：下一页 |
| 上/中/下 | 菜单 | 下一页（中）/ 上一页（下） | — |
| 仅中间 | 不响应 | 不响应 | 仅中心九宫格呼菜单 |

分区是**位置性**的（与设置文案一致）；反向阅读方向翻转的是页码增量（`pageDeltaFor`），不是分区。全仓库只有**一个**点击检测器：上游给每个段落挂的 `combinedClickable` 已移除（它会把单击吞掉，导致分区翻页在文字区域失效），双击翻译改由 Pager 上的同一检测器 + `readerNodeAtY` 命中测试处理。阅读页把双击判定窗口从 300ms 降到 200ms，翻页动画改成 200ms tween（见 `PROGRESS.md` §3.13）。

### 3.3 章节切换提示

跨章时在顶部显示章名 1.5 秒。分页模式跨章会重新分页并淡出正文，没有这个提示用户不知道"已经换章"。

跟随的是「当前章名变化」，因此目录跳转、进度条拖动、断点恢复也会触发（刻意如此：那些操作同样让人失去位置感）。

### 3.4 书签（上游完全没有）

上游的 `Checkpoint(nodeId, nodeIntraOffset, offset)` 只是会话内「回到上次位置」的快照，不落盘、无列表。

- **定位**：`(nodeId, nodeIntraOffset)`，绝不用页码（改字号即失效）；跳转走新增的 `ReaderEvent.OnScrollToPosition`
- **去重**：按 `nodeId`（一个段落一条）。代价是同段不能放两条，收益是**改字号后图标与去重永远正确**（偏移会变，nodeId 不会）。在已有书签的段落里点另一行是**移动**而非新增
- **存储**：`bookmarkentity` 表（Room 18 → 19，`AutoMigration`），唯一索引 `(bookId, nodeId, nodeIntraOffset)`；章名与 60 字摘录冗余存储，列表不 join
- **写路径**：`@Transaction moveBookmark` 先插后删，插入返回 `-1` 就不动旧行——先删后插一旦冲突会让书签**永久消失**
- **UI**：顶栏两个动作（切换当前段落收藏 / 打开列表）+ 列表面板；按钮有第三态（"会移动"）的无障碍描述
- **清理**：删书时一并删书签（`deleteBookmarksForBook` 在上游是死代码）

### 3.5 书架

- **阅读进度环**：网格封面上的进度从纯文字胶囊改为环形进度 + 百分比，配 scrim 底衬（纯白封面上环形进度不可见）
- **排序可见**：当前生效排序是「最近阅读」时，顶栏显示副标题说明——上游默认按最近阅读排，但界面上没有任何地方说明，用户看不出书为什么是这个顺序
- **封面圆角统一**：网格用 `shapes.medium`(12dp)、列表用 `shapes.small`(8dp)，同一张封面两种形状；现在统一走 `Radius.cover`

### 3.6 排版面板重排

上游改一次字号要：点正文 → 底部面板 → 切「阅读器」Tab → 进「字体」子页 → 拖滑块，且拖动时面板遮住正文。

- Tab 顺序改为 **排版 / 更多 / 颜色 / 翻译器**，第一个 Tab 前七行就是高频项：字号、行距、段距、字距、缩进、对齐、字体；翻译与词典设置独立为最后一个 Tab
- **4 个排版预设**：紧凑 / 标准 / 大字 / 护眼，一次写入 5 个设置
- **面板内样例预览**：分页模式下任何排版参数变化都会把正文淡出到 alpha 0 重新分页，所以"拖动时看面板上方的正文"不成立。改为在面板里渲染一段固定文字，与阅读布局无关
- 面板高度：排版页 0.74（顶部固定「全局/本书」选择器 + 样例预览），其余 0.62、颜色页 0.6；排版/颜色页 scrim 透明（露出上方正文）

### 3.7 用户导入字体（中文字体问题的正解）

上游 31 个内置字体**全是拉丁字库**，中文书下「字体」这一项选了等于没选。本 fork 支持用户自己导入。

- **导入**：`OpenDocument` + 扩展名与魔数双重校验（TrueType `00 01 00 00` / OTTO），拒绝 `.ttc`（需 face index）与 woff/woff2（给出可操作提示），上限 40MB，拷贝到 `filesDir/fonts/`
- **中文字形检测**：解析字体的 `cmap` 表（纯 JVM，可单测），同时查汉字与全角标点——只查汉字会漏掉缺中文标点的字库
- **回退链**：`FontFamily(typeface)` 没有回退链，所以每个 Typeface 都用 `CustomFallbackBuilder(...).setSystemFallback("sans-serif")` 包一层，纯拉丁字体下中文落到系统字体而不是豆腐块
- **性能**：Compose 的 `Font(file=…)` 构造即建 Typeface，照抄内置字体的 9 字重写法会把 20MB 的 CJK 字体读 9 遍；这里按 `(file, weight, italic)` 用 LruCache 只建需要的那份，并在 `Dispatchers.Default` 上建
- **持久化**：索引是 `filesDir/fonts/index.json`，在 `Application.onCreate` 里**同步**加载——`SettingsManager.fontFamily` 的反序列化是同步的，索引若异步加载会在重启后静默回落默认字体
- **UI**：列表显示体积 + `Chinese glyphs` / `No Chinese glyphs` + `Variable` 徽标，配三行预览（中文 / 拉丁+数字 / 全角标点），让用户能自己判断而不是盲信徽标；删除当前字体时先回落默认字体再删文件

### 3.8 内置 ECDICT 词典（离线取词的数据基础）

上游没有任何词典功能。本 fork 随 APK 分发一份 ECDICT（英汉，MIT），首次启动解压到应用私有目录，供后续取词使用。

- **数据**：官方 ECDICT `ecdict.csv`（SHA-256 `d1ecbdf9…43cc`，MIT）→ StarDict（`ecdict.ifo`/`.idx`/`.dict`，**770,611** 词条）→ LZMA2 7z（**21.7MB**）。转换与打包是构建期脚本 `tools/dict/prepare_ecdict.py`，App 里不跑。词条里带两个不可见标记：`<!--ecdict|c=柯林斯|o=牛津|t=考试标签|b=BNC|f=语料库-->` 与 `<!--def-->`（英文释义起始），供取词卡片分栏与显示牛津/考试/词频标签
- **首次启动**：`Application.onCreate` 在 IO 线程把资产拷到 cacheDir，再用 commons-compress 解压到 `filesDir/dictionaries/builtin/`。解压出 **90MB**，引擎另写 **34MB** `.jidx` 索引缓存（目录合计约 120MiB）。启动不被阻塞，未就绪时 `openBuiltIn()` 返回 null
- **幂等**：目录里写 `version` 标记（版本 + 词条数），命中则跳过 —— 重启不重复解压，mtime 不变；换资产时递增 `ECDICT_ASSET_VERSION`
- **失败安全**：临时目录 + `rename` 原子换目录，失败保留旧版本；进程死在两次 rename 之间时，下次启动先把 `builtin.old` 搬回来再判标记（不重解）；解压前按「解压量 × 1.5 + 旧目录 + `.old` 孤儿」检查剩余空间；空间不足/资产损坏/解压结果打不开都报 `DictionaryInstallException`；解压条目统一落成小写 StarDict 后缀
- **许可与归属**：ECDICT 为 MIT，`assets/dictionaries/ecdict/ECDICT-LICENSE.txt` + `NOTICE.txt`（数据来源、CSV 与 7z 的 SHA-256）随包分发；引擎（juicedict）是 GPL-3.0，本 fork 整体也是 GPL-3.0-only
- **实测**（JVM，非真机）：解压 1.3s，冷启动打开 77 万词条 190ms；单测用真实资产跑「解压 → `openStarDict` → `lookupSmart("apple")` → `toHtml()` 含中文」与「二次安装跳过」

### 3.9 第二阶段：取词、AI 翻译与持久化缓存

| 能力 | 实现 |
| --- | --- |
| 内置词典 | 随包 ECDICT（MIT，77 万词条）：官方 `ecdict.csv` → StarDict → LZMA2 7z（21MB）→ 首次启动解压到 `filesDir/dictionaries/builtin/`；`version` 标记保证重启不重复解压，失败保留旧目录。 |
| 自定义词典 | 导入 `.mdx`（单文件）或 StarDict 文件夹（`.ifo` + `.idx(.gz)` + `.dict(.dz)` + 可选 `.syn`）；`index.json` 持久化，启用/禁用/优先词典；`.mdd` 资源包经「导入 MDX 文件夹」一起导入（§3.10）。 |
| 取词 | 单击/长按可选，**按书保存、默认关闭**；两种布局都用单一手势检测器 + `TextLayoutResult` 命中，浮动卡片贴词、安全区避让、夜间配色；未命中可去导入词典或用外部应用。 |
| AI 段落翻译 | 双击整段（`NodeContent.Paragraph.text`，非分页切片）→ 居中对话框；**按书开关、默认关闭**；翻译标签流式输出，可复制/分享/重新生成/取消；外部应用翻译只保留为错误回退。 |
| 语法分析 | 整段切句后逐句分析：多句时列出句子，点哪句分析哪句、再点一次收起，单句切到标签即分析（无“开始分析”按钮）；结果是模型写的 Markdown，用 commonmark 渲染（标题/列表/粗体/行内代码），逐句缓存；语法关闭时标签不出现。 |
| 持久化缓存 | `translationcacheentity`（Room 20 → 21，`AutoMigration`）：键 = promptVersion/kind/目标语言/model/BaseURL host/规范化正文的 SHA-256；只写完整结果，命中不请求；20,000 条 / 200MB LRU；设置页显示占用并可清除。 |
| 配置/安全 | OpenAI 兼容 Base URL/Model/Key/目标语言；Key 使用 Tink `AndroidKeysetManager` AES-GCM 加密后存 DataStore；loopback（localhost/127.0.0.1/10.0.2.2）允许空 Key；网络明文只放行这三个回环地址。 |
| 许可 | ECDICT MIT；juicedict 引擎 GPL-3.0（vendored `:dict-engine`，`LICENSE`/`PROVENANCE.md`）；openai-kotlin MIT、Ktor/Tink/commons-compress Apache-2.0、xz Public Domain；AboutLibraries/Credits 与 README 已列。 |
| 体积 | debug APK ~155MB；release-debug ~55.9MB（含 21MB 词典资产）；首次安装数据目录 ~123MB。 |

**配置拆分**：取词手势与双击翻译按书保存、默认关闭（每本书单独开启）；词典与 AI 凭据/目标语言/语法仍是全局共享。全局设置页只保留「词典管理」「AI 翻译设置」两个入口，书内翻译器 Tab 分「本书设置 / 全局词典与 AI」两段，后段提供词典管理与 AI 设置的直达条目。详见 `docs/per-book-translator-design.md`、`PROGRESS.md` §3.22。

实现细节、缓存键、已知限制与未执行的设备矩阵见 `docs/phase-2-design.md` 与 `PROGRESS.md` §3.1–§3.11。

### 3.10 MDD 资源 / CSS / 图片 / 发音 + 混合渲染

上游与本 fork 的前一阶段都只能看纯文本词条：MDict 词典的排版（CSS/字体/表格）、图片和真人发音全在 `.mdd` 资源包里，而导入入口是单文件 `OpenDocument`，拿不到同目录兄弟文件的授权。结果是牛津高阶这类词典拉开全是裸文字，音标后的喇叭图标是个空 `<img>`。本 fork 把这层接上，并且**只对引用资源的词条切渲染器**。

**为什么绕不开引擎改动**：上游的 `MdxResourcePack` 是 `internal`，而且只躲在 `MdxDictionary.resource()` 后面，模块外拿不到。这是 vendored 引擎的第 4 条本地扩展（`PROVENANCE.md`）：

| 新增 | 作用 |
| --- | --- |
| `MdxResources` / `openMdxResources(mddFiles)` | public 只读包装：`names` + `read(path)`；单包坏掉只进 `logSink` 并跳过（分卷资源丢一个不能让其余的不可读），`read` 按传入顺序回退 |
| `checkMdxResourcePack(file)` → `MdxPackCheck` | 导入用：`Ok(resourceCount)` / `Encrypted` / `NotResourcePack` / `Corrupt`。`openMdxResources` 按设计吞包内失败，导入却必须区分「加密」与「损坏」（下一步动作不同） |

两个都是 public 包装，**没有改动任何解析文件**；KDoc 明写实例非线程安全（`seek`+`readFully` 非原子），调用方按实例串行。

**导入形态**：

| 项 | 行为 |
| --- | --- |
| 入口 | 词典管理页第三行「导入 MDX 文件夹（含图片、发音）」，`OpenDocumentTree` 选目录；单文件 `.mdx` 入口保留给纯文本词典 |
| 配对 | 目录内恰好 1 个 `.mdx`，自动配对 `<base>.mdd` / `<base>.1.mdd`…；**编号必须连续**（缺号 → `RESOURCE_PARTS_MISSING`），`.images.mdd` 这类非数字后缀忽略（与引擎 `pairsFor` 一致） |
| 体积 | mdx 仍走 1 MiB 头预检；单个 `.mdx` ≤ 512MB（`MAX_IMPORT_BYTES`），**总量 ≤ 2 GiB**（`MAX_IMPORT_TOTAL_BYTES`）—— 真实 OALD9 是 54MB mdx + 154MB + 518MB + 1.14GB 三个 mdd |
| 校验 | 每个 `.mdd` 复制后 `checkMdxResourcePack` 独立校验（加密 → `ENCRYPTED`，损坏 → `RESOURCE_CORRUPT`）；之后复用现有 `install()`（rename + 写索引） |
| 索引 | `index.json` 格式不变：`hasResources` 由 `mddFilesFor` 运行时推导；`sizeBytes` 本来就按整目录求和 |

**读取与缓存**（`DictionaryResourceStore`）：WebView 的 `shouldInterceptRequest` 在后台线程阻塞取资源，所以 `read` 是阻塞的、按词典串行，和取词引擎各持一把锁；内存缓存单条 256KB / 总量 8MB（大图与音频不进内存）、句柄 LRU 2、发音落盘 `cache/dict-audio/` 上限 32MB、打开失败按 `dictId` 负缓存，禁用/删除/重装即关句柄。

**安全基线**（词条 HTML 来自第三方，不能当可信内容）：

| 层面 | 做法 |
| --- | --- |
| WebView | JS 关闭、DOM storage 关闭、`file://` 访问关闭、`blockNetworkLoads` 全封 |
| HTML | `LookupHtmlRewriter` 剥 `<script>` / `on*` / `javascript:` / `vbscript:`（按**属性名**清，OALD9 里有 158 个 `xhtml:a` 命名空间标签，按元素名会漏），删 `<base>` / `<meta refresh>` / `<iframe>` / `<object>` / `<embed>`，`<style>` 保留但去 `@import` |
| 资源 | 只服务 `https://dict.local/<dictId>/…`；根路径与其它 host 返回 `null`。**根路径必须放行**：`loadDataWithBaseURL` 会把 base URL 本身送进拦截器，回 404 会让整篇变 `net::ERR_HTTP_RESPONSE_CODE_FAILED`；只有「路径存在但包里没有」才 404（子资源缺失不影响主文档） |
| 链接 | `sound://` → `dict-audio://<dictId>/<encoded>`（交给音频播放器），`file:///…` → 相对路径；`juice://lookup/…` 与同词条 `#frag` 原样放行 |

**分流规则**：`hasResources && html 命中 (img|table|link[rel=stylesheet]|sound://)` 才换成 `LookupBody.Media` + WebView；其余词条继续走 Compose 结构化卡片。ECDICT/StarDict 的观感与性能不变，富媒体词条要的是保真。卡片布局：WebView 分支 `weight(1f)` 且**不**套 `verticalScroll`（否则嵌套滚动），头尾仍固定；`textZoom = 14 × fontScale × 100 / 16`（把词典 CSS 的 16px 基准缩到卡片正文 14sp，1.0 时 88%；刻意不跟阅读正文字号走，否则「大字」预设下比 ECDICT 卡片大一圈）。销毁顺序 `stopLoading()` → 换回默认 `WebViewClient` → `about:blank` → `destroy()`，直接把还在加载的 WebView `destroy()` 会在内部线程抛 NPE。

**发音**：`DictionaryAudioPlayer`（`MediaPlayer` + `AUDIOFOCUS_GAIN_TRANSIENT`，失焦即停）与系统 TTS（`WordSpeaker`）由 `DictionaryAudioController` 状态机串行化——播文件前先停 TTS，点 TTS 前先停文件；一个词条的多条候选依次试，全部播不了才回落 TTS 并 toast（静默换机器音会让人以为点错了按钮）。口音靠路径 token 分类：真实 OALD9 里词条音是 `apple__gb_1.mp3` / `apple__us_1.mp3`，例句音却是 `_apple__brs_2.mp3`（英）/ `_apple__uss_2.mp3`（美）/ `_apple__gbs_2.mp3`（英）/ `_apple__ams_2.mp3`（美），所以 `uss`/`ams` = 美、`gbs`/`brs` = 英；匹配单位是完整 token，`business`/`usage` 不会被误判。设置项「发音口音 自动 / 美音 / 英音」（DataStore `dictionary_audio_accent`），chip 在词典管理页；`AUTO` 不猜口音，取候选第一条。

**真机证据**（Lenovo TB322FC / Android 16 / 1904×3040，debug 包）：

| 项 | 结果 |
| --- | --- |
| 导入 1.85GB 词典 | 1,864,845,709 字节（54MB mdx + 154MB + 518MB + 1.14GB mdd）完整 SAF 复制+校验成功；索引 `wordCount=246,787`、`sizeBytes=1,864,845,709` |
| 渲染 | 查 `nightmare`：ECDICT 卡片照旧；切到 OALD9 chip 后 `oalecd9.css` 生效（蓝色词头 `night·mare`、IPA、蓝底 NOUN 徽标、中文释义、斜体例句），喇叭 `<img>` 从 `.mdd` 取到 |
| 滚动 | 卡片体内可滚动，阅读页不跟着滚（无嵌套滚动冲突） |
| 发音 | 点词条喇叭 → `cache/dict-audio/481c698b060e03e7.mp3`，MediaPlayer/NuPlayer 播放，`requestAudioFocus` 由 `DictionaryAudioPlayer` 发起 |
| 管理页 | 口音 chip 与三个导入入口都在；导入后条目带「含资源」 |

**已知限制**：`.spx`（Speex）系统播不了，只能回落 TTS 并提示；`<iframe>/<object>/<embed>` 是直接移除而不是降级渲染；CSS 里 `url()` 不改写（根相对路径靠拦截器兜）；暗色预设只在 OALD9 上验过（深底浅字、无双反转），其余词典 CSS 未逐个看；逐词典覆盖口音、图片点击放大、WebView 内长按选词再查都没做。

实现细节与测试清单见 `docs/dictionary-resources-design.md`、`PROGRESS.md` §3.18。

### 3.11 按书排版（全局 / 本书）

上游只有一套全局排版。本 fork 在阅读器底部面板的排版 Tab 顶部加了「全局 / 本书」选择器：
每本书可以拥有独立的 12 项排版（字体家族、字重、粗体字重、斜体、字号、行距、段距、
字距、缩进、对齐、左右/上下边距）。第一次切到「本书」会快照当时的全局值，之后整组独立；
切回全局只停用、记录保留，再切回来恢复记忆值；与全局不同的项显示圆点标记；
「恢复全局默认」删除本书记录并回到全局。

- 存储：DataStore 单键 `reader_typography_by_book`，JSON 以书 `uuid` 为键，写入 200ms 合并。
- 解析：`ReaderTypographyResolver` 纯函数；字体 id 失效回落全局字体。
- 边界：页眉/图片/进度条/系统项仍只走全局。
- 详情：`docs/per-book-typography-design.md`、`PROGRESS.md` §3.19。

---

## 4. 设计系统（上游没有这一层）

上游 `BookStoryTheme` 直接写 `Typography()`，即 Material 3 默认值，等于没有排版决定；间距、圆角、动画时长全是散落字面量。

| 文件 | 内容 |
| --- | --- |
| `ui/theme/Motion.kt` | 8 档时长（0/100/150/200/250/300/350/400）+ 4 条缓动；**41 处** `tween` 调用全部迁移，默认缓动 = `Motion.Legacy` = Compose 的 `FastOutSlowInEasing`，逐值等价（对照表见 `docs/motion-migration.md`） |
| `ui/theme/Typography.kt` | 只收紧 5 个大字号的字距并加重字重；title/body/label 不动（它们是全 App 布局高度的依据） |
| `ui/theme/Dimens.kt` | 间距/圆角/固定尺寸；只收有真实出处的值，没人用的随时删 |
| `ui/theme/ReaderPalette.kt` | 阅读区配色（背景/栏底/栏内文字/强调色）从阅读预设派生，含 WCAG 对比度保证 |
| `ui/theme/SystemBars.kt` | 状态栏/导航栏图标明暗跟随阅读背景，而不是 App 主题 |

### ReaderPalette 解决什么

上游阅读页背景来自用户的颜色预设（羊皮纸/护眼绿/夜黑），但上下栏、栏内图标用的是 **App 主题**的 `surfaceContainer`/`onSurfaceVariant`。两者互不相干：夜间黑页上浮着一条亮栏，且栏内文字对比度没有任何保证。

现在 `bar / onBar / onBarMuted / accent` 全部从 `(background, onBackground, accentSeed)` 派生：栏底与背景必然同色系且可分辨（暗色端有保底抬升），强调色不足 3:1 时按二分混向字色以保留色相。系统栏图标明暗也随之切换（上游只看 App 主题，深色系统 + 浅色预设会出现浅底浅图标）。

---

## 5. 中文适配

| 项 | 说明 |
| --- | --- |
| 文案 | `values/` +22 条（新功能），`values-zh-rCN` +45 条（其中 23 条是上游尚未翻译的存量条目） |
| 错译修正 | `alignment_justify` 上游译作「文本对齐」（与分区标题同名）→ 「两端对齐」；`progress_count_quantity` 「数量」→「段落」 |
| 单位标签修正 | 三个滑块的 `"pt"` 是错的：行距是「字号之上的 sp 增量」、字距是 1/100 em、缩进是 ×6sp 倍率。已改为 `sp` / `×0.01em` / `×6sp` |
| 排版实测 | 新增仪器测试 `TypographyLabTest`，在真机/模拟器上量避头尾违规数、两端对齐实际撑宽量、字距是否生效、整章分页耗时。基线数据见 `docs/typography-lab/` |

### 实测结论（与最初假设相反的三条）

1. **避头尾不需要写代码**：UAX#14 禁则由 ICU 无条件保证，四种 `LineBreak` 模式在 61 个宽度下违规恒为 0。但 `LineBreak.Paragraph` 并非无关——它改变的是断行平衡（避免孤字寡行）。
2. **中文两端对齐只能补一半**：Android 只有 `JUSTIFICATION_MODE_INTER_WORD`，实测 23 行里只有 5 行被撑宽，平均 0.12 字宽；同样引擎跑拉丁能贴边。**结论：不自研，保留 Justify 默认值**（自研要 1.5 天换 0.12 字宽，不划算）。
3. **分页不需要优化**：3000 字整章 12ms，`ReaderPagePaginator` 列为不可触碰区。

---

## 6. 用户可感知的行为差异清单

| 变化 | 上游 | 本 fork |
| --- | --- | --- |
| 安装身份 | `ua.acclorite.book_story` | `dev.jia.reader`（可与原版共存） |
| 最低系统 | Android 8.0 (26) | **Android 10 (29)** |
| 阅读页配色 | 栏/图标跟 App 主题 | 跟阅读背景，含系统栏图标 |
| 翻页动画 | 只有平移 | 平移 / 淡入淡出（可选） |
| 正文点击 | 只有切换菜单 | 可配三种分区，支持点击翻页 |
| 跨章 | 无提示 | 顶部短暂显示章名 |
| 书签 | 无 | 有（按段落去重、精确到行跳转） |
| 书架进度 | 文字胶囊 | 环形进度 + 百分比 |
| 书架排序 | 不说明 | 「最近阅读」时顶栏显示说明 |
| 排版面板 | 字号在二级子页 | 7 项高频项在第一 Tab，含 4 个预设与实时样例预览 |
| 按书排版 | 只有全局 | 每本书独立 12 项，可切回全局、恢复默认、差异标记 |
| 翻译器行为 | 全局设置，默认开启 | 每本书单独开启（取词/双击，默认关闭）；词典与 AI 配置仍全局 |
| 单位标签 | 三个滑块都写 pt | 改为真实单位 |
| 大字号标题 | M3 默认字距 | 收紧字距 + 加重 |
| 动画时长 | 散落字面量 | 收敛为 8 档 token（观感不变） |
| 内置词典 | 无 | 随包 ECDICT（77 万词条，首次启动解压约 120MiB 到私有目录，APK +21MB） |
| minified 构建 | **无法打开任何书** | 已修复 |

---

## 7. 与上游同步（rebase）

有三处**不会编译失败**的分歧，rebase 后必须逐条检查，详见 `REBASE.md`：

1. `app/build.gradle.kts` 的 `minSdk = 29`（上游 26）——退回 26 会静默失去中文字形回退能力
2. `AndroidManifest.xml` 三个 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` 名称必须匹配 `dev.jia.reader` / `.debug` / `.release.debug`，否则 `tools:node="remove"` 静默失效
3. `ReaderTopBar` 使用 M3 的实验性 `subtitle` 槽位，material3 升版可能改名

会正常冲突（不静默）的文件：`ui/library/*`、`ui/reader/*`、`ui/theme/*`、`SettingsManager.kt`、`ReaderModel.kt`。

---

## 8. 明确未做

| 项 | 理由 |
| --- | --- |
| 内置中文字体 | 31 个内置字体全是拉丁字库。内置 3 款中文子集要 +15MB APK，而**用户导入**（已实现，§3.7）覆盖同样需求且体积为零 |
| ~~用户导入字体~~ | **已实现**（见 §3.7），这是中文字体问题的正解 |
| 双指调亮度 | 设置里已有亮度滑块；且双指手势要在 Pager 上再挂一个 drag 检测器，与"只能有一个检测器"的教训冲突 |
| 音量键翻页 | 需要拦截 `dispatchKeyEvent`，与系统音量、无障碍服务、外接键盘冲突 |
| 「最近阅读」置顶分区 | 默认排序本来就是它，再置顶会与分类 Tab 的排序语义重叠 |
| 长按多选批处理栏 | 上游的多选本身可用，改造为常驻批处理栏是纯打磨 |
| 全局 Snapshot 回归测试 | 需要仪器环境与固定测试书；目前只有 JVM 单测 + 手工真机验证 |
| zh-rTW 等 28 个语言 | 新文案回退英文（上游 zh-rTW 本身只有 12 条翻译） |
| 列表布局的进度样式 | 仍是上游的三色胶囊，与网格的环形进度视觉语言不一致 |
| 搜索 / TTS / 云端同步 | 超出范围 |
