# 词典资源（MDD / CSS / 发音 / 图片）设计与实施计划

状态：**已实施（2026-10）**。§3.1–§3.10 全部落地并通过四道门禁；§3.11 记录已知限制与后置项，§3.12 的提交拆分按三段执行。
每一步的落地文件、测试与偏差写在该步末尾的「落地情况」，真机与真实语料数据见 §3 末尾的「实测数据」。
适用代码：`app/`（`data/dictionary`、`ui/reader`、`ui/settings`）+ `third_party/juicedict-engine/`
相关文档：`docs/phase-2-design.md` §4.3（已落地）、§1.2（非目标改写）、`UPSTREAM_DIFF.md` §3.10、`PROGRESS.md` §3.18、`third_party/juicedict-engine/PROVENANCE.md` 第 4 条

## 0. 一句话

把 `.mdd` 资源包导入到词典目录，并让词条里的 CSS、图片、表格、字体和 `sound://` 真人发音真正生效：
**纯文本词条继续走现有 Compose 卡片，只有引用资源的词条切到 WebView**（jsoup 改写 + 自定义协议 + 资源仓库）。

已确认的产品决策（提问环节的选择）：

| 维度 | 决定 |
| --- | --- |
| 渲染 | 混合：有资源才用 WebView，纯文本保持现有结构化卡片 |
| MDD 导入 | 新增「导入 MDX 文件夹」（`OpenDocumentTree`），单文件入口保留 |
| 发音 | 词条自带音频优先，缺失/格式不支持才回落系统 TTS；口音可设置 |
| 首期范围 | 图片 + CSS + 音频 + 字体与表格 |

## 1. 现状与证据（读代码得到的事实）

> 下表是**实施前**（2026-10 初）读代码得到的事实，保留作决策依据；实施后的状态见 §3 各步「落地情况」。

| 项 | 结论 |
| --- | --- |
| 引擎 | `MdxResourcePack` 已实现：MDD 按键索引读取、路径容错（`/`↔`\`、缺前导反斜杠、忽略大小写）、多个 pack 按顺序回退 |
| 引擎出口 | 只在 `MdxDictionary.resource()` 上，**没有进 `DictionaryEngine` 接口**，app 层拿不到 |
| 链接 | `MdxDictionary.rewriteLinks` 已把 `entry://` 改成 `juice://lookup/…`（卡片已支持该 scheme）；`sound://`、`file:///` 原样保留 |
| 导入 | `DictionaryImporter` 只接受单个 `.mdx`（`OpenDocument`），拿不到同目录 `.mdd`；`DictionaryRepository.openDictionaryEngine` 里 `mddFilesFor(...)` 的调用点已就位，只是永远为空 |
| 元数据 | `index.json` 只存元数据；`sizeOf(directory)` 已按整目录求和，`hasDictionaryFiles(MDX)` 只看 `.mdx` —— **加 `.mdd` 不需要改索引格式** |
| 渲染 | 卡片正文 = `parseLookupBody` → `Sections`（结构化）或 `Rich`（`AnnotatedString.fromHtml`）；`img` 属块级标签，走 `Rich` 后**不显示**；CSS/字体完全没接 |
| 现存缺陷 | 块级回退 `LookupBody.Rich(phonetic = null, …)` 会丢掉音标 —— 富媒体词条正好走这条路，需一并修 |
| 布局约束 | 正文是 `Column(verticalScroll)`；WebView 必须换成不套滚动 + `weight(1f)`，否则嵌套滚动 |
| 依赖 | `jsoup 1.21.2`、`coil-compose 2.7.0` 已在；WebView 需新增 `androidx.webkit`（算法暗化） |
| 外部限制 | `docs/phase-2-design.md` §1.2 把「`.mdd` 渲染」「在 WebView 中执行词典 HTML/JS」列为非目标 —— 本设计变更：允许 **渲染**（JS 关闭、外网封锁），仍不执行脚本 |

## 2. 关键决策

1. **混合渲染**：`hasResources && html 命中 (img|table|link[rel=stylesheet]|sound://)` 才走 WebView；否则维持现有卡片。
   理由：ECDICT/StarDict 的观感与性能不变；富媒体词条要的是保真（CSS / `@font-face` / 表格 / 相对路径图片）。
2. **资源句柄与查询引擎分离**：`MdxResources`（只读 pack）独立于 `DictionaryEngine`，各有各的 LRU 与锁。
   理由：WebView 的 `shouldInterceptRequest` 在后台线程阻塞取资源，不能和取词路径共用一把锁。
3. **不改上游解析文件**：所有新出口都加在 `DictionaryEngines.kt`（PROVENANCE 允许扩展的唯一本地文件）。
4. **导入走目录**：新增「导入 MDX 文件夹」，自动配对 `<base>.mdd` / `<base>.1.mdd`…；单文件入口保留给纯文本 MDX。
5. **索引格式不变**：`hasResources` 运行时由 `mddFilesFor` 推导，不写进 `index.json`，避免迁移。
6. **发音优先级**：词条自带音频 > 系统 TTS；口音由设置项（自动/美音/英音）+ 路径 token 分类器决定，未识别则回落。
7. **安全基线**：JS 关闭、DOM storage 关闭、`file://` 访问关闭、网络全封、只服务 `https://dict.local/<dictId>/…`、剥离 `<script>` / `on*` / `javascript:`。

## 3. 实施步骤（12 步，与任务清单一一对应）

### 1) 引擎适配层：把 MDD 资源读出来
- `third_party/juicedict-engine/src/main/kotlin/com/qiuminal/juicedict/engine/DictionaryEngines.kt` 新增：
  - `interface MdxResources : AutoCloseable { val names: List<String>; fun read(path: String): ByteArray? }`
  - `fun openMdxResources(mddFiles: List<File>): MdxResources?` —— 按传入顺序尝试各 pack；单个损坏只 `logSink` 上报并跳过（《字源》的 43MB 扫描件分在两个包里，丢一个不能让另一个不可读）。
- `PROVENANCE.md`「本地改动」加第 4 条：只加 public 包装，未动解析器。
- 验收：`:dict-engine:test` 绿；`PROVENANCE.md` 与代码一致。

**落地情况**：`DictionaryEngines.kt` 里 `MdxResources` / `openMdxResources` 已就位，`PROVENANCE.md` 第 4 条同步。
偏差：多加了 `MdxPackCheck` + `checkMdxResourcePack(file)` —— 导入必须区分「加密」与「损坏」，
而 `openMdxResources` 按设计吞掉单包失败；它不进 `DictionaryEngine` 接口，只服务导入前的校验。

### 2) 引擎测试：合成一个 MDD fixture
- 新增 `third_party/juicedict-engine/src/test/kotlin/.../MdxResourcesFixtureTest.kt`，复用 `MdxFixtureTest` 的容器拼装套路：
  根标签 `Library_Data`（即 `MdxKind.RESOURCE`）、key 为 UTF-16LE、`StripKey="No"`、record 无尾 NUL。
- 用例：`\a.css` 命中；无前导 `\` / `/` 分隔 / 大小写差异也能命中；未知名 → `null`；多 pack 顺序（同键取前一个）；空 pack / 空路径 → `null`。
- 验收：不依赖本机语料（`E:\dictionary\MDict` 那批回归在 CI 上是空跑），CI 可跑。

**落地情况**：`MdxResourcesFixtureTest` 14 例覆盖上述四类命中、未知名、多包回退、坏包跳过（断言 `logSink` 上报），
外加 `MdxPackCheck` 的 `Ok` / `NotResourcePack` / `Corrupt` 三分类；`:dict-engine:test` 116 例全绿。

### 3) 数据层：`DictionaryResourceStore`
- 新增 `app/src/main/java/ua/acclorite/book_story/data/dictionary/DictionaryResourceStore.kt`：
  - `fun read(dictId: String, path: String): ByteArray?` —— **阻塞**（`shouldInterceptRequest` 在 WebView 后台线程），按词典串行（`MdxResourcePack` 的 seek+read 非原子）。
  - `suspend fun audioFile(context: Context, dictId: String, path: String): File?` —— 写 `cacheDir/dict-audio/`（`MediaPlayer` 走文件最稳），>32MB 按 mtime 清理。
  - 内存缓存：单条 ≤256KB、总量 ≤8MB（CSS/图标/小图）；大图与音频不进内存缓存。
  - 句柄 LRU ≤2，订阅 `DictionaryCatalog.dictionaries` 时 `evictUnavailable()`（禁用/删除/重装即关闭）。
  - 路径规范化：HTML 实体已由 jsoup 解好，再 `URLDecoder`、去 `file:///` 前缀、截掉 `#`/`?`。
- `InstalledDictionary` 增加 `hasResources`（`DictionaryCatalog.publish()` / `builtInEntry()` 里用 `mddFilesFor` 推导）。
- 验收：`DictionaryResourceStoreTest`（fake provider）覆盖缓存命中/淘汰/禁用关闭/路径规范化。

**落地情况**：`DictionaryResourceStore`（进程内单例 `shared`）+ `InstalledDictionary.hasResources` 已就位，`DictionaryResourceStoreTest` 25 例。
上限定为同文件常量：单条内存缓存 256KB、内存总量 8MB、句柄 LRU 2、发音落盘 32MB（按 mtime 从旧到新清，另清过期 `.part`）；
打开失败按 `dictId` 负缓存，禁用/删除/重装即 `evictUnavailable()` 关句柄。

### 4) 导入：新增「导入 MDX 文件夹」
- `DictionaryImporter` 新增 `suspend fun importMdxDirectory(context, treeUri): DictionaryImportError?`：
  - 目录内恰好 1 个 `.mdx`（0 → `INCOMPLETE`，多个 → `MULTIPLE_DICTIONARIES`）。
  - 配对 `<base>.mdd`、`<base>.N.mdd`，**编号必须连续**（缺号 → 新错误 `RESOURCE_PARTS_MISSING`，缺一段会静默丢图/丢音）；`.images.mdd` 这类非数字后缀忽略（与引擎 `pairsFor` 一致）。
  - 体积：mdx 仍走 1 MiB 头预检；**总上限 2 GiB**（新增 `MAX_IMPORT_TOTAL_BYTES`，`MAX_IMPORT_BYTES` 保留给单文件），空间按总量 ×1.5 校验；进度按已复制字节 / 总量。
  - 复制顺序 mdx → 各 mdd；每个 mdd 复制后 `openMdxResources` 独立校验（加密 → `ENCRYPTED`，损坏 → 新错误 `RESOURCE_CORRUPT`）。
  - 之后复用现有 `install()`（rename + 写索引）；`sizeBytes` 已按整目录求和。
- `DictionaryImportError` 新增 `RESOURCE_PARTS_MISSING`、`RESOURCE_CORRUPT`；`classifyMdxFailure` 补映射。
- UI：`DictionaryImportActions` 增加第三行「导入 MDX 文件夹（含图片/发音资源）」；`onImport` 的 `Boolean` 改成 `ImportKind { MDX_FILE, MDX_FOLDER, STARDICT_FOLDER }`；`ImportedDictionaryItem` 加「含资源」标记。
- 文案：`values` + `values-zh-rCN`（其余语言回落英文）；`dictionary_import_note` 里「MDD 本期不导入」那句要改。
- 验收：导入测试覆盖 只选 mdx / mdx+mdd / 编号缺号 / 加密 mdd / 超限；`DictionaryImportErrorTest` 补两条映射。

**落地情况**：`DictionaryImporter.importMdxDirectory`（`OpenDocumentTree`）+ 纯函数 `planMdxFolder`（16 例 `DictionaryImportPlanTest`）；
`MAX_IMPORT_TOTAL_BYTES = 2 GiB`，`MAX_IMPORT_BYTES`（512MB）只管单个 `.mdx`；错误码 `RESOURCE_PARTS_MISSING` / `RESOURCE_CORRUPT`、
`ImportKind { MDX_FILE, MDX_FOLDER, STARDICT_FOLDER }`、第三行入口、「含资源」标记均按计划接上。
偏差：「加密 mdd」没有做端到端文件的用例（合成加密 MDD 代价高），由 `MdxPackCheck.Encrypted` 分类 + `classifyMdxFailure` 映射的单测覆盖；
真实语料侧由 1.85GB 四件套导入实测兜底。校验用的是 `checkMdxResourcePack` 而不是 `openMdxResources`：后者按设计吞单包失败，分不出「加密」与「损坏」。

### 5) HTML 改写：纯函数 `LookupHtmlRewriter`
- 新增 `app/src/main/java/ua/acclorite/book_story/ui/reader/LookupHtmlRewriter.kt`：
  - `rewriteArticleHtml(html, dictId): RewrittenArticle(html, audio: List<AudioCandidate>)`
  - 删 `<script>`、`on*` 属性、`javascript:` 链接；保留 `<style>`（去掉 `@import`）。
  - `sound://X` → `dict-audio://<dictId>/<encoded X>`；`file:///…` → 相对路径（交给 base URL）。
  - `img` / `link` / `source` / `srcset` / 内联 `style` 里的 `url()` **不改写**，依赖 `loadDataWithBaseURL("https://dict.local/<dictId>/")` 解析（含反斜杠规范化）。
  - 保留引擎已改写的 `juice://lookup/…` 与同词条 `#frag`。
- 验收：`LookupHtmlRewriterTest` 用真实形态片段（OALD9 的 `sound://`、字源的 `<img src="\pic\...">`、带 `<link>`/`@font-face` 的 CSS 引用）钉住改写结果与脚本剥离。

**落地情况**：`LookupHtmlRewriter`（`rewriteArticleHtml` → `RewrittenArticle(html, audio)`）14 例。
比计划多删三类可导航元素：`<base>`（会改掉相对解析基准）、`<meta http-equiv="refresh">`（JS 关掉也会导航）、`<iframe>/<object>/<embed>`；
`javascript:` / `vbscript:` 按**属性名**清洗 —— OALD9 里有 158 个命名空间标签（`xhtml:a`），按元素名白名单会整片漏掉。
仍按计划不做：CSS 里 `url()` 不改写（根相对路径靠拦截器兜）。

### 6) 渲染：`LookupBody.Media` + WebView 正文
- `ui/reader/ReaderLookupArticle.kt`：
  - 新增 `LookupBody.Media(phonetic, meta, html, audio)`；`parseLookupBody(html, word, hasResources = false)` 决定分流。
  - 顺手修现存缺陷：块级回退的 `Rich(phonetic = null, …)` 丢音标（两处都要先算 `phonetic`）。
- 新增 `ui/reader/DictionaryArticleWebView.kt`：安全设置见 §2.7；`shouldInterceptRequest` 只认 `https://dict.local/<dictId>/…` → `DictionaryResourceStore.read` → `WebResourceResponse`（带 `Content-Length`）；`shouldOverrideUrlLoading` 处理 `juice://` 与 `dict-audio://`；注入主题 `<style>`（透明底、`img{max-width:100%}`、`textZoom` 对齐卡片正文 14sp），暗色开算法暗化。
- 卡片：`LookupCard` 增加 `fixedHeightBody`；WebView 分支 `weight(1f)` 且**不**套 `verticalScroll`（避免嵌套滚动），头尾仍固定。
- 验收：JVM 钉住分流函数（`shouldUseMediaRenderer`）；真机验证嵌套滚动、暗色、字号缩放。

**落地情况**：`LookupBody.Media` + `shouldUseMediaRenderer` + `DictionaryArticleWebView`（8 例 `DictionaryArticleWebViewTest`，钉 base URL、路径语义、MIME、注入样式、`textZoom`、`dict-audio://` 解码、锚点放行；分流函数本身由 `ReaderLookupArticleTest` 钉）。
被真机与实现修正的三点：

| 计划 | 现实 |
| --- | --- |
| 拦截器对未知路径统一 404 | `loadDataWithBaseURL` 会把 base URL 本身送进 `shouldInterceptRequest`。根路径回 404 → 整篇变 `net::ERR_HTTP_RESPONSE_CODE_FAILED`。**改为：根路径 / 其它 host 返回 `null`，只有「路径存在但包里没有」才 404**（子资源缺失不影响主文档） |
| `textZoom` 对齐卡片正文 | 不跟阅读正文字号（`settings.fontSize`）走：卡片排版本来是固定的（义项用 `bodyMedium` = 14sp），跟过去会让「大字」预设下 WebView 正文比 ECDICT 卡片大一圈（真机反馈 22pt 阅读字号 → 137%）。最终 `textZoom = 14 × fontScale × 100 / 16`（1.0 → 88%、1.3 → 114%）。Compose 的 sp 自动吃 `fontScale`，WebView 不吃，漏乘会在系统放大字号时偏小 |
| `onDispose` 里直接 `destroy()` | 文档还在加载时直接销毁会在内部线程抛 NPE。顺序：`stopLoading()` → 换回默认 `WebViewClient`（否则 `about:blank` 被当外部导航拦下）→ `loadUrl("about:blank")` → `destroy()` |

暗色走 `WebSettingsCompat.setAlgorithmicDarkeningAllowed`（AndroidX WebKit 1.14.0），浅色预设显式关掉，否则正常配色会被压暗。

### 7) 音频播放 + 状态接线（合并原计划第 9 步）
- 新增 `ui/reader/DictionaryAudioPlayer.kt`：`MediaPlayer` + `AudioManager` 焦点，`play(file)/stop()/release()`；状态机抽到可测接口 `AudioOutput`（测试用 fake）。
- `WordSpeaker` 增 `stop()`；文件音频与 TTS 互斥（播文件前停 TTS，点 TTS 前停文件）。
- `DictionaryLookupModel`：`LookupState.Success` 增 `hasResources`（`select()` 时重算）；提供 `suspend fun audioFile(...)`；关闭卡片/切词条停音频。
- 头部喇叭：有音频播音频，`onError`（资源缺失 / `.spx` 等格式）回落 TTS + toast。
- 验收：`DictionaryAudioStateTest`（连续点击不叠加 / 播放中关闭释放 / 失败回落）；`DictionaryLookupModelTest` 补切换与停止。

**落地情况**：`DictionaryAudioPlayer`（`MediaPlayer` + `AUDIOFOCUS_GAIN_TRANSIENT`，失焦即停）、`DictionaryAudioController`（`AudioOutput` / `SpeechOutput` 状态机，6 例 `DictionaryAudioStateTest`）、
`WordSpeaker.stop()`、`LookupState.Success.hasResources`、`audioFile` 接线均按计划完成。
释放顺序落在卡片层：`DisposableEffect` 关卡片时 `audio.release()` + `speaker.shutdown()`，`LaunchedEffect(currentWord, selectedIndex)` 切词/换命中词典时 `audio.stop()`。
全失败才回落 TTS 并 toast —— 静默换成机器音会让人以为点错了按钮。

### 8) 口音：分类器 + 设置项
- 新增 `ui/reader/DictionaryAudioAccent.kt`：`enum AudioAccent { US, UK, UNKNOWN }`、`classifyAccent(path)`、`pick(candidates, preference)`。
  - 词表：美 `us|usa|_us|__us|us_|\us\|en-us|ame`；英 `gb|uk|_gb|__gb|_uk|\gb\|en-gb|bre`。
  - 回落顺序：偏好命中 → 另一口音 → UNKNOWN → 空。
- `SettingsManager.dictionaryAudioAccent`（`AUTO`/`US`/`UK`，默认 `AUTO`，反序列化用安全查找而不是 `valueOf`）；chip 放词典管理页（比塞进 `SystemSubcategory` 更贴题，也不会污染阅读设置底栏）。
- debug 日志：每个词典首次出现的音频路径按 token 聚合计数（tag `DictionaryAudio`），用真实 OALD9/字源 实测后补词表 —— 命名约定没有规范，只能按数据校准。
- 验收：`DictionaryAudioAccentTest` 覆盖各家族命名与优先级回落；真机对照日志。

**落地情况**：`DictionaryAudioAccent`（`AUTO`/`US`/`UK`，反序列化用安全查找）、`AudioAccent` 分类器 16 例、`SettingsManager.dictionaryAudioAccent`（DataStore `dictionary_audio_accent`）、词典管理页 chip。
用真实 OALD9 校准过词表：词条音 `apple__gb_1.mp3` / `apple__us_1.mp3`，例句音 `_apple__brs_2.mp3`（英）、`_apple__uss_2.mp3`（美）、`_apple__gbs_2.mp3`（英）、`_apple__ams_2.mp3`（美）——
即 **`uss`/`ams` = 美、`gbs`/`brs` = 英**。匹配单位是完整 token，`business`/`usage`/`fuss` 不会被误判。`AUTO` 不猜口音，直接取候选第一条。

### 9) 设计文档与欠账收口
- `docs/phase-2-design.md`：§1.2 两条非目标改写（WebView 只渲染不执行）；§4.3 的 `.mdd` 欠账段落改为落地说明 + 新导入入口；§2 组件表补 `androidx.webkit`。
- `UPSTREAM_DIFF.md` 新增 §3.x（本 fork 新增能力：MDD 资源、CSS、发音、混合渲染）。
- `PROGRESS.md` 新增一节（现象 / 方案 / 实测数据 / 已知限制）。
- `third_party/juicedict-engine/PROVENANCE.md` 第 4 条本地改动。

**落地情况**：本步的产物即本文档本身。`docs/phase-2-design.md` §1.2（两条非目标）/ §2（`androidx.webkit`）/ §4.3（欠账段落改落地说明）/ §15（已知限制）已同步；
`UPSTREAM_DIFF.md` 新增 §3.10；`PROGRESS.md` 新增 §3.18 与 §4 真机表格。`PROVENANCE.md` 保持不变 —— 第 4 条已完整描述 `MdxResources` / `checkMdxResourcePack`。

### 10) 测试与门禁
- 四道门禁：`:dict-engine:test` → `:app:testDebugUnitTest` → `:app:assembleDebug` → `:app:assembleRelease-debug`（R8 + lintVital）。
- 新增：`MdxResourcesFixtureTest`、`LookupHtmlRewriterTest`、`DictionaryAudioAccentTest`、`DictionaryResourceStoreTest`、`DictionaryAudioStateTest`。
- 补充：`DictionaryImporter*Test`（5 例）、`DictionaryImportErrorTest`（2 例）、`DictionaryLookupModelTest`（2 例）、`ReaderLookupArticleTest`（媒体分流 2 例）。
- 真机矩阵：牛津高阶 9（CSS / 字体 / 音频 / 表格）、字源（43MB 分卷扫描件）、无资源 MDX（观感回归）、ECDICT（完全走旧渲染器）、禁用/删除后资源句柄关闭、`sw600dp` 不受影响。

**落地情况**：四道门禁全绿（`:dict-engine:test`、`:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleRelease-debug`）。
本轮新增 7 个测试文件、99 例：`MdxResourcesFixtureTest` 14、`DictionaryImportPlanTest` 16、`DictionaryResourceStoreTest` 25、`DictionaryArticleWebViewTest` 8、`DictionaryAudioAccentTest` 16、`DictionaryAudioStateTest` 6、`LookupHtmlRewriterTest` 14；
另补 `DictionaryImportErrorTest`、`DictionaryLookupModelTest`、`ReaderLookupArticleTest`。
真机矩阵只走过 OALD9 与 ECDICT 两条（见下方「实测数据」）；字源分卷扫描件、无资源 MDX 的观感回归、句柄关闭时机仍只在单测层面验证。

### 11) 风险与后置项（本轮不做）
- WebView 实例化 50–100ms，媒体词条卡片变全高；词典 CSS 可能自带白底 / 极小字号 / 隐藏文本 —— 算法暗化 + 强制透明底 + 字号下限兜底，个别词典接受观感差异。
- `.spx`（Speex）发音系统播不了，只能提示；`.ogg`/`.wav`/`.mp3` 正常。
- CSS 里的 `url(\x.png)`：反斜杠在 CSS 中是转义符，可能取不到图；记日志不阻断。
- MDD 上百 MB 时导入耗时分钟级，文案要写明体积与耗时。
- 后置：WebView 内长按选词再查（需只读 JS 桥）、图片点击放大、每部词典单独覆盖口音、`@font-face` 是否跟随阅读字体、StarDict 资源页、加密 `@font-face` 字体。

**落地后的实际状态**：上面「后置」六项全部未做；`.spx` 仍播不了（`MediaPlayer` 抛异常 → 回落 TTS + toast）；`<iframe>/<object>/<embed>` 是直接移除而不是降级渲染；
CSS 里 `url()` 不改写；暗色预设下的观感已在 OALD9 上实测（深底浅字、无算法暗化双反转），其余词典的 CSS 未逐个看。

### 12) 提交拆分
1. `feat(dict-engine): expose MDD resource reads` —— `DictionaryEngines.kt` + `MdxResourcesFixtureTest` + `PROVENANCE.md`。
2. `feat(dict): import MDX folders with resource packs` —— 导入入口、错误码、目录配对、UI、文案、导入测试；另含 `InstalledDictionary.hasResources`（导入 UI 的「含资源」标记要用，放本段才不至于让本段单独 checkout 时编译不过）与口音**设置**（`DictionaryAudioAccent` + `SettingsManager` + 词典管理页 chip，属设置页 UI）。
3. `feat(reader): render MDX resources (CSS, images, audio)` —— 资源仓库、改写器、WebView、音频播放与口音选路、卡片分流、`androidx.webkit` 依赖、文档。

**落地情况**：三段按此顺序执行完毕（口音设置在第 2 段、口音选路与播放器在第 3 段），文档收口落在第三段；每段都单独编译通过。

---

### 实测数据

真机 Lenovo TB322FC（Android 16 / 1904×3040），debug 包；语料是真实牛津高阶英汉双解词典（第 9 版），「导入 MDX 文件夹」整目录选取。

| 项 | 数据 |
| --- | --- |
| 导入体积 | **1,864,845,709 字节**：`.mdx` 54MB + `.mdd` 154MB + `.1.mdd` 518MB + `.2.mdd` 1.14GB，完整 SAF 流程复制 + 校验一次成功 |
| 索引结果 | `wordCount = 246,787`、`sizeBytes = 1,864,845,709`（`.mdd` 计入体积，`index.json` 格式未变） |
| 管理页 | 「发音口音 自动 / 美音 / 英音」chip + 三个导入入口（MDX 文件 / MDX 文件夹 / StarDict 文件夹）；导入后条目带「含资源」 |
| 渲染 | 查 `nightmare`：ECDICT 卡片照旧；切到 OALD9 chip 后 WebView 渲染成功 —— `oalecd9.css` 生效（蓝色词头 `night·mare`、IPA、蓝底 NOUN 徽标、中文释义、斜体例句），`<img>` 喇叭图标从 `.mdd` 取到 |
| 滚动 | 卡片体内可滚动，阅读页不跟着滚（无嵌套滚动冲突） |
| 字号 | 首版把 `textZoom` 绑在阅读正文字号上，设备是「大字」预设（22pt）→ 137%，词条正文明显大于 ECDICT 释义；改成「词典 16px 基准 → 卡片正文 14sp」的固定映射（1.0 → 88%）后两者视觉一致，同一条 `nightmare` 能完整显示两条例项 |
| 暗色 / 系统字号 | 暗色预设下深底浅字、无双反转；系统字号 1.3 时正文与卡片标题同步放大 |
| 发音 | 点词条喇叭 → `cache/dict-audio/481c698b060e03e7.mp3`（文件名含 dictId 参与的 hash），`MediaPlayer`/NuPlayer 播放，`requestAudioFocus` 由 `DictionaryAudioPlayer` 发起 |
| 真机 bug（已修） | `loadDataWithBaseURL` 把 base URL 本身送进 `shouldInterceptRequest`，空路径回 404 → 整篇 `net::ERR_HTTP_RESPONSE_CODE_FAILED`；改成根路径 / 非 `dict.local` 返回 `null`，只有存在但缺失的资源才 404 |

1.85GB 是这类词典的真实体量，也是 `MAX_IMPORT_TOTAL_BYTES` 定在 2 GiB 的依据（`MAX_IMPORT_BYTES` 的 512MB 只够单个 `.mdx`）。
导入耗时分钟级，进度按已复制字节 / 总量走。

## 4. 附：数据与格式要点

- MDD 是第二个 MDict 容器，根标签 `Library_Data`；key 恒为 UTF-16LE、`StripKey="No"`（`\0.png` 排在 `\00.png` 之前）；record 无尾 NUL，体积靠下一条的 offset 推导。
- 路径容错由 `MdxResourcePack.candidatesFor` 负责（原样 / 加 `\` / 去 `\`），大小写与 `/`↔`\` 在 `find()` 里处理。
- 口音判定只能靠词典作者的命名约定（`_us` / `__gb` / `\us\` 等），没有规范；判定失败不影响播放，只影响「播哪一个」。
- MDX 正文里的 `entry://` 已被引擎改写成 `juice://lookup/<urlencoded>`，WebView 侧只要在 `shouldOverrideUrlLoading` 里接住即可跨词条跳转。

## 5. 与任务清单的对应

| 清单 | 本文档 |
| --- | --- |
| 1 引擎适配层 | §3.1 |
| 2 引擎测试（MDD fixture） | §3.2 |
| 3 `DictionaryResourceStore` | §3.3 |
| 4 导入 MDX 文件夹 | §3.4 |
| 5 `LookupHtmlRewriter` | §3.5 |
| 6 `LookupBody.Media` + WebView | §3.6 |
| 7 `DictionaryAudioPlayer` + TTS 互斥 | §3.7 |
| 8 口音分类器 + 设置项 | §3.8 |
| 9 设计文档与欠账收口 | §3.9 |
| 10 测试与门禁 | §3.10 |
| 11 风险与后置项 | §3.11 |
| 12 提交拆分 | §3.12 |

§3 末尾另加「实测数据」小节（真机证据 + 真实语料体量），不在任务清单内。

> 说明：任务清单把原计划「`DictionaryLookupModel` 接线」一步省略了，本文档把它并入 §3.7 ——
> 头部喇叭要拿到当前词条的 `dictId` / `hasResources` / 音频候选，切词条与关卡片要停音频，这一步无法省。
