# 进度记录

> 快照时间：2026-10-29
> 基线：`upstream/master` = `464891e`（Acclorite/book-story，GPL-3.0-only）
> 分支：`ui-redesign`

---

## 1. 一句话状态

计划的 9 个阶段全部完成，25 个提交、129 个单元测试全绿、三重构建门禁通过；
真机（Lenovo TB322FC / Android 16）上已验证：minified 构建能读书、GBK 编码能正确识别、
Room 19→20 迁移无损、分页翻页正常。

---

## 2. 代码状态

| 项 | 数值 |
| --- | --- |
| 相对上游的提交 | **26** |
| 改动文件 | 110（+7479 / −384） |
| 单元测试 | **26 个文件 / 167 个用例**（上游 7 / 30） |
| 仪器测试 | 1（`TypographyLabTest`，排版自检） |
| 工作区 | 干净（`git status` 无改动） |
| 最近提交 | `6922dc27 docs: correct the scope of the protobuf/R8 finding` |

三重构建门禁（每次改动都要全过）：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease-debug
```

第三个门禁是硬要求：**R8 相关的缺陷只在 minified 构建里暴露**，前两个门禁抓不到。

---

## 3. 阶段完成情况

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| Phase 0 | fork 落地、minSdk 29、zh-CN 补 23 条、排版自检实验室 | ✅ |
| Phase 1 | Motion / Typography / Dimens token（41 处 tween 逐值迁移） | ✅ |
| Phase 2a | `ReaderPalette` + 系统栏图标跟随阅读背景 | ✅ |
| Phase 2b | 排版面板重排 + 4 预设 + 面板内实时预览 | ✅ |
| Phase 3a | 翻页动画（平移 / 淡入淡出） | ✅ |
| Phase 3b | 点击分区（左中右 / 上中下 / 仅中间） | ✅ |
| Phase 3c | 章节切换提示 | ✅ |
| Phase 4a | 书签（段落级去重、行级跳转） | ✅ |
| Phase 4b | 书架进度环、排序可见、封面圆角统一 | ✅ |
| 额外 | TXT 编码探测 + 手动切换 | ✅ |
| 额外 | **用户导入字体**（含中文字形检测与回退链） | ✅ |
| 额外 | R8/protobuf 修复（minified 构建可读书） | ✅ |

每个阶段都走完 2 轮独立评审 + 修复，评审发现的问题全部记录在提交信息里。

### 3.1 第二阶段（翻译与词典）/ S1

| 项 | 内容 |
| --- | --- |
| 分支 | `phase-2` |
| 设计文档 | [`docs/phase-2-design.md`](docs/phase-2-design.md) |
| S1 内容 | 设计文档、依赖（openai-kotlin 4.1.0 + Ktor 3.0.0、tink-android 1.18.0、commons-compress 1.28.0 + xz 1.10）、三个 JVM spike 测试 |
| 状态 | ✅ 三重门禁通过（`assembleDebug` / `testDebugUnitTest` / `assembleRelease-debug`，171 个单测全绿） |

S1 的门禁命令与第一阶段相同：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease-debug
```

**R8 限制（review 发现，必须在 S3/S6 回查）**：`assembleRelease-debug` 能过，但
minified 构建里 Tink / commons-compress / xz / openai 的类被**整体 shrink 掉了**——S1 还没有
任何生产代码调用它们，R8 自然视作无用。因此 S1 的 “R8 通过” 只证明**构建能过**，
**不证明**这些库在 R8 下可用。S3 必须在 minified 构建/设备上真解一份 7z，
S6 必须在 minified 构建/设备上真跑一次 Tink 存取，否则不得认为这两个依赖已验证。
在此之前**不**加任何 speculative 的 keep 规则（无条件 keep 会把整个库拉进包，体积无法评估）。

### 3.2 第二阶段（翻译与词典）/ S2

| 项 | 内容 |
| --- | --- |
| 模块 | `:dict-engine`（`third_party/juicedict-engine/`，`kotlin("jvm")`，纯 JVM，无 Android 依赖） |
| 来源 | `qiuminal/juicedict` @ `7bbcb874`，GPL-3.0；28 个引擎源码 + 12 个上游测试逐字复制，改动范围见 `third_party/juicedict-engine/PROVENANCE.md` |
| 适配层 | `DictionaryEngines`（设计文档早期叫 `EngineFactory`）：`openStarDict` / `openMdx` / `probeMdx` / `probeMdxHead` / `mddFilesFor` / `logSink` |
| 状态 | ✅ 引擎单测 **101 个用例全绿**（`./gradlew :dict-engine:test --rerun-tasks`）；`:app:assembleDebug` 通过 |

S2 的 fixture 覆盖（**都不依赖外部语料**，测试运行时现场生成）：

| 测试 | 钉住什么 |
| --- | --- |
| `MdxFixtureTest.probesAndRendersSyntheticMdx` | 用 `java.util.zip.Deflater` 合成的合法 MDX 2.0（单 key block + 单 record block）：`probeMdx` 的 title/description/entryCount、`openMdx` → `lookupSmart("hello")` → `article().toHtml()`，外加一条 `@@@LINK=` 重定向 |
| `DictionaryEnginesTest.openStarDictReadsDirectoryAndRendersArticle` | `.ifo` + `.idx` + `.dict` 装配、`.jidx` 缓存写入 |
| `DictionaryEnginesTest.openStarDictReadsGzippedIndexAndSynAlias` | `.idx.gz`（`GZIPInputStream`）+ `.syn` 别名命中 + `.jidx` 缓存重开 |
| `DictionaryEnginesTest.mddFilesForPairsBaseAndNumberedPacks` | `.mdd` 配对顺序（`<base>.mdd` → `.1.mdd` → `.2.mdd` → `.10.mdd`） |
| `DictionaryEnginesTest.openStarDictReportsSwallowedFailureThroughLogSink` | 坏文件仍返回 `null`，但失败原因经 `logSink` 上报 |

上游 5 个未复制测试（`StarDictEngineTest` / `BigEcdictTest` / `FuzzyPerfTest` /
`MdxSmoke` / `MdxVerify`）与保留测试里以本机语料为输入的方法（`E:\dictionary\MDict`、
`../internal-dicts/dict`）都在 `PROVENANCE.md` 里逐条记录了原因与实际期望路径；
这些方法在无目录时直接 `return`，报告里显示为 passed 而非 skipped。

有意留到后续阶段的：

- `.dict.dz`（dictzip）在适配层里的装配分支：S3 的内置资产用的是明文 `.dict`（见 §3.3），
  所以这条分支仍只有上游 `DictZipLastChunkTest` 覆盖，适配层本身没走到 → 留给 S4 导入真实 `.dict.dz` 词典。
- 引擎实例的线程安全：适配层 KDoc 已声明「非线程安全，调用方按引擎加锁」，
  真正的 LRU + 每引擎锁 → S4（上游 `DictionaryRepository` 的做法）。
- `openMdx` 在构造失败时的 fd 泄漏是上游既有问题（`MdxFileReader` 先打开后 `error`），
  未在 vendored 副本里改；S4 按「先 `probeMdx` 预检再构造」规避，真修回上游。

### 3.3 第二阶段（翻译与词典）/ S3

| 项 | 内容 |
| --- | --- |
| 资产 | `app/src/main/assets/dictionaries/ecdict/stardict.7z`：**21,112,502 字节**（LZMA2，SHA-256 `91c1367c…6aec`），含 `ecdict.ifo` / `.idx` / `.dict` 三个条目；`ECDICT-LICENSE.txt`（MIT）+ `NOTICE.txt` 同目录 |
| 数据来源 | 官方 ECDICT `ecdict.csv`（SHA-256 `d1ecbdf9…43cc`，MIT），由 `tools/dict/prepare_ecdict.py` 转成 StarDict 再打包；脚本是构建期工具，App 里不跑。**可复现**：输入固定 mtime（`SOURCE_DATE_EPOCH`，默认 2024-01-01T00:00:00Z）+ 先打 ustar 再转 7z（7z 会存 atime/ctime，ctime 无法从用户态固定），同一份 CSV 重建两次 SHA 完全相同；中间产物只写 `build/ecdict/`，只有 7z 进 assets |
| 解压结果 | **90,090,039 字节**（`.dict` 73,054,537 + `.idx` 17,035,288 + `.ifo` 214），引擎另写 `.jidx` 索引缓存 **35,611,830 字节**，目录合计 **125,701,869 字节（约 120 MiB）** |
| 词条数 | **770,611**（`.ifo` 的 `wordcount`，与引擎 `wordCount` 一致） |
| 安装器 | `data/dictionary/BuiltInDictionaryInstaller`（纯 JVM，无 `android.*`）：只读归档头拿条目名/大小 → 空间检查（解压量 × 1.5 + 旧目录 + `.old` 孤儿）→ 解压到 `<target>.tmp` → 打开词典校验 → 写 `version` 标记 → `rename` 原子换目录。条目名统一落成小写 StarDict 后缀（`tiny.IFO` → `tiny.ifo`）；失败（含 `Error`）都先删临时目录；进程死在换目录中间时，下次启动先把 `<target>.old` 搬回来再判标记（直接跳过安装） |
| 注册表 | `data/dictionary/DictionaryRegistry`：`StateFlow<BuiltInState>`、`ensureBuiltIn`（Mutex 串行）、`openBuiltIn`、`close`；`ECDICT_ASSET_VERSION = 1` |
| 启动接入 | `Application.onCreate` 在 `Dispatchers.IO` 上 `launch { ensureBuiltIn(...) }`，不阻塞启动 |
| 状态 | ✅ `:dict-engine:test --rerun-tasks` 101 个用例全绿；`:app:testDebugUnitTest` **178 个用例全绿**（新增 7 个）；`:app:assembleDebug` / `:app:assembleRelease-debug` 通过 |

S3 的测试（前四个**用真实资产**，中间两个用 `SevenZOutputFile` 合成几 KB 的最小 StarDict，最后一个校验资产字节）：

| 测试 | 钉住什么 |
| --- | --- |
| `BuiltInDictionaryInstallerTest.installsRealEcdictAndServesLookups` | 解压出 `.ifo`/`.idx`/`.dict` + `version` 标记（含版本与词条数）；`wordCount > 700_000`；`openStarDict` 后 `lookupSmart("apple", 5)` 命中、`toHtml()` 非空且含中文；进度以 1.0 结束 |
| `BuiltInDictionaryInstallerTest.secondInstallWithSameVersionIsSkippedAndKeepsFiles` | 同版本第二次安装 `skipped = true` 且四个文件 mtime 不变；版本号变化必须重装（顺带覆盖「旧目录存在时换目录」） |
| `BuiltInDictionaryInstallerTest.corruptArchiveThrowsAndLeavesNothingBehind` | 随机字节的 7z 抛 `DictionaryInstallException`，且不留 `builtin` / `builtin.tmp` / `builtin.old` |
| `BuiltInDictionaryInstallerTest.failedInstallKeepsThePreviousDictionary` | 已装好 v1 后用损坏资产装 v2：抛异常、旧文件 mtime 不变、标记仍是 v1、不留 `.tmp`（原子换目录的失败路径） |
| `BuiltInDictionaryInstallerTest.mixedCaseArchiveSuffixesAreNormalizedToLowercase` | 合成 `tiny.IFO` / `tiny.Idx` / `tiny.DICT`：计划阶段按 `ignoreCase` 匹配，落盘必须是 `tiny.ifo` / `.idx` / `.dict` 且能被 `openStarDict` 打开（否则会通过计划再报「不完整」） |
| `BuiltInDictionaryInstallerTest.interruptedSwapIsRecoveredFromOldDirectory` | 模拟进程死在两次 rename 之间（把 `builtin` rename 成 `builtin.old`）：下次安装把它搬回来、`skipped = true`、mtime 不变、`.old` 消失 |
| `BuiltInDictionaryAssetTest.shippedAssetMatchesNotice` | 随包 7z 的大小与 SHA-256 等于 `NOTICE.txt` 公布的值（21,112,502 / `91c1367c…6aec`），且 `NOTICE.txt` 里确实写了这两个值 |

实测数据（**JVM，非真机**）：

| 项 | 数值 |
| --- | --- |
| 解压 90MB（commons-compress + xz，256KB 缓冲） | 约 **1.3s**（同一份资产用 `bsdtar` 是 0.7s；测试里整个首次安装含校验打开约 1.6–1.7s） |
| 冷启动打开 77 万词条（`.idx` 全解析 + 写 `.jidx`） | 约 **190ms** |
| 单元测试堆 | **未改**：`app` 单测默认堆就够（`maxHeapSize` 仍是 Gradle 默认值），没有 OOM |
| Debug APK | **154,756,852 字节**（其中资产 21,118,922） |
| Release-debug APK | **54,789,964 字节**（其中资产 21,118,947；R8 keep 规则把 7z/xz 解码器整包留下，比加规则前大 164KB） |

两个值得记下来的发现：

1. **资产在 APK 里是 deflate 的**：`.7z` 不在 aapt 的 `noCompress` 默认列表里，21,112,502 →
   21,118,922 字节（反而大 6KB）。所以 `AssetManager.openFd` 拿不到长度，拷贝阶段报不了中间进度；
   这次没有改 `noCompress`（属于打包决策，留给 S9 按体积/启动耗时评估）。
2. **解压目录比资产大 6 倍**：120 MiB 里 34 MiB 是引擎的 `.jidx` 索引缓存。安装器的空间检查
   按「解压量 × 1.5 + 旧目录大小」算，正好覆盖这份缓存。

未验证（**不要当成已验证**）：

- 真机首次启动的解压耗时与内存峰值（只有 JVM 数据）。
- 仪器测试：S3 **没有**新增 `androidTest`，`DictionaryRegistry` 的资产拷贝与启动接入只经过编译验证。
- minified 构建下**真解**一份 7z：`:app:assembleRelease-debug` 能过（54,789,964 字节，含 21,118,947 资产），
  `app/proguard-rules.pro` 已加 `-keep class org.apache.commons.compress.archivers.sevenz.** { *; }` /
  `-keep class org.tukaani.xz.** { *; }` / `-dontwarn org.tukaani.xz.**`（commons-compress 的 7z
  coder 表按类名反射实例化，`-keepnames` 只保名字不保构造器），并在 `classes.dex` 里确认
  `SevenZFile` / `LZMADecoder` / `Coders` / `LZMAInputStream` / `LZMA2InputStream` / `XZInputStream`
  都在；但**没在设备上跑过** minified 包的首次解压（S9 首启验证）。
- 空间不足分支没有测试（`File.usableSpace` 不可注入）。
- 仪器测试与真机上的 `.old` 中断恢复：只有 JVM 上用合成归档模拟过。

Review Round-1 已关的项（S3 内）：

- **P1** 7z 反射路径的 R8 keep 规则（见上）。
- 资产 SHA-256/大小有测试钉住（`BuiltInDictionaryAssetTest`），`NOTICE.txt` 同步更新。
- 资产按新脚本重打：`.ifo` / `.idx` / `.dict` 与旧资产**逐字节相同**（只改了 7z 头里的时间戳），
  SHA-256 从 `857eea24…4453` 变为 `91c1367c…6aec`，大小 21,112,524 → 21,112,502。
  另外发现工作区里的脚本被改过（音标前的 `<br>` 变成了空格），直接重建会改变正文渲染；
  已把脚本改回与已审资产一致的 `<br>`。
- `tools/dict/prepare_ecdict.py`：中间产物默认只写 `build/ecdict/`（`--keep-intermediates` 才留
  `.ifo/.idx/.dict`），只把 `stardict.7z` 拷进 assets；固定 mtime + ustar 中转后重建 SHA 恒定；
  `.gitignore` 兜底 assets 下的 `ecdict.*` / `stardict.tar`。
- 安装器：`Throwable` 也清临时目录（`Error` 原样重抛）、`.old` 孤儿恢复并计入空间检查、
  条目名统一小写后缀；`DictionaryRegistry` 仍接住 `Throwable`（放行 `CancellationException`），
  保持 `ensureBuiltIn` 「启动路径不抛异常」的约定。
- `DictionaryRegistry` 的 `context.cacheDir` 注释说明为何不用 `FileData.getCacheDirectory()`。

有意留到后续阶段的（review Round-1 的 P2，**不是**遗漏）：

- `openBuiltIn()` 仍返回裸引擎、锁由调用方负责：S4/S5 改成 `withBuiltIn { }`（或每引擎 `Mutex`），
  并在换实例时等旧引擎的使用者退出后再 `close()`。S3 没有调用方，现在改等于凭空设计接口。
- 暂存目录仍是 `dictionaries/builtin.tmp` / `.old`：S4 枚举 `dictionaries/*` 时要过滤这两个名字
  （或那时把暂存目录挪到 `dictionaries/.staging/`）。
- 错误文案仍是硬编码英文：S4 接设置页时改成 `BuiltInState.Error` 带稳定 code + 字符串资源。
- 安装器的其余边角用例（无 `.ifo`、两个 StarDict、缺 `.idx`、`.dict.dz` 变体、坏 `version` 标记、
  空间不足）：S4 导入路径会复用同一批校验，到时用合成归档一并补。
- 四个真实资产测试每次跑要解 4 次 90MB（整套约 6s，`/tmp` 峰值约 360MB）：S9 若 CI 磁盘吃紧再改成共享解压目录。

### 3.4 第二阶段（翻译与词典）/ S4

| 项 | 内容 |
| --- | --- |
| 目录 | `data/dictionary/DictionaryCatalog` + `DictionaryIndexCodec`：元数据在 `filesDir/dictionaries/index.json`，导入词典在 `dictionaries/imported/<uuid>/`；内置条目从 `DictionaryRegistry.builtInState` 派生（id `builtin`，不可删除/禁用）。枚举 `imported/` 时跳过 `.staging` / `*.tmp` / `*.old`（`isTransientDirectoryName`），`refresh` 丢掉文件已消失的条目、收养未入索引的目录（原子 rename 与写索引之间进程被杀时不会留孤儿）。启动时 `sweepStaging` 清掉上次进程留下的 `.staging/*`（只在 `Application.onCreate` 跑，不会碰正在进行的导入）；`decode`/`reconcileIndex` 丢弃 `builtin` 与重复 id（Compose `key = { it.id }` 不能重复）；索引里的 `type` 用稳定 wire 字符串（`"MDX"`/`"STARDICT"`）而不是枚举名 |
| 导入 | `data/dictionary/DictionaryImporter`：MDX 走 `OpenDocument`（`*/*`）→ 复制前读前 1 MiB 跑 `probeMdxHead`（非 MDict 直接拒绝，不先复制一遍）→ `.staging/<uuid>/` → `probeMdx` + `openMdx` 校验（关闭）→ `imported/<uuid>/`；StarDict 走 `OpenDocumentTree` → 校验 `.ifo` + `.idx(.gz)` + `.dict(.dz)`（可选 `.syn`）→ `openStarDict` 校验 → 原子换目录。错误是 `DictionaryImportError` 枚举（含 NOT_MDX/ENCRYPTED/RESOURCE_PACK/CORRUPT/INCOMPLETE/TOO_LARGE/NO_SPACE），`ImportState` 以 StateFlow 暴露进度；全部磁盘工作在 `Dispatchers.IO`。**`.mdd` 资源包本期不导入**（单文件 `OpenDocument` 拿不到兄弟文件授权；设计 §4.3 已把这条记成欠账，`openDictionaryEngine` 里的 `mddFilesFor` 调用点留着，等资源渲染阶段复制 `.mdd` 后自动生效） |
| 查询 | `data/dictionary/DictionaryRepository`：`lookup(query, limit = 5)` 合并内置 + 已启用导入词典，精确 > 前缀 > 模糊（有精确/前缀时丢掉模糊），同级内**优先词典**（`activeDictionaryId`）先查、其余按目录顺序稳定排序；`article(dictId, hit)` 在 `hit.dictId != dictId` 时返回 null（防止用别的词典的偏移读出垃圾字节），否则返回 `Article.toHtml()`；每引擎一把进程级 `Mutex`（仓库内部串行；`DictionaryRegistry.openBuiltIn()` 仍是裸引擎，见下方 S3 遗留项），导入引擎 LRU 最多 2 个、淘汰/禁用时**先拿该引擎的锁再 close**，内置实例只借不关（每次向注册表现取，重装换实例后不会用到已关闭的引擎） |
| 设置 | `presentation/settings/DictionaryManagerScreen` + `ui/settings/reader/dictionary/DictionaryManager{Content,Layout,Scaffold,TopBar}` + `components/`：内置 ECDICT 状态/进度/词条数/体积/资产版本（`ECDICT_ASSET_VERSION`，不可删）、导入词典的启用开关/删除/优先词典单选、MDX 与 StarDict 导入按钮、导入进度与错误文案；`TranslatorSubcategory` 新增「词典管理」入口（S9 并入完整翻译设置页）。导入协程挂在**页面级**作用域（`DictionaryManagerLayout`），滚动列表不会取消导入 |
| 设置项 | `SettingsManager.activeDictionaryId`（`stringPreferencesKey("active_dictionary_id")`，默认 `"builtin"`）：语义是**优先**（先查它，再查其余已启用词典），不是唯一；删除或禁用当前词典时设置页回落内置。S5 构造 `DictionaryRepository` 时把 `activeDictionaryId` 注入为 `activeDictionaryId = { settings.activeDictionaryId.lastValue }`，在此之前仓库默认 `null`（按目录顺序查） |
| 状态 | ✅ `:app:testDebugUnitTest` **213 个用例全绿**（35 个文件，新增 35 个）；`:app:assembleDebug` / `:app:assembleRelease-debug` 通过；`:dict-engine:test --rerun-tasks` 101 个用例全绿（未改动） |

S4 的测试（全部纯 JVM，不依赖 Android 上下文）：

| 测试 | 钉住什么 |
| --- | --- |
| `DictionaryIndexCodecTest`（8） | 字段完整往返、空索引、整份 JSON 损坏返回 null、单条缺字段/类型未知时跳过、可选字段的默认值；`type` 落成稳定 wire 字符串且兼容旧枚举名；`builtin` 与重复 id 被丢弃（保留第一条） |
| `DictionaryCatalogTest`（6） | `.staging` / `*.tmp` / `*.old` 不被收养（合成 StarDict fixture）；索引条目文件消失后被丢弃、文件在时原样保留；`builtin` 与重复 id 在 reconcile 时被丢弃；启动清理 `.staging/*` 且不动 `imported/`；相对路径不能逃出词典目录。临时目录在 `finally` 里递归删除 |
| `DictionaryRepositoryTest`（16） | 假引擎上的精确/前缀/模糊排序与「有强命中就丢模糊」、目录顺序稳定、limit、禁用词典不打开、引擎复用、LRU 淘汰并 close、禁用后下一次查询关闭引擎；优先词典在同 rank 内排最前且 rank 仍然优先；`article` 拒绝不属于该词典的命中；并发 8 路查询同一引擎时 `maxInFlight == 1`；默认 opener 用合成 StarDict fixture 跑通 lookup → preview → article。临时目录在 `finally` 里递归删除 |
| `DictionaryImportErrorTest`（5） | 引擎英文消息 → `DictionaryImportError` 映射（资源包/加密/非 MDX/截断/未知）；文件名主干净化（保留 Unicode 字母、去掉路径分隔符） |

Round-1 review（CHANGES REQUIRED）的修复：

- P1-1 导入作用域：`DictionaryImportActions` 不再自己取 `rememberCoroutineScope()`，改为 `onImport(uri, isMdx)` 由 `DictionaryManagerLayout` 的页面作用域启动 —— 滚动列表不再取消导入，离开页面仍然取消（见手工矩阵 #7 / #7b）。
- P1-2 `.mdd`：本期**不导入**，已写进设计 §4.3 作为欠账（单文件 `OpenDocument` 拿不到兄弟文件授权），`DictionaryImporter` 的注释不再误引 §1.2。
- P2 已做：复制前 `probeMdxHead` 预检；`decode`/`reconcileIndex` 丢弃 `builtin` 与重复 id；启动清理 `.staging/*`；淘汰/禁用引擎时先拿该引擎的锁再 `close()`；`article` 校验 `hit.dictId`；`DictionaryType` 用稳定 wire 字符串；`activeDictionaryId` 真正影响查询顺序并改称「优先」；内置条目显示资产版本；英文复数 bug 改中性文案（`Entries: %d`）；测试临时目录改 `finally` 递归删除；本文不再声称 `openBuiltIn()` 接缝已关闭。
- P2 仍留给后续（review 同意不在此轮做）：删除按钮失败反馈 / TalkBack `Role`（#12）、上游 `MdxDictionary.init` 的 fd 泄漏（#13）、导入器其余边角用例（StarDict 缺文件/多部词典的自动化覆盖需要 SAF URI）。

S3 遗留项在 S4 的落地情况：

- ⏳ `openBuiltIn()` 的锁：`DictionaryRepository` 内部有进程级每引擎 `Mutex`，但**仓库在 S4 还没有消费者**（没有 Hilt 绑定、没有调用方），`openBuiltIn()` 对外仍是裸引擎。真正关掉这条接缝要等 S5 让取词路径只走 `DictionaryRepository`（S5 接管）。
- ✅ 枚举过滤 `.tmp` / `.old` / `.staging`：见 `DictionaryCatalogTest`。
- ⏳ 内置词典的错误文案仍是英文消息（`BuiltInState.Error.message` 原样显示）。S4 只把**导入**错误做成枚举 + 字符串资源；改 `BuiltInState` 的形状会牵动 S3 的安装器契约，留到 S9 统一文案时做。
- ⏳ 安装器边角用例（无 `.ifo`、两个 StarDict、坏 `version` 标记、空间不足）：S4 的导入路径不复用安装器的 7z 计划逻辑，StarDict 的「缺文件/多部词典」在导入侧已有枚举分支，但**没有**自动化测试（需要 SAF URI，见下方手工矩阵）。

**未新增 `androidTest`**：导入路径的入口是 SAF `Uri`（`OpenDocument` / `OpenDocumentTree`），没有用户交互就构造不出可读的授权；仪器测试只能覆盖到目录索引与列表渲染，价值低于维护成本。因此以下**手工矩阵未执行**，不得当作已验证：

| # | 场景 | 期望 |
| --- | --- | --- |
| 1 | 导入一部小 MDX（几 MB） | 进度 → 校验 → 「已导入」；列表出现条目，词条数/体积正确；重启后仍在 |
| 2 | 导入 StarDict 文件夹（`.ifo` + `.idx` + `.dict`） | 同上；优先词典选中后重启仍保持 |
| 3 | 选 `.mdd` 资源包 / 加密 MDX / 改扩展名的普通文件 | 分别提示「资源包」「不支持加密」「不是 MDX」；`imported/` 与 `.staging/` 不留文件 |
| 4 | 选空文件夹 / 只有 `.ifo` 的文件夹 / 含两部 StarDict 的文件夹 | 分别提示「不完整」「不完整」「多部词典」 |
| 5 | 关闭某部词典的开关 | 条目变灰/开关关闭；取词（S5）不再命中；重启后保持关闭 |
| 6 | 删除当前优先词典 | 优先词典回落内置 ECDICT；`imported/<uuid>/` 被删除；列表立即更新 |
| 7 | 导入过程中滚动设置页列表（把导入行滚出屏幕） | 导入**继续**：协程挂在页面级作用域，`LazyColumn` 回收 item 不会取消它（Round-1 修复前会静默中止） |
| 7b | 导入过程中按返回键离开设置页再回来 | 导入被取消（页面作用域随页面销毁），暂存目录被清理、状态回到 Idle；若取消发生在 rename 之后，词典文件留在 `imported/` 且未入索引，下次启动 `refresh` 会收养（不会出现半个词典） |
| 8 | 内置词典首次安装中进入词典管理页 | 显示「正在准备… N%」进度条；安装完成后变为词条数/体积/资产版本（`v1`） |
| 9 | 剩余空间不足（模拟小分区） | 复制前提示空间不足，不留暂存目录 |
| 10 | 导入的 MDX 词条引用 `entry://` 图片/音频 | 正文缺图（本期不导入 `.mdd`，见设计 §4.3 的欠账说明），不应崩溃 |
| 11 | 滚动导入行出屏幕后再滚回来 | 进度/状态仍在（`ImportState` 是进程级 `StateFlow`，不随 item 回收丢失） |

---

## 4. 真机验证结果

设备：Lenovo TB322FC（Android 16 / API 36 / 1904×3040 / 440dpi）

### 已验证 ✅

| 项 | 证据 |
| --- | --- |
| minified 构建能打开书籍 | 修复前：`File appears to be empty`；修复后：正常渲染 |
| GBK 中文 TXT | `Detected charset [GB18030] with confidence [LIKELY]`，正文正确显示中文 |
| Room 19 → 20 迁移 | 升级后书库与 47.6% 阅读进度全部保留 |
| 分页翻页 | 设置「分页」下，向左滑动从第 5 页到第 6 页 |
| 新设置项渲染 | 「更多」Tab 显示 阅读模式 / 翻页方向 / 翻页动画 / 点击分区 |
| 书架 | 导入真书（含封面提取）、进度环显示 |
| 系统语言为中文时的界面 | 全中文，无英文残留 |
| 字体导入 | 导入 Noto Sans SC（17.8MB 可变）→ 显示 Chinese glyphs / Variable + 三行预览；纯拉丁 Garamond → 显示 No Chinese glyphs，预览如实反映回退 |
| 用户字体在阅读页生效 | 选中 Noto Sans SC 后打开中文 EPUB，字形确为该字体 |

### 未在真机验证 ❌（只做过单测或模拟器）

- 翻页动画两种模式的**观感对比**（平移 vs 淡入淡出）
- 点击分区三种布局的**实际翻页**（左/中/右、上/中/下、仅中间）
- 书签完整流程（添加 → 列表 → 跳转 → 删除；含跳到远处书签不再白屏）
- 章节切换提示的出现与消失
- 排版预设 4 个 chip、面板内样例预览的实时性
- 系统栏图标明暗跟随深色阅读背景
- 阅读配色在纯黑/羊皮纸预设下的对比度观感
- 列表布局（书架网格已验，列表未验）
- 用户字体的删除流程（代码里先回落默认字体再删文件，未在设备上走过）

**建议下一步就在真机上把这 8 项过一遍**，尤其是书签跳转——那是本 fork 唯一修过两次 blocker 的功能。

---

## 5. 发现的上游缺陷（两个，均未发布）

上游 v1.8.0（2025-10-13 发布）与 master（2026-10-25）差异很大，下面两条都只存在于 **master**，会随下个版本发布出去：

### 5.1 protobuf 与 R8 不兼容 → minified 构建读不了任何书

| | 官方 v1.8.0 | master |
| --- | --- | --- |
| `.proto` 文件 | 0 | 1 |
| `protobuf-javalite` | 无 | 4.33.2 |
| minified 构建能读书 | ✅ | ❌ |

四组对照（同一台设备、同一本真实书《无人生还》）：

| 构建 | 结果 |
| --- | --- |
| 官方 v1.8.0（minified，无 protobuf） | ✅ |
| master debug（不开 R8） | ✅ |
| master release-debug（R8 + protobuf） | ❌ `Could not parse book content` |
| master + keep 规则（本 fork） | ✅ |

根因：`protobuf-javalite` 靠反射按**字段名**读写消息，自身不带 consumer proguard 规则，
R8 把 `text_` 改名成 `e` 后 `parseFrom` 抛异常，而异常被 `runCatching` 吞掉 → 报「文件似乎为空」。

修法：`app/proguard-rules.pro` 一行
`-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }`

**值得报给上游。**

### 5.2 TXT 无条件按 UTF-8 解码 → GBK 中文全是乱码且静默

`TxtContentParser` 用 `bufferedReader()`，全仓库无编码探测。GBK（中文网文常见编码）渲染成
`���`，而且 U+FFFD 通过 `containsVisibleText()`，乱码被当正常内容缓存，用户得不到任何提示。

本 fork 已修：严格解码探测（BOM → UTF-8 → GB18030 → BIG5 → 兜底）+ 手动切换 + 可信度提示。

### 5.3 顺带确认：v1.8.0 没有分页功能

`ReaderPagePaginator.kt` / `ReaderLayoutPaged.kt` 在 v1.8.0 中**不存在**，只有连续滚动。
分页是 master 上新增的未发布功能（默认即为分页）。所以官方版用户只能滚动。

---

## 6. 环境与复现方式

本机原本没有 JDK / Android SDK，本次已安装：

| 组件 | 版本 / 路径 |
| --- | --- |
| JDK | OpenJDK 17.0.21（`/opt/homebrew/opt/openjdk@17`） |
| Android SDK | `~/Library/Android/sdk`，platform 36 / build-tools 36.0.0 |
| Gradle | 8.13（wrapper） |
| 模拟器 AVD | `reader34`（API 34，arm64） |
| 真机 | `HA297V1V`（Lenovo TB322FC，Android 16） |

`local.properties` 里写 `sdk.dir`。上游的 `gradlew` 在 git 里是 644，macOS 上需 `chmod +x`。

### 设备上已安装

| 应用 | 包名 | 用途 |
| --- | --- | --- |
| 本地阅读器 | `dev.jia.reader.release.debug` | 最新（含 R8 修复 + 编码支持），日常测试用这个 |
| 本地阅读器 | `dev.jia.reader.debug` | 调试版（未重装，缺编码支持） |
| Book's Story | `ua.acclorite.book_story` | 官方 v1.8.0，用于对比 |

测试书在 `/sdcard/Download/books/`（含两本真书 + 三本自制样本）。

---

## 7. 文档索引

| 文件 | 内容 |
| --- | --- |
| `DEV_PLAN.md` | 设计与验收记录（含实测数据、每阶段的决策与偏差） |
| `UPSTREAM_DIFF.md` | 与上游的全部差异说明（可直接给他人看） |
| `REBASE.md` | 从上游同步时必须检查的三处静默失效点 |
| `docs/motion-migration.md` | 41 处动画时长/缓动的逐值对照表 |
| `docs/typography-lab/` | 中文排版实测原始输出 |
| `app/schemas/.../19.json`、`20.json` | Room 迁移的 schema 快照 |

---

## 8. 明确未做

内置中文字体（**用户导入已实现**，内置仍不做）、双指调亮度、音量键翻页、
「最近阅读」置顶分区、长按批处理栏、全局 Snapshot 回归测试、zh-rTW 等 28 个语言、
列表布局的进度样式。

理由都记在 `DEV_PLAN.md` §8 与 `UPSTREAM_DIFF.md` §8。

---

## 9. 下一步建议（按优先级）

1. **真机上过一遍 §4 的 8 项未验证功能**，重点是书签跳转（唯一修过两次 blocker 的功能）
2. **给上游提 issue**：protobuf + R8（一行修法，能避免他们下个版本发布包完全不可用）
3. ~~决定是否做「用户导入字体」~~ **已完成**（`bc3edd8c`），minSdk 29 的成本已兑现
4. 定期 `git rebase upstream/master`，按 `REBASE.md` 逐条检查
