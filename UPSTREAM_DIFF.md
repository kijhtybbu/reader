# 与上游的差异说明

本文档记录本 fork（`dev.jia.reader`）相对上游 **Acclorite/book-story**（`ua.acclorite.book_story`，GPL-3.0-only）的全部差异。

- 基线：`upstream/master` = `464891e`（feat: update translations）
- 本 fork：23 个提交，89 个文件，+6075 / −360 行
- 单元测试：21 个文件 / 110 个用例（上游 7 个文件 / 30 个用例）
- 仪器测试：1 个（排版自检）

因为本 fork 派生自 GPL-3.0 项目，**整个 fork 同样是 GPL-3.0-only**，不能闭源分发。

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
| 应用名 | Book's Story | 本地阅读器 | — |

`gradlew` 的文件模式从 644 修正为可执行（上游在 git 里记录的是 644，macOS 上直接 `./gradlew` 会 `Permission denied`）。

---

## 2. 上游缺陷修复（本 fork 修的是上游的 bug）

这两条不是"我们的新功能"，而是**上游 `master` 上已经存在、但尚未发布出去的缺陷**。

准确范围（已用官方发布包 + 未改动的上游源码双向验证）：

| | 官方发布版 **v1.8.0** | `upstream/master`（本 fork 的基线） |
| --- | --- | --- |
| `.proto` 文件 | 0 个 | 1 个 |
| `protobuf-javalite` | 无 | 4.33.2 |
| minified 构建能否打开书籍 | ✅ 正常 | ❌ **失败** |

protobuf 是在 `upstream/master` 那唯一一个超出 v1.8.0 的提交（`464891ec`，提交信息写的是 "feat: update translations"）里引入的。
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
- 未改动的 `upstream/master` 构建 `release-debug`——同一台设备、同一个文件，失败（`Could not parse book content.`）
- 同一份 master 的 **debug** 构建——正常（不开 R8）
- 上游 `proguard-rules.pro` 里没有任何 protobuf 规则，而 `protobuf-javalite` 自身不带 consumer 规则

唯一变量是 R8 与 protobuf 的组合。

**修复**：加上 keep 规则后，minified 构建在真机上正常渲染。

### 2.2 错误信息撒谎，把排查引向错误方向

`NodeHandler.cacheNodes` 的失败被 `.getOrNull()` 静默吞掉，于是**缓存写失败**被报成「文件似乎为空」。用户会去检查文件，而问题在序列化。

**修复**：仓库层记录异常；`TxtContentParser` 区分「文件真的没有节点」与「节点写不进缓存」（后者报 `error_could_not_save_file`）。这条改动是定位 §2.1 的前提。

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

| 分区 | 左/上 | 中 | 右/下 |
| --- | --- | --- | --- |
| 左/中/右（默认） | 上一页 | 菜单 | 下一页 |
| 上/中/下 | 菜单 | 下一页 | 上一页 |
| 仅中间 | 不响应 | 菜单 | 不响应 |

分区是**位置性**的（与设置文案一致）；反向阅读方向翻转的是页码增量（`pageDeltaFor`），不是分区。全仓库只有**一个**点击检测器：上游给每个段落挂的 `combinedClickable` 已移除（它会把单击吞掉，导致分区翻页在文字区域失效），双击翻译改由 Pager 上的同一检测器 + `readerNodeAtY` 命中测试处理。

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

- Tab 顺序改为 **排版 / 更多 / 颜色**，第一个 Tab 前七行就是高频项：字号、行距、段距、字距、缩进、对齐、字体
- **4 个排版预设**：紧凑 / 标准 / 大字 / 护眼，一次写入 5 个设置
- **面板内样例预览**：分页模式下任何排版参数变化都会把正文淡出到 alpha 0 重新分页，所以"拖动时看面板上方的正文"不成立。改为在面板里渲染一段固定文字，与阅读布局无关
- 面板高度 0.7 → 0.62，排版页 scrim 透明（露出上方正文）

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
| 单位标签 | 三个滑块都写 pt | 改为真实单位 |
| 大字号标题 | M3 默认字距 | 收紧字距 + 加重 |
| 动画时长 | 散落字面量 | 收敛为 8 档 token（观感不变） |
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
| 内置中文字体 | 31 个内置字体全是拉丁字库，中文书下字体选择无效。内置 3 款中文子集要 +15MB APK，而用户导入路径覆盖同样需求、体积为零 |
| 用户导入字体 | 设计见 `DEV_PLAN.md` 附录 A，尚无代码。这是中文字体问题的正解 |
| 双指调亮度 | 设置里已有亮度滑块；且双指手势要在 Pager 上再挂一个 drag 检测器，与"只能有一个检测器"的教训冲突 |
| 音量键翻页 | 需要拦截 `dispatchKeyEvent`，与系统音量、无障碍服务、外接键盘冲突 |
| 「最近阅读」置顶分区 | 默认排序本来就是它，再置顶会与分类 Tab 的排序语义重叠 |
| 长按多选批处理栏 | 上游的多选本身可用，改造为常驻批处理栏是纯打磨 |
| 全局 Snapshot 回归测试 | 需要仪器环境与固定测试书；目前只有 JVM 单测 + 手工真机验证 |
| zh-rTW 等 28 个语言 | 新文案回退英文（上游 zh-rTW 本身只有 12 条翻译） |
| 列表布局的进度样式 | 仍是上游的三色胶囊，与网格的环形进度视觉语言不一致 |
| 搜索 / TTS / 云端同步 | 超出范围 |
