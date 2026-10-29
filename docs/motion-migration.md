# Motion token 迁移对照表

Phase 1 的「零视觉变化」声明靠这张表核对。`before` 全部是裸 `tween(N)`，
即 Compose 的默认缓动 `FastOutSlowInEasing`；`after` 的默认值必须是同一个曲线（`Motion.Legacy`）。

| 文件 | before 时长 | before 缓动 | after token | after 时长 | after 缓动 | 一致 |
| --- | --- | --- | --- | --- | --- | --- |
| `ui/common/components/common/Snackbar.kt` | 350 | FastOutSlowInEasing | `Motion.slower()` | 350 | Legacy | ✅ |
| `ui/common/components/common/Snackbar.kt` | 350 | FastOutSlowInEasing | `Motion.slower()` | 350 | Legacy | ✅ |
| `ui/common/components/common/Snackbar.kt` | 200 | FastOutSlowInEasing | `Motion.base()` | 200 | Legacy | ✅ |
| `ui/common/components/common/Snackbar.kt` | 100 | FastOutSlowInEasing | `Motion.fast()` | 100 | Legacy | ✅ |
| `ui/common/components/navigation_bar/NavigationBarItem.kt` | 150 | FastOutSlowInEasing | `Motion.short()` | 150 | Legacy | ✅ |
| `ui/common/components/navigation_rail/NavigationRailItem.kt` | 150 | FastOutSlowInEasing | `Motion.short()` | 150 | Legacy | ✅ |
| `ui/history/HistoryEmptyPlaceholder.kt` | 0 | FastOutSlowInEasing | `Motion.instant()` | 0 | Legacy | ✅ |
| `ui/library/LibraryEmptyPlaceholder.kt` | 0 | FastOutSlowInEasing | `Motion.instant()` | 0 | Legacy | ✅ |
| `ui/reader/ReaderLayoutContinuous.kt` | 150 | FastOutSlowInEasing | `Motion.short()` | 150 | Legacy | ✅ |
| `ui/reader/ReaderLayoutContinuous.kt` | 0 | FastOutSlowInEasing | `Motion.instant()` | 0 | Legacy | ✅ |
| `ui/reader/ReaderLayoutPaged.kt` | 150 | FastOutSlowInEasing | `Motion.short()` | 150 | Legacy | ✅ |
| `ui/reader/ReaderSettingsBottomSheet.kt` | 300 | FastOutSlowInEasing | `Motion.long()` | 300 | Legacy | ✅ |
| `ui/reader/ReaderSettingsBottomSheet.kt` | 300 | FastOutSlowInEasing | `Motion.long()` | 300 | Legacy | ✅ |
| `ui/settings/appearance/theme_preferences/components/AppThemeOption.kt` | 300 | FastOutSlowInEasing | `Motion.long()` | 300 | Legacy | ✅ |
| `ui/settings/appearance/theme_preferences/components/AppThemeOption.kt` | 300 | FastOutSlowInEasing | `Motion.long()` | 300 | Legacy | ✅ |
| `ui/settings/appearance/theme_preferences/components/AppThemeOption.kt` | 100 | FastOutSlowInEasing | `Motion.fast()` | 100 | Legacy | ✅ |
| `ui/theme/Animations.kt` | 300 | FastOutSlowInEasing | `Motion.long()` | 300 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 300 | FastOutSlowInEasing | `Motion.long()` | 300 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 300 | FastOutSlowInEasing | `Motion.long()` | 300 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 250 | FastOutSlowInEasing | `Motion.medium()` | 250 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 250 | FastOutSlowInEasing | `Motion.medium()` | 250 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 250 | FastOutSlowInEasing | `Motion.medium()` | 250 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 350 | FastOutSlowInEasing | `Motion.slower()` | 350 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 350 | FastOutSlowInEasing | `Motion.slower()` | 350 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 350 | FastOutSlowInEasing | `Motion.slower()` | 350 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 350 | FastOutSlowInEasing | `Motion.slower()` | 350 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 350 | FastOutSlowInEasing | `Motion.slower()` | 350 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 350 | FastOutSlowInEasing | `Motion.slower()` | 350 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 350 | FastOutSlowInEasing | `Motion.slower()` | 350 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 350 | FastOutSlowInEasing | `Motion.slower()` | 350 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 0 | FastOutSlowInEasing | `Motion.instant()` | 0 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 0 | FastOutSlowInEasing | `Motion.instant()` | 0 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 300 | FastOutSlowInEasing | `Motion.long()` | 300 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 300 | FastOutSlowInEasing | `Motion.long()` | 300 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 150 | FastOutSlowInEasing | `Motion.short()` | 150 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 100 | FastOutSlowInEasing | `Motion.fast()` | 100 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 400 | FastOutSlowInEasing | `Motion.slowest()` | 400 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 400 | FastOutSlowInEasing | `Motion.slowest()` | 400 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 350 | FastOutSlowInEasing | `Motion.slower()` | 350 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 200 | FastOutSlowInEasing | `Motion.base()` | 200 | Legacy | ✅ |
| `ui/theme/Transitions.kt` | 300 | EaseInOut | `Motion.long()` | 300 | Balanced | ✅ |

共 41 处；不一致 0 处。
