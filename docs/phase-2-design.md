# 第二阶段设计：取词词典与 AI 翻译

> 状态：已确认，按阶段实施中
> 基线：`ui-redesign` 分支（fork of Book's Story）
> 目标分支：`phase-2`
> 相关文档：`DEV_PLAN.md`、`UPSTREAM_DIFF.md`、`PROGRESS.md`、`REBASE.md`

## 0. 已确认的产品决策

| 问题 | 决策 |
| --- | --- |
| 取词手势 | 单击与长按都支持，设置项 `SINGLE_TAP / LONG_PRESS / OFF`，默认 `SINGLE_TAP` |
| 内置词库 | 完整 ECDICT，官方 StarDict 数据（MIT） |
| 词库分发 | 仓库提交官方 `stardict.7z`（约 51MB），首次启动解压到应用私有目录 |
| 自定义词库 | 支持导入 `.mdx`（可选 `.mdd`），复用同一引擎 |
| AI 接口 | OpenAI 兼容：Base URL + Model + Key，兼容 OpenAI / DeepSeek / Gemini(OpenAI 兼容) / OpenRouter / Ollama |
| 取词框 | 浮动卡片贴词，自动避让屏幕边缘 |
| 翻译框 | 居中大对话框，标签页：翻译 / 语法分析 |
| 双击段落 | 翻译**整段文本**，替换原外部翻译；默认开启，保留开关 |
| 语法页 | 切到标签即分析，不再有“开始分析”按钮 |
| 结果保存 | 翻译、语法、AI 单词解释全部持久化；第二次点击直接显示缓存 |
| 终审 | `reviewer-expert` 整体 review（Gemini 3.8 Flash 本会话不可用） |

## 1. 目标与非目标

### 1.1 目标

1. 阅读页单击/长按单词，弹出贴词卡片，查内置 ECDICT 或导入的 MDX。
2. 双击段落，打开居中 AI 翻译对话框，流式翻译整段。
3. 语法标签点击即请求，结果分节展示。
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
| 内置词库 | ECDICT 官方 StarDict | `skywind3000/ECDICT` `stardict.7z` | MIT | 不生成 MDX，不转换数据格式 |
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
- 只新增 `EngineFactory` 适配层（public），用于访问上游 `internal` 的
  `MdxDictionary` / `MdxProbe`，并提供 `openMdx` / `openStarDict` / `probeMdx`。
- 解析、LZO、RIPEMD128、dictzip、链接改写、词形/模糊逻辑一律不修改。

### 4.2 内置 ECDICT

- 仓库提交 `app/src/main/assets/dictionaries/ecdict/stardict.7z`（官方数据，约 51MB）。
- `DictionaryRegistry.ensureBuiltIn()`：
  1. 首次启动/版本变化时，在 IO 线程用 `SevenZFile` 解压 `.ifo` / `.idx` / `.dict.dz`（可选 `.syn`）。
  2. 解压到 `filesDir/dictionaries/builtin/`，临时目录 + 原子 rename，写入 `version`（SHA-256/大小）。
  3. 失败保留旧版本；设置页显示进度与错误。
- 启动不阻塞；查询时若未就绪，卡片显示“词典准备中”，并保留 AI 解释入口。
- 内存：ECDICT 约 76 万词条，StarDict 索引常驻内存；项目已启用 `largeHeap`，实测后记录峰值。

### 4.3 导入与查询

- 导入 `.mdx`：先读头部用 `MdxProbe.readFromHead` 预检（版本、加密位、资源包、条目数），
  再流式复制并校验；`.mdd` 作为附属资源复制，本期不渲染。
- StarDict 导入：`OpenDocumentTree` 选目录，校验 `.ifo/.idx/.dict(.dz)` 后复制。
- 元数据 `index.json`：id、type、files、title、entries、size、enabled、importedAt。
- 引擎实例 LRU（内置 + 最近 2 个导入词典），删除/禁用时 `close()`。
- 查询：`lookupExact` → 无结果 `lookupSmart(limit=5)`；多词典按“精确 > 前缀 > 模糊”排序。
- 正文：`engine.article(hit).toHtml()` → Compose `AnnotatedString.fromHtml`；
  拦截 `juice://lookup/` 做二次查询。
- 无结果：卡片显示“未找到”，提供“用 AI 解释这个词”（结果可缓存）与“去导入词典”。

## 5. 阅读页取词

### 5.1 手势

- 设置项 `lookupGesture`：
  - `SINGLE_TAP`（默认）：命中单词即取词；空白区仍走原翻页/菜单。
  - `LONG_PRESS`：保留单击翻页；长按选中后自动取词。
  - `OFF`：关闭取词。
- 分页布局：沿用 Pager 上**唯一**的 `detectTapGestures`；`readerNodeAtY` 找到 item 后，
  用 item 内坐标 `TextLayoutResult.getOffsetForPosition` + `getWordBoundary` 取词。
- 连续布局：取词模式下用一个手势处理器做同样命中；不新增第二套翻页检测器。
- 长按：保留 `SelectionContainer` 选区与工具栏；自定义 `SelectionToolbar` 增加
  `onSelectionSettled(text, rect)`（`showMenu` 时记录 rect），防抖 350ms：
  单词/短文本 → 取词卡片；长选区 → 翻译对话框。

### 5.2 取词卡片

- `Popup` 贴词，自动判断上/下与左右边界；最大宽 360dp、最大高 45% 屏高，内容滚动。
- 使用 `LocalReaderPalette` 配色，夜间不闪白。
- 外部点击、返回、翻页、滚动关闭。
- 显示词头、音标、来源词典、释义；多词典结果用来源标签区分。
- 支持 `juice://lookup/` 链接点击继续查询。

## 6. AI 配置与调用

### 6.1 设置项

- `aiBaseUrl`：默认 `https://api.openai.com/v1/`；规范化补尾部 `/`。
- `aiModel`：自由文本 + 常用预设。
- `aiTargetLanguage`：跟随界面 / 简体中文 / 繁體中文 / English / 日本語 / 한국어 / 自定义。
- `aiGrammarEnabled`：默认开启（只影响是否显示语法标签）。
- API Key：不进入普通 DataStore 明文。

### 6.2 Key 存储

- `com.google.crypto.tink:tink-android` 的 `AndroidKeysetManager` 管理 AES256-GCM keyset。
- 密文 base64 存 DataStore；解密失败提示重填；日志绝不打印 Key 与正文。
- `allowBackup=false` 已关闭备份。

### 6.3 客户端

- `openai-kotlin`：`OpenAIConfig(token, host = OpenAIHost(baseUrl), timeout, headers,
  logging = LoggingConfig(logLevel = None), retry = RetryStrategy(maxRetries = 0/1))`。
- 翻译：`ChatCompletionRequest(stream = true)`，`chatCompletions()` 收集流。
- 语法：`responseFormat = ChatResponseFormat.JsonObject`，非流式或流式聚合均可。
- 错误映射：401 Key 无效、403、404 模型/路径不存在、429 限流、5xx、超时、断网。
- 长段落按句子边界切块（≤3000 字符）顺序输出；关闭对话框取消 Job。
- 明文 HTTP 只放行 `localhost` / `127.0.0.1` / `10.0.2.2`（`res/xml/network_security_config.xml`），
  其余流量强制 HTTPS。IPv6 回环 `::1` 与局域网 HTTP 本期**不**放行，本机/局域网明文策略留给 S6 决定。

### 6.4 提示词

- 翻译系统提示：只输出译文、保留段落、目标语言 X。
- 语法系统提示：只输出 JSON：
  `summary`、`sentences[{original,translation,structure,points}]`、`vocabulary[{word,pos,meaning}]`。
- 解析容错：去掉 Markdown 围栏；失败则原文展示。
- `PROMPT_VERSION` 常量随提示词变更递增。

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
- “重新生成”：删除该键 → 重新请求 → 覆盖缓存。
- 容量：默认全部保留；超过 20,000 条或 200MB 时按 `lastAccessedAt` LRU 清理；
  设置页显示条数/占用并提供“清除翻译缓存”。

## 8. 双击与语法页行为

- 双击段落：取 `NodeContent.Paragraph.text` 的整段文本（不是分页切片），
  打开翻译对话框；翻译标签自动开始（缓存命中则直接显示）。
- `doubleClickTranslation` 保留，默认 `true`；开启时单击翻页/菜单会等待约 300ms
  的双击判定，设置页说明取舍，可关闭恢复即时单击。
- 语法标签：点击即请求；不再有“开始分析”按钮；错误态提供“重试”，成功后提供“重新分析”。
- 关闭对话框取消请求；已完成结果保留；进程被杀后再次双击从 Room 读取。
- 外部翻译 Intent 仅保留为对话框错误态的“用其他应用”回退。

## 9. 设置与文案

- `TranslatorSubcategory`：双击翻译开关、取词手势、进入“翻译与词典设置”。
- `TranslationSettingsScreen`：
  - 词典：内置 ECDICT 版本/状态、导入/启用/删除、导入进度与错误。
  - AI：Base URL、Model、Key（密码框/显示切换）、目标语言、测试连接。
  - 缓存：条数/占用、清除。
  - 隐私与费用说明：正文会发送给配置的第三方；切换语法标签即请求。
- 新文案进 `values/strings.xml` 与 `values-zh-rCN/strings.xml`，其他语言回退英文。

## 10. 分阶段实施与验收

每个阶段由 `worker` 实现，完成后 `reviewer` 第 1 轮、修复后 `reviewer-expert` 第 2 轮；
两轮通过后提交并进入下一阶段。

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| S1 | 设计文档、`phase-2` 分支、依赖与 Spike（openai-kotlin/Ktor、Tink、7z） | Debug/Release-debug 可编译；MockEngine 流式测试；Tink 存取测试；7z 解压测试；R8 通过 |
| S2 | `:dict-engine` 模块 + `EngineFactory` + 上游测试 | 引擎单测通过；MDX/StarDict fixture 查询/渲染通过 |
| S3 | ECDICT `stardict.7z` 资产 + 首次解压 + 内置词典注册 | 首次启动解压成功；`apple`/`perceive` 可查；重启不重复解压 |
| S4 | 词典仓库、MDX/StarDict 导入、设置页词典管理 | 导入/启用/删除；损坏/加密/空间不足错误明确 |
| S5 | 取词手势、文本布局命中、取词卡片 | 两种布局单击/长按取词；空白区翻页/菜单不回归；RTL/夜间正常 |
| S6 | AI 设置、Tink、openai 客户端、MockEngine 测试 | Key 加密存取；流式/错误/取消；测试连接 |
| S7 | Room 缓存表/DAO/迁移/仓库 | 迁移 20→21；读写/touch/LRU/clear 通过 |
| S8 | `TranslationModel`、双击整段、翻译/语法对话框、缓存优先、语法点击即分析 | 二次双击 0 请求；语法二次点击 0 请求；杀进程后仍命中；取消不写半截 |
| S9 | 文案/许可/文档、全量回归、release-debug、终审 | 全量测试通过；体积/内存记录；`reviewer-expert` 整体 review |

## 11. 测试矩阵

### JVM / 单元

- 引擎：上游 `Lzo1xTest`、`MdxHeaderTest`、`MdxKeyOrderTest`、`MdxLinkRewriteTest`、
  `MdxBytesTest`、`Ripemd128Test`、`StarDict*Test`。
- 缓存键：模型/语言/提示词/空白变化；SHA-256 稳定。
- `TranslationModel`：缓存命中、miss、并发去重、取消、重新生成（fake repository）。
- AI：Ktor `MockEngine` 的流式拼接、`[DONE]`、错误码、JSON 围栏、取消。
- 取词候选：word boundary 纯函数、CJK 限长、标点剥离。

### Android / 仪器

- Room DAO、迁移 20→21、缓存跨进程重建。
- 内置 ECDICT 解压、查询、内存峰值。
- Tink Key 存取。
- 取词卡片/翻译对话框 Compose 行为（可用现有测试 runner，不引入 Compose UI 测试库）。

### 手工 / 真机

- 分页/连续、单击/长按/双击、空白翻页/菜单、长按选择复制。
- 夜间预设、RTL、超大字号、横竖屏、进程重建。
- 无网、401/429、流式中途关闭、超长段落。
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
