# Rebase checklist

fork 与上游的三处分歧**不会在编译期报错**：改错了照样能 build，只是功能静默退化。
每次 `git rebase upstream/master` 之后跑一遍下面的检查，任何一条不通过都不要继续。

```bash
git remote -v            # upstream 应指向 Acclorite/book-story
git log --oneline -3     # 确认自己在 fork 分支上，不是 detached HEAD
```

---

## 1. `minSdk = 29`（上游为 26）

**失效表现**：rebase 直接采用上游的 `defaultConfig`，`minSdk` 悄悄退回 26。
编译、安装、启动全部正常，但 `Typeface.CustomFallbackBuilder`（API 29）在 26~28 上不可用 ——
用户导入字体的中文字形回退链失效，而这正是提到 29 的唯一硬理由。
同时 `ui/theme/Theme.kt` 里已删掉的 `SDK_INT >= Q` 判断也会重新变成「该有却不在」的状态。

预期值：`app/build.gradle.kts` 的 `defaultConfig` 里是 `minSdk = 29`。

```bash
grep -nE '^\s*minSdk\s*=\s*29\s*$' app/build.gradle.kts || echo 'FAIL: minSdk 不是 29（上游默认 26）'
```

站内标记：`app/build.gradle.kts` 的 `minSdk` 上方有 `// fork: rebase-critical` 注释。

## 2. Manifest 里的三个 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`

**失效表现**：上游用的是它自己的 `applicationId`。这三条 `tools:node="remove"` 靠**名字精确匹配**
才生效 —— 名字对不上时 manifest merger 不会报错，只是这三条权限又回到最终 manifest 里，
lint/商店检查变成了「行为退化但没人发现」。名字必须跟着 `applicationId` 走：

| build type | 必须出现的权限名 |
| --- | --- |
| main | `dev.jia.reader.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` |
| debug | `dev.jia.reader.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` |
| release-debug | `dev.jia.reader.release.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` |

预期值：`app/src/main/AndroidManifest.xml` 里恰好 3 条，且前缀与 `applicationId`
（`dev.jia.reader`，debug 后缀 `.debug`，`release-debug` 后缀 `.release.debug`）一致。

```bash
grep -c 'dev\.jia\.reader\(\.debug\|\.release\.debug\)\?\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION' app/src/main/AndroidManifest.xml   # 期望 3
```

站内标记：`app/src/main/AndroidManifest.xml` 顶部这三条上面有 `<!-- fork: rebase-critical -->` 注释。

## 3. `ReaderTopBar` 的 M3 `subtitle` slot

**失效表现**：阅读页顶栅的章节名走 `TopAppBar(subtitle = { ... })`，而 `subtitle` 是
`material3` 的实验性（`ExperimentalMaterial3ExpressiveApi`）API。`material3` 一升版，
这个 slot 可能被改名/改形状 —— 这次**会**编译失败，但崩的是阅读页顶栅，
不要在「先让 build 过」的压力下把它降级成一个可滚动的 title：
章节名必须留在第二行，否则标题行会被长章名挤成省略号。

预期值：`ReaderTopBar.kt` 里 `TopAppBar` 仍有 `subtitle = { ... }`，文件头的 `@OptIn`
同时包含 `ExperimentalMaterial3Api` 与 `ExperimentalMaterial3ExpressiveApi`。

```bash
grep -nE 'subtitle = \{|ExperimentalMaterial3ExpressiveApi' app/src/main/java/ua/acclorite/book_story/ui/reader/ReaderTopBar.kt   # 期望两行都命中
```

站内标记：`ui/reader/ReaderTopBar.kt` 的 `subtitle = {` 上方有 `// fork: rebase-critical` 注释。

---

## 做完检查之后

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:assembleDebug
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:testDebugUnitTest
```

如果 rebase 把 `app/src/main/java/ua/acclorite/book_story/ui/reader/paged/ReaderPagePaginator.kt`
或 `data/parser/` 改出了冲突，**以 fork 侧为准**并重跑排版回归测试
（`ReaderPagePaginatorTest`，断言是「分页后拼回的文字与原文完全相等」）。
