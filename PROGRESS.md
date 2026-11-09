# 进度记录

> 快照时间：2026-10-31
> 基线：tag `upstream-base` = `464891ec`（Acclorite/book-story 的 `master`，GPL-3.0-only）
> 仓库：独立仓库 `kijhtybbu/reader`，分支 `main`（2026-10-31 起独立发展，上游只作只读参考）
> §1/§2 为第一阶段 `ui-redesign` 快照，该分支已并入 `main` 并删除；当前进度见 §3.1–§3.18

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
| 分支 | `main`（独立仓库 `kijhtybbu/reader`） |
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

- ✅ `openBuiltIn()` 的锁：`DictionaryRepository` 内部有进程级每引擎 `Mutex`。S4 时仓库还没有消费者，`openBuiltIn()` 对外仍是裸引擎；**S5 已接管** —— 仓库有 `@Singleton` 绑定、取词路径只走仓库，`openBuiltIn()` 的唯一调用点就是仓库的 `engineFor`（见 §3.5）。
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
| 7b | 导入过程中按返回键离开设置页再回来 | 导入被取消（页面作用域随页面销毁）：复制循环每读一块（256 KB）`ensureActive()` 检查一次，所以取消在下一块就生效，不会把 512 MB 复制完才响应；暂存目录由 `finally` 递归删除、状态回到 Idle；若取消发生在 rename 之后，词典文件留在 `imported/` 且未入索引，下次启动 `refresh` 会收养（不会出现半个词典）。页面级作用域用的是 `applicationContext`，旋转屏幕不会吊住已销毁的 Activity |
| 8 | 内置词典首次安装中进入词典管理页 | 显示「正在准备… N%」进度条；安装完成后变为词条数/体积/资产版本（`v1`） |
| 9 | 剩余空间不足（模拟小分区） | 复制前提示空间不足，不留暂存目录 |
| 10 | 导入的 MDX 词条引用 `entry://` 图片/音频 | 正文缺图（本期不导入 `.mdd`，见设计 §4.3 的欠账说明），不应崩溃 |
| 11 | 滚动导入行出屏幕后再滚回来 | 进度/状态仍在（`ImportState` 是进程级 `StateFlow`，不随 item 回收丢失） |

### 3.5 第二阶段（翻译与词典）/ S5

| 项 | 内容 |
| --- | --- |
| 取词手势 | `presentation/reader/model/ReaderLookupGesture`（`SINGLE_TAP` / `LONG_PRESS` / `OFF`）+ `SettingsManager.lookupGesture`（`stringPreferencesKey("lookup_gesture")`，默认 `SINGLE_TAP`；认不出的存量值经 `fromName` 回落 `SINGLE_TAP`，不会静默关掉功能）；设置页 `TranslatorSubcategory` 新增 chip 行 + 说明（单击接管「点到文字」，空白区仍翻页/呼菜单） |
| 仓库接线 | `AppModule.provideDictionaryRepository`（`@Provides @Singleton`）：`activeDictionaryId = { settings.activeDictionaryId.lastValue }`。取词路径只走仓库，不再有裸 `openBuiltIn()` 调用方 |
| 文本布局注册表 | `ui/reader/ReaderTextLayoutRegistry`：键 = 节点 id + 页项起点；存 `TextLayoutResult` + **窗口矩形**；命中取「后注册的优先」；注销按 entry 身份比对（重排时旧组合的 `onDispose` 晚于新 `register`，按 key 无条件删会把新布局删掉）。`StyledText` 两种重载都加了 `onTextLayout` |
| 词归一 | `ui/reader/ReaderLookupWord`：剥首尾标点/引号，连字符与撇号只在词内保留，CJK 截 8 字，纯标点返回 null；`firstLookupWord` 给多词选区取首个词；`juice://lookup/<urlencoded>` 解码（含 U+FFFD 的截断转义拒绝） |
| 分页命中 | 沿用 Pager 上唯一的 `detectTapGestures`：`readerPageItemAtY` 定位页项（页脚空白 → null），再用该项注册的 `TextLayoutResult` 取词；命中就取词并吃掉这次点击，未命中照旧 `tapActionFor` 翻页/呼菜单 |
| 连续命中 | 内容层**一个** `detectTapGestures`（替换原来的 `noRippleClickable`），段落/标题/图注把文本块注册进表里，点击换算成窗口坐标后查表；空白仍切菜单。**菜单开着时先收菜单 + 关卡片并 return**（与分页一致，排在取词之前）。段落不再各挂 `combinedClickable`（那会吞掉单击） |
| 长按 | `SelectionContainer` 新增 `onSelectionSettled(rect)`（rect 本身就是窗口坐标：同一个 rect 原样交给 ActionMode 的 `onGetContentRect`，不再二次 `localToWindow`），阅读页防抖 350ms 后用注册表按选区中心反查单词；跨行选区（矩形高于词高的 1.5 倍，两行选区必被拒）本期忽略。工具栏「词典」改为应用内取词，多词选区只查第一个词（整串短语必 miss），真 miss 时外部 Intent 退到卡片未找到时的「用其他应用查询」 |
| 取词模型 | `presentation/reader/DictionaryLookupModel`（`@HiltViewModel`）+ `LookupState`（`Idle`/`Loading`/`Success`/`NotFound`/`Error`）：新查询取消旧 Job，`select(index)` 取该条正文，`dismiss()` 取消并复位；`Success.fromCache` 恒 false（S7），`sentence` 参数留给 S6 |
| 取词卡片 | `ui/reader/ReaderLookupPopup`：`Popup` + 自定义 `PopupPositionProvider`（先算位置再渲染，不会先画下面再跳上面；RTL 下也没有 `Popup(alignment, offset)` 的坐标翻转问题）；最大宽 360dp、最大高 **45% 安全区高度**（窗口扣系统栏/导航栏/刘海挖孔，定位与夹紧也都在安全区内）、内部滚动，配色只用 `LocalReaderPalette`；`AnnotatedString.fromHtml` 渲染正文并拦截 `juice://lookup/`，解析失败退化成引擎的纯文本预览；未找到给「导入词典」/「AI 解释（S6，禁用）」/「用其他应用查询」，错误态给「重试」。外部点击/返回/翻页/滚动/切菜单都会关卡片 |
| 状态 | ✅ `:app:testDebugUnitTest` **257 个用例全绿**（40 个文件，新增 44 个）；`:dict-engine:test --rerun-tasks` 101 个全绿（未改动）；`:app:assembleDebug` / `:app:assembleRelease-debug` 通过 |

S5 的测试（全部纯 JVM，无 Android 上下文）：

| 测试 | 钉住什么 |
| --- | --- |
| `ReaderLookupWordTest`（13） | 引号/括号/句末标点剥离、词内撇号与连字符保留、首尾连字符剥离、CJK 截 8 字、拉丁长词不截、纯标点/空白返回 null、归一范围（含 CJK 截断后的 end）、`juice://lookup/` 解码与畸形转义拒绝、多词选区取首个词、单词选区与归一一致、无词的选区返回 null |
| `ReaderTextLayoutRegistryTest`（8） | 矩形内命中、矩形外/负坐标不命中、后注册的优先、同 key 替换旧布局、注销、**旧 entry 的注销不会删掉新 entry**、`clear` |
| `ReaderLookupPopupPositionTest`（8） | 贴词下方、放不下翻到上方、左右夹在屏幕内、锚点未知时居中、卡片比屏幕还高时贴顶、系统栏/挖孔 inset 缩小夹紧范围、无锚点时在安全区内居中、翻到上方的卡片仍不越过状态栏 |
| `ReaderNodeHitTestTest`（+3） | `readerPageItemAtY` 的 `topInPage`/`localY`、段前空隙 `localY < 0`、越过最后一项返回 null（原有 5 条双击翻译命中不变） |
| `DictionaryLookupModelTest`（8） | Loading → Success（第一条命中的正文）、查询词先归一、无命中 → NotFound、仓库抛异常 → Error 且 `retry()` 重跑、切词条重新取正文、`dismiss()` 取消且**迟到的结果不会把卡片弹回来**、第二次查询取消第一次、纯标点查询不动状态 |
| `ReaderLookupGestureTest`（3） | 三个取值 round-trip、null 回落 `SINGLE_TAP`、未知/大小写不符回落 `SINGLE_TAP` |
| `DictionaryCatalogTest`（+1） | `imported/builtin/` 目录不被收养（会与内置条目撞 id） |

与设计稿的偏差（实施时的结论，已同步进 `docs/phase-2-design.md` §5.1）：

1. `onSelectionSettled` 只传矩形，不传选中文本。`TextToolbar.showMenu` 只给矩形；拿文本只能读剪贴板，而 Android 13+ 每次写剪贴板都会弹系统提示，拖选择手柄等于连续弹窗。词由注册表按选区中心反查。
2. 连续布局的双击翻译改为「注册表命中段落」：原来段落自己的 `combinedClickable` 是第二套点击检测器，会把单击吞掉。代价是点在段落的**行尾空白/左右留白**上双击不再翻译，也不会切菜单（无响应）；双击正文文字不受影响。
3. `app/build.gradle.kts` 打开 `testOptions.unitTests.isReturnDefaultValues = true`：取词状态机的错误分支会 `logW`（`android.util.Log`），不开这个开关就只能在真机上测错误态。
4. `DictionaryRepository` 的三处 `catch (e: Exception)` 现在先重抛 `CancellationException`：否则「取消上一次取词」会被仓库吞成空结果，已取消的查询会把「未找到」写回卡片。

**真机上要重点确认的一条**：`SINGLE_TAP`（默认）下，正文铺满页面时「点空白翻页」的空白只剩行间空隙、页脚与 `sidePadding`（默认 6dp）—— 分区翻页的可点区域会明显变小。这是设计 §0/§5.1 已确认的取舍（设置项里说明了「单击接管点到文字」），但手感只有真机能判断；若不可接受，S9 再评估「只在中间分区取词」或把 `sidePadding` 下限抬高，不改本期行为。

有意留给后续阶段（**不是遗漏**）：

- AI 解释：卡片里的按钮是禁用态，`DictionaryLookupModel.lookup(..., sentence)` 的 sentence 参数目前不参与查询 → S6。
- 缓存：`LookupState.Success.fromCache` 恒 false，第二次查同一个词仍然走引擎 → S7/S8。
- 长选区 → 翻译对话框：本期直接忽略跨行选区 → S8。
- 卡片是 `focusable` 的：卡片开着时点正文的第一下只是关卡片（不会同时取下一个词），这是浮层的常见行为；如果真机上觉得别扭，S9 再评估改成非 focusable + 自定义外部点击检测。
- 翻页动画期间注册表里存的是动画**前**的窗口矩形（`boundsInWindow` 只在 placement 时刷新，`graphicsLayer` 平移不会重新布局）：动画中点击理论上会命中旧位置。重排时内容 alpha 为 0、滑动中途点击罕见，本期仅记录（S8/S9 如要做平移入场的动画再评估）。
- `isReturnDefaultValues` 保持开启（S5 错误路径确实用到 `logW`）。它会把未来误用 android.* 的单测静默变成 `0`/`null`；如果要收紧，S8/S9 改成注入 logger（或 `Warn` lambda）进 `DictionaryLookupModel`，而不是全局开关。本期无具体失败，不动。
- `.mdd` 资源包、`entry://` 图片仍然缺图（S4 的欠账）。

S5 的手工矩阵**未执行**（没有 Compose UI 测试依赖，按项目约定不加），不得当作已验证：

| # | 场景 | 期望 |
| --- | --- | --- |
| 1 | 分页 + 单击正文单词 | 卡片贴在该词上方/下方，显示词头、来源词典与释义 |
| 2 | 分页 + 单击页脚/段间空白、左中右分区 | 仍然翻页/呼菜单，不弹卡片 |
| 3 | 连续 + 单击正文单词 | 同 #1；卡片跟随滚动前的位置正确 |
| 4 | 连续 + 单击空白 | 仍然切菜单 |
| 5 | 长按选中一个词（手势设为 LONG_PRESS，两种布局） | 350ms 后弹出卡片，锚点是选区 |
| 6 | 长按拖选跨行文本（包括刚好选中两行） | 不取词；复制/分享/翻译工具栏动作仍然正常 |
| 7 | 选中单词后点工具栏「词典」 | 应用内卡片（不再跳外部词典应用） |
| 7b | 选中**两个词**后点工具栏「词典」 | 只查第一个词（不是「未找到」）；真查不到时卡片上的「用其他应用查询」仍能拉起外部应用 |
| 8 | 查一个不存在的词 | 卡片显示「未找到」+「导入词典」/「AI 解释（S8，禁用）」/「用其他应用查询」 |
| 9 | 导入 MDX 后查一个内置词库没有的词 | 命中导入词典，卡片显示该词典名；多词典命中时 chip 可切换 |
| 10 | 卡片开着时点卡片外/按返回/翻页/滚动/切菜单 | 卡片关闭，且不误触翻页；**菜单开着时**点正文第一下只收菜单 + 关卡片（两种布局一致） |
| 11 | 卡片正文里的 `juice://lookup/` 链接 | 在原位换成目标词的释义 |
| 12 | RTL 布局 + 阿拉伯语/希伯来语正文 | 卡片不跑到屏幕外，锚点贴词 |
| 13 | 夜间/纯黑预设 | 卡片不闪白，正文与链接可读 |
| 14 | 超大字号 + 横屏（带刘海/挖孔） | 卡片不超过 45% **安全区**高，不会钻到状态栏/挖孔下面，内部可滚动 |
| 15 | 双击段落（分页 + 连续） | 分页照旧；连续下双击正文文字仍打开翻译，双击行尾空白无响应（见偏差 2） |
| 16 | 取词手势设为 OFF | 单击完全不取词；工具栏「词典」仍可用 |

### 3.6 第二阶段（翻译与词典）/ S6

| 项 | 内容 |
| --- | --- |
| 设置项 | `SettingsManager.aiBaseUrl`（`stringPreferencesKey("ai_base_url")`，默认 `https://api.openai.com/v1/`，存取两端 `normalizeAiBaseUrl`：没写协议补 `https://`、再补尾部 `/`）；`aiModel`（默认 `gpt-4o-mini`，空值回落默认）；`aiTargetLanguage`（`AiTargetLanguage`，默认 `FOLLOW_UI`）；`aiGrammarEnabled`（默认 `true`） |
| 目标语言 | `data/ai/AiTargetLanguage`：跟随界面 + zh-CN / zh-TW / en / ja / ko；设置里存枚举名，提示词用 `promptName(uiLocale)`，`FOLLOW_UI` 每次请求按 `SettingsManager.language` 解析（zh-TW/HK/MO → 繁体，其余 zh → 简体，未知语言用英文语言名 + BCP-47 标签）。**自定义语言未做**（见偏差 1） |
| Key 加密 | `data/ai/AiKeyCipher` 接口 + `TinkAiKeyCipher`（`AndroidKeysetManager` + `AeadKeyTemplates.AES256_GCM`，主密钥在 Android Keystore，keyset 在应用私有 SharedPreferences）；`AiKeyStore` 把 base64 密文存 `stringPreferencesKey("ai_api_key_enc")`。`get/set/clear` 均为 suspend，加解密在 `Dispatchers.IO`；解密失败清空存量并返回 null，日志只有固定文案、不含 Key/密文/正文。`clear()` 写空串（`DataStore` 接口没有 remove）；`set/clear` 共用一把 `Mutex`（晚到的 `set` 不能盖掉 `clear`）。`AiKeyStore.get()` 与 `AiSettingsModel` 的保存/清除都捕 `Throwable`（`CancellationException` 原样抛）：R8 删掉 Tink 后抛的是 `NoClassDefFoundError`/`ExceptionInInitializerError`，只捕 `Exception` 会让设置页直接崩 |
| 客户端 | `data/ai/AiTranslationRepository`：`OpenAI(OpenAIConfig(token, host = OpenAIHost(baseUrl), timeout, headers = X-Title, logging = None, retry = maxRetries 0, engine))`；`testConnection()` 调 `/models`；`translate()` 用 `chatCompletions()` 收集流式 delta，长文本按句末标点切块（≤3000 字符，拼回去等于原文）后顺序请求；`analyzeGrammar()` 同样流式（`chatCompletions()`，不发 `response_format`），delta 由 `TranslationModel` 聚合、判空后才写缓存（A.8）。Ktor engine 可注入（MockEngine 测试），**生产显式传 `OkHttp.create()`**（`AppModule`），不依赖 Ktor 的 ServiceLoader。**本机地址免 Key**：Base URL 的 host 是 `localhost`/`127.0.0.1`/`10.0.2.2` 时允许不配 Key（空串 token，请求上带一个空的 `Authorization: Bearer`，本机服务不校验），公网地址无 Key 仍在建连前抛 `NO_KEY`。流式请求用 per-request `RequestOptions(Timeout(request = INFINITE))` 覆盖整次调用 60s 上限，卡死由 socket 超时兜住 |
| 生命周期 | 客户端按请求创建、`finally` 关闭（翻译的多块共用一个）。不跨请求缓存客户端，Key/Base URL 改完下一次请求必然用新配置；没有「旧客户端还挂着旧 Key」的窗口 |
| 错误 | `AiError`（NO_KEY / INVALID_KEY / RATE_LIMIT / NOT_FOUND / TIMEOUT / NETWORK / SERVER / CANCELLED / UNKNOWN）+ `AiException(error, statusCode)`；401/403 → INVALID_KEY、404 → NOT_FOUND、429 → RATE_LIMIT、5xx → SERVER（状态码从 `OpenAIServerException` 的 cause 里捞，UI 能显示「(HTTP 503)」）、`OpenAITimeoutException` → TIMEOUT、IO/HTTP → NETWORK。异常消息只含错误名与状态码，不含响应体/Key；`CancellationException` 原样传播，不映射成 CANCELLED |
| 设置页 | `presentation/settings/AiSettingsScreen` + `presentation/settings/AiSettingsModel` + `ui/settings/reader/ai/{AiSettingsContent,AiSettingsLayout,AiSettingsScaffold,AiSettingsTopBar}`：Base URL（失焦提交，提交后输入框回填真正生效的值——空值会变回默认值，不会显示 `""` 却用着默认地址）、模型（失焦提交 + 常用预设 chip，同样回填）、API Key（密码框 + 显示/隐藏 + 显式保存/清除，**不**用 `rememberSaveable`，明文不进 saved instance state；**保存成功后才清空输入框**，失败保留已输入内容；本机 Base URL 允许空 Key 并给出说明）、目标语言 chip、语法开关、「测试连接」按钮（loading/成功/失败）、隐私与费用说明（只说「AI 请求会把正文发给配置的服务」，不许诺 S8 的语法自动请求）。`TranslatorSubcategory` 新增「AI 翻译设置」行，词典管理行保留 |
| 文案/DI | 新文案进 `values/strings.xml` 与 `values-zh-rCN/strings.xml`；`AppModule` 提供 `AiKeyCipher`（Tink）与 `AiTranslationRepository`（`@Singleton`，配置用 lambda 现取 `SettingsManager.lastValue`） |
| 状态 | ✅ `:app:testDebugUnitTest` **305 个用例全绿**（47 个文件，新增 48 个）；`:dict-engine:test` 101 个全绿（未改动）；`:app:assembleDebug` 通过；`:app:assembleRelease-debug` 通过（本机 2g Gradle 堆在 R8/lint 阶段 GC thrash，改用 `-Xmx4096m` 后通过，见偏差 6） |

S6 的测试（全部纯 JVM，无 Android 上下文、无真实网络）：

| 测试 | 钉住什么 |
| --- | --- |
| `AiKeyStoreTest`（6） | set 后 DataStore 里是密文、get 解密回原值、缺失返回 null、空白 set 等同 clear、clear 清空、密文损坏时返回 null 且清掉存量、类加载 `Error`（Tink 被 R8 删光）退化成「没有 Key」而不是崩 |
| `AiClientConfigTest`（5） | 空/空白 Base URL 回落默认、trim + 补尾部 `/`、已有 `/` 不重复、没写协议补 `https://`、本机 host（`localhost`/`127.0.0.1`/`10.0.2.2`，含大小写与带端口）被识别且 `localhost.example.com`/局域网 IP 不算本机 |
| `AiTargetLanguageTest`（5） | 枚举名 round-trip、未知/null 回落 FOLLOW_UI、固定语言提示词名、FOLLOW_UI 按 zh-CN/zh-TW/zh-HK/ja/ko/fr 解析、语言名为空时回落 `English (en)` |
| `AiPromptsTest`（7） | 短文本单块、长文本每块 ≤3000 且拼回原文、英文句号切块、CJK 句号切块、无空白硬切、翻译提示词含目标语言/只输出译文/保留段落、语法提示词含完整 JSON schema |
| `AiErrorMappingTest`（9） | 401/403/429/404/400/5xx/超时/网络/未知异常映射；5xx 从 cause 里取回 HTTP 状态码（真实 Ktor `ServerResponseException` 构造）；已有 `AiException` 透传；`CancellationException` 不被映射；异常消息不含原始 body |
| `AiTranslationRepositoryTest`（15） | MockEngine 下 URL/host/Bearer/X-Title 断言、SSE delta 拼接、长文本多请求顺序输出、`/models` 测试连接成功、401→INVALID_KEY、404→NOT_FOUND、500→SERVER+500、`SocketTimeoutException`→TIMEOUT、429→RATE_LIMIT、**本机地址无 Key 可直连且不带凭据**、公网地址无 Key 时 testConnection/translate 均 NO_KEY 且不碰网络、语法 JSON 请求 + 原始 JSON 返回、取消传播且不发出半截文本、空文本一次请求都不发（计 MockEngine 调用次数）、**流式不受整次调用 request 超时影响**（同配置下 `/models` 会 TIMEOUT、SSE 正常读完） |
| `AiOkHttpEngineTest`（1） | 生产引擎（`OkHttp.create()`）+ loopback 最小 HTTP 服务：同一个仓库实例先 `testConnection()`（`/models`）再 `translate()`（流式 `/chat/completions`），两次顺序请求共用一个注入引擎——证明生产不靠 ServiceLoader 发现引擎，且关闭每次请求的 `OpenAI` 不会关掉共享引擎（第二次请求失败即为回归）；无 Key，服务端 trim 后只看到空的 `Bearer` |
| `TinkAiKeyCipherTest`（androidTest，**未执行**） | 真实 Android Keystore：两次加密 nonce 不同、往返解密、跨实例 keyset 复用、坏密文抛异常 |

与设计稿的偏差（实施时的结论，已同步进 `docs/phase-2-design.md` §6）：

1. **自定义目标语言未做**：枚举无法同时表达「预设」与「自由文本」；设计里是可选项，留到有需求时再评估。
2. **测试连接只用 `/models`**：这是最便宜的鉴权/地址探测，但部分 OpenAI 兼容服务（尤其只实现 chat 的本地服务）可能没有该端点，会显示 NOT_FOUND/UNKNOWN。没有做「失败后改发最小 chat 请求」的回退——那会产生真实费用，先不静默做。
3. **客户端不缓存**：每次请求新建 `OpenAI` 并在 `finally` 关闭。比「缓存客户端 + 配置变化时关闭」多一次 Ktor 客户端构造，但不存在关闭正在流式的旧客户端的问题，也天然满足「不跨 Key/Base URL 复用」。
4. **`AiError.CANCELLED` 只用于 UI 兜底**：仓库对 `CancellationException` 直接重抛，不把它变成一条错误状态；否则关闭对话框会被画成「未知错误」。
5. **AI 单词解释仍未接**：取词卡片的按钮仍是禁用态，文案从 `(S6)` 改成 `(S8)`；S6 只做配置/客户端/设置页。
6. **本机 2g Gradle 堆在 `assembleRelease-debug` 的 R8/lint 阶段 GC thrash**：`./gradlew ... -Dorg.gradle.jvmargs='-Xmx4096m ...'` 后通过；这是本机内存/守护进程问题，不是代码或 R8 规则失败。
7. **明文 HTTP 策略保持 S1 的决定**：只放行 `localhost` / `127.0.0.1` / `10.0.2.2`；IPv6 回环与局域网 HTTP 不放行，局域网用户需自行反向代理/HTTPS。`network_security_config.xml` 注释已更新。
8. **本机地址免 Key 用的是空 Bearer**：openai-kotlin 4.1.0 无条件安装 Bearer 鉴权，没有「跳过鉴权」的开关，所以本机地址不配 Key 时请求仍带一个空的 `Authorization: Bearer`（服务端看到的可能是 `Bearer` 或 `Bearer `）。本机服务不校验它；公网地址在 `resolve()` 里就被 `NO_KEY` 拦下。loopback 测试在服务端 trim 后断言，钉住的性质是「没有真凭据」，不依赖引擎对尾部空格的处理。
9. **流式请求没有整次调用超时**：`chatCompletions` 用 per-request `RequestOptions(Timeout(request = INFINITE))`，只保留 60s socket 超时；否则长 SSE 会在第 60 秒被误判成 TIMEOUT。`/models` 与语法请求仍保留 60s 整次上限。

S6 的手工/真机矩阵**未执行**，不得当作已验证：

| # | 场景 | 期望 |
| --- | --- | --- |
| 1 | 设置页填 Base URL/模型/目标语言，杀进程重开 | 值保留；Base URL 自动补 `/` |
| 2 | 保存 Key → 杀进程重开 | 显示「已保存 Key」，不显示明文；再输入新 Key 可覆盖 |
| 2b | Base URL 刚输入完还没失焦，直接点「保存 Key」 | 点击先提交 Base URL（输入框回填规范化值）再保存 Key；之后的「测试连接」用的是这个地址 |
| 3 | 清除 Key / 手改 DataStore 密文 | 显示未配置/解密失败后清空，不崩溃、不把密文发出去 |
| 4 | 测试连接（真实 OpenAI/DeepSeek；本机 Ollama 不填 Key） | 成功显示「连接成功」；错误只显示类别 + HTTP 状态码；Ollama 这类本机服务留空 Key 也能连 |
| 5 | 401 / 429 / 断网 / 错误 Base URL | 分别显示 INVALID_KEY / RATE_LIMIT / NETWORK / NOT_FOUND，不泄露响应体 |
| 6 | 长段落（>3000 字符）翻译 | 顺序流式输出，段落不丢不重 |
| 7 | 翻译中途关闭/取消 | 请求取消，不追加错误文本 |
| 8 | 跟随界面语言 + zh-TW 界面 | 提示词目标语言为繁体中文 |
| 9 | minified `release-debug` 上保存/读取 Key | Tink/Keystore 路径正常（S1 遗留的 R8 回查项） |
| 9b | minified `release-debug` 上真发一次对话/流式（引擎已显式 OkHttp，仍需实测） | 有响应、流式不中断（若这里失败，第一嫌疑是 R8 规则而非 ServiceLoader） |
| 10 | Base URL 指向 `http://127.0.0.1:11434/v1/`，**不填 Key** | 允许明文、允许空 Key（设置页说明「可以留空」），测试连接/翻译能通；换成局域网 IP 则被网络安全配置拒绝；公网地址不填 Key 显示「尚未保存 API Key」 |

### 3.7 第二阶段（翻译与词典）/ S7

| 项 | 内容 |
| --- | --- |
| 实体 | `data/storage/room/entity/TranslationCacheEntity`：表 `translationcacheentity`，主键 `cacheKey: String`，列 `bookId/nodeId/kind/model/baseUrlHost/targetLanguage/promptVersion/payload/complete/createdAt/lastAccessedAt`；索引 `lastAccessedAt`（LRU）与 `bookId`（将来按书清理）。不存正文全文，也没有外键——同一段文字在不同书/章节共享缓存（设计 §7.2） |
| DAO | `dao/TranslationCacheDao`（`@Dao abstract class`，与 `BookmarkDao` 同风格）：`get` / `touch` / `put`（`REPLACE`）/ `clear` / `stats()`（条数与字节数一次查询取回）/ `deleteOldest(limit)` / `trimToEntries(limit)`（两者的 `limit` 都按至少 1 处理，`LIMIT 0` 不会删光缓存）/ `trimToBytes(maxBytes)`（事务方法）。**占用按 UTF-8 字节**：`LENGTH(CAST(payload AS BLOB))`，`LENGTH(text)` 数的是字符数（中文只有实际体积的 1/3） |
| 数据库 | `AppDatabase` 版本 **21**，`entities` 加 `TranslationCacheEntity`，`AutoMigration(20, 21)`，抽象 `translationCacheDao`；不碰既有表、无破坏性迁移。schema 导出到 `app/schemas/ua.acclorite.book_story.data.storage.room.AppDatabase/21.json`（由 KSP 生成，随本次改动进工作区） |
| 缓存键 | `data/ai/cache/TranslationCacheKey`（纯 JVM）：`SHA-256("v<promptVersion>\|<kind>\|<targetLanguage>\|<model>\|<baseUrlHost>\|<normalize(text)>")`；`TranslationKind` 的 `wire` 是落库稳定字符串（`TRANSLATION`/`GRAMMAR`/`WORD`）。`normalizeCacheText` = trim + CRLF/CR → LF，**不合并内部空白**；host 复用 S6 的 `aiBaseUrlHost`（先规范化 Base URL，畸形地址返回空串而不抛异常） |
| 仓库 | `data/ai/cache/TranslationCacheRepository`（接口）+ `TranslationCacheRepositoryImpl`（`@Inject constructor(AppDatabase)`，`RepositoryModule` 里 `@Binds @Singleton`）：`get`（命中推进 `lastAccessedAt`，`complete = false` 的行忽略）/ `put`（恒写 `complete = true`，`withContext(NonCancellable)`）/ `clear` / `stats(): CacheStats(count, payloadBytes)` / `trim(maxEntries = 20_000, maxBytes = 200MB)` |
| 状态 | ✅ `:app:testDebugUnitTest` **315 个用例全绿**（48 个文件，新增 10 个）；`:app:compileDebugAndroidTestKotlin` 通过；`:app:assembleDebug` 通过；`:app:assembleRelease-debug` 通过（`-Xmx4096m`）；`:dict-engine:test` 未改动。**仪器测试这次在模拟器上真跑了**：`./gradlew :app:connectedDebugAndroidTest` → 模拟器 `reader34`（Android 14 / API 34，arm64）上 **21 个用例全绿**（S7 新增 11 个 + `TinkAiKeyCipherTest` 3 个 + `TypographyLabTest` 7 个），0 失败 0 跳过；修复轮后又单独跑了 `TranslationCacheRepositoryTest`（11 个，含新增的 `limit = 0` 用例），同样全绿 |

S7 的测试：

| 测试 | 钉住什么 |
| --- | --- |
| `TranslationCacheKeyTest`（JVM，10） | 同输入同键且是 64 位小写十六进制；**固定输入钉死哈希值**（`dcad577a…f80eda`，拼接格式一改就红）；trim + CRLF/CR→LF 归一且内部空白不合并；`kind` 三种不同；模型/目标语言/`promptVersion` 不同即不同键；**省略 `promptVersion` 等于显式传 `AI_PROMPT_VERSION`**（提示词一改旧缓存就失效靠这个默认值）；只有 host 进键（协议/端口/路径/大小写不影响）；空 Base URL 的 host 是默认地址 `api.openai.com`；畸形地址 `::::`/`///` 的 host 是空串且不抛异常；CJK 与 emoji（含肤色修饰符）稳定且区分 |
| `TranslationCacheRepositoryTest`（androidTest，11） | put→get 返回 payload/模型/语言/`createdAt`/`fromCache = true`，落库的 `kind`/`baseUrlHost` 正确，**读取推进 `lastAccessedAt` 而 `createdAt` 不动**，且落库键等于键构造器算出的键；`complete = false` 的行既读不到也不被 touch；同键 `put` 覆盖（1 行、新 payload）；`clear` 清空后 stats 归零；`stats.payloadBytes` 数的是 UTF-8 字节（`你好` = 6）；`trim` 按条数保留最新 N；按字节删最旧的直到不超预算、到第一行超预算为止；**单行超预算时保留最新一行**；`deleteOldest(n)` 精确删最旧 n 行；`trimToEntries(0)`/`deleteOldest(0)` 按至少 1 处理（不会删光缓存） |
| `TranslationCacheMigrationTest`（androidTest，1） | `MigrationTestHelper` 用导出的 `20.json` 建库、跑 `AutoMigration(20, 21)` 并按 `21.json` 校验：既有 `CategoryEntity` 行数不变、`translationcacheentity` 表存在、新表能按实体列插入 |

与设计稿的偏差 / 实施时的结论（已同步进 `docs/phase-2-design.md` §7.4）：

1. **`payloadBytes` 用 `LENGTH(CAST(payload AS BLOB))`**：设计稿只给了 200MB 预算，没规定按字节还是按字符计；SQLite 对 TEXT 的 `LENGTH()` 返回字符数，中文段落按字符算只有实际体积的 1/3，200MB 预算会被撑到 600MB，所以实现按 UTF-8 字节算。
2. **`trimToBytes` 至少保留最新一行**：单行就超预算时（例如超长段落的语法 JSON）删掉它只会「下次访问重新请求 → 写回 → 再被删」，来回计费。实现上先读 `(cacheKey, 字节数)` 投影算切点再分批删（每批 500 键）：不用窗口函数（不同 Android 版本 SQLite 版本不一致），也不用 O(n²) 自连接。
3. **没有单独的 `TranslationCacheEntry` 类型**：任务描述里 `TranslationCacheEntry`/`CachedAiResult` 二选一，只保留 `CachedAiResult`（`payload/model/targetLanguage/createdAt/fromCache`），避免两个同形类型。
4. **`touch` 用系统时钟**：只有 `put` 的 `now` 由调用方传入（S8 有完成时刻），没有为测试注入时钟——测试断言的是「比写入时的 1_000L 大」。
5. **`androidTest` 需要手动把 schema 目录加进 assets**：`MigrationTestHelper` 在测试进程里按 assets 路径读 schema，Room Gradle 插件不会自动加。`app/build.gradle.kts` 的 `androidTest` sourceSet 加了 `assets.srcDir("$projectDir/schemas")`，并新增 `androidTestImplementation("androidx.room:room-testing:2.7.2")`。加之前 androidTest APK 里没有 `assets/…AppDatabase/*.json`，迁移测试会在运行时找不到 `20.json`。
6. **键只含 host**（设计 §7.2 原文如此）：同一主机不同路径的两个自建网关会共用缓存；换 `/v1` → `/v2` 不会 miss。已在 `TranslationCacheKey` 的文件头写明这是设计取舍。
7. **`RepositoryModule` 的 `@Singleton` 绑定**：与其它仓库一致（实现类不标 `@Singleton`），`AppDatabase` 由 `AppModule` 提供。

S8 必须这样调用（设计 §7.4 已写）：

- 算键：`translationCacheKey(text, TranslationKind.TRANSLATION, targetLanguage.promptName(uiLocale), model, baseUrl)`（`promptVersion` 默认 `AI_PROMPT_VERSION`）。
- 读：`repo.get(key)` → 命中直接展示，`CachedAiResult.fromCache == true`，不建网络请求。
- 写：请求**完整**结束后 `repo.put(key, kind, bookId, nodeId, payload, model, baseUrl, targetLanguage, AI_PROMPT_VERSION, now)`；流式中途取消/失败**不调用** `put`（仓库侧也会用 `NonCancellable` 保证「已完成的结果」写进去）。
- 「重新生成」：直接 `put` 覆盖同一键（`REPLACE`），不需要先删（仓库没有按单键删除的 API）；`clear()` 只给设置页的「清除翻译缓存」用。
- 容量：`trim()` 由设置页/S8 在合适时机调（本期没有自动触发点）。

**未验证（不要当成已验证）**：

- 缓存**跨进程重建**（杀进程后再次双击命中）：S7 只有 DAO/仓库测试，真要验证得等 S8 有调用方，或写「先 buildDatabase 写入 → 关库 → 重开」的仪器测试（本期没写：内存库重开即空，文件库要自己清理，价值低于 S8 端到端）。
- **真机**：所有仪器测试跑在模拟器 `reader34`（API 34）上，没有在真机（Lenovo TB322FC / API 36）上跑过。
- minified `release-debug` 上的 Room 迁移：只验证了构建能过，没有装到设备上跑 20→21。
- 顺带：`TinkAiKeyCipherTest`（S6 记录为「仪器测试已写但未执行」）这次随整套 connected 测试在模拟器上跑过并通过（3 例）；真机仍未验证，§3.6 的结论不变。

### 3.8 第二阶段（翻译与词典）/ S8

> 本节记录的语法 JSON 方案已被 §3.11 取代（逐句分析 + Markdown 渲染）；翻译侧的结论不变。

| 项 | 内容 |
| --- | --- |
| 状态机 | `presentation/reader/TranslationModel`（`@HiltViewModel`）+ `model/TranslationState`（`TranslationTab`、`AsyncText`、`AsyncGrammar`、`TranslationState`）：`open(text, bookId, nodeId)` 固定一份 `Session`（配置快照 + translation/grammar 两个 cacheKey + 正文 + 节点），缓存优先、`Map<cacheKey, Job>` 去重、`dismiss()` 取消并递增 `requestId`、`regenerate(tab)` 只重置该标签 |
| 配置快照 | `data/ai/AiRequestConfig` + `AiRequestConfigProvider`（`@Singleton`，现取 `SettingsManager` 的 `aiModel/aiBaseUrl/aiTargetLanguage/aiGrammarEnabled/language`）：缓存键用 `open` 那一刻的快照（model/baseUrl/目标语言名，FOLLOW_UI 已按界面语言解析）；**只有目标语言同时进了请求**，model/baseUrl 由 `AiTranslationRepository` 在请求开始时现取设置（见偏差 1） |
| 双击整段 | `ui/reader/ReaderTranslationActions` + `ReaderNode.Paragraph.wholeParagraphText()`（取 `NodeContent.Paragraph.text`，不是分页切片）；分页与连续布局都传整段 + 节点 id；选区工具栏「翻译」传选中文字 + `nodeId = null` |
| 对话框 | `ui/reader/ReaderTranslationDialog`：Compose `Dialog`（`usePlatformDefaultWidth = false`）+ 居中 `Surface`，最大宽 600dp、高 80%，配色只用 `LocalReaderPalette`；标签「翻译 / 语法分析」（跟随 `ai_grammar_enabled`）；翻译标签流式输出、原文可折叠、复制/分享、模型/目标语言、缓存标记、重新生成；语法标签切到即分析（无「开始分析」按钮），结构化展示 summary/sentences/vocabulary，JSON 不合法时展示原始文本、JSON 合法但没有字段时只提示结果为空；翻译标签错误态给「重试」与「用其他应用翻译」，语法标签错误态只有「重试」 |
| 语法解析 | `presentation/reader/model/GrammarAnalysis`（纯 JVM）：取第一个 `{` 到最后一个 `}`（围栏/前后说明文字自动跳过），字段全部可空、多字段忽略、`points` 支持数组或字符串、空条目跳过；解析失败返回 null |
| 默认值 | `SettingsManager.doubleClickTranslation` 默认 `false` → **`true`**（设计 §0/§8 的既定决策；S5 时期双击只是外部 Intent 才保持关闭），设置项说明写明「单击会等约 300ms」 |
| 状态 | ✅ `:app:testDebugUnitTest` **349 个用例全绿**（51 个测试类；新增 34 个用例 / 3 个测试类，另有 1 个测试辅助 `FakeTranslationCache`）；`:dict-engine:test` 101 个全绿（未改动）；`:app:compileDebugAndroidTestKotlin` 通过（未新增仪器测试）；`:app:assembleDebug` 通过；`:app:assembleRelease-debug` 通过（`minifyRelease-debugWithR8 --rerun-tasks` 也在默认堆下跑过，本机这次不需要 S6 的 `-Xmx4096m`） |

S8 的测试（全部纯 JVM，无 Android 上下文、无真实网络；AI 用 MockK，缓存用内存 fake）：

| 测试 | 钉住什么 |
| --- | --- |
| `TranslationModelTest`（21） | 缓存命中 → 译文立即展示且 **0 次** `translate`/`analyzeGrammar`、不写库；未命中 → 流式拼接并 `put` 完整结果（键/kind/bookId/nodeId/payload/model/baseUrl/目标语言/promptVersion 全对）；同一段第二次打开 → 命中缓存（只请求 1 次）；语法缓存命中 → 切标签 0 请求；语法未命中 → 切标签立即请求并解析；重复点标签/来回切 → 在途请求只发 1 次；`dismiss()` → 取消且**不写半截**、状态复位；**不响应取消的上游在 `dismiss()` 之后继续发一块 → 状态仍是 `Idle`、不写库**（真的走到 `publish` 的 `isActive`/`requestId` 校验）；重新生成 → 同一 cacheKey 写两次、只重置该标签，**被取消的旧请求在重新生成之后才结束也盖不掉新结果**（落库前复查）；空译文 → `Error(UNKNOWN)` 且不写库；`AiException(429)` → `Error(RATE_LIMIT, 429)`；语法失败 → `Error(NO_KEY)` 且「重试」重新请求成功；写缓存失败/读缓存失败都不影响展示；整段正文原样送进 `translate`（含换行，不是切片）；`nodeId = null` 落库 `-1`；空白文本不打开对话框；设置里关掉语法时标签不可用且不发请求；语法解析默认在 `Dispatchers.Default` 上 |
| `TranslationModelCacheKeyTest`（4） | 模型读的键**等于** `translationCacheKey(text, kind, promptName, model, baseUrl)`（两个 kind 各一次，顺序也钉住）；同配置同文本第二次打开命中（0 额外请求）；换模型 / 换目标语言 → 换键 → 必 miss 并重新写一条 |
| `GrammarAnalysisTest`（9） | 完整字段解析；``` 围栏 + 前后说明文字；缺字段为 null/空列表；多字段忽略；`points` 是字符串；空句子/非对象条目跳过；空对象 `isEmpty`；非 JSON/截断/顶层数组/空串 → null（UI 退化成原始文本）；数字/布尔字段不崩 |

**缓存行为（JVM 已验证的部分）**：

- 命中：`open` 只 `get`，不 `put`、不请求；第二次 `open` 同一段文字（甚至不同 bookId/nodeId）命中 —— 键不含书/节点。
- 未命中：流式过程中**不**写库；只有流结束后写一次 `complete = true` 的行；写失败只记日志。
- 语法：`open` 时只预取缓存，不请求；切标签时先等预取落定，再决定是否需要请求。
- 重新生成：重新请求后 `put` 覆盖同一键（`REPLACE`），没有先删。
- 错误/取消：不写库；状态里只有 `AiError` + HTTP 状态码，不含响应体/Key。

**未验证（不要当成已验证）**：

- 对话框的 UI 行为一条都没在设备上走过（见下方手工矩阵）；S8 **没有**新增 `androidTest`。
- **缓存跨进程重建**（杀进程后再次双击命中）：仍然只有 JVM 的内存 fake，没有「写文件库 → 关库 → 重开」的仪器测试。S7 的这条欠账顺延到 S9。
- minified `release-debug` 上的真实流式/语法请求、Tink 解密 Key、Room 缓存读写：只验证了构建与 R8 能过。
- 真机上的双击判定延迟（默认开启后单击翻页会等约 300ms）是否可接受：只有设计上的取舍，没有手感数据。

与设计稿的偏差 / 实施时的结论（已同步进 `docs/phase-2-design.md` §8.1）：

1. **配置快照只保证目标语言**：`AiRequestConfigProvider` 在 `open` 时取一次配置，缓存键用这份快照；但请求里只有目标语言是按快照传的，model/baseUrl 由 `AiTranslationRepository` 在请求开始时现取设置。所以「键与请求同一份配置」只对目标语言成立，model/baseUrl 只在设置没变时一致（对话框挡住设置页，app 内碰不到这条缝隙；S9 若要让键与请求严格同源，得把 model/baseUrl 也传进 `translate/analyzeGrammar`）。
2. **空译文按失败处理**（`AiError.UNKNOWN`，不写缓存）：否则空串会被缓存成一张白纸，下次命中反而看不到任何东西。语法侧仓库本来就把空 content 当失败。
3. **语法解析不用严格反序列化**：`JsonElement` 手写宽容解析，`points` 允许字符串；解析失败不抛异常，返回 null 让 UI 展示原始 JSON。解析本身跑在 `Dispatchers.Default` 上（`TranslationModel.grammarResult`），不在主线程解 JSON。
4. **`nodeId = null` 落库 `-1`**：`translationcacheentity.nodeId` 非空，而选区翻译没有段落；`-1` 只是元数据，不参与缓存键。
5. **取词卡片的「AI 解释」按钮文案从 `(S8)` 改为 `(S9)`**：S8 只做段落翻译与语法分析，单词 AI 解释（设计 §1.1 第 4 条里的「AI 单词解释」）没有做。
6. **`doubleClickTranslation` 默认改为 `true`**：设计 §0/§8 的既定决策；S5 时期双击是外部 Intent，默认关闭是那时的取舍。设置项说明同步写明单击延迟。
7. **错误文案复用 S6 资源**（`ai_error_*` + `ai_error_with_status`），对话框没有新增一套错误字符串。
8. **关闭对话框不主动 `trim()` 缓存**：容量清理仍由设置页触发（S7 结论不变）。
9. **语法标签的错误态没有「用其他应用翻译」**：外部 Intent 只能翻译原文，给不了语法分析，摆在那里会承诺一个做不到的动作；语法错误只给「重试」。
10. **语法 JSON 合法但字段全空**（例如 `{}`）：不再回显 `{}`，改提示 `translation_dialog_empty_analysis`；只有 JSON 不合法才退化成展示原始回复。

**S8 Round-1 review 修复（本次，未提交）**：
- 两个新用例钉住取消之后的行为：不响应取消的上游在 `dismiss()` 后继续发一块 → 状态仍 `Idle`、不写库；被取消的旧请求在「重新生成」之后才结束 → 盖不掉新译文。
- `putCache` 落库前复查「还是不是当前这次 open + 协程没被取消」：仓库内部 `NonCancellable` 写库，晚到的旧结果原本可能覆盖重新生成的新结果。`flow {}` 在取消时会被 `SafeCollector` 自己截停，所以这条防线在真实上游下够不到，用例用一个自定义 `Flow` 模拟不守规矩的第三方实现。
- 空译文用例（`flowOf("")` → `Error(UNKNOWN)`、不写库）。
- 语法 JSON 解析移到 `Dispatchers.Default`（`parseDispatcher` 可注入，单测换成测试调度器）。
- 文档收窄三处过度声明（键与请求「绝不会各用一份配置」→ 只有目标语言保证；选区工具栏「翻译」已覆盖跨行选区，单行的只剩 S5 的选后自动取词）。
- 语法空结果文案 + 去掉语法错误态的外部翻译回退。
- 复跑门禁：`:app:testDebugUnitTest` 349 绿、`:dict-engine:test` 102 绿、`:app:assembleDebug` 与 `:app:assembleRelease-debug` 均通过。

**顺延到 S9（Round-1 review 提出，本轮未做）**：
- 删掉 `ReaderEffect.OnOpenTranslator.translateWholeParagraph`（现在恒为 `false`，`ReaderEffects` 里的 `createChooser` 分支已死，留着会让这个 flag 暗示一个不存在的行为）。
- 若要让缓存键与请求严格同源：把 model/baseUrl 也作为参数传进 `AiTranslationRepository.translate/analyzeGrammar`，而不是让它现取设置。
- 缓存跨进程重建的仪器测试（S7 欠账）、S8 手工矩阵（见下）。

**S8 手工矩阵未执行**（没有 Compose UI 测试依赖，按项目约定不加），不得当作已验证：

| # | 场景 | 期望 |
| --- | --- | --- |
| 1 | 分页 + 双击段落正文 | 居中对话框，翻译标签自动流式输出整段译文（不是本页切片） |
| 2 | 连续 + 双击段落正文 | 同上；双击行尾空白/左右留白仍无响应（S5 偏差 2） |
| 3 | 双击后立刻切到「语法分析」 | 无「开始分析」按钮，直接出 loading → 结构化分节（总览/句子/词汇） |
| 4 | 关掉对话框再次双击同一段 | 译文立刻出现并显示「来自缓存」；再点语法标签也立刻出现缓存结果 |
| 5 | 杀进程后重新打开同一本书、双击同一段 | 仍然命中（Room 持久化 + 键只含正文/模型/语言/host） |
| 6 | 流式过程中按返回/点外部/点关闭 | 请求取消，不追加错误文本，缓存里不留半截 |
| 7 | 成功态点「重新生成」 | 重新请求并覆盖同一键；另一标签的结果保持不变 |
| 8 | 401 / 429 / 断网 / 未配 Key | 只显示错误类别（+ HTTP 状态码），不泄露响应体；错误态可「重试」或「用其他应用翻译」 |
| 9 | 语法返回带 ``` 围栏/多字段/缺字段的 JSON | 正常结构化展示；完全不是 JSON 时显示「原始回复」+ 原文 |
| 10 | 夜间/纯黑预设、RTL、超大字号、横屏 | 对话框不闪白、不超出安全区；内容内部可滚动；文字可选中复制 |
| 11 | 设置里关闭「语法分析」 | 对话框只有「翻译」一个标签，且不会请求语法 |
| 12 | 设置里关闭「双击翻译」 | 双击不再打开对话框，单击立即翻页/呼菜单（无 300ms 等待） |
| 13 | `release-debug` minified：双击翻译 → 语法 → 二次双击 | 有响应、流式不中断、二次命中（R8 下 openai/Room/序列化路径） |

### 3.9 第二阶段（翻译与词典）/ S9（收尾）

| 项 | 内容 |
| --- | --- |
| 状态 | ✅ 第二阶段 S1–S9 全部完成；S1–S8 已按阶段提交，S9 本轮提交 |
| 清理 | 删除已失效的 `ReaderEffect.OnOpenTranslator.translateWholeParagraph` 与 `createChooser = !flag` 分支（外部应用翻译只剩错误态回退，仍让用户选应用）；新增 `AiError.EMPTY_RESPONSE`，模型返回空内容不再显示为「未知错误」 |
| 缓存管理 | AI 设置页新增「翻译缓存」：条数、占用（按 UTF-8 字节折算 MB）、清除前确认、清除后重新读占用；`AiSettingsModel` 新增 `AiCacheState`（Loading/Ready/LoadError/ClearError），不清空 Key/连接状态 |
| 许可/归属 | `aboutlibraries.json` 增加 ECDICT（MIT）与 juicedict 引擎（GPL-3.0）条目；README 增加第三方数据/源码说明；engine 的 `LICENSE`/`PROVENANCE.md` 与 ECDICT 的 `LICENSE`/`NOTICE.txt` 随仓库分发；`LicensesCreditsTest` 钉住条目存在 |
| 文档 | README 功能列表、本文件、`UPSTREAM_DIFF.md`、`docs/phase-2-design.md` 最终状态/已知限制；设计文档 S9 行改为 ✅ |
| JVM 测试 | `:app:testDebugUnitTest` **379 个用例 / 55 个测试类 / 0 失败 / 0 跳过**（含语法 Markdown/句子选择/TTS 与 MDX 1.2 相关用例）；`:dict-engine:test` **102 个 / 0 失败** |
| 仪器测试 | `:app:connectedDebugAndroidTest` 在 `reader34`（API 34，arm64）上 **22 个 / 0 失败 / 0 跳过**（Room DAO 11、迁移 1、Tink 3、TypographyLab 7） |
| 构建 | `:app:assembleDebug` 通过；`:app:assembleRelease-debug`（R8 + lintVital）通过；`:app:compileDebugAndroidTestKotlin` 通过 |
| APK | debug **155,192,122 B**；release-debug **56,025,366 B**（含 21MB ECDICT 资产与 R8 keep 的 7z/xz 路径） |
| 首次安装（模拟器实测） | 冷启动后在 IO 线程解压/建索引约 **24.8s**；`files/dictionaries/builtin` 约 **122,800 KB**（`.dict` 73,054,537 + `.idx` 17,035,288 + `.ifo` 214 + `.jidx` 35,611,830 + version），应用数据目录约 **123MB**；重启命中 `version` 标记，不重复解压 |
| 手工 UI 矩阵 | **未执行**（项目没有 Compose UI 测试依赖，按约定不加）。S5/S8 的设备矩阵仍只是设计/代码级验证，不得当成已验证；真机手感（默认双击导致的 ~300ms 单击延迟、取词卡片空白区缩小）仍需人工确认 |
| 最终整体 review | 按用户要求**取消**（原计划 `reviewer-expert` 全量 review）；S1–S9 每阶段均完成两轮 `reviewer`，最终以本报告收尾 |

S9 的测试增量：

| 测试 | 钉住什么 |
| --- | --- |
| `AiSettingsModelTest`（5） | 缓存 stats 读取、读取失败显示 LoadError、清除成功/失败、清除后重新读占用、MB 格式化不依赖 locale |
| `LicensesCreditsTest`（2） | 7 个手工条目含许可证 id 与 scm url；全部引用到的许可证都有定义 |
| `AiTranslationRepositoryTest`（+） | 空响应映射为 `EMPTY_RESPONSE`，不再复用 `UNKNOWN` |

**已知限制（S9 收尾时确认）**：

- 没有实现单词级 AI 解释：取词卡片里的按钮仍是禁用态（`(S9)` 文案改为中性说明），需要时可在后续阶段接 `kind=WORD` 缓存。
- `.mdd` 资源包不导入、正文图片/音频不渲染；导入的 MDX 只有文本。
- 加密（需要密码）的 MDX 不支持；`Encrypted=2` 的快速加密可读（引擎能力），密码保护仍拒绝。
- 长按选区的自动取词只处理单行；工具栏「翻译」可翻整段多行选区。
- 缓存跨进程重建只有 JVM fake 验证，S7 的“关库重开再命中”仪器测试仍未写。
- `release-debug` 上的真实 AI 流式/Tink 解密/Room 缓存路径只做了构建与静态验证；模拟器只跑了 DAO/迁移/Tink/Typography。
- 词典查询/翻译缓存没有按书清理入口；缓存清理只有设置页全量清除。
- 默认 `doubleClickTranslation = true` 会让分页单击翻页多等约 300ms；可在设置关闭。

### 3.10 第二阶段后续修复（设备反馈）

| 问题 | 修复 |
| --- | --- |
| 取词卡片多个按钮文字完全相同 | 卡片原本用 `dictName` 当 chip 文案，ECDICT/导入词典的前缀候选全显示成同一个词库名。改为显示 `entry.word`；同一个词来自多部词典时才追加 `· dictName`。 |
| OpenCode Go / 部分网关返回 HTTP 400 | 服务端要求 `x-opencode-session`，否则返回 `MissingSessionID`。新增每安装稳定的 `ai_session_id`（UUID，存 DataStore），所有 AI 请求带 `x-opencode-session`；普通 OpenAI 兼容服务忽略该头。用 `curl` + 占位 Key 验证：无头 400 `MissingSessionID`，有头 401 `Invalid credential`（说明已进入鉴权阶段）。 |
| 推理模型 400 / 兼容端点无 `/models` | 翻译/语法请求不再显式传 `temperature`（o1/o3/gpt-5 会拒绝）；`testConnection()` 在 `/models` 返回 400/404/405 时回退到最小 chat 请求；语法 `response_format=json_object` 遇 400 时去掉该字段重试一次（**该字段已在 §3.11 移除，回退一并删除**）。 |
| 选模型但 Base URL 不匹配 | AI 设置页新增“服务预设”（OpenAI/DeepSeek/Gemini/OpenRouter/OpenCode Go/Ollama），一次写入 Base URL + model。 |
| 空响应文案 | 新增 `AiError.EMPTY_RESPONSE`，不再显示“未知错误”。 |

新增测试：`x-opencode-session` 头断言、`/models` 400 → chat 回退。当前 `:app:testDebugUnitTest` **358 个用例**；release-debug 重新构建后安装到 TB322FC。

### 3.11 语法分析 UX 改动（逐句 + Markdown）

| 项 | 内容 |
| --- | --- |
| 提示词 | `AI_PROMPT_VERSION` 1 → **2**；`grammarSystemPrompt` 从「返回 JSON schema」改成「分析用户给的这一句、用短标题 + 要点讲主句结构/从句/易漏语法点/重要搭配」。版本号进缓存键，v1 的 JSON 缓存整批失效 |
| 仓库 | `AiTranslationRepository.analyzeGrammar` 去掉 `response_format=json_object` 与 400 回退重试，直接把 Markdown 原文作为 `Result<String>` 返回；空 content 仍是 `EMPTY_RESPONSE`。删除 `GrammarAnalysis`/`parseGrammarAnalysis`/`parseDispatcher`/`startGrammarProbe` |
| 切句 | 新增 `data/ai/SentenceSplitter.splitIntoSentences`（纯 JVM，`java.text.BreakIterator.getSentenceInstance(Locale.ENGLISH)`，trim + 丢空）。已知限制：无缩写词典，`Dr. Smith` 会在 `Dr.` 后断开，不做后处理 |
| 状态机 | `TranslationState` 新增 `sentences` / `selectedSentenceIndex`；`AsyncGrammar.Success(text, fromCache)` 就是 Markdown 原文。`open` 只切句；`selectTab(GRAMMAR)` 多句不请求、单句自动分析；`selectSentence(index)` 同句收起、异句展开并分析；结果按句算 `translationCacheKey`，本次打开另有一份内存 map（收起再展开零请求）；切换句子不取消上一句请求，迟到结果只能进缓存（发布前校验 requestId + 当前展开的是哪一句）；`regenerate(GRAMMAR)` 取消当前句在途请求后重发并覆盖同键 |
| 渲染 | 新增 `ui/reader/MarkdownText`：`org.commonmark` AST → `AnnotatedString`（标题加粗放大、列表前缀与缩进、粗体/斜体、行内代码、围栏代码块、引用竖线、块间空行），颜色来自 `LocalReaderPalette`，纯 JVM 可测 |
| 对话框 | 语法标签 = 句子卡片列表（编号、最多两行、选中高亮、点句子行展开/收起，结果区不在点击范围内）；多句未选时显示「选择一句开始分析」；结果区显示 loading / Markdown + 缓存标记 / 错误 + 重试；「重新分析」只作用于选中句；复制/分享用 Markdown 原文。新增文案 `translation_dialog_sentence_pick`/`translation_dialog_reanalyze`（en/zh），删除 JSON 时代的 5 条语法文案 |
| 测试 | 新增 `SentenceSplitterTest`（8）、`MarkdownTextTest`（11）；`TranslationModelTest` 语法用例重写（21 → 27 个用例：多句零请求、点哪句分析哪句、逐句键、收起/切换、迟到响应不覆盖、单句自动分析、重新分析、关闭复位）；`TranslationModelCacheKeyTest` 改为「open 只读翻译键 + 语法读句子键」；`AiPromptsTest`/`AiTranslationRepositoryTest` 改为断言 Markdown 请求（无 `response_format`、400 不再重试）；删除 `GrammarAnalysisTest`（9） |
| 验证 | `:app:testDebugUnitTest` **379 个用例 / 55 类 / 0 失败**（含并行的取词卡片改动新增用例）；`:dict-engine:test` 102 绿；`:app:assembleDebug`、`:app:assembleRelease-debug`（R8 + lintVital）通过 |
| 未验证 | 句子列表的点击/收起、长列表滚动、Markdown 排版都没有在设备上走过（项目无 Compose UI 测试依赖）；`BreakIterator` 在 Android(ICU) 上的切句细节（尤其缩写）与 JVM 可能不同 |

**MDX 1.2 支持（设备反馈后续）**：用户导入的牛津高阶第 9 版是 `GeneratedByEngineVersion="1.2"`，引擎原先只按 2.0 的 40 字节前导/8 字节字段解析，误报“文件已损坏”。现已按版本切换 1.2 的 16 字节前导、4 字节字段、未压缩 key-block-info；`MdxV12FixtureTest` 覆盖小样本，真实 OALD 1.2 验证 246,787 词条可打开、`apple` 可查。`.mdd` 资源仍不导入。

### 3.12 取词卡片排版重构 + ECDICT 元信息 + 高亮锚点修复（设备反馈）

| 项 | 内容 |
| --- | --- |
| 卡片结构 | `ReaderLookupPopup` 改成三层，**只有正文滚动**：固定头部（词头/音标/来源/元信息矩阵/多词典 chip）、可滚动正文（`weight(1f, fill = false)`，内容短时卡片跟着收缩）、固定按钮区（未找到/错误）。以前按钮和正文在同一个 `verticalScroll` 里，词条一长按钮就被滚走 |
| 词条排版 | 新增 `ui/reader/ReaderLookupArticle`（纯 JVM 可测）：丢弃与标题重复的 `<b>词</b>`、音标提到头部、义项按「词性/领域标签 + 释义」两列排、英文 WordNet 释义放分隔线下的次要小节、词形说明放文末。只拆「仅含行内标签」的词条；块级标签/未知标签退回 `fromHtml` |
| ECDICT 资产 | `prepare_ecdict.py` 在词条里埋两个不可见标记：`<!--ecdict|c=柯林斯|o=牛津|t=考试标签|b=BNC|f=当代语料库-->` 与 `<!--def-->`（英文释义起始）；`ECDICT_ASSET_VERSION` 1 → **2**。新资产 **21,691,246 字节**（+579 KB），SHA-256 `c93fec78…7ad3`，`NOTICE.txt` 与 `BuiltInDictionaryAssetTest` 同步 |
| 高亮错位（真因两条） | ① `ReaderTextLayoutRegistry` 原先用 `boundsInWindow()`：它会被祖先（LazyColumn 视口）裁剪并夹到根节点尺寸内，段落滚出屏幕一半时原点被钉在视口边上，局部→窗口换算与锚点整体偏移。改为 `positionInWindow()` + 节点尺寸，注册表存**未裁剪**矩形。② 长按取词把 `showMenu` 的选区矩形当锚点：它是「选区包围盒 + 底部 `4 × HandleHeight` + 裁剪到可见区」，带标点/靠边的词必偏。改为用注册表命中的**精确词矩形**；探测点用选区顶边而不是几何中心（中心会被手柄内边距推到下一行） |
| 弹窗窗口 | 改为**全屏 Popup + 透明遮罩**：卡片内部用 `offset` 摆放（自己 `onSizeChanged` 量尺寸），外部点击吃到遮罩里关闭；窗口刻意**非 focusable**，返回键用 `BackHandler`。原因：wrap-content 窗口靠 ACTION_OUTSIDE 接外部点击，而选区手柄/系统 ActionMode 都是带 `WATCH_OUTSIDE_TOUCH` 且叠在卡片之上的独立窗口，会把外部点击截走（真机复现：长按取词后卡片关不掉） |
| 卡片定位与展开 | **方向只看空间**：`lookupPopupPlacement` 比较词上/下方剩余空间，取大的一侧，与卡片高度无关（所以不会「先朝下再翻上去」）；**贴词那条边固定**：`lookupPopupPosition` 在词下方时钉顶边、在上方时钉底边，卡片长高只有远端在动。几何上恒成立：两侧取大者 ≥ 50%·安全区高 > 45% 的上限，卡片不会被夹回来 |
| 尺寸变化动画 | 卡片内容一律按**最终尺寸**排版，外层 `Box` 用 `clip(卡片圆角) + animateContentSize(180ms, alignment = 贴词侧)` 让容器长到位 —— 内容是「从贴词那侧展开」，动画期间不重新测量子内容（富媒体 WebView 不会每帧重排）。位置在 `Modifier.offset { }` 的 lambda 里算，动画期间只重排不重组 |
| 富媒体正文高度 | 加载完成后用 `contentHeight × scale` 量一次（`DictionaryArticleWebView.onContentHeight` 检查 0/90/260ms 取最大值、每篇词条只报一次，之后的图片懒加载不再重测），卡片是「内容多高就多高，最多到上限」；量不出来（800ms 兜底）则退回「吃满剩余高度」 |
| 切词典时尺寸不动 | 换命中（点词典 chip）后新的富媒体词条要先把正文量出来，这期间卡片高度**冻结**在「上一次内容确定时」的尺寸（`Modifier.layout` 里把内容也按冻结高度测量，否则内容照旧撑大等于没冻），正文在卡片内裁剪；高度一到再一次性变（真机日志：`1254 → 1254(mediaH=0) → 1368`，中间没有过渡尺寸） |
| 长按取词（已修复） | Compose 1.9 默认启用新上下文菜单，绕过项目自定义的 `LocalTextToolbar`：升级后长按选中后的自动取词（`onSelectionSettled`）**不会触发**，选区工具栏也只剩系统默认项。修复：`Application.onCreate()` 首行把 `ComposeFoundationFlags.isNewContextMenuEnabled` 置 false（官方临时回退开关，早于 Compose 代码加载），`SelectionManager` 重新走 `textToolbar.showMenu(...)`，整条链路恢复；`ComposeCompatibilityFlagsTest` 钉住。迁移到 `text.contextmenu.provider` 后可删 |
| 测试 | 新增 `ReaderLookupArticleTest`（12 个用例：新旧 ECDICT 拆分、块级回退、链接保留、元信息容错、词性标签、释义词性拆分、标记不泄漏）与 `ReaderLookupSelectionTest`（4 个用例：跨行判定/裁剪/行宽未知）；`BuiltInDictionaryAssetTest` 改为新 SHA/大小 |
| 验证 | 三重门禁通过（`assembleDebug` / `testDebugUnitTest` / `assembleRelease-debug`，**395 个用例 / 57 类 / 0 失败**）；真机（同一台 TB322FC）用 `dev.jia.reader.debug` 验证：资产 v2 自动解压（`version=2`）、`perceive`/`completely`/`upstairs` 卡片排版与高亮对齐、NotFound 卡片三个按钮一页可见、外部点击/返回键关闭卡片 |

**第二轮设备反馈（同一轮工作内）**

| 项 | 内容 |
| --- | --- |
| 高亮仍偏 | 最终方案：注册表在布局时用**同字号、同字体**的 `Paint.getTextBounds` 量词本身的墨迹 bbox（`FontFamily.Resolver.resolveAsTypeface` 取 Typeface），再按 `getLineBaseline` 贴到词上。行框方案随行高变胖；「基线 ± 0.72/0.22em」对无下伸字母的词（`crack`）底部留白、上伸字母被切。真机像素复测：`crack` 上 3 / 下 4px，`suddenly` 上 4 / 下 4px，`perceived`（有下伸字母）同样贴合 |
| 点击无反馈 | 单击取词被双击判定延迟 ~300ms。`detectTapGestures(onPress = ...)` 里先命词并写入 `DictionaryLookupModel.previewAnchor`，只画高亮；`onTap` 落地后才查词，卡片稍后出现；手势取消/双击翻译时撤掉预览。两种布局都接 |
| 高亮横向错位（两端对齐） | 用户复现 `through`：高亮选到上一个词的末尾 `e`、漏掉最后的 `h`。真因：Android 的 `getPrimaryHorizontal` 返回**未拉伸**坐标，而绘制时两端对齐会把词间空格拉宽（实测 20pt/行宽 1804px 时，行尾词偏左 ~35px，越靠行尾越大）。修复：按「词前空格数 × 每间隔拉伸量」把词矩形右移，命中点先按同量左移再取词；非 Justify/段落末行差值为 0 不校正。真机复测 `through` 高亮左 3px/右 6px 贴合，点行尾词的右半也能命中正确单词 |
| 候选 chip 滚动 | 多词条候选从横向滚动改成 `FlowRow` 换行；同时把 Material3 `FilterChip`（固定 32dp 高）换成 ~24dp 的紧凑胶囊，否则 5 个前缀候选取代后会把固定头部撑掉半个卡片。候选组与上方元信息胶囊之间补 10dp + 发丝分隔线 + 10dp（两者都是胶囊，原来挤在一起） |
| 英文释义词性 | `LookupDefinition(pos, html)`：每行开头的 `n./v./a./s./r.` 解析成独立词性，渲染为浅灰底 + 粗体小徽标（与中文义项的彩色词性标签区分主次） |

---

### 3.13 第四轮设备反馈：高亮留白、翻译对话框、Kindle 分区、翻页延迟

| 项 | 内容 |
| --- | --- |
| 高亮留白 | `ReaderScaffold` 画高亮时四周各放宽 1.5dp（卡片定位仍用未放宽的矩形）。上一轮修的是横向对齐，这轮只改观感 |
| 翻译对话框 | 刚打开/等待首个 token 时是一张 **360dp 宽紧凑卡片**（标题 + 标签 + 居中加载行）；首段流式文字/结果到达后 `animateContentSize` 平滑展开到最大 600dp 宽、80% 高，超出后正文滚动。模型与目标语言从滚动正文挪到标题下的固定副标题，标题/标签与正文之间加分隔线。`AsyncText.Idle` 不再渲染空白，按 loading 画 |
| 点击分区 | 默认改成 **Kindle 式**：顶部 12% 呼出菜单、左侧 25% 上一页、其余 75% 下一页；旧的「左/中/右」被替换（存量 `SIDES` 按名字找不到自动回落默认，完成迁移），「上/中/下」「仅中间」保留 |
| 翻页延迟 | 调查结论：与文件大小无关，是「双击翻译判定 300ms」+「默认 spring 翻页动画 ~416ms」叠加。修复：阅读页 `LocalViewConfiguration.doubleTapTimeoutMillis` 300→200ms；`animateScrollToPage` 改用 200ms tween。真机实测「按下→onTap」300→**208ms**、「onTap→落定」416→约 **250ms**。剩余等待是双击窗口本身，关掉双击翻译可完全即时 |
| 设备验证 | 高亮留白、Kindle 分区、翻页时序均在 Lenovo TB322FC 上实测；翻译对话框的最终视觉复测被设备锁屏/熄屏中断（截屏全黑），代码与门禁已通过，待设备解锁后补一次目视确认 |

### 3.14 长按取词恢复 + 语法分析流式

| 项 | 内容 |
| --- | --- |
| 长按取词（根因） | Compose 1.9 的 `SelectionManager.updateSelectionToolbar()` 在默认开启的 `ComposeFoundationFlags.isNewContextMenuEnabled` 下走新上下文菜单，**完全不读 `LocalTextToolbar`**：阅读页自定义的 `SelectionToolbar.showMenu` 收不到回调，`onSelectionSettled` 永不触发，长按自动取词与选区的「翻译/词典/分享/搜索」一起失效 |
| 长按取词（修复） | `Application.onCreate()` 第一行调用新增的 `applyComposeCompatibilityFlags()`，把该 flag 置 false（早于任何 Compose 代码加载）。`updateSelectionTextToolbar()` 恢复调用 `textToolbar.showMenu(rect, …)`，链路回到 1.8 及以前：长按选区 → 350ms 防抖 → 注册表按选区顶边反查单词 → 取词卡片。语义不变：只有手势设置 `LONG_PRESS` 时才自动取词。代价：全局回落到旧上下文菜单（平台 ActionMode），迁移到 `text.contextmenu.provider` 后可删。`ComposeCompatibilityFlagsTest` 钉住 |
| 语法流式（仓库） | `analyzeGrammar()` 由 `suspend fun … : Result<String>` 改为 `fun … : Flow<String>`，与 `translate()` 共用 `chatCompletions()` + `STREAMING_REQUEST_OPTIONS`；仍不发 `response_format`。判空从仓库移到状态机：空结果 = `AiError.EMPTY_RESPONSE`，不落库 |
| 语法流式（状态机） | 新增中间态 `AsyncGrammar.Streaming(text)`；按 `GRAMMAR_STREAM_PUBLISH_STEP`（32 字符）攒块推送，避免每个 token 都重解析 + 重排版 Markdown；写完才 `put` 缓存并推 `Success`。片段另存 `grammarStreaming`：写到一半收起再展开能续显，且不重发请求（`inFlight` 去重仍生效，`regenerate` 会丢掉旧片段） |
| 语法流式（UI） | `AsyncGrammar.Streaming` 用 `MarkdownText` 渲染已到达部分，不显示复制/分享/重新分析（内容不完整）；没写完的 Markdown 自然退化成纯文本 |
| 测试 | `AiTranslationRepositoryTest`：SSE 语法请求（断言 `Accept: text/event-stream`、`"stream":true`、不发 `response_format`）+ 空流不发 delta（`chatCompletion` 非流式 helper 随之删除）；`TranslationModelTest`：长答案先出片段、空结果 = `EMPTY_RESPONSE` 且不落库、收起再展开续显片段（27 → 30 个用例）；`TranslationModelCacheKeyTest` 与既有语法用例改为 `flowOf(...)` 桩；新增 `ComposeCompatibilityFlagsTest` |
| 验证 | `:app:testDebugUnitTest` **399 个用例 / 58 类 / 0 失败**；`:app:assembleDebug`、`:app:assembleRelease-debug`（R8 + lintVital）通过 |
| 未验证 | 长按取词与语法流式的**真机交互**尚未复测（当前无连接设备）：JVM 侧只钉住 flag 赋值与状态机；Markdown 逐块重排的观感、低端机上的滚动流畅度待设备确认 |

---

### 3.15 长按取词二次修复（设备反馈）

| 项 | 内容 |
| --- | --- |
| 现象 | ① 长按先选中一个**错的**词、再叠加卡片高亮的**正确**词，两块高亮混在一起；② 点旁边关掉卡片后约 1 秒自己又弹出；③ 拖动选择手柄也触发取词 |
| 根因 | 长按取词当时借用 `SelectionContainer` 的选区：Compose 的 `SelectionAdjustment.Word` 与正文注册表（`getOffsetForPosition` + 两端对齐 `justificationShift`）不是一个坐标系，系统选区会选到隔壁词；卡片再弹是因为 `showMenu` 被重报（焦点回来、`hide()` 后 `SelectionManager` 再次 `showToolbar`、选区变动），拖手柄同理每次都算「新选区」 |
| 修复 | 两种布局**唯一**的 `detectTapGestures` 加 `onLongPress`：`LONG_PRESS` 下按住直接按注册表取词（锚点 = 卡片用的精确词矩形）；阅读页 `SelectionContainer` 新增 `selectionEnabled`，长按取词模式下用 `DisableSelection` 把正文挡在选区之外 —— 没有选区就没有第二块高亮/手柄/ActionMode 重报 |
| 删除 | `onSelectionSettled`、`rememberLookupSelectionHandler`、`spansMultipleLines`、`WordHit.lineWidth`、`ReaderLookupSelectionTest`（这条链路整体不再存在） |
| 语义 | `SINGLE_TAP`/`OFF`：长按仍是普通选区（复制/分享/搜索/翻译/词典照旧）；`LONG_PRESS`：长按归取词，正文不可选（设置页文案已说明）；`OFF` 不接管任何手势。按下预览改为「取词开启就亮」，长按模式短按抬起即撤预览 |
| 语法流式首块 | 第一块 delta **立刻渲染**（不再攒到 32 字符）；仓库层给 `translate`/`grammar` 两条流各加一条时间线日志（logcat tag `AiRepository`：`first chunk Xms, chunks=N, total=Yms`），用来区分「服务器/模型首块慢」与「客户端渲染慢」 |
| 思考阶段（实测） | 真机 logcat：翻译 `first SSE event 2146ms → first content 5805ms`，43 事件 / 1509 字 reasoning / 98 字译文；语法 `1206ms → 35010ms`，545 事件 / **8608 字 reasoning** / 1429 字正文。结论：流是通的，时间全在模型思考上（`delta.reasoning_content`），不是渲染也不是网关攒包 |
| 思考强度 | 新增 `AiReasoningEffort`（默认 **OFF** → `reasoning_effort: "none"`；`DEFAULT` 不发送，给不认该值的端点留退路；另含 LOW/MEDIUM/HIGH）+ 设置页「思考强度」chips；缓存键不含它，日志里带实际档位（`grammar[off]`） |
| 档位实测 | 默认（不发送）：首段正文 35–42s / reasoning 8–10k；`low`：11.6–23s / 2–4.9k；`minimal`：**被静默忽略**（不报 400，思考回到 7.9k、42s）；`none`：**reasoning 0 字符**、首段正文 0.8–3.4s（5 次请求全部如此）。结论：`none` 才是真关思考，默认已改成它；剩余延迟是网关排队/首包 |
| 思考提示 | 仓库用 `onThinking` 回调把 reasoning 累计字数交给 `TranslationState.thinkingChars`，loading 文案变成「模型思考中… N 字」，>3s 无输出则「等待模型响应… N 秒」；第一块 delta 立即渲染 |
| 手柄残留 | `DisableSelection` 的位置错了：它只把 `LocalSelectionRegistrar` 置空，而 `SelectionContainer` 内部会再 provide 一层新的 registrar，套在外面等于立刻被覆盖。挪到 `SelectionContainer` 的 content 内层后，`LONG_PRESS` 模式下正文彻底不可选中（无高亮/手柄/ActionMode） |
| 语法标签排版 | 句子卡片去掉 `maxLines = 2`（长句完整显示）；分析结果从句子卡片搬出，独立成卡：标题「分析」+ 发丝分隔线 + 不同底色，句子（选哪句）与分析（答案）不再共用一块底色 |
| 边缘快速翻页 | 正文区左右各 10% 的点击跳过双击判定直接翻页：在 `detectTapGestures` 之后追加一个 `pointerInput`（链上更内层、Main pass 先跑），带内 + `tapActionFor` 为 NEXT/PREVIOUS 才继续，再按取词模式分流：`SINGLE_TAP` 命中单词→放手给取词、空白→`down.consume()` 抢占后松手即翻；`LONG_PRESS`/`OFF`→down 不消费，用 `withTimeoutOrNull(longPressTimeout)` 等抬手，单击立刻翻页、超时（长按）放手给外层。外层 `onTap` 在带内跳过翻页（否则一翻两页），`onDoubleTap` 在带内忽略。翻页动作抽成 `turnPage()` 与分区点击共用；菜单带/中间区域/连续布局不变。迭代两轮：第一版消费了所有边缘 down，导致边缘长按取词失效、单击单词不取词 |
| 验证 | `:app:testDebugUnitTest` **399 个用例 / 58 类 / 0 失败**（删掉 4 条选区用例，新增思考强度/思考回调用例）；`:app:assembleDebug` / `:app:assembleRelease-debug` 通过；`reasoning_effort=low` 真机复测：语法首段正文 35.0s → 11.6s，思考 8.6k → 4.6k 字符 |

### 3.16 ECDICT 英英释义的无点词性（数据 vs 转换的归因）

| 项 | 内容 |
| --- | --- |
| 现象 | 取词卡片的英文释义小节里词性徽标时有时无：`one` 是 `[n.] the smallest whole number…`，`0` 却是 `· n a mathematical element…`（那个圆点是 `ReaderLookupPopup` 里 `pos == null` 的占位符，不是数据里的点） |
| 归因 | **上游 ECDICT 数据问题，不是 `prepare_ecdict.py`**。脚本对 `definition` 只做 `html.escape`。钉死的 `ecdict.csv`（SHA-256 `d1ecbdf9…43cc`）里两套写法互不混用：带点 `n.`/`v.`/`a.`/`s.`/`r.` 共 92,184 条（WordNet 3.0 + 韦氏 1913），不带点 `n`/`v`/`a`/`s`/`r` 共 45,045 条（另一条 WordNet 抽取流水线，覆盖屈折形式：`is` = `v have the quality of being…` 与 `be` = `v. have the quality of being…` 同源）。字节级证据：`0` 的定义是 `6e 20 61…`（`n` + 空格），`one` 是 `6e 2e 20…`（`n.` + 空格）；全为无点的词条里 `^[nvasr] ` 行有 95k 行，全为带点的词条里同类残行 **0** 条，所以按行放宽不会误伤 |
| 修复 | `ReaderLookupArticle` 新增 `BARE_DEFINITION_PREFIX_REGEX = ^([nvasr])\s+(\S.*)$`，`splitDefinition(line, allowBarePos = false)` 加开关，只在对 `inDefinitions`（`<!--def-->` 之后）时置 true：无点行也出 `[n.]` 徽标。`<!--def-->` 只有内置 ECDICT 资产会写，导入词典走的还是 `looksLikeEnglishDefinition` + 严格点号，`a piece of land` 这类正文不会被当词性 |
| 有意不修 | ① 同一批数据还有约 15 条残行（`O.K.` → `k an endorsement`、`Ph.D.` → `d a doctorate`、`i.e.` → `e that is to say`），`k/d/e/o` 不在白名单，仍按正文显示——这是上游解析残留，属数据问题；② 数据本身仍然缺点号，要彻底干净得重跑 `prepare_ecdict.py` 归一（`^(n|v|a|s|r)\s` → `\1. `）并联动 `NOTICE.txt` 的 SHA、`BuiltInDictionaryAssetTest` 常量、`ECDICT_ASSET_VERSION`，本轮不动资产 |
| 测试 | `ReaderLookupArticleTest` 12 → 13 个用例（无点释义拆分、白名单外残行、未打 `<!--def-->` 的导入词典不享受放宽） |
| 验证 | `:app:testDebugUnitTest` **400 个用例 / 58 类 / 0 失败**；`:dict-engine:test` 102 绿；未跑真机 |

### 3.17 大屏上「锁定竖屏」无效（Android 16 定向策略，不是代码 bug）

| 项 | 内容 |
| --- | --- |
| 现象 | 平板上阅读页「屏幕方向」的六个 chip（默认/自由/竖屏/横屏/锁定竖屏/锁定横屏）点了全部没反应；手机正常 |
| 归因（代码侧全部正常） | `SettingsManager.screenOrientation`（DataStore `screen_orientation`）持久化正常；`ReaderScreen.kt:445` `activity.requestedOrientation = settings.screenOrientation.lastValue.code`；`Setting.lastValue` 就是 `_value.value`（不是滞后值）；manifest 里没有 `android:screenOrientation`，全局也没有第二处改 `requestedOrientation` |
| 真因 | `app/build.gradle.kts` `targetSdk = 36`（上游 `1772d9e7` 从 35 抬上来的）+ Android 16 新行为：targetSdk 36 的应用在**最小宽度 ≥ 600dp** 的屏幕上被忽略 `screenOrientation` / `setRequestedOrientation()` / `getRequestedOrientation()` / 宽高比限制，全屏与多窗口都一样。测试机 TB322FC：1904×3040 @440dpi → sw ≈ **692dp**，正好踩中。官方例外只有游戏（`appCategory`）、用户在系统「应用宽高比」里手动选择按应用默认行为、以及 `sw < 600dp` 的屏幕 |
| 修复 | `MainActivity` 加 `<property android:name="android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY" android:value="true"/>`；合并后的 manifest 已确认带上（`app/build/intermediates/merged_manifest/debug/...`），不引入任何代码改动 |
| 时限 | Android 17（API 37）**删除了这个 opt-out**，`sw≥600dp` 上永久失效；抬 targetSdk 前必须把大屏上的方向 UI 换掉（隐藏 chip / 改成应用内提示）。已写进 `REBASE.md` §4 与 `UPSTREAM_DIFF.md` §2.3 |
| 上游状态 | `upstream-base`（`464891ec`）的 manifest 同样没有 `<property>`，所以这是上游缺陷（§2.3），fork 只是先修 |
| 测试 | 新增 `ReaderOrientationManifestTest`（2 个用例：MainActivity 必须带该 property；`ReaderScreen` 必须继续写 `requestedOrientation` 并在离开时复位 `UNSPECIFIED`）——rebase 吞掉 property 时直接失败，不用等设备 |
| 验证 | `:app:assembleDebug` / `:app:assembleRelease-debug`（R8 + lintVital）通过；`:app:testDebugUnitTest` **402 个用例 / 59 类 / 0 失败** |
| 未验证 | 真机行为未复测（当前无连接设备）。设备复测时建议同时跑：`adb shell dumpsys activity top \| grep -i requestedOrientation`（`SCREEN_ORIENTATION_PORTRAIT` 的常量值是 1）、`adb shell dumpsys window \| grep -i mLastOrientation`（加 opt-out 前后应发生变化） |

### 3.18 词典资源（MDD / CSS / 图片 / 发音）

| 项 | 内容 |
| --- | --- |
| 现象 | 导入的 MDX 词典只能看裸文字：排版（CSS/字体/表格）、图片、真人发音全在 `.mdd` 里，而导入入口是单文件 `OpenDocument`，拿不到同目录兄弟文件；引擎的 `MdxResourcePack` 是 `internal`，app 层没有读资源的出口。结果是牛津高阶这类词典的喇叭图标是空 `<img>` |
| 方案 | 三段：①引擎 public 包装 `MdxResources` / `openMdxResources` / `checkMdxResourcePack`；②新「导入 MDX 文件夹」（`OpenDocumentTree`，自动配对 `.mdd` 分卷）与 `DictionaryResourceStore`；③混合渲染——`hasResources && 命中 (img\|table\|stylesheet\|sound://)` 的词条切 WebView，其余继续走 Compose 结构化卡片 |
| 关键决策 | 混合而不是全量 WebView（ECDICT/StarDict 观感与性能不变）；资源句柄与查询引擎分离（拦截器在后台线程阻塞取资源，不能共锁）；`hasResources` 运行时由 `mddFilesFor` 推导，`index.json` 格式不变；发音优先级：词条自带音频 > 系统 TTS；安全基线：JS/DOM storage/`file://` 关闭、网络全封、只服务 `https://dict.local/<dictId>/`、剥 `<script>`/`on*`/`javascript:` |
| 导入 | 目录内恰好 1 个 `.mdx`；`.mdd` 编号必须连续（缺号 → `RESOURCE_PARTS_MISSING`）；单个 `.mdx` ≤ 512MB（`MAX_IMPORT_BYTES`），**总量 ≤ 2 GiB**（`MAX_IMPORT_TOTAL_BYTES`）；每个 `.mdd` 导入前 `checkMdxResourcePack` 校验（加密 → `ENCRYPTED`，损坏 → `RESOURCE_CORRUPT`）；UI 三个入口（MDX 文件 / MDX 文件夹 / StarDict 文件夹），带资源条目显示「含资源」 |
| 资源读取 | `DictionaryResourceStore`：阻塞 `read`（供 `shouldInterceptRequest`）按词典串行；内存缓存单条 256KB / 总量 8MB；句柄 LRU 2；发音落盘 `cache/dict-audio/` 上限 32MB（文件名是 dictId + 路径的 SHA-256 前缀 + 原扩展名）；打开失败负缓存；禁用/删除/重装即 `evictUnavailable()` |
| 渲染 | `LookupBody.Media` + `shouldUseMediaRenderer` + `DictionaryArticleWebView`；`LookupHtmlRewriter` 把 `sound://` 改成 `dict-audio://<dictId>/<encoded>`、`file:///…` 改成相对路径，剥 `<script>`/`on*`/`javascript:`/`vbscript:`（按属性名清，OALD9 有 158 个 `xhtml:a`），删 `<base>`/`<meta refresh>`/`<iframe>`/`<object>`/`<embed>`，`<style>` 只去 `@import`；卡片体内滚动，外层不套 `verticalScroll` |
| 发音 | `DictionaryAudioPlayer`（`MediaPlayer` + `AUDIOFOCUS_GAIN_TRANSIENT`，失焦即停）+ `DictionaryAudioController`（`AudioOutput`/`SpeechOutput` 状态机）：播文件前先停 TTS，点 TTS 前先停文件；多候选依次试，全失败才回落 TTS + toast；关卡片 `release()`，切词/换命中词典 `stop()`。口音：`DictionaryAudioAccent`（AUTO/US/UK，DataStore `dictionary_audio_accent`）+ 路径 token 分类器 |
| 口音校准 | 真实 OALD9：词条音 `apple__gb_1.mp3` / `apple__us_1.mp3`，例句音 `_apple__brs_2.mp3`（英）/ `_apple__uss_2.mp3`（美）/ `_apple__gbs_2.mp3`（英）/ `_apple__ams_2.mp3`（美）→ **`uss`/`ams` = 美、`gbs`/`brs` = 英**。匹配单位是完整 token（`business`/`usage`/`fuss` 不误判）；`AUTO` 不猜口音，取候选第一条 |
| 测试 | 新增 7 个文件 / 99 例：`MdxResourcesFixtureTest` 14（合成 MDD，含 `MdxPackCheck` 三分类）、`DictionaryImportPlanTest` 16、`DictionaryResourceStoreTest` 25、`DictionaryArticleWebViewTest` 8、`DictionaryAudioAccentTest` 16、`DictionaryAudioStateTest` 6、`LookupHtmlRewriterTest` 14；另补 `DictionaryImportErrorTest`（资源包分类映射）、`DictionaryLookupModelTest`、`ReaderLookupArticleTest`（媒体分流） |
| 门禁 | `:dict-engine:test`、`:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleRelease-debug`（R8 + lintVital）全绿 |
| 真机验证 | 见 §4 「已验证 ✅」新增的五条：1.85GB 文件夹导入、OALD9 WebView 渲染、词条发音、暗色预设下的 WebView、系统字号缩放 |
| 真机 bug（已修） | `loadDataWithBaseURL` 会把 base URL 本身送进 `shouldInterceptRequest`；根路径回 404 → 整篇变 `net::ERR_HTTP_RESPONSE_CODE_FAILED`。改为：根路径 / 非 `dict.local` 返回 `null`，只有「路径存在但包里没有」才 404 |
| 已知限制 | `.spx`（Speex）播不了，只能回落 TTS + toast；`<iframe>/<object>/<embed>` 直接移除而不是降级渲染；CSS 里 `url()` 不改写（根相对路径靠拦截器兜）；逐词典覆盖口音、图片点击放大、WebView 内长按选词未做 |
| 偏差 | ①计划外多了一个 `MdxPackCheck`/`checkMdxResourcePack`（导入需区分加密与损坏，`openMdxResources` 按设计吞包内失败）；②`textZoom` 不跟阅读正文字号，改为「词典 16px 基准 → 卡片正文 14sp」的固定映射（真机反馈跟阅读字号走时比 ECDICT 正文大一圈，已成为一条独立修复）并乘系统 `fontScale`；③WebView 销毁必须 `stopLoading()` → 换回默认 `WebViewClient` → `about:blank` → `destroy()`（直接销毁还在加载的实例会在内部线程 NPE）；④「加密 mdd」只做到分类 + 映射单测，没做端到端文件用例 |
| 文档 | `docs/dictionary-resources-design.md`（已实施）、`docs/phase-2-design.md` §1.2/§2/§4.3/§15、`UPSTREAM_DIFF.md` §3.10、`PROVENANCE.md` 第 4 条 |

### 3.19 按书排版（全局 / 本书）

| 项 | 内容 |
| --- | --- |
| 需求 | 每本书独立一套排版；没设置的书跟随全局。书内可显式切换「全局 / 本书」，两边各自记忆、可来回切；与全局不同的项给标记；一键恢复全局默认 |
| 范围 | 12 项：字体家族、字重、粗体字重、斜体、字号、行距、段距、字距、缩进、对齐、左右/上下边距。页眉/图片/进度条/系统项不覆盖 |
| 语义 | 整组覆盖：第一次切到「本书」时快照当前全局值（12 项完整）；切回全局只 `enabled=false`，记录保留；再切回恢复记忆值；恢复默认＝删除记录并回全局（二次确认） |
| 存储 | DataStore 单键 `reader_typography_by_book`，JSON 对象以书 `uuid` 为键；可空字段、未知枚举、单条坏记录都逐项容错；整串损坏保留原始值、下次写入自愈 |
| 并发 | 内存同步更新（避免切换作用域时滑块写到错误目标、reset 后残留回调复活记录）；磁盘 200ms 合并、锁外写盘、写失败只记日志；读取完成前的写入先排队，绝不先写空表 |
| 解析层 | 纯函数 `ReaderTypographyResolver`（全局+记录→生效值、差异集合，字体 id 通过 `resolveFont` 注入）；`LocalReaderTypography` 统一 12 个控件的数据源，全局设置页注入全局态、阅读页注入本书态 |
| 模式分流 | actions 按当前模式写：全局模式写 `SettingsManager`，本书模式写 Store。只按「有无记录」判断会让全局模式下的滑块静默把书切进本书模式，已规避 |
| UI | 排版 Tab 顶部固定选择器（在样例预览上方）；Tab 1 顶部紧凑版；本书模式下与全局不同的项在左侧留白显示 6dp 圆点；「恢复全局默认」弹确认框 |
| 清理 | 删书 → `DeleteBookUseCase` 调 `store.remove(uuid)`；删用户字体 → `UserFontOption` 调 `store.removeFontReferences(fontId)`（否则差异标记会一直亮着） |
| 启动 | `ReaderTypographyStore.initialized` 加入 `MainActivity` splash 条件，避免首帧先按全局画再跳本书 |
| 测试 | 新增 3 文件 / 25 例：`ReaderTypographyCodecTest` 6、`ReaderTypographyResolverTest` 8、`ReaderTypographyStoreTest` 11（含同字段顺序、合并写次数、reset 不可复活、坏 JSON 自愈） |
| 门禁 | `:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:assembleRelease-debug`（R8 + lintVital）全绿 |
| 偏差 | ①「边距」不在排版 Tab，因此选择器在 Tab 1 的边距组上方放了一份紧凑版；②差异标记用包装层实现，不改 12 个控件与 3 个共享设置组件；③actions 在阅读页动态读模式写全局/本书，`BookTypographyActions` 只负责本书写入 |
| 评审修复 | 两轮独立 review（数据层 + Compose）后重写 Store：内存同步更新、200ms 合并落盘、锁外写盘、读取前写入排队；动态 `isBookMode` 关闭切换竞态；`update` 不再复活 reset 后的记录；`compositionLocalOf` 限制重组范围；字体列表加入 remember key；选择器改两行布局；差异点补无障碍说明 |
| 文档 | `docs/per-book-typography-design.md` |
| 真机验证 | 联想 TB322FC（Android 16）：装 `release-debug` → 打开《测试书 EPUB》→ 面板顶部切到「本书」→ 拖动字号，状态变「与全局有 1 项不同」、行首圆点出现、预览与正文同步放大；杀进程重装后仍是本书的 29pt；「恢复全局默认」二次确认后回到全局、按钮消失、字号回落 22pt。全程无崩溃 |
| 面板高度 | 实测 0.62 会把列表压到只剩一行（选择器 + 预览占掉约 200dp），排版 Tab 改为 0.74；「更多」保持 0.62、颜色 0.6 |

### 3.20 阅读器面板：翻译器独立 Tab

| 项 | 内容 |
| --- | --- |
| 需求 | 翻译器设置从排版 Tab 里拆出来，与排版并列，顺序放在最后 |
| 改动 | 「排版」页移除末尾的 `TranslatorSubcategory`，并把 `ProgressSubcategory` 收尾为无分隔线；新增第 4 页渲染 `TranslatorSubcategory(showTitle=false, showDivider=false)`；`HorizontalPager` 页数 3→4；Tab 顺序 **排版 / 更多 / 颜色 / 翻译器** |
| 行为 | 翻译器页沿用「更多」的遮罩与 0.62 高度；顶部菜单保持显示（仍只有颜色页隐藏菜单）；跨页滑动不受影响 |
| 验证 | 真机装 `release-debug`：4 个 Tab 顺序正确；翻译器页显示取词手势 / 双击翻译 / 词典管理 / AI 翻译设置；三重门禁通过 |

### 3.21 标题格式标记泄漏（第 20 章显示 {{B-START}}20{{B-END}}）

| 项 | 内容 |
| --- | --- |
| 现象 | 《One Way》第 20 章标题渲染成 `{{B-START}}20{{B-END}}`；只有标题内嵌 `<b>`/`<i>`/`<a>` 的章节会出现，纯文本标题正常 |
| 根因 | `JsoupDocumentParser.processDocument` 给 `strong/b/em/i/a` 统一插入段落格式标记；`processMarkedDocument` 的 Header 分支直接取 `getHeaderTitle`，没有像 Paragraph 分支那样清标记。`NodeContent.Header` 只存纯文本，标记于是被当成标题正文 |
| 修复 | Header 标题先 `Paragraph.clearMarkers(...)` 再 `trim()`；`ParsersData.JSOUP_PARSER` 2 → 3，使 EPUB/HTML/XML 的缓存内容失效，重开书时自动按新解析器重建，已读位置保留 |
| 测试 | 新增 `JsoupDocumentParserHeaderTest` 3 例：标题内 bold 清理、italic + link 清理、段落 bold 仍生成 style range |
| 真机验证 | 装 `release-debug` 后重开《One Way》：标题从 `{{B-START}}20{{B-END}}` 变为「20」，仍是标题样式；阅读位置保持 64.1% |
| 门禁 | `:app:testDebugUnitTest`、`:app:assembleRelease-debug`、`:app:assembleDebug` 通过 |
| 文档 | `UPSTREAM_DIFF.md` §2.4 |

### 3.22 翻译器行为改为按书（默认关闭）

| 项 | 内容 |
| --- | --- |
| 需求 | 翻译器行为不再作为全局设置，只能在书里开启，且只对本书生效 |
| 边界 | 按书：取词手势、双击翻译；全局共享：词典、优先后口音、AI 的 Base URL/模型/Key/目标语言/语法/推理强度 |
| 语义 | 所有书默认 `OFF` / `false`，不继承旧全局值；两者都关 = 本书不启用翻译器；选中文本后的复制/翻译工具栏保持原行为 |
| 全局入口 | 「设置 → 阅读器 → 翻译器」只保留「词典管理」「AI 翻译设置」两行，行为开关移除 |
| 书内入口 | 翻译器 Tab：分「本书设置」「全局词典与 AI」两段；说明 + 取词手势 + 双击翻译，分隔线后是全局说明与词典管理/AI 设置两个可点击条目 |
| 存储 | DataStore 单键 `book_translator_by_book`，`uuid → {lookupGesture, doubleClickTranslation}`；同步内存 + 200ms 合并落盘；删书联动清理 |
| 解析 | 缺失/类型错误/未知手势名一律回落 `OFF`（不复用旧的 `fromName` 的 `SINGLE_TAP` 回落） |
| 测试 | `BookTranslatorCodecTest` 6、`BookTranslatorStoreTest` 11、`ReaderLookupGestureTest` 改写 3；全量 561 例 0 失败 |
| 门禁 | `:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleRelease-debug` 全绿 |
| 真机验证 | 最后一轮改动后 USB 设备断开，书内 Tab / 提示跳转 / 跨书隔离尚未复验；已在 `docs/per-book-translator-design.md` §6 记录 |
| 评审 | 两轮独立 review（数据/状态 + Compose/UI）：仅 3 条 P2；已修 `FlowRow` 垂直间距、空 uuid 写入防御、补读取前排队测试。快速连点提示按钮可能重复 push 页面，明确记为已知 minor |
| 文档 | `docs/per-book-translator-design.md` |

### 3.23 提示图标内联 + 翻译器页分组

| 项 | 内容 |
| --- | --- |
| 问题 1 | 所有设置提示的图标被排成「图标一行、文字一行」，占一行空白且扫读时先看到大图标 |
| 修复 1 | `SettingsSubcategoryNote` 改为「浅色容器 + 行首图标」：`secondaryContainer` 圆角块内放 20dp 图标 + 权重文字，文字/图标用 `onSecondaryContainer`；全 App 12 处提示统一生效 |
| 问题 2 | 翻译器页把说明、开关、跳转入口混在一起，缺少层级 |
| 修复 2 | 书内翻译器 Tab 分两段：「本书设置」（作用域说明 → 取词手势 → 双击翻译）与「全局词典与 AI」（分隔线 → 共享说明 → 词典管理 → AI 设置）；说明文案缩短；删除自定义 `TranslatorGlobalConfigHint`，改用现有可点击条目（带箭头） |
| 验证 | 真机 `release-debug`：提示为带底色的圆角块、图标在行首；翻译器页两段标题清晰；默认「关闭」+ 双击翻译关闭；切到「单击」后关闭再打开面板仍是「单击」；词典管理与 AI 设置两个条目均能打开对应全局页 |
| 门禁 | `:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleRelease-debug` 通过（561 例 0 失败） |
| 已知行为 | 从书内跳到全局页返回后，阅读器面板不自动恢复（与既有词典入口一致） |

### 3.24 收藏夹（单词 / 句子）+ 提示块改浅灰

| 项 | 内容 |
| --- | --- |
| 入口 | 主页面（书架/历史/浏览）右上角 ⋮ → 收藏（`NavigatorBottomSheet`）；收藏页本身不在底部导航里 |
| 数据 | Room 22：`AutoMigration(21, 22)` 新建 `favoriteentity`；`(type, text)` 唯一索引 + `createdAt` 索引；收藏全局共享，不按书 |
| 单词 | 取词卡片头部新增五角星；收藏时保存词头（trim+小写）、音标、释义摘要（纯文本，最多 300 字）；收藏页默认标签显示词头/音标/解释/发音按钮，点击复用阅读页的 `ReaderLookupPopup`（带行级锚点） |
| 句子 | 语法分析成功后的操作行新增五角星；保存句子原文 + 语法 Markdown；收藏页句子完整换行显示，点击打开详情：原文、译文、语法分析 |
| 译文 | 单句段落收藏时若译文已成功则一并保存；多句段落只存语法，打开详情时按句补一次译文并回填数据库（失败显示可重试错误） |
| 五角星同步 | 三处（取词卡片 / 语法结果 / 收藏页）都订阅仓库 `Flow`；任意一处切换，其余立即同步 |
| 提示块 | `SettingsSubcategoryNote` 从 §3.23 的 `secondaryContainer` 改为中性浅灰（浅色 `#F2F2F2` / 深色 `#2B2B2B`），不再跟随主题强调色；12 处提示统一生效 |
| 并发 | `FavoritesModel` 的 toggle 用 `Mutex` 串行化「先查再写」，连点两下仍是收藏→取消；句子详情用 openSentenceKey + 取消旧 Job 丢弃晚到译文 |
| 测试 | 新增 `FavoritesModelTest`（6）、`FavoriteMapperTest`（6）、`ReaderLookupFavoriteTest`（2），并在 `ReaderLookupArticleTest` 补 `favoriteDefinition` 3 例；仪器测试新增 `FavoriteMigrationTest`（21→22 建表 + 旧数据存活 + 唯一索引） |
| 门禁 | `:app:assembleDebug`、`:app:testDebugUnitTest`（**578 例 0 失败**）、`:app:assembleRelease-debug`、`:app:compileDebugAndroidTestKotlin` 全绿 |
| 评审 | `reviewer` 两轮（`reviewer-expert` 因 provider 不可用未跑成）。第一轮：1 major（收藏页取词卡片漏传 `onAudioFile` / `onTtsUnavailable`，MDD 录音与 TTS 安装提示失效）+ 3 minor（`Rect.Zero` 锚点、toggle 竞争、缺状态机测试），全部已修。第二轮复核：仅 3 条 P2 测试强度建议（晚到用例被取消而恒真、连击用例未真正并发、硬编码灰色不跟高对比度），前两条已用「慢读库门闩 + 读后 yield」重写并跑绿；高对比度明确保留常量灰（当前对比度 ≥ 8:1） |
| 真机验证 | Lenovo TB322FC / Android 16 / 1904×3040，USB 覆盖安装 release-debug（54MB）成功；旧 Room 21 数据库启动时自动迁移到 22；⋮ 菜单出现「收藏」；`air` 取词卡片点五角星变实心，收藏页出现 `air /eə/ · n. 空气, 旋律, 态度; vt. 晾, 使通风, 夸耀` 与喇叭；点该行能从收藏页再次弹出取词卡片；force-stop 后重启收藏仍在；句子标签空态文案正常；深色主题下提示块实测背景 `#2B2B2B`、文字 `#D8D8D8` |
| 未做 | 句子收藏的「语法分析 → 五角星 → 详情对话框」在真机上未跑（需要一次 AI 请求，改由单测覆盖）；收藏页批量删除/搜索；收藏按书分组 |

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
| 导入 1.85GB 词典文件夹 | 从设备 `Download/词典/` 目录用「导入 MDX 文件夹」走完整 SAF 流程：1,864,845,709 字节（54MB mdx + 154MB `.mdd` + 518MB `.1.mdd` + 1.14GB `.2.mdd`）复制+校验成功，索引 `wordCount=246,787`、`sizeBytes=1,864,845,709`；条目显示「含资源」 |
| OALD9 WebView 渲染 | 查 `nightmare` 切到 OALD9 chip：`oalecd9.css` 生效（蓝色词头 `night·mare`、IPA、蓝底 NOUN 徽标、中文释义、斜体例句），喇叭 `<img>` 从 `.mdd` 取到；卡片体内滚动而阅读页不跟着滚 |
| 词条发音 | 点喇叭 → `cache/dict-audio/481c698b060e03e7.mp3`（含 dictId hash 的文件名），MediaPlayer/NuPlayer 播放，`requestAudioFocus` 由 `DictionaryAudioPlayer` 发起 |
| 暗色预设下的 WebView | 阅读配色切到暗色预设后重查 `nightmare` 并切 OALD9：卡片深底、正文浅字、蓝色词头/NOUN 徽标正常，没有出现「算法暗化把注入的浅色字反成深色」的双反转 |
| 系统字号缩放（fontScale） | `settings put system font_scale 1.3` 后重查 OALD9：WebView 正文（词头/IPA/NOUN/释义）与卡片标题同步放大，`textZoom` 乘了 `LocalDensity.current.fontScale` |
| 词条正文与 ECDICT 同字号 | 阅读预设为「大字」（22pt）时，修前 WebView 正文 `textZoom=137%` 明显大于 ECDICT 释义；改成「词典 16px 基准 → 卡片正文 14sp」固定映射后两者视觉一致，同一条 `nightmare` 能完整显示两条例项 |
| 按书排版 | 《测试书 EPUB》切「本书」→ 字号拖到 29pt：状态「与全局有 1 项不同」、行首圆点出现、预览与正文同步变化；全局设置未被改动；重装 APK 后重开仍是 29pt；「恢复全局默认」确认后回全局、字号 22pt、按钮消失 |
| 章标题格式标记 | 《One Way》第 20 章标题从 `{{B-START}}20{{B-END}}` 修复为「20」；解析器版本升级后自动重建缓存，64.1% 阅读位置未丢 |

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
- `.spx` 发音回落 TTS 的 toast（真实语料里未点过 Speex 词条）
- 内置 ECDICT 之外的无资源 MDX 的观感回归（本轮只验过 OALD9 与 ECDICT）

**建议下一步就在真机上把这 11 项过一遍**，尤其是书签跳转——那是本 fork 唯一修过两次 blocker 的功能。

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
| `docs/dictionary-resources-design.md` | 词典资源（MDD / CSS / 发音 / 图片）设计与实施记录（已实施；真机数据见 §3.18） |
| `docs/per-book-typography-design.md` | 按书排版（全局 / 本书）的语义、存储、解析层与测试（已实施；见 §3.19） |
| `docs/per-book-translator-design.md` | 按书翻译器行为、默认关闭、全局词典/Key 入口与测试（已实施；见 §3.22） |
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
4. 上游已转只读（`upstream` push URL = `no_push`），不再定期 rebase；仅在主动拾取上游修复（cherry-pick / 局部 merge）时按 `REBASE.md` 逐条检查
