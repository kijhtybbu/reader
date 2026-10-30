# juicedict 引擎（vendored）

本目录是 [qiuminal/juicedict](https://github.com/qiuminal/juicedict) 的引擎源码副本，
以 Gradle 模块 `:dict-engine`（`kotlin("jvm")`，纯 JVM，不依赖 Android）引入。
上游未发布 Maven 构件，因此按设计文档 §2/§4.1 以源码形式 vendoring。

| 项 | 值 |
| --- | --- |
| 仓库 | https://github.com/qiuminal/juicedict |
| commit | `7bbcb87404183c24cb102eede0aa9551eeb8241f` |
| 上游许可 | GPL-3.0（见同目录 `LICENSE`） |
| 模块路径 | `:dict-engine`（目录 `third_party/juicedict-engine/`） |
| 包名 | `com.qiuminal.juicedict.engine`（保持不变，便于与上游 diff） |

## 复制范围

### 主源码（逐字复制，未修改）

`app/src/main/java/com/qiuminal/juicedict/engine/` → `src/main/kotlin/com/qiuminal/juicedict/engine/`

```
Article.kt  ArticleParser.kt  ByteOps.kt  Compare.kt  DictDataReader.kt
DictionaryEngine.kt  DictZipReader.kt  EditDistance.kt  Ifo.kt  Morphology.kt
StarDict.kt  StarDictIndex.kt
mdict/Lzo1x.kt  mdict/MdxBlockReader.kt  mdict/MdxBytes.kt  mdict/MdxDictionary.kt
mdict/MdxFileReader.kt  mdict/MdxHeader.kt  mdict/MdxKeyBlockCrypto.kt
mdict/MdxKeyIndex.kt  mdict/MdxKeyOrder.kt  mdict/MdxLog.kt  mdict/MdxProbe.kt
mdict/MdxRecordStore.kt  mdict/MdxResourceFiles.kt  mdict/MdxResourcePack.kt
mdict/MdxWordIndex.kt  mdict/Ripemd128.kt
```

### 测试（逐字复制）

`app/src/test/java/com/qiuminal/juicedict/engine/` → `src/test/kotlin/com/qiuminal/juicedict/engine/`

```
ArticleLinkTest.kt  CompactIndexTest.kt  DictZipLastChunkTest.kt  EditDistanceTest.kt
StarDictSupplementaryTest.kt  StarDictSynTest.kt
mdict/Lzo1xTest.kt  mdict/MdxBytesTest.kt  mdict/MdxHeaderTest.kt
mdict/MdxKeyOrderTest.kt  mdict/MdxLinkRewriteTest.kt  mdict/Ripemd128Test.kt
```

测试资源：`app/src/test/resources/lzo/fixtures.txt` → `src/test/resources/lzo/fixtures.txt`
（`Lzo1xTest` 的 LZO1X 压缩样本，Base64 文本）。

## 本地改动

1. **新增** `src/main/kotlin/com/qiuminal/juicedict/engine/DictionaryEngines.kt`
   （唯一新增的 main 文件）：上游把 `MdxDictionary`、`MdxProbe`、`MdxResourceFiles` 都标为
   `internal`，StarDict 的组装逻辑写在上游 Android 层
   `com.qiuminal.juicedict.data.DictionaryRepository` 里，模块外无法构造引擎实例。
   该文件只提供 public 入口与数据类，逻辑照搬上游
   `DictionaryRepository.openStarDict` / `openMdx`（`.ifo` 解析、`.idx`/`.idx.gz` +
   可选 `.syn`、`.jidx` 缓存、`.dict.dz`/`.dict` 选择）：
   - `openStarDict(id, dir, baseName)` / `openMdx(id, mdxFile, mddFiles, title, description)`
   - `probeMdx(file)` / `probeMdxHead(headBytes)` → `MdxProbeInfo`
   - `mddFilesFor(dir, baseName)`：包装 `internal` 的 `MdxResourceFiles.pairsFor`
     （`<base>.mdd` 在前，`.1.mdd`/`.2.mdd`/… 按数字序在后），否则 app 层（S4 导入）
     只能自己重写这条配对规则或漏掉 `.1.mdd` 里的扫描件/音频。
   - `var logSink: ((tag: String, message: String) -> Unit)?`：转发到 `internal` 的
     `MdxLog.sink`；`openStarDict` 吞掉的 `Throwable` 也经它上报（仍返回 `null`）。
   - KDoc 明确：返回的引擎**不是**线程安全的（`StarDictIndex` 用普通 `HashMap`，
     `DictZipReader`/`PlainDictReader` 的 `seek`+`readFully` 非原子），调用方需按引擎加锁
     ——上游 `DictionaryRepository` 就是 `synchronized(mutex)`。上游 `DictionaryEngine`
     接口 KDoc 里「safe to use from several threads」的说法与实现不符，但为了字节一致
     不去改它。
   **没有改动任何解析文件。**

2. **新增 MDict 1.2 支持**（2026-10，Phase 2 后续）：
   `MdxKeyIndex` / `MdxRecordStore` / `MdxWordIndex` / `MdxProbe` 按 `GeneratedByEngineVersion`
   在 4 字节（1.2）与 8 字节（2.0）字段之间切换；1.2 的 key-block-info 不压缩、key 长度
   为 1 字节且无 NUL。新增 `src/test/resources/mdict/v12.mdx` 与
   `MdxV12FixtureTest`；用真实牛津高阶 9 版 1.2 MDX 验证过 246,787 词条可打开/查词。
   这是本模块最重要的本地行为扩展，升级上游时需保留/重新适配。

3. **修改** `src/test/kotlin/com/qiuminal/juicedict/engine/IndexCacheTest.kt`：删除了
   `matchRankOrdersExactPrefixFuzzy` 一个测试方法及其
   `import com.qiuminal.juicedict.data.MatchRank`。该方法测的是上游 Android 层
   `com.qiuminal.juicedict.data.MatchRank`（候选排序等级，属于 app 业务层，不属于
   engine 源码集）；把它带进本模块就得连 `data/` 一起复制，那超出 vendoring 范围。
   app 层在 S3/S4 实现跨词典排序时会自行覆盖该行为。

3. **新增** 两个本地测试（上游没有，也不会冲突）：
   - `src/test/kotlin/com/qiuminal/juicedict/engine/DictionaryEnginesTest.kt`：适配层回归。
     StarDict 侧用临时目录现写 `.ifo` / `.idx` / `.idx.gz` / `.syn` / `.dict` 并断言
     `lookupSmart` 与 `article().toHtml()`；另覆盖 `.jidx` 缓存重开、`mddFilesFor` 配对、
     `logSink` 上报与「坏文件返回 `null`」。`.dict.dz` 分支留给 S4 导入真实词典（S3 的
     内置 ECDICT 资产是明文 `.dict`）。
     S3 追加：`.jidx` 缓存重开前先断言缓存文件存在、再删掉 `.idx.gz` 与 `.syn`，
     缓存路径一旦坏掉重开会返回 `null`，不会静默回退到重新解析原始索引。
   - `src/test/kotlin/com/qiuminal/juicedict/engine/MdxFixtureTest.kt`：测试运行时用
     `java.util.zip.Deflater` 合成一个合法 MDX 2.0（单 key block + 单 record block，
     含一条 `@@@LINK=` 重定向），跑通 `probeMdx` → `openMdx` → `lookupSmart` →
     `article().toHtml()`。上游唯一打开真实 `.mdx` 的测试依赖本机
     `E:\dictionary\MDict`，没有该目录时直接 `return`，所以容器布局必须由合成 fixture 钉住。
     S3 追加：同一条 fixture 也断言 `probeMdxHead` 的 `entryCount`，因为 S4 导入时
     只读头部（不拷整个文件）就要拿到条目数。

## 未复制的上游测试及原因

| 测试 | 原因 |
| --- | --- |
| `StarDictEngineTest` | 依赖 `app/src/main/assets/dict` 里打包的 CC-CEDICT（52 万词条、`.idx` + `.dict` 数十 MB），仓库不 vendoring 大词典数据；构造 `dictDir` 时直接 `error()`，在无资产的环境必然失败。S3 的 app 侧测试 `BuiltInDictionaryInstallerTest` 用真实 ECDICT 资产覆盖了等价路径（解压 → `openStarDict` → `lookupSmart` → `toHtml`）。 |
| `BigEcdictTest` | 真实 340 万词条 ECDICT 的容量/性能回归，靠环境变量 `ECDICT_DIR` 提供外部目录，且断言写死了该词典的 `wordCount = 3_402_564`。 |
| `FuzzyPerfTest` | 纯 benchmark（只 `println` 耗时），依赖本机 `internal-dicts/` 的 CC-CEDICT 与 chibigenc，无断言。 |
| `mdict/MdxSmoke.kt`、`mdict/MdxVerify.kt` | 不是 JUnit 测试，而是 `object` + `main()` 的开发期命令行工具，默认路径是本机 Windows 词典库 `E:\dictionary\MDict`，靠 Gradle JavaExec 手动运行。 |

其余保留下来的测试中，少数方法以本机语料为输入，目录不存在时直接 `return`（**报成 passed，
不是 skipped**）：

| 测试 | 期望路径（相对模块目录 `third_party/juicedict-engine/`） |
| --- | --- |
| `MdxBytesTest`（`.mdd` 配对）、`MdxHeaderTest`、`MdxKeyOrderTest`、`MdxLinkRewriteTest` 的真实语料回归 | `E:\dictionary\MDict`（Windows 绝对路径；Gradle 测试工作目录是模块目录，所以在 macOS/Linux/CI 上永远不存在） |
| `IndexCacheTest`、`StarDictSynTest` 的真实 chibigenc 回归 | `../internal-dicts/dict` 或 `internal-dicts/dict`，相对模块目录即 `third_party/internal-dicts/dict` 或 `third_party/juicedict-engine/internal-dicts/dict`（**不是**仓库根下的 `internal-dicts/dict`） |

要在本机启用这批回归，得按上表把语料放到对应位置（或临时改常量）；改成
`Assume.assumeTrue` 让报告显示 skipped 属于上游改动，按「不改上游测试」的约定没做。

## 构建环境

- `build.gradle.kts` 用 `jvmToolchain(17)`，需要本机有 JDK 17 工具链（项目门禁本来就
  `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`）；`settings.gradle.kts` 没有引入
  foojay resolver，所以不会自动下载。换机器/CI 时要么装 JDK 17，要么把这里改成
  `compilerOptions { jvmTarget = JvmTarget.JVM_17 }`（与 `app/build.gradle.kts` 的写法一致）。
- 模块是纯 JVM：不得引入 `android.*` 依赖，`MdxLog` 就是为此存在的日志间接层。

## 维护约定

- 本目录是**副本**，不要在这里改解析逻辑；需要改就回上游打补丁。
- 升级上游时按上面的复制范围重新 copy，`DictionaryEngines.kt` 与 `PROVENANCE.md`
  单独保留（升级后再核对 `MdxDictionary` / `MdxProbe` 的构造签名是否变化）。
- 模块内 `internal` 可见性覆盖整个模块：测试源码集可直接访问 `MdxDictionary`、
  `MdxProbe` 等 internal 类型（Kotlin 的 friend 机制），所以上述测试无需适配。
