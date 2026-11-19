# 麦麦阅读器 · UI 改造计划（基于 book-story fork）

> 本文件写于改名之前，文中出现的应用名「本地阅读器」即现名「麦麦阅读器」（v2.0 起）。
> 来源与授权声明见 [`NOTICE.md`](NOTICE.md)。
>
> 前两版 `DEV_PLAN.v0.generated.md`（自研引擎）与 `DEV_PLAN.v2-readium.md`（Readium 自建外壳）均已废弃。
> 本版前提：**引擎与解析全部复用开源实现，工作量只落在 UI 上。**

---

## 0. 结论先行

你提的三项需求（视觉重做+主题系统、中文排版面板、手势与翻页动效），在 **Acclorite/book-story** 里**能力已经全部存在**，缺的是设计与交互，不是功能。所以这份计划的形状是「改造」而不是「开发」。

但盘库后有三件事必须先纠正预期：

| 需求 | 现状 | 结论 |
| --- | --- | --- |
| 中文排版面板 | 参数齐全，但埋在 `设置 → 阅读器` 的 11 个子页里 | 只做**面板重构**，不加能力 |
| 自定义字体 | 内置 27 个字体文件，**全部是拉丁字库**，无中文字形 | **必须补中文字体**，否则中文下换字体无效果；并支持用户自行导入（附录 A） |
| 多书签、精确到行 | **不存在**（`Checkpoint` 只是本次会话的位置快照） | 唯一需要**新增功能**的项，约 1 天 |

基座信息（已实测拉取）：`minSdk 26` / `compileSdk 36`、Kotlin 2.x + Compose 1.9.3 + Material3 1.5.0-alpha06、Hilt 2.57.2、Room 2.7.2、DataStore Preferences、**GPL-3.0-only**、UI 代码 29,075 行、已有 `values-zh-rCN` / `values-zh-rTW`。

**已决定：minSdk 26 → 29**（Android 10）。原因只有一个 —— `Typeface.CustomFallbackBuilder` 是 API 29 才有的，而 `FontFamily(typeface)` 没有回退链，不提 minSdk 就无法给用户导入的字体做中文字形兜底（详见附录 A.3）。顺带的好处：`ui/theme/Theme.kt:40` 的 `SDK_INT >= Q` 分支变成死代码可删，其余 4 处 `SDK_INT` 判断（31/32 行为差异）仍需保留。

代价评估：全项目只有 5 处 `Build.VERSION` 判断，无 26–28 专属 API 依赖，改动量近乎为零。设备覆盖约 92%（Android 10+）。

额外补充：`android.graphics.text.LineBreaker` 也是 API 29 才有，但**实测表明避头尾与 `LineBreak` 无关**（见附录 B.4 的 M1），所以「提到 29 能修避头尾」这个理由不成立，不提也罢。minSdk 29 的唯一硬理由就是 `Typeface.CustomFallbackBuilder`。

---

## 1. Phase 0 · 基座落地与现状实测（Day 1）

### 1.1 落地步骤

```bash
git clone https://github.com/Acclorite/book-story.git reader
cd reader
git remote rename origin upstream      # 保留上游，便于后续 rebase
git checkout -b ui-redesign
```

改造点：`app/build.gradle.kts` 的 `namespace` / `applicationId` / `versionName` / **`minSdk = 29`**；`fastlane/` 元数据清空；`aboutLibraries` 保留（GPL 合规要求声明上游）。

> `minSdk` 是与上游的永久分歧点，上游每次 rebase 都可能把 26 改回来。建议把版本号抽到 `gradle/libs.versions.toml` 或在 `build.gradle.kts` 里加显式注释，rebase 时优先检查这一行。

构建依赖：JDK 17、Android SDK 36、protobuf 插件会下载 `protoc 4.33.2`（首次构建需联网）。

### 1.2 必须实测的五件事（决定后面要不要动排版代码）

| # | 测什么 | 怎么测 | 不达标时的代价 |
| --- | --- | --- | --- |
| 1 | 中文避头尾 | 用含大量 `。，」）` 的 1000 字段落，开 `textAlignment = Justify`，看是否有标点落在行首 | 需改 `ReaderScreen.kt` 的 `TextStyle`（`lineBreak` 已是 `LineBreak.Paragraph`，可能仍不足） |
| 2 | 两端对齐观感 | 纯中文长段 + 中英混排段各一段 | 只能退回 `Start`，两端对齐选项降级为「仅英文」 |
| 3 | `letterSpacing` 对 CJK 的效果 | 字距拉到最大，看标点间距是否被破坏 | 面板里对中文隐藏该项 |
| 4 | 分页耗时 | 单章 3000 字，`paginateReaderPages` 计时（`ReaderLayoutPaged.kt:265` 附近已有 `Dispatchers.Default` 调用，加 `measureTimeMillis`） | > 100ms 需优化 `ReaderPagePaginator.kt` |
| 5 | 跨章翻页是否有空白帧 | 快速滑到章末再继续滑 | 需改 `ReaderLayoutPaged.kt` 的 `leadingPages / trailingPages` 策略 |

### 1.3 中文化复查

`values/strings.xml` 有 371 条，`values-zh-rCN` 已有对应翻译。需 diff 两者，补齐上游新增未翻译的条目。这属于「UI 改进」里最容易被用户感知的部分。

---

## 2. 现状盘点（改哪里，别改哪里）

| 关注点 | 现有实现 | 是否需要动 |
| --- | --- | --- |
| 排版参数 | `data/settings/SettingsManager.kt:128-234`：`fontFamily` `fontThickness` `boldFontThickness` `italic` `fontSize` `lineHeight` `paragraphHeight` `paragraphIndentation` `sidePadding` `verticalPadding` `textAlignment` `letterSpacing` | 参数**不动**，只改暴露方式 |
| 中文避头尾 | `presentation/reader/ReaderScreen.kt:242` `lineBreak = LineBreak.Paragraph` | 待实测决定 |
| 首行缩进 | `ReaderScreen.kt:236` `TextIndent(firstLine = paragraphIndentation)` | 不动 |
| 分页引擎 | `ui/reader/paged/ReaderPagePaginator.kt`（226 行，`TextMeasurer` 逐行切片，含 Activity 取消检查） | **不要动**，除非 1.2 第 4 项不达标 |
| 分页容器 | `ui/reader/ReaderLayoutPaged.kt`（487 行，`HorizontalPager` + 首尾占位页实现跨章） | 只加动效与手势 |
| 目录抽屉 | `ui/reader/ReaderChaptersDrawer.kt` | 视觉改造 |
| 阅读设置面板 | `ui/reader/ReaderSettingsBottomSheet.kt`（155 行，3 Tab：General / Reader / Color） | **主要改造对象** |
| 设置子页 | `ui/settings/reader/{font,text,headers,padding,images,progress,reading_mode,reading_speed,system,translator,misc}` | 保留，作为「高级」入口 |
| 主题 | `ui/theme/color/` 18 套硬编码色板 + `DynamicTheme` + 3 档对比度 + `animateColorScheme` 色彩过渡 | 不重写，在其上包一层 token |
| 设计 token | **缺失**（`Theme.kt:60` 为 `typography = Typography()`，即 M3 默认值；间距/圆角散落各处） | 需新建 |
| 动效 | 散落的 `tween(durationMillis = 300)` | 收敛为 token |
| 字体 | `ui/reader/data/ReaderData.kt` 注册 17 款，`res/font/` 29MB，**全为拉丁字库** | 需补中文 |
| 书签 | 无 | 需新增 |
| 选中/翻译/词典 | `ui/common/components/common/SelectionContainer.kt` 已支持复制/分享/搜索/翻译/词典 | 不动 |

---

## 3. Phase 1 · 设计系统层（Day 2–3）

现在 `29k` 行 UI 全靠 M3 默认值与硬编码 `dp`/`tween`，任何「视觉重做」都会退化成逐个文件调数字。先补三层 token，再做视觉。

**零视觉变化的确切范围（重要，不要笼统说“零变化”）：**

| 文件 | 视觉影响 |
| --- | --- |
| `Motion.kt` | **零**。41 处 `tween` 全部逐值等价（默认缓动 = `Motion.Legacy` = Compose 的 `FastOutSlowInEasing`），对照表见 `docs/motion-migration.md` |
| `Dimens.kt` | **零**（目前无消费者） |
| `Typography.kt` | **有意改变**。只会影响 `headlineMedium` 的 1 个界面（`ui/start/StartDoneLayout.kt:53` 的“设置完成！”）；`display*` 与 `headlineLarge` 无消费者，`headlineSmall` 未动 |

**新建文件：**

`ui/theme/Dimens.kt`
```
间距：4 / 8 / 12 / 16 / 18(≈仓库事实标准) / 24
圆角：pill（999.dp）+ 角色别名 cover(12dp)；4/8/12/16 直接用 M3 MaterialTheme.shapes
固定尺寸：coverAspect 1f/1.5f、hintMaxWidth、coverProgressRing 及其描边
只收有真实出处的值；写不准的宁可不收；没人用的随时删（Phase 4 收尾时删了
`topBarHeight`、`Spacing.xxl`、`Spacing.pageInset`、`Radius.xs/s/m/l/sheet`）
```

`ui/theme/Typography.kt`
只收紧 display*/headline* 的字距并加重字重；title/body/label 不动（它们是全 App 布局高度的依据）。

`ui/theme/Motion.kt`
```
durationShort 150 / Medium 250 / Long 400
easingStandard / easingEmphasized
```
把现有散落的 `tween(300)` 全部替换（`ui/theme/Animations.kt`、`ReaderSettingsBottomSheet.kt:67-76`、`ReaderLayoutPaged.kt` 的 `animateFloatAsState`）。

`ui/theme/ReaderPalette.kt`（阅读主题与应用主题解耦）

> **已推迟到 Phase 2。** 它在 Phase 1 没有消费者，先落地就是死抽象；
> 等到重做排版面板、真正需要「阅读配色不污染 App 主题」时再引入，
> **注意（已核实并纠正）**：`ReaderScreen.kt:196-212` 的 `BlendMode` 链路作用于**插图**着色
> （`ReaderColorEffects.FONT/BACKGROUND` 把图片按前景/背景染色），不是给背景或文字上色。
> 那是独立功能，**不要**因为引入 `ReaderPalette` 而删除。
现状：阅读背景/文字色来自 `colorPreset`；上下栏与栏内图标却取 App 主题（`readerBarsColor` /
`onSurface` / `onSurfaceVariant`），两套配色互不相干。改为：
```
ReaderPalette(bg, text, accent, contrastLevel)   // 阅读区专用
```
应用主题（动态取色）只管书架与设置页；阅读页的栏、图标、进度条、强调色一律走 `ReaderPalette`。

**实测修正**：早先以为旧代码「深色预设 + 浅色主题会让栏内文字不可读」，实测不成立——
旧栏底是 App 主题 `surfaceContainer` 90% 透明，文字与栏底同属一套主题，最差组合也有约 11:1。
真正的问题是**观感割裂**（夜间黑页上浮着一条亮栏）以及 `readerBarsColor` 的 10% 底色渗透。
所以本次改动的价值是「配色统一」，不是「修不可读」。但迁移必须彻底：只迁文字不迁图标，
反而会把图标对比度降到 1.3:1 —— 这正是 Round 1 抓到的 blocker。

> 纪律：这一层只加映射，**不重写** `ui/theme/color/` 的 18 套色板。

---

## 4. Phase 2 · 中文排版面板重做（Day 4–5）

### 4.1 现在的问题

改一次字号要：点正文 → 底部面板 → 切到「Reader」Tab → 进 `FontSubcategory` → 拖滑块 → 返回。层级 4 层，且拖动时面板遮住正文，看不到效果。

### 4.2 目标形态（已实现，含两处与原计划的偏差）

**偏差一：保留 3 个 Tab，而不是 2 个。** 原计划「2 Tab + 更多设置 →」会失去
阅读模式/边距/系统/速度/杂项这 5 组设置的入口（它们原本在「常规」Tab）。
改为 **排版 / 更多 / 颜色**：第一个 Tab 就是高频排版区，原「常规」内容整体挪到「更多」，
所有设置仍在面板内可达，不用跳去设置页。

**偏差二：实时预览改为「面板内样例」，不是「面板上方的正文」。** 见下方实测结论。

```
┌─────────────────────────────┐
│  他合上书，窗外的雨还没停。      │  ← 面板内样例预览（按当前设置渲染）
│      「明天再读吧。」          │
│  预设  紧凑 │ 标准 │ 大字 │ 护眼  │
│  字号 ━━━━●━━━━              │
│  行距 ━━━●━━━━━ 段距 ━━●━━━━  │
│  字距 ━●━━━━━━ 缩进 ━━━●━━━━  │
│  [左对齐] [两端对齐] [居中] …    │
│  字体  默认 宋 黑 楷 …          │
│  ─────────────────────────   │
│  字重 / 粗体字重 / 斜体          │  ← FontSubcategory 精简后
│  图片 / 标题 / 进度 / 翻译        │
└─────────────────────────────┘
```

**实测结论（Round 1 评审抓出，我原计划写错了）**：分页模式下 `ReaderLayoutPaged.kt:249`
在每次 config 变化时把 `contentAlpha` 推到 **0** 重新分页，`:308` 才恢复；而滑块是逐步回调的。
所以「拖动时看面板上方的正文」在默认（分页）布局下根本不成立——文字会一直闪。
修法不是拆掉那个淡出闸门（它还负责首次加载与滚动定位），而是在面板里放一段用当前设置
渲染的样例文字：与阅读布局无关，拖到哪看到哪。同时把排版页的 scrim 改为透明 —— 注意 scrim 画在面板**之外**，
它不会盖住面板内的样例预览，透明是为了让面板上方那段正文不被蒙暗。

其余交互：
- 面板高度 0.7 → 0.62（排版页），多露出一些正文。
- **预设一键切换**：4 个 chip 一次性写入 `fontSize / lineHeight / paragraphHeight /
  letterSpacing / paragraphIndentation`，不引入新存储。当前值完全匹配时 chip 显示为选中。
- **字体补中文**：仍未做，见 §4.4。

### 4.4 未完成项：中文字体（明确推迟）

`res/font/` 里 31 个字体全是拉丁字库，所以中文书下「字体」这一项选了等于没选。
推迟的理由不是工作量，而是**取舍**：

- 内置 3 款中文子集化字体，APK 从 ~30MB 涨到 ~45MB，且每款仍需自己用 `pyftsubset` 裁剪；
- 而「用户自行导入字体」（附录 A）能覆盖同一需求，且体积为零、字体选择无限，
  还能顺带解决「我喜欢某款商用字体」这类诉求。

结论：**优先做附录 A 的用户导入**，内置中文子集字体作为可选补充。在导入能力落地前，
`FontFamilyOption` 里应给出提示——这一点目前**尚未实现**，属于 Phase 2.5 的验收项。

### 4.3 要改的文件

```
ui/theme/ReaderPalette.kt                       阅读区配色 + 对比度派生（已完成）
ui/reader/ReaderContent.kt                      提供 ReaderPalette / 系统栏覆盖（已完成）
ui/theme/SystemBars.kt                          系统栏图标明暗跟随阅读背景（已完成）
ui/reader/ReaderTypographyQuickSettings.kt      常驻高频排版区（已完成）
ui/reader/ReaderTypographyPresets.kt            4 个预设 + 面板内样例预览（已完成）
ui/reader/ReaderSettingsBottomSheet.kt          重排为 排版/更多/颜色（已完成）
ui/reader/ReaderSettingsBottomSheetTabRow.kt    3 个 Tab 标签（已完成）
ui/settings/reader/font/FontSubcategory.kt      include 开关 + （待）缺中文提示
ui/reader/ReaderTextStyle.kt                    正文样式单一来源（阅读页与预览共用）
ui/reader/ReaderTextStyleTest.kt                钉住行高/字距/缩进/斜体语义
res/font/ + ReaderData.kt                       内置中文字体 —— 推迟，见 §4.4
```

---

## 5. Phase 3 · 手势与翻页动效（Day 6–7）

### 5.1 翻页动效（已实现，含一处删减）

现状：`ReaderLayoutPaged.kt` 用 `HorizontalPager` 默认滑动，无转场选项。新增 `pagedAnimation` 设置：

| 模式 | 实现 |
| --- | --- |
| 平移（默认） | 保持 Pager 默认行为，不做任何额外变换 |
| 淡入淡出 | 两页都抵消 Pager 位移，再按 `1 - |offsetDistance|` 交叉淡变 |

**删减：不做「覆盖」。** 覆盖要求「离场页静止、新页滑入盖住它」，而 Pager 的 `currentPage`
在拖动过中点时就翻转，单凭 `(index, currentPage, offsetFraction)` 无法可靠判断「这次手势从哪页开始」。
试写版本在翻转点会跳半页或在两页间露出背景色——都是肉眼可见的破绽。想要「页面替换」观感，
FADE 已能提供且数学无歧义。**也不做「无动画」**：跟手拖拽本身就是动画，二者互斥。

关键约定（写错不会崩，只会「看着不对」，所以必须钉死）：

```
offsetDistance = page - currentPage - currentPageOffsetFraction   // Compose 源码定义，正数在前方
Pager 把页面画在 offsetDistance * pageSize 处（reverseLayout 时取反）
⇒ 抵消位移 = -offsetDistance * pageSize * (reverseLayout ? -1 : 1)
```

第一版把 `offsetFraction` 的符号写成了 `+`，Round 1 评审据 Compose 源码指出并判定 blocker。
现在由 `PagerPageTransformTest` 钉住：核心不变量是 `offsetDistance * pageSize + translation == 0`
（否则两页会分离），另有 reverseLayout 反号、alpha 夹取、纵向轴长取 `size.height`。
纵向 pager（TOP_TO_BOTTOM / BOTTOM_TO_TOP）与反向布局都必须覆盖，否则只有四分之一的方向是对的。

### 5.2 点击分区可配

现状：正文点击只切菜单（`ReaderLayoutPaged.kt:186` `noRippleClickable { menuVisibility }`）。改为 3 种可选布局：
- 三分（左/中/右：上一页 / 菜单 / 下一页）
- 上下中（上/中/下：菜单 / 下一页 / 上一页）
- 仅中间（其余区域不响应）

实现要点（已按此落地）：**只能有一个点击检测器**。原来段落自己挂的 `combinedClickable`
（单击切菜单 + 双击翻译）等于第二套检测器，会把单击吞掉，导致「点右三分之一翻页」在文字区域失效。
现在单击/双击统一挂在 Pager 的 modifier 上：单击走 `tapActionFor`，双击用 `readerNodeAtY`
把 y 坐标换算成段落再翻译。

菜单开着时点击先收起菜单（与 `ReaderLayoutContinuous` 一致），否则会出现「点左三分之一既翻页又关不掉菜单」。
连点用 `pendingTapTarget` 累积，避免两次点击只翻一页。

### 5.3 其它手势（已按需求裁剪）

**已明确不做**（用户决定）：

| 功能 | 结论 |
| --- | --- |
| 双指上下滑调亮度 | **不做**。设置里已有亮度滑块；双指手势还要在 Pager 上再挂一个 drag 检测器，正是 5.2 刚踩过的坑（第二套检测器互相吃事件）。 |
| 音量键翻页 | **不做**。需要 `Activity.dispatchKeyEvent` 拦截，会与系统音量调节、无障碍服务、外接键盘冲突，收益不抵复杂度。 |

**保留：章节切换提示。** 跨章时在顶部短暂显示章名（约 1.5s 后淡出），
因为跨章翻页时正文会重新分页并淡出，用户容易不知道「已经换章了」。

语义说明：提示跟随的是「当前章名变化」，因此**目录跳转、进度条拖动、书签/断点恢复**也会触发。
这是刻意的——那些操作同样会让用户失去位置感。若将来只想在「跨边界」时提示，需要在事件源上区分。

实现要点（Round 1 评审后修正）：
- 不能用 M3 `Surface` 做胶囊：它会装一个空的 `pointerInput` 节点，显示期间吞掉落在顶部中央的点击
  （而那里正是「呼出菜单」区）。改用 `Box + clip + background`。
- 章名变 `null` 时必须主动收起胶囊：那次 `LaunchedEffect` 的 `delay` 已被取消，
  没有别人会关它，会永远挂在屏幕上。

## 6. Phase 4 · 书架与收尾（Day 8）

**已交付（Phase 4b，见 `e7b4a00`）：**

- `ui/library/LibraryGridItem.kt`：网格封面加阅读进度环（`Dimens.coverProgressRing` 16dp +
  `coverProgressRingStroke` 2dp，配 scrim 底衬，白/黑/灰封面都已实测可读），`libraryShowProgress` 仍然总闸。
- 封面圆角走 token：`Radius.cover`（12dp）由 `LibraryGridItem` 与 `LibraryListItem` 共用，
  网格与列表的同一张封面不再是两个形状。
- 顶栏副标题：当前生效排序是「最近阅读」时显示 `sort_order_last_read`，
  解决了「书为什么是这个顺序」；逐分类排序的解析与 `LibraryPager` 一致。

**未做（明确不是遗漏，是没排进本轮）：**

- 「最近阅读」置顶分区 —— 默认排序本来就是 `LAST_READ`（`SettingsManager.kt:346`），
  再单独置顶一段会和分类 Tab 的排序语义重叠；原始诉求（顺序不可解释）已由顶栏副标题满足。
- 长按多选批处理 —— 上游的多选本身可用（长按选中、顶栏切换），改造为常驻批处理栏是纯打磨，
  没有功能阻塞，本轮未开始。
- 全局 `Snapshot` 回归测试 —— 需要 instrumentation 环境与固定测试书；本轮只有 JVM 单测
  （`assembleDebug` + `testDebugUnitTest`）可以跟随时跑，既未开始也未验收，
  「位置不漂」目前只靠手工在 API 34 模拟器上验过。

---

## 7. 唯一新增功能：多书签（精确到行）

**已交付。** 上游的 `Checkpoint(nodeId, nodeIntraOffset, offset)` 只在会话内用于「回到上次位置」，
不落盘、无列表；书签是独立的第二套，以下为**实际落地形态**（下面就是仓库里的真实签名）。

```kotlin
// data/storage/room/entity/BookmarkEntity.kt
@Entity(
  tableName = "bookmarkentity",                                  // 不是 "bookmark"
  indices = [
    Index(value = ["bookId", "nodeId", "nodeIntraOffset"], unique = true),
    Index(value = ["bookId"])                                     // 按书查询用
  ]
)
data class BookmarkEntity(
  @PrimaryKey(autoGenerate = true) val id: Int = 0,               // Int，不是 Long
  val bookId: Int,                                                // Int，与 Book.id 一致
  val nodeId: Int,             // 对应 ReaderNode 的 NodeId
  val nodeIntraOffset: Int,    // 段内偏移
  val chapterTitle: String,    // 冗余，列表直接显示
  val snippet: String,         // 该行起始若干字
  val createdAt: Long
)
```

- 唯一索引在 `(bookId, nodeId, nodeIntraOffset)`：同一行重复添加不会产生两条记录。
  Room schema 版本仍为 19，未新增迁移。
- 去重粒度实际是**段落级**：图标与 toggle 都用 `bookmarkForNode`（只看 nodeId），
  因为重排会让同一行的段内偏移变化；存下来的 offset 仍然精确，跳转还是行级。
  同一段落里给另一行加书签 = 把这一段已有的那条挪过去，不新增。
- 跳转：`ReaderEvent.OnScrollToPosition(nodeId, nodeIntraOffset)` ——
  这个事件**存在**（`ReaderEvent.kt:52`，fork 新增），由 `ReaderContent.kt:181` 的书签面板调用，
  走现有的 `ScrollTarget` 机制。
- UI：`ReaderTopBar` 的书签按钮是**三态**，由 `BookmarkButtonState` 决定：
  `NONE`（加书签）/ `HERE`（取消这个书签）/ `ELSEWHERE`（这一段已有书签但在别的行 → 移动到这里）。
  列表在 `ReaderBookmarksBottomSheet`（底部面板，不是侧边抽屉）。
- 复用 `ReaderLayoutContinuous.kt` 的段落定位思路，没有另写一套。

工作量：实体 + DAO + Repository（复用现有 Hilt 模块）+ Sheet UI ≈ 1 天。

---

## 8. 排期

| 阶段 | 天 | 产出 |
| --- | --- | --- |
| Phase 0 基座 + 实测 | 1 | 可运行 fork、五项实测结论、中文补齐 |
| Phase 1 设计 token | 2 | `Motion` / `Typography` / `Dimens`（`ReaderPalette` 推迟到 Phase 2） |
| Phase 2 排版面板 | 2 | 排版/更多/颜色 三 Tab + 高频区 + 预设 + 面板内样例预览（内置中文字体推迟，见 §4.4） |
| Phase 2.5 用户导入字体 | 1.5 | 见附录 A |
| Phase 3 手势动效 | 2 | 翻页动效（平移/淡入淡出）、点击分区、章节提示（双指亮度与音量键翻页已裁掉，见 §5.3） |
| Phase 4 书架 + 书签 | 1 | 书签完整功能、书架视觉、回归 |
| **合计** | **10.5** | |

时间主要花在 Phase 0 的实测与 Phase 2 的字体处理上，而不是写代码。

---

## 9. 风险

1. **GPL-3.0-only**：fork 后你的所有修改也必须 GPL。自用不发布没问题，但放弃「将来闭源/上架」的可能。若介意，只能回到 `DEV_PLAN.v2-readium.md` 的 Readium（BSD-3）路线。
2. **上游同步**：book-story 迭代较快（当前 1.8.0）。策略：所有改动限定在 `ui/` 与 `presentation/*/model/`，不碰 `data/parser/`、`paged/ReaderPagePaginator.kt`，按功能拆 commit，每 2 周 `git rebase upstream/master` 一次。
3. **排版核心回归风险**：`ReaderPagePaginator.kt` 是唯一保证「文字不丢、页不断错」的模块，任何改动都必须用「全章字数校验」（分页后所有 `ReaderPageItem.text` 拼回原文本必须完全相等）做断言。
4. **Compose alpha 依赖**：`material3:1.5.0-alpha06`。改 UI 时避免依赖该版本独有 API，防止上游降版时冲突。
5. **字体体积**：中文字体是 APK 膨胀主因，必须子集化；否则 3 款全量字体直接 +40MB。
6. **`BlendMode` 着色**（`ReaderScreen.kt:196-212`）：作用于**插图**染色（`imagesColorEffects`），
   与 `ReaderPalette` 无关，**保留**。早先把它误判为「背景/文字着色」并计划删除，已纠正。
7. **用户字体与 `saveable` 状态**：`ReaderLayoutPaged.kt:164-165` 用 `rememberSaveable(book.uuid)` 保存 `anchorNodeId/anchorOffset`。字体切换若导致 Typeface 重建失败（文件被删），必须回落到内置字体并保留 anchor，不能抛异常打断阅读。

---

## 10. 明确不做

换引擎、换 `ReaderPagePaginator`、加书源/网络书库、TTS/听书、云端同步、PDF 重排、AI 摘要。

---

## 11. 第二阶段（翻译与词典）

第一阶段（§1–§10，UI/动效/字体）已完成。第二阶段另开分支 `phase-2`（S1–S9 已完成，设计与验收见 `docs/phase-2-design.md`），
完整设计见 **[`docs/phase-2-design.md`](docs/phase-2-design.md)**（产品决策、组件选型、
缓存表结构、测试矩阵、风险均在那里，本文不复述）。

| 阶段 | 内容 |
| --- | --- |
| S1 | 设计文档、`phase-2` 分支、依赖与 Spike（openai-kotlin/Ktor、Tink、7z） |
| S2 | `:dict-engine` 模块 + `DictionaryEngines` + 上游测试 |
| S3 | ECDICT `stardict.7z` 资产 + 首次解压 + 内置词典注册 |
| S4 | 词典仓库、MDX/StarDict 导入、设置页词典管理 |
| S5 | 取词手势、文本布局命中、取词卡片 |
| S6 | AI 设置、Tink、openai 客户端、MockEngine 测试 |
| S7 | Room 缓存表/DAO/迁移/仓库 |
| S8 | `TranslationModel`、双击整段、翻译/语法对话框、缓存优先 |
| S9 | 文案/许可/文档、全量回归、`release-debug`、终审 |

约定同第一阶段：每阶段 `worker` 实现 → `reviewer` 第 1 轮 → 修复 → `reviewer` 第 2 轮（最终整体 review 按要求取消），
两轮通过后提交；每次改动都要过三重构建门禁（含 `:app:assembleRelease-debug`）。

---

## 附录 A · 用户手动导入字体（实现设计）

### A.1 为什么不能照抄内置字体的注册方式

内置字体走 `ReaderData.kt:161-197` 的 `provideVariableFontFamily`：用 9 个 `Font(resId, weight, variationSettings)` 注册 W100–W900，靠可变字体的 `wght` 轴实现字重滑块。

文件版 `Font(file:)` 的实现是（Compose `AndroidPreloadedFont.android.kt:113-140`）：

```kotlin
internal class AndroidFileFont(...) : AndroidPreloadedFont(...) {
    override fun doLoad(context: Context?) = TypefaceBuilderCompat.createFromFile(file, context, variationSettings)
    init { typeface = doLoad(null) }        // ← 构造即加载
}
```

即 `Font(file = f, weight = w)` 每创建一个实例就建一个 Typeface。照抄 9 字重写法 = 读 9 次 10MB 字体文件，启动卡死。**必须自己管 Typeface。**

### A.2 三条解析路径

| 类型 | 检测 | 构建方式 |
| --- | --- | --- |
| 静态字体 | 无 `fvar` 表 | `Typeface.createFromFile(file)` 一次 → `FontFamily(base)`（`LoadedFontFamily`，零开销）；字重/斜体变化时 `Typeface.create(base, style)` 合成，`LruCache<(weight, italic), Typeface>` |
| 可变字体 | 有 `fvar` + `wght` | `Typeface.Builder(file).setFontVariationSettings("'wght' $w").build()`（API 26+），按 `(weight, italic)` 缓存 |
| 成套字体 | 用户提供 4 个文件 | `FontFamily(Font(file = regular, W400, Normal), Font(file = bold, W700, Normal), Font(file = italic, W400, Italic), …)`，4 次 eager 加载可接受 |

`fvar` 检测：读文件头 table directory 找 tag `fvar`（12 字节 header + 16 字节/entry），约 20 行，无额外依赖。

```kotlin
object UserFontResolver {
    private val cache = LruCache<String, Typeface>(16)   // key: "/path#weight#italic"
    fun resolve(file: File, variable: Boolean, weight: Int, italic: Boolean): FontFamily =
        FontFamily(cache[file.absolutePath] ?: build(file, variable, weight, italic))
}
```

**必须放到 `Dispatchers.Default` 预热**：20MB 的 CJK 字体首次 `createFromFile` 需 100–300ms，同步建在 UI 线程会在切换字体那一下掉帧。

### A.3 中文回退链（关键）

`FontFamily(typeface)` 在 Compose 里是 `LoadedFontFamily(typeface)`（`FontFamily.kt:255`），**单一 typeface、无回退链**，缺字形直接豆腐块。用户下载的字体大概率是拉丁字库。

minSdk 已定为 29，所以只需一条路径：

```kotlin
Typeface.CustomFallbackBuilder(base)          // API 29
    .setSystemFallback("sans-serif")          // 缺字落到系统字体
    .build()
```

`setSystemFallback` 取系统字体族的名字（`"sans-serif"` / `"serif"`），中文环境下会命中系统内置的 CJK 字体。若想落到「黑体」或「宋体」，用 `addCustomFallback(Typeface.create("serif", NORMAL))`。

**导入时仍必须做字形检测**：`Paint().hasGlyph('汉')` + `hasGlyph('。')`，缺中文就提示「该字体不含中文字形，将由系统字体代显示」，并允许继续导入（看英文书的人需要）。检测结果存进索引，用于给字体打「中文/西文」标签。

### A.4 导入流程

复用现成的 `data/storage/file/CachedFile.kt`（已封装 SAF → 本地缓存拷贝）：

```
OpenDocument(arrayOf("*/*"))            // 不要只给 font/*，很多 ROM 报 application/octet-stream
  → CachedFile(context, uri)
  → 校验扩展名 + 魔数：
       00 01 00 00  TrueType
       4F 54 54 4F  "OTTO"  OTF
       74 74 63 66  "ttcf"  .ttc 集合 → v1 拒绝（需选 face index）
       77 4F 46 46  "wOFF" / "wOF2" → 提示「请先转成 ttf/otf」
  → 大小上限 40MB
  → 拷到 filesDir/fonts/<uuid>.ttf
  → 更新 index.json → 重建 FontRegistry
  → hasGlyph 检测 → 提示
```

### A.5 索引持久化（最易踩的坑）

`SettingsManager.kt:128-134` 的反序列化是**同步**的：

```kotlin
val fontFamily = setting<FontWithName, String>(
    key = stringPreferencesKey("font"),
    serialize = { it.id },
    deserialize = { id -> ReaderData.fonts.find { it.id == id } ?: ReaderData.fonts[0] }
)
```

若用户字体存在 Room 里，启动瞬间查不到 → **静默回落默认字体**，用户以为导入的字体丢了。

解决：索引存 `filesDir/fonts/index.json`（几 KB），`Application.onCreate` 里同步读进 `FontRegistry`，`deserialize` 改查 `FontRegistry`。不引入任何异步依赖。

```json
[{ "id": "user:0f3a…", "name": "霞鹜文楷", "fileName": "0f3a.ttf",
   "size": 18874368, "variable": false, "hasCjk": true, "importedAt": 1761700000 }]
```

### A.6 `FontWithName` 改造

现在 `font: FontFamily` 是 eager 字段，用户字体不能 eager：

```kotlin
// ui/reader/model/FontWithName.kt
@Immutable
data class FontWithName(
    val id: String,                 // "builtin:garamond" | "user:<uuid>"
    val fontName: UIText,
    val source: FontSource          // Bundled(FontFamily) | User(file, variable)
) {
    val font: FontFamily get() = /* 带缓存，见 A.2 */
}
```

连带改动：

- `ui/settings/reader/font/components/FontFamilyOption.kt:33` 的 `selected = item == settings.fontFamily.value` 依赖 data class 相等性 → 改为比 `id`；
- 3 个调用点需按 `(fontId, fontThickness, italic)` 重新解析（用户字体要重建 Typeface）：`ReaderScreen.kt:229`、`settings/reader/font/components/FontStyleOption.kt:30/41`、`FontFamilyOption.kt:30`；
- 字体选择条从 `ReaderData.fonts.map` 换成 `FontRegistry.all`。

### A.7 UI 组件

`ui/settings/reader/font/` 下新增：

- 「导入字体文件」按钮（`ActivityResultContracts.OpenDocument`）
- 用户字体卡片，含**三行预览**：`读万卷书，行万里路。` / `Reading is dreaming with eyes open. 123` / `「引号、括号（全角）」` —— 能一眼看出是否缺中文字形
- 缺中文警告徽标、体积、删除按钮（删除前若为当前字体则先回落默认）
- 数量上限 10 个

### A.8 风险

1. **内存**：同时常驻多个大字体 Typeface 会 OOM。`LruCache` 上限 16 且只缓存当前字体的 `(weight, italic)` 组合，切换字体时清掉上一款的缓存。
2. **字体文件被删/损坏**：`Typeface` 构建失败必须捕获并回落 `FontFamily.Default`，同时保留 `anchorNodeId/anchorOffset`（`ReaderLayoutPaged.kt:164-165` 的 `rememberSaveable`），不能打断阅读。
3. **重排成本**：换字体触发全章重分页，大字体下 `TextMeasurer` 测量变慢，需在 Phase 0 的分页耗时基线上留 1.5 倍余量。
4. **`.ttc` / `woff2`**：一律拒绝并给出可操作的提示，不要静默失败。
5. **minSdk 29 的上游分歧**：`build.gradle.kts` 会与上游持续冲突，且未来上游若引入 26–28 兼容代码会变成死代码（不影响运行，可忽略）。

---

## 附录 B · Phase 0 执行记录

### B.1 环境（本机实测可用）

| 组件 | 版本 |
| --- | --- |
| JDK | OpenJDK 17.0.21（`brew install openjdk@17`） |
| Android SDK | platform 36 / build-tools 36.0.0 / platform-tools |
| Gradle | 8.13（wrapper） |
| 模拟器 | AVD `reader34`，Android 14（API 34），arm64-v8a，headless |

`local.properties` 里写 `sdk.dir`。`gradlew` 上游记录的模式是 644，macOS 上需 `chmod +x gradlew`。

### B.2 与计划的偏差（三处，均已确认）

| 计划写的 | 实际做法 | 原因 |
| --- | --- | --- |
| 清空 `fastlane/` | **保留** | 它不进 APK；删掉会给每次 rebase 多出 60+ 文件变更 |
| 改 `namespace` | **只改 `applicationId`** | `namespace` 与 Kotlin 包路径、`R` 引用绑定，改名要触及 29k 行的全部 import。`applicationId` 与 `namespace` 相互独立，只改前者即可让 App 以 `dev.jia.reader` 身份安装、不覆盖原版 |
| — | 新增 `testInstrumentationRunner` + `androidx.test.ext:junit` | 上游没配 runner（默认是已废弃的 `android.test.InstrumentationTestRunner`），`AndroidJUnit4` 根本跑不起来 |

### B.3 其它已完成收尾

- `values-zh-rCN` 补齐上游尚未翻译的 23 条（`app_name` 与 4 个 badge、`header_level_label` 为 `translatable="false"`，按规范不应出现在 zh 文件）
- manifest 里 3 个 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` 随 applicationId 同步改名 —— 漏改会让 `tools:node="remove"` 静默失效

### B.4 实测数据（AVD reader34 / API 34 / density 2.625 / 屏宽 411dp / Compose 1.9.3）

> 原始输出已入库：`docs/typography-lab/report-api34-avd.txt`（可复核，不是转述）。
> 1 字宽 = 16sp × density 2.625 = **42px**。
> **模拟器数值只作基线与相对比较，绝对值不代表真机**；Phase 2 验收需在真机复测。

| 项 | 量的是什么 | 结果 | 结论 |
| --- | --- | --- | --- |
| M1a | 行首/行尾禁则违规 + 破折号/省略号成对拆分，2 个样本 × 61 宽度 × 4 种 `LineBreak` | **全部 0** | 避头尾（UAX#14 CL/OP）**不需要写代码** |
| M1b | 四种 `LineBreak` 的断行差异 | 平文样本：`Simple`/`Paragraph`/`Heading` 均 0/24；**对抗性样本：`Paragraph` 21 处差异（19 vs 21 行）、`Heading` 21 处** | `LineBreak` 确实生效，改的是**断行平衡**（避免孤字寡行），与禁则正交。现有 `LineBreak.Paragraph` 应保留 |
| M2 | 中文 justification **逐行**插入宽度（见下方方法与修正记录） | 插入 avg **5px（0.12 字宽）**，max 23px，23 行中只有 **5 行**被撑宽；剩余残留 avg 24px | 中文两端对齐**实质上无效** |
| M2b | 拉丁对照 | 剩余残留 avg **3px** | 同一个 `TextAlign.Justify`，拉丁能贴边 → 证明引擎只在空格处分配余量 |
| M3 | `letterSpacing` 0em → 0.1em | 行数 24 → 27，高度 1280 → 1439px | 对 CJK **生效**，面板保留此项 |
| M4 | 3000 字单段整章分页（预热 + 取 3 次最小值） | 4 页 / **9ms** | 远低于 100ms 预算，**不需要动 `ReaderPagePaginator.kt`** |
| M5 | 改字号重排（仅分页部分） | 16sp 6ms / 20sp 11ms | 同上；真实路径还要加 `TextMeasurer` 构造 + 重组 |
| M6 | 跨章翻页空白帧 | — | 无法自动化，需人工观察（Phase 3） |

数据口径说明：

- M1 的「对抗性样本」是 Round 2 评审要求补的。原样本只包含 `：，。”）；`，对 `！？、》」』`、破折号 `——`、
  省略号 `……`、长 URL、数字串均未覆盖。特别是 U+2014 属 UAX#14 的 B2 类（前后均可断），
  它被行边界劈成两半时旧计数器会报 0 违规，因此新增了成对标点拆分计数器。
- M2 的测法经过两次修正：初版用 `getLineRight`（拿不到绘制期的 justification，**无效**）；
  二版“分别统计两侧右边缘再相减”（**逻辑错误**：justify 会改变断行位置，两边不是同一批行）；
  三版为逐行配对：取 justify 自己的行边界，将同一段文字单独排版（不换行、左对齐）求自然墨迹右缘，
  相减即为该行被撑宽的量。并断言「任何一行撑宽值不得为负」——旧口径曾得出
  “以标点结尾的行 justify 54px > start 46px”这种算术上不可能的结果，该不变量就是防线。

### B.5 实测结论（三条与最初假设相反）

1. **避头尾不需要做，但 `LineBreak` 不是无关的。** UAX#14 CL/OP 禁则在两个样本、61 个宽度、
   四种模式下违规恒为 0（含破折号/省略号成对拆分）。然而 `LineBreak.Paragraph` 在对抗性样本上
   改变了 **21 处**断点（19 vs 21 行）——它做的是断行平衡（避免孤字寡行），不是禁则。
   `ReaderScreen.kt:242` 的现有设置**应当保留**。
2. **中文两端对齐实质上无效。** 逐行配对测量：23 行里只有 5 行被撑宽，平均插入宽度 5px（0.12 字宽）；
   同样引擎跑拉丁，剩余残留 3px。原因：Android 只有 `JUSTIFICATION_MODE_INTER_WORD`，只在空格处分配。
   **决策：不自研字符间距分配；保留 `ReaderTextAlignment.JUSTIFY` 默认值，面板保留对齐选项。**
   即这一项**零改动**——换回左对齐没有收益（两者视觉几乎无差异），自研也不值 1.5 天。
3. **`letterSpacing` 对 CJK 生效**（与 Readium 文档的“CJK 不支持”相反，以 Compose 实测为准）。
4. **分页不需要优化。** 3000 字 9ms，“不碰 `ReaderPagePaginator.kt`”可作为硬约束。

### B.6 其它待 Phase 2 处理的默认值问题

1. **首行缩进默认值为 0**：`SettingsManager.kt:154` 的默认值是 `0`（×6sp = 0）。
   **Phase 2 的决定：保持 0，不改默认值。** 理由：默认值一旦改动会覆盖老用户已保存的设置无从区分，
   且「排版预设」已经让一键切到两字缩进变成一次点击（标准/大字/护眼预设都是 5 ≈ 30sp）。
   把默认值改成 5 会静默改变所有现有用户的正文外观，收益与风险不成比例。
2. **`lineHeight` 语义是绝对增量**：`ReaderScreen.kt:118` 为 `(fontSize + lineHeightSetting).sp`，即行高 = 字号 + 增量（默认 16+4=20sp）。做「行距倍数」UI 时必须换算，不能直接把用户的 1.8 倍存进去。
3. **`letterSpacing` 单位是 em**：`ReaderScreen.kt:121` 是 `setting / 100f` em。滑块刻度与用户感知一致性需在面板重做时确认。

### B.7 Review Round 1 抓到的两个真实问题（已修）

1. **调试包和发布包跑的不是同一套文本引擎**：上游把 `ui-test-junit4` / `ui-tooling` / `ui-test-manifest` 钉在 `1.10.0`，而 app 本身用 `1.9.3`。Compose 属于 atomic group，debug/androidTest 变体整体升到 `ui:1.10.0`（已用 `dependencyInsight` 确认），release 仍是 `1.9.3`。
   → 后果：M1–M5 的数字描述的是 1.10.0 的引擎，不是发布版的。已全部钉回 `1.9.3` 并重跑。
2. **`LineBreak.Paragraph` 依赖 API 33，不是 29**：它背靠 `android.graphics.text.LineBreakConfig`。所以“提到 29 就能让避头尾全量生效”是错的（且实测避头尾根本与 `LineBreak` 无关）。`build.gradle.kts` 里的注释已改正。

Round 1 还提到、但我用构建结果直接否定的两条：

- “`androidTest` 看不到 `internal`” → `compileDebugAndroidTestKotlin` 已通过，friend-path 成立
- “`TextLayoutResult.multiParagraph` 在 1.9.3 不可见” → 已编译并运行通过

### B.8 Review Round 2 抓到的问题（已修）

1. **M2 的对比不是逐行对齐的（方法错，不是代码错）**：justify 会改变断行位置，两个布局的行不是同一批内容，
   比较两组的平均值无效。修正后结论从“部分有效（0.82→0.48 字宽）”变成**“实质无效（0.12 字宽）”**。
   旧口径还产出过「以标点结尾的行 justify 54px > start 46px」这种算术上不可能的数据——
   逐行看 justification 只增宽、残留只能变小。现已加「撑宽值不得为负」不变量断言。
2. **M1 样本覆盖不足**：声明的 26 个禁则字符里样本只出现了 6 个；`！？、》」』` 未测；
   `——`（U+2014，UAX#14 B2 类，前后均可断）被拆分时旧计数器会报 0 违规。
   已加对抗性样本 + 成对标点拆分计数器。
3. **测试的 `ReaderPageConfig` 不是运行时那份**：版心宽用 360dp 推算，而设备实际 411dp
   （报出的 851px 是一个这台设备不存在的宽度，M1 的扫描区间甚至没覆盖到真实宽度）；
   `gap` 8dp vs 运行时的 24dp（`paragraphHeight 8 × 3`）；`separatorHeight` 1 vs 3dp；
   缺 `textDirection = Content`。现已与 `ReaderLayoutPaged.kt:200-219` 逐字段对齐。
   修正后 M4 从 12ms/6 页变为 9ms/4 页。
4. **两项 zh-CN 翻译错**（上游遗留，恰好在 Phase 2 要重建的面板里）：
   `alignment_justify` “文本对齐”→“两端对齐”、`progress_count_quantity` “数量”→“段落”。
5. **根因而非钉版本**：删掉了 `ui-test-junit4` / `ui-test-manifest`（本仓库没有 UI 测试，用不到），
   而不是靠三个版本号保持同步。`ui-tooling` 保留（`@Preview` 需要）并钉在 1.9.3。
6. 其它小项：M1/M2 结论已升级为断言（Phase 2 换字体后它们就是回归测试）；
   无墨迹行不再计入残留统计；原始输出入库到 `docs/typography-lab/`。

Round 2 的验收判断：**无阻塞项**，可以作为 Phase 1 基线。其中 M1/M2 是数据债，
只影响 Phase 2 的对齐面板文案，不影响 Phase 1 的设计 token 工作。
