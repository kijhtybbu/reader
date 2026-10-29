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
