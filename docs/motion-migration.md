# Motion token 迁移对照表

Phase 1 的「零视觉变化」由这张表核对。`before` 是迁移前的原始调用（取自 `00320b2^`），
`after` 是当前源码里的对应调用（行号对应 HEAD）。

- 40/41 处 `before` 是**裸 `tween(N)`**，即走 Compose 默认缓动 `FastOutSlowInEasing`。
- 第 41 处（`Transitions.kt` 的 `FadeTransitionPreservingSpace`）原本就**显式**传了 `easing = EaseInOut`，
  迁移后以 `Motion.long(Motion.Balanced)` 保留，`Motion.Balanced == EaseInOut`。
- 已核对：被移除的 `tween(...)` 中**没有一处**使用 `delayMillis`（否则会被判成「一致」而实际存在时长偏移）。
- `Motion.Legacy == FastOutSlowInEasing`，即所有未显式传缓动的 helper 与原调用等价。
- 共 41 处，不一致 0 处，使用 delayMillis 0 处。

| 文件 | before行 | after行 | before时长 | before缓动 | delay | after调用 | after时长 | after缓动 | 一致 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `ui/common/components/common/Snackbar.kt` | 91 | 91 | 350 | FastOutSlowInEasing | 0 | `Motion.slower()` | 350 | FastOutSlowInEasing | ✅ |
| `ui/common/components/common/Snackbar.kt` | 91 | 91 | 350 | FastOutSlowInEasing | 0 | `Motion.slower()` | 350 | FastOutSlowInEasing | ✅ |
| `ui/common/components/common/Snackbar.kt` | 92 | 92 | 200 | FastOutSlowInEasing | 0 | `Motion.base()` | 200 | FastOutSlowInEasing | ✅ |
| `ui/common/components/common/Snackbar.kt` | 92 | 92 | 100 | FastOutSlowInEasing | 0 | `Motion.fast()` | 100 | FastOutSlowInEasing | ✅ |
| `ui/common/components/navigation_bar/NavigationBarItem.kt` | 55 | 55 | 150 | FastOutSlowInEasing | 0 | `Motion.short()` | 150 | FastOutSlowInEasing | ✅ |
| `ui/common/components/navigation_rail/NavigationRailItem.kt` | 54 | 54 | 150 | FastOutSlowInEasing | 0 | `Motion.short()` | 150 | FastOutSlowInEasing | ✅ |
| `ui/history/HistoryEmptyPlaceholder.kt` | 34 | 34 | 0 | FastOutSlowInEasing | 0 | `Motion.instant()` | 0 | FastOutSlowInEasing | ✅ |
| `ui/library/LibraryEmptyPlaceholder.kt` | 36 | 36 | 0 | FastOutSlowInEasing | 0 | `Motion.instant()` | 0 | FastOutSlowInEasing | ✅ |
| `ui/reader/ReaderLayoutContinuous.kt` | 177 | 177 | 150 | FastOutSlowInEasing | 0 | `Motion.short()` | 150 | FastOutSlowInEasing | ✅ |
| `ui/reader/ReaderLayoutContinuous.kt` | 368 | 368 | 0 | FastOutSlowInEasing | 0 | `Motion.instant()` | 0 | FastOutSlowInEasing | ✅ |
| `ui/reader/ReaderLayoutPaged.kt` | 233 | 233 | 150 | FastOutSlowInEasing | 0 | `Motion.short()` | 150 | FastOutSlowInEasing | ✅ |
| `ui/reader/ReaderSettingsBottomSheet.kt` | 59 | 59 | 300 | FastOutSlowInEasing | 0 | `Motion.long()` | 300 | FastOutSlowInEasing | ✅ |
| `ui/reader/ReaderSettingsBottomSheet.kt` | 63 | 63 | 300 | FastOutSlowInEasing | 0 | `Motion.long()` | 300 | FastOutSlowInEasing | ✅ |
| `ui/settings/appearance/theme_preferences/components/AppThemeOption.kt` | 143 | 143 | 300 | FastOutSlowInEasing | 0 | `Motion.long()` | 300 | FastOutSlowInEasing | ✅ |
| `ui/settings/appearance/theme_preferences/components/AppThemeOption.kt` | 144 | 144 | 300 | FastOutSlowInEasing | 0 | `Motion.long()` | 300 | FastOutSlowInEasing | ✅ |
| `ui/settings/appearance/theme_preferences/components/AppThemeOption.kt` | 145 | 145 | 100 | FastOutSlowInEasing | 0 | `Motion.fast()` | 100 | FastOutSlowInEasing | ✅ |
| `ui/theme/Animations.kt` | 21 | 20 | 300 | FastOutSlowInEasing | 0 | `Motion.long()` | 300 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 37 | 35 | 300 | FastOutSlowInEasing | 0 | `Motion.long()` | 300 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 38 | 36 | 300 | FastOutSlowInEasing | 0 | `Motion.long()` | 300 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 40 | 38 | 250 | FastOutSlowInEasing | 0 | `Motion.medium()` | 250 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 40 | 38 | 250 | FastOutSlowInEasing | 0 | `Motion.medium()` | 250 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 41 | 39 | 250 | FastOutSlowInEasing | 0 | `Motion.medium()` | 250 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 43 | 41 | 350 | FastOutSlowInEasing | 0 | `Motion.slower()` | 350 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 44 | 42 | 350 | FastOutSlowInEasing | 0 | `Motion.slower()` | 350 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 45 | 43 | 350 | FastOutSlowInEasing | 0 | `Motion.slower()` | 350 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 46 | 44 | 350 | FastOutSlowInEasing | 0 | `Motion.slower()` | 350 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 48 | 46 | 350 | FastOutSlowInEasing | 0 | `Motion.slower()` | 350 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 49 | 47 | 350 | FastOutSlowInEasing | 0 | `Motion.slower()` | 350 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 50 | 48 | 350 | FastOutSlowInEasing | 0 | `Motion.slower()` | 350 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 51 | 49 | 350 | FastOutSlowInEasing | 0 | `Motion.slower()` | 350 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 53 | 51 | 0 | FastOutSlowInEasing | 0 | `Motion.instant()` | 0 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 54 | 52 | 0 | FastOutSlowInEasing | 0 | `Motion.instant()` | 0 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 83 | 81 | 300 | FastOutSlowInEasing | 0 | `Motion.long()` | 300 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 85 | 83 | 300 | FastOutSlowInEasing | 0 | `Motion.long()` | 300 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 86 | 84 | 150 | FastOutSlowInEasing | 0 | `Motion.short()` | 150 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 88 | 86 | 100 | FastOutSlowInEasing | 0 | `Motion.fast()` | 100 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 102 | 100 | 400 | FastOutSlowInEasing | 0 | `Motion.slowest()` | 400 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 103 | 101 | 400 | FastOutSlowInEasing | 0 | `Motion.slowest()` | 400 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 104 | 102 | 350 | FastOutSlowInEasing | 0 | `Motion.slower()` | 350 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 105 | 103 | 200 | FastOutSlowInEasing | 0 | `Motion.base()` | 200 | FastOutSlowInEasing | ✅ |
| `ui/theme/Transitions.kt` | 140 | 138 | 300 | EaseInOut | 0 | `Motion.long(Motion.Balanced)` | 300 | EaseInOut | ✅ |
