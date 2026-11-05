# 按书排版（全局 / 本书）设计与实施记录

> 状态：已实施（2026-11-01）
> 入口：阅读器底部面板「排版」Tab 顶部；「边距」所在 Tab 1 顶部有紧凑版

---

## 1. 需求

每本书可以拥有自己的一套排版；没有设置的书跟随全局。要求在书内可以显式切换
「全局 / 本书」，切换时各自记住自己的选择，可以来回切；选项与全局不同时给出标记；
提供一键恢复为全局默认。

12 项被覆盖：字体家族、字重、粗体字重、斜体、字号、行距、段距、字距、首行缩进、
对齐、左右边距、上下边距。

**不在范围内**：页眉、图片、进度条、阅读模式、系统设置。它们不在排版 Tab 的调参路径上，
一起按书覆盖会让回归面翻倍，且没有明确需求。

---

## 2. 语义

| 状态 | 生效值 | 面板写入目标 |
| --- | --- | --- |
| 没有记录 | 全局 | 全局（`SettingsManager`） |
| 有记录，`enabled=false` | 全局 | 全局 |
| 有记录，`enabled=true` | 本书快照 | 本书记录（`ReaderTypographyStore`） |

- **整组覆盖**：第一次切到「本书」时，用当时的全局值生成一条包含全部 12 项的记录。
  之后整组独立于全局，全局再改不影响这本书。
- **记忆切换**：切回全局只把 `enabled` 置 false，记录保留；再切回本书恢复上次的本书记录。
- **差异标记**：本书模式下逐字段比较本书记录与当前全局值，不同的项在控件左侧显示圆点。
  全局模式与设置页不显示标记。
- **恢复全局默认**：「恢复全局默认」删除本书记录并回到全局，需二次确认（不可撤销）。
- **容错解释**：记录里的字段可空。单个字段缺失/非法只会让该字段回退全局，而不是丢整本书。
  正常写入时 12 项始终完整。字体 id 失效（用户字体被删/记录来自别的设备）时回退**全局字体**，
  而不是硬编码默认字体；差异标记也按解析后的字体比较，避免「标记亮着但观感与全局一致」。

---

## 3. 存储

- 位置：与 `SettingsManager` 相同的 DataStore（`data_store`），单键
  `reader_typography_by_book`，值为 JSON 对象。
- 键：书 `uuid`（内容库/阅读布局已按 uuid 作用域；自增 id 跨备份不稳定）。
- 编解码：`ReaderTypographyCodec`，使用 `kotlinx.serialization` 的 `JsonElement` API，
  仓库未启用 serialization 编译器插件（与 `UserFontIndexCodec` 一致）。
- 可空字段省略不写；认不出的枚举值（如 `textAlignment`）按「未设置」处理，回退全局。
  整串 JSON 损坏时返回 `null`，调用方保留原始字符串，下次正常写入自愈。
- 并发模型（实现时按 review 重写）：
  - **内存同步**：`enable/disable/update/reset` 在调用线程上直接更新 StateFlow。否则
    「点切换 → 下一次滑块事件」之间状态还没更新，事件会被写到错误的作用域；`reset` 后
    残留回调还可能复活刚删除的记录。
  - **磁盘异步合并**：整张表是一个 JSON 字符串，滑块按触摸事件频率回调。只在停止操作
    200ms 后写最新快照，且写盘不持锁（锁内只取快照），避免磁盘慢时阻塞 UI。
  - **先读后写**：读取完成前到达的写入排队，读完按顺序应用，绝不先写空表覆盖磁盘。
  - **只更新已启用的记录**：记录不存在/已停用时忽略写入，防止产生只有单个字段的
    半快照记录。
- 启动：`ReaderTypographyStore.initialized` 加入 `MainActivity` 的 splash 条件，避免先按
  全局画一帧再跳到本书。

JSON 示例：

```json
{
  "550e8400-e29b-41d4-a716-446655440000": {
    "enabled": true,
    "fontFamily": "user_font_0f3a",
    "fontThickness": 400,
    "boldFontThickness": 300,
    "italic": false,
    "fontSize": 18,
    "lineHeight": 6,
    "paragraphHeight": 10,
    "letterSpacing": 0,
    "paragraphIndentation": 5,
    "textAlignment": "JUSTIFY",
    "sidePadding": 6,
    "verticalPadding": 0
  }
}
```

---

## 4. 组合层

12 个排版控件不再直接读 `SettingsManager`，而是读 `LocalReaderTypography`：

- `ProvideSettings`（App 根部）注入**全局态**：值来自 `SettingsManager`，
  `modifiedFields` 恒为空，actions 写全局。全局设置页因此不需要任何改动。
- `ReaderScreen` 用 `rememberBookReaderTypography(book.uuid)` 生成**本书态**，
  通过 `CompositionLocalProvider` 覆盖到 `ReaderContent`，正文、底部面板、样例预览
  看到的是同一套值。
- actions 按当前模式分流：全局模式写 `SettingsManager`，本书模式写
  `ReaderTypographyStore`。不能只按「有没有记录」判断 —— 否则在全局模式拖滑块会
  静默创建本书记录并切进本书模式。
- `ReaderTypographyResolver` 是纯函数：全局值 + 记录 → 生效值；并提供差异字段集合。
  字体 id 通过 `resolveFont` 注入，单测不依赖 `FontRegistry` / Android 资源。

数据流：

```
SettingsManager (12 个 Setting)
        │  @Composable .value
        ▼
ReaderTypographyValues ──┐
                         ├─ Resolver.resolve ─► ReaderTypographyState.values ─► ReaderScreen / 预览 / ReaderNodes
ReaderTypographyStore ───┘                                   │
  records: StateFlow<Map<uuid, Record>>                     └─ modifiedFields ─► 控件圆点
        ▲
        │ actions（本书模式）
   12 个排版控件
```

---

## 5. 改动文件

新增：

| 文件 | 作用 |
| --- | --- |
| `data/typography/ReaderTypographyValues.kt` | 值模型 / 记录 / 模式 / 字段枚举 / 快照 |
| `data/typography/ReaderTypographyCodec.kt` | JSON 编解码与容错 |
| `data/typography/ReaderTypographyResolver.kt` | 解析与差异集合（纯函数） |
| `data/typography/ReaderTypographyStore.kt` | DataStore 存储、串行写入、清理 |
| `ui/reader/typography/ReaderTypography.kt` | CompositionLocal、全局态/本书态、actions |
| `ui/reader/ReaderTypographyScopeSelector.kt` | 全局/本书选择器、差异圆点、恢复对话框 |

主要修改：

- 12 个设置控件：`FontFamilyOption`、`FontThicknessOption`、`BoldFontThicknessOption`、
  `FontStyleOption`、`FontSizeOption`、`LetterSpacingOption`、`LineHeightOption`、
  `ParagraphHeightOption`、`ParagraphIndentationOption`、`TextAlignmentOption`、
  `SidePaddingOption`、`VerticalPaddingOption`。
- `ReaderScreen`：12 处读取改为解析值；用 Provider 包住 `ReaderContent`。
- `ReaderNodes`：正文加粗字体家族改为读当前态。
- `ReaderTypographyPresets`：预设与样例预览写/读当前态。
- `ReaderSettingsBottomSheet`：排版 Tab 顶部固定选择器；Tab 1 顶部紧凑选择器。
- `FontSubcategory` / `TextSubcategory` / `PaddingSubcategory` / `ReaderTypographyQuickSettings`：
  用 `ModifiedTypographyOption` 包一层差异圆点（全局页恒不显示）。
- `ProvideSettings` / `MainActivity`：注入 store、splash 条件。
- `DeleteBookUseCase`：删书时删记录；`UserFontOption`：删用户字体时清理本书记录里的引用。

---

## 6. 测试

新增 3 个文件 / 25 例：

| 文件 | 覆盖 |
| --- | --- |
| `ReaderTypographyCodecTest` | 往返、可空字段、坏 JSON、跳过坏条目、未知对齐值、字段类型错误 |
| `ReaderTypographyResolverTest` | 无记录/未启用走全局、整组覆盖、逐字段回退、字体回落、差异集合、快照完整性 |
| `ReaderTypographyStoreTest` | 首次快照、停用记忆、再启用恢复、同字段/跨字段连续更新顺序、reset 后不可复活、合并写只落一次盘、无记录更新被忽略、重置落盘、字体引用清理、坏 JSON 自愈 |

门禁：`:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:assembleRelease-debug`
（R8 + lintVital）全绿。评审后按意见重写了 Store 并发模型（同步内存 + 合并落盘），
并修掉作用域切换竞态、reset 复活、选择器窄屏溢出、差异点无障碍等问题。

真机（联想 TB322FC / Android 16）验证通过：切换「本书」、改字号（差异计数 + 圆点）、
杀进程重装后仍保留、恢复全局默认后回落全局。排版 Tab 的面板高度从 0.62 提到 0.74 ——
选择器与预览固定占约 200dp，0.62 时列表只剩一行。

---

## 7. 已知限制

- 书被重新导入（uuid 变化）后不会继承旧记录，旧记录会成为孤儿；与阅读进度/内容库的
  行为一致，暂未加启动期清理。
- DataStore 单键整张表写入：滑块拖动时内存状态同步更新，磁盘写入按 200ms 合并（
  `PERSIST_DEBOUNCE_MS`），避免每个触摸事件都重写所有书的记录。代价是进程在最后一次
  改动后 200ms 内被杀，该次改动可能未落盘（与现有异步设置写入同风险量级）。
- 写盘失败只记录 `logW`，不会让阅读页崩溃，但该次改动会丢失，直到下一次成功写入。
- 页眉只继承本书的字体家族/正文样式，字号/字重/对齐仍为全局 —— 符合范围约定。
