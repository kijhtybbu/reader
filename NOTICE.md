# NOTICE — 来源、修改与授权

本文件是分发 GPL-3.0-only 程序的「显著声明」（GNU GPL-3.0 §5），随仓库与 APK 一起提供。

```
应用名称  麦麦阅读器 (MaiMai Reader)
版本      2.0  (versionCode 15)
包名      dev.jia.reader / dev.jia.reader.debug / dev.jia.reader.release.debug
授权协议  GPL-3.0-only
```

---

## 1. 上游来源

本程序是 [Book's Story](https://github.com/Acclorite/book-story) 的**修改版本（fork）**，不是上游的官方发布。

| 项 | 值 |
| --- | --- |
| 上游项目 | Book's Story — Material You eBook Reader for Android |
| 上游作者 | Acclorite（2024-2026） |
| 上游仓库 | <https://github.com/Acclorite/book-story> |
| 上游包名 | `ua.acclorite.book_story` |
| 分叉基线 | tag `upstream-base` = `464891ec`（上游 `master`，2026-10-31） |
| 本分支仓库 | <https://github.com/kijhtybbu/reader> （分支 `main`；`upstream` 远程的 push URL 为 `no_push`） |

本 fork 与上游的**逐条差异**见 [`UPSTREAM_DIFF.md`](UPSTREAM_DIFF.md)；每处改动的时间、权衡与实测结论见
[`PROGRESS.md`](PROGRESS.md)。

## 2. 修改声明

上游代码按 GPL-3.0-only 授权，本 fork 的全部修改同样以 **GPL-3.0-only** 发布，不改变原授权。
修改自 2026-10-31 起累积，来源与内容如下（摘要，非完整清单）：

- 阅读体验：用户导入字体与中文字形回退、按书排版、分页与翻页动画、阅读配色预设
- 词典：内置 ECDICT（MIT 数据）、Mdx/StarDict 导入、MDD 资源（CSS / 图片 / 发音）、WebView 词条渲染
- AI：OpenAI 兼容的段落翻译与语法分析、流式输出、翻译缓存（Room）、本地无 Key 端点
- 收藏夹：单词 / 句子收藏（Room 22）
- 品牌与合规（**v2.0**）：应用更名「麦麦阅读器」、版本号 2.0、"关于" 页新增上游来源 / 授权协议 / 源码入口
- 其它：`applicationId` 改为 `dev.jia.reader`（与上游共存）、`minSdk` 26 → 29、修复上游若干回归

完整的对应源码（含全部修改）即本仓库，见第 1 节链接。

## 3. 授权协议

```
麦麦阅读器 — Material You eBook reader for Android.
A fork of Book's Story. Licensed under GPL-3.0-only.

Book's Story — free and open-source Material You eBook reader.
Copyright © 2024-2026 Acclorite
Modifications in this fork are copyright © their respective authors.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, version 3 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <https://www.gnu.org/licenses/>.
```

协议全文见根目录 [`LICENSE`](LICENSE) 或 <https://www.gnu.org/licenses/gpl-3.0.html>。

## 4. 应用内呈现

| 位置 | 内容 |
| --- | --- |
| 关于 → 应用版本 | `麦麦阅读器 v2.0 (<git 短哈希>)` |
| 关于 → 上游来源 | Book’s Story by Acclorite · GPL-3.0-only → 上游仓库 |
| 关于 → 授权协议 | GNU GPL-3.0-only → <https://www.gnu.org/licenses/gpl-3.0.html> |
| 关于 → 源码 | 本分支仓库（含全部修改） |
| 关于 → 开源许可证 | AboutLibraries 自动生成的第三方依赖清单 |

## 5. 打包进来的第三方组件

| 组件 | 授权 | 备注 |
| --- | --- | --- |
| [ECDICT](https://github.com/skywind3000/ECDICT) | MIT | 词典数据，`app/src/main/assets/dictionaries/ecdict/`，附 `ECDICT-LICENSE.txt` / `NOTICE.txt` |
| [juicedict](https://github.com/qiuminal/juicedict) | GPL-3.0 | 词典引擎，`third_party/juicedict-engine/`，附 `LICENSE` / `PROVENANCE.md` |
| [openai-kotlin](https://github.com/aallam/openai-kotlin) | MIT | AI 调用 |
| [Ktor](https://ktor.io) | Apache-2.0 | HTTP 客户端 |
| [Tink](https://github.com/tink-crypto/tink-java) | Apache-2.0 | API Key 本地加密 |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) / [XZ for Java](https://tukaani.org/xz/java.html) | Apache-2.0 / Public Domain | 7z 解压 |

其余依赖由**关于 → 开源许可证**在运行时列出。

---

## English summary

**麦麦阅读器 (MaiMai Reader) v2.0** is a modified version of
[Book's Story](https://github.com/Acclorite/book-story) by Acclorite, distributed under the same
license, **GPL-3.0-only**.

- Upstream source: <https://github.com/Acclorite/book-story>
- Complete corresponding source of this modified version (all changes):
  <https://github.com/kijhtybbu/reader>
- Modifications are documented in [`UPSTREAM_DIFF.md`](UPSTREAM_DIFF.md) and
  [`PROGRESS.md`](PROGRESS.md); the license text is in [`LICENSE`](LICENSE).
- Copyright © 2024-2026 Acclorite; modifications © their respective authors.
