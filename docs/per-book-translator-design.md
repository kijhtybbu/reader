# 按书翻译器行为（取词 / 双击翻译）设计与实施记录

> 状态：已实施（2026-11-03）
> 入口：阅读器底部面板「翻译器」Tab

---

## 1. 需求与边界

翻译器的**行为开关**不再作为全局设置，只能在书内配置，并且只对本书生效：

- 按书保存：取词手势、双击翻译
- 全局共享：词典（安装/启用/优先/口音）、AI 的 Base URL / 模型 / API Key / 目标语言 / 语法 / 推理强度
- 全局设置页只保留「词典管理」「AI 翻译设置」两个入口，不再出现行为开关
- **所有书默认关闭**：取词手势 `OFF`、双击翻译 `false`；旧全局值不迁移、不自动启用
- 两者都关闭 = 本书不启用翻译器（选中文本后的复制/翻译工具栏仍按原行为保留）

---

## 2. 数据与解析

- DataStore 单键 `book_translator_by_book`，值为 JSON 对象，键是书 `uuid`：

```json
{
  "550e8400-e29b-41d4-a716-446655440000": {
    "lookupGesture": "SINGLE_TAP",
    "doubleClickTranslation": true
  }
}
```

- 记录字段可空；缺失/类型错误/未知手势名一律解析为 `OFF` / `false`。
  这里**不能**复用旧的 `ReaderLookupGesture.fromName`（它回落 `SINGLE_TAP`），
  否则升级后会悄悄重新打开取词。
- `BookTranslatorStore` 与 `ReaderTypographyStore` 同一套并发模型：
  - 内存同步更新（开关点击立即生效）；
  - 磁盘 200ms 合并、锁外写盘、失败只记 `logW`；
  - 读取完成前的写入排队，绝不先写空表；
  - 删书时 `DeleteBookUseCase` 同步删除记录。

---

## 3. 组合层

- `LocalBookTranslator`：书内 Tab 与阅读页共用的唯一数据源。
- App 根部（`ProvideSettings`）注入默认态（关闭、no-op），全局设置页不会渲染这两个开关。
- `ReaderScreen` 用 `rememberBookTranslator(book.uuid)` 覆盖成本书态，并把
  `lookupGesture` / `doubleClickTranslation` 传给 `ReaderContent`。
- `BookTranslatorStore.initialized` 加入 splash 条件，避免首帧先按「关闭」画再跳成
  本书的实际值。

---

## 4. UI

「翻译器」Tab 内容（书内），分两段：

**本书设置**

1. 说明：只对本书生效、默认关闭；两者都关 = 不启用。
2. 取词手势（关闭 / 单击 / 长按）。
3. 双击翻译开关。

**全局词典与 AI**（分隔线之后）

4. 说明：词典与 API Key 由所有书共享。
5. 词典管理 → `DictionaryManagerScreen`（整行可点，带箭头）
6. AI 翻译设置 → `AiSettingsScreen`（整行可点，带箭头）

全局设置页（设置 → 阅读器 → 翻译器）只剩词典管理与 AI 翻译设置两行。

提示组件 `SettingsSubcategoryNote` 改为带底色的圆角块：`secondaryContainer` 背景 +
`onSecondaryContainer` 文字，图标 20dp 在行首与文字同行（影响全 App 的所有提示）。
这样提示与可点击的设置项、灰色说明文字在视觉上直接区分开。

已知行为：从书内跳到全局词典/AI 页再返回，阅读器底部面板不会自动恢复 —— 与既有的
词典管理入口一致，返回后停在阅读页，需要重新呼出面板。

---

## 5. 测试

| 文件 | 覆盖 |
| --- | --- |
| `BookTranslatorCodecTest`（6） | 往返、缺字段、未知手势回落 OFF、坏 JSON、跳过坏条目、错误类型逐字段丢弃 |
| `BookTranslatorStoreTest`（11） | 无记录默认关闭、独立写入、双关即禁用、合并写、删书清理、坏 JSON 自愈、未知/部分记录回落、读取前写入排队、空 uuid 忽略 |
| `ReaderLookupGestureTest`（3） | 存储名往返、无记录 OFF、未知值 OFF |

评审修复：`FlowRow` 补垂直间距；`BookTranslatorStore` 对空 `bookUuid` 直接忽略；
补「读取完成前写入排队」与空 uuid 测试。快速连点提示按钮可能重复 push 页面，
属于已知 minor，未加防抖。

门禁：`:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleRelease-debug` 全绿。

---

## 6. 已知限制 / 未做

- AI 凭据、目标语言、优先词典、口音仍全局共享（按需求确认的「仅行为开关」）。
- 设备在最后一轮改动后断开连接，书内 Tab、提示跳转、跨书隔离只完成了编译与单测验证，
  尚未重新做真机复验；PROGRESS §3.22 有记录。
- 旧 DataStore 键 `double_click_translation` / `lookup_gesture` 不再读取，未主动删除
  （避免升级时做破坏性清理；键本身很小）。
