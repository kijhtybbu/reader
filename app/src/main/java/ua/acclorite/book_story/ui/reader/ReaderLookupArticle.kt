/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：取词卡片正文的结构化解析（第二阶段 S5 增强）。
 *
 * 为什么不再把整段 HTML 直接丢给 `AnnotatedString.fromHtml`：
 *  - 内置 ECDICT 的固定结构是「`<b>词</b>` + 音标 span + 中文义项 + 英文 WordNet 释义 +
 *    `<small>词形码</small>`」，整段渲染时词头与卡片标题重复、音标埋在正文里、
 *    中英释义一个字号混排，长词条很难读。
 *  - 词条数据是我们自己生成的（`tools/dict/prepare_ecdict.py`），可以在词条里埋两个
 *    不可见标记：`<!--ecdict|c=…|o=…|t=…|b=…|f=…-->`（柯林斯星级/牛津/考试标签/词频）
 *    与 `<!--def-->`（英文释义起始位置）。解析出它们才能把「词典元信息」和「正文」分开排版。
 *
 * 兼容性：导入的 MDX/StarDict 词条没有这些标记，按同样的启发式拆分（整行 `<small>` 当词形、
 * 整行 `<i>` 当形态注、`n. …` 这类纯英文行当英文释义）。带块级标签（div/table/ul…）或未知
 * 标签的复杂词条不拆，原样交给 `fromHtml`，避免把排版拆坏。
 *
 * 纯函数、无 `android.*`：JVM 单测覆盖（ReaderLookupArticleTest）。
 */

package ua.acclorite.book_story.ui.reader

/** 词条自带的词典元信息（目前只有内置 ECDICT 会写）。 */
internal data class LookupMeta(
    /** 柯林斯星级 1..5。 */
    val collins: Int? = null,
    /** 是否牛津核心词表（Oxford 3000）。 */
    val oxford: Boolean = false,
    /** 考试标签：zk / gk / cet4 / cet6 / ky / toefl / ielts / gre。 */
    val tags: List<String> = emptyList(),
    /** BNC 词频排名。 */
    val bncRank: Int? = null,
    /** 当代语料库词频排名。 */
    val frqRank: Int? = null
) {
    val isEmpty: Boolean
        get() = collins == null && !oxford && tags.isEmpty() && frequencyRank == null

    /** 展示用词频：两个语料库里更靠前的那个名次。 */
    val frequencyRank: Int?
        get() = listOfNotNull(bncRank, frqRank).minOrNull()
}

/** 一条义项。[label] 是词性或领域标签（`n.` / `vt.` / `[医]`），没有时为 null。 */
internal data class LookupSense(
    val label: String?,
    val html: String
)

/**
 * 一条英文释义。[pos] 是 WordNet 的词性前缀（`n.` / `v.` / `a.` / `s.` / `r.`），
 * 单独拿出来渲染成小徽标，让人一眼看出是词性而不是释义正文。
 */
internal data class LookupDefinition(
    val pos: String?,
    val html: String
)

/** 结构化后的词条正文。 */
internal sealed interface LookupBody {

    val phonetic: String?
    val meta: LookupMeta?

    /**
     * 拆成「义项 / 英文释义 / 附注」三段的简单词条。
     * 每段保留原始 HTML 片段（链接仍然可点）。
     */
    data class Sections(
        override val phonetic: String?,
        override val meta: LookupMeta?,
        val senses: List<LookupSense>,
        val definitions: List<LookupDefinition>,
        val notes: List<String>
    ) : LookupBody {
        val isEmpty: Boolean
            get() = senses.isEmpty() && definitions.isEmpty() && notes.isEmpty()
    }

    /** 结构太复杂（块级标签等），整段交给 `fromHtml`。 */
    data class Rich(
        override val phonetic: String?,
        override val meta: LookupMeta?,
        val html: String
    ) : LookupBody
}

/** ECDICT 资产里英文释义的起始标记，见 `tools/dict/prepare_ecdict.py`。 */
internal const val LOOKUP_DEFINITION_MARKER = "<!--def-->"

private val DEFINITION_MARKER_REGEX =
    Regex(Regex.escape(LOOKUP_DEFINITION_MARKER), RegexOption.IGNORE_CASE)

/** 元信息注释：`<!--ecdict|c=4|o=1|t=cet4,cet6|b=1234|f=2345-->`。 */
private val META_COMMENT_REGEX =
    Regex("""<!--\s*ecdict\b(.*?)-->""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

private val BR_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

private val TAG_REGEX = Regex("""<\s*/?\s*([a-zA-Z][a-zA-Z0-9]*)\b[^>]*>""")

/** 出现这些标签说明词条有块级结构，拆行会破坏排版。 */
private val BLOCK_TAG_REGEX = Regex(
    """</?\s*(div|p|ul|ol|li|dl|dt|dd|table|thead|tbody|tfoot|tr|td|th|h[1-6]|img|section|article|blockquote|hr|pre|code|style|script)\b""",
    RegexOption.IGNORE_CASE
)

/** 只允许行内标签；`font` 是导入词典里常见的老式写法。 */
private val ALLOWED_INLINE_TAGS = setOf(
    "b", "strong", "i", "em", "u", "s", "strike", "del", "small", "big",
    "sub", "sup", "a", "span", "font", "br"
)

/** 整行只有 `<b>词</b>`（词头重复）。 */
private val BOLD_ONLY_REGEX = Regex(
    """^<(b|strong)>\s*(.*?)\s*</\1>$""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

/** 整行只有 `<small>词形码</small>`。 */
private val SMALL_ONLY_REGEX = Regex(
    """^<small>\s*(.*?)\s*</small>$""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

/** 整行只有 `<i>词性/形态</i>`。 */
private val ITALIC_ONLY_REGEX = Regex(
    """^<(i|em)>\s*(.*?)\s*</\1>$""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

/** 词性标签接释义，如 `n. 苹果` / `vt. 感觉`。 */
private val SENSE_LABEL_REGEX = Regex("""^([A-Za-z]{1,6})\.\s*(.+)$""")

/** 领域标签接释义，如 `[医] 苹果` / `[网络] 兜帽`。 */
private val DOMAIN_LABEL_REGEX = Regex("""^(\[[^\]\n]{1,10}\])\s*(.+)$""")

/** ECDICT 用到的词性缩写；白名单以外的 `xx.` 不当标签（避免把 `e.g.` 之类当成词性）。 */
private val SENSE_LABELS = setOf(
    "n", "v", "vt", "vi", "a", "ad", "adj", "adv", "prep", "conj", "pron", "num",
    "art", "aux", "int", "interj", "abbr", "pl", "pref", "suf", "suff", "comb",
    "un", "na", "vbl", "pla", "st", "sing", "plu"
)

/** 纯英文释义行：`n. fruit with…` / `a. having…` / `s. …` / `r. …`。 */
private val DEFINITION_REGEX = Regex("""^[a-z]{1,6}\.\s+\S""")

/** 英文释义的词性前缀。 */
private val DEFINITION_PREFIX_REGEX = Regex("""^([a-z]{1,6}\.)\s+(.+)$""")

private val PHONETIC_SPAN_REGEX = Regex(
    """^<span[^>]*>\s*(.*?)\s*</span>$""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

private val EXCHANGE_BLOCK = Regex("<small>([^<]*)</small>", RegexOption.IGNORE_CASE)

/**
 * ECDICT 的 CSV 里换行是字面量 `\n`（不是真实换行），部分词典还混用真实换行；
 * `fromHtml` 会把它们当普通空格。统一转成 `<br>` 再交给 Compose 的 HTML 解析器。
 * 不处理 `<pre>`：词典释义里没有需要保留空白的代码块。
 *
 * 另外把 ECDICT 的 `exchange` 机器码（`p:resulted/s:results`）转成人能读的词形说明。
 */
internal fun normalizeDictionaryHtml(html: String): String = html
    .replace("\\r\\n", "<br>")
    .replace("\\n", "<br>")
    .replace("\r\n", "<br>")
    .replace("\r", "<br>")
    .replace("\n", "<br>")
    .replace(EXCHANGE_BLOCK) { match ->
        val humanized = humanizeExchange(match.groupValues[1]) ?: return@replace match.value
        if (humanized.isEmpty()) "" else "<small>词形：$humanized</small>"
    }

private val EXCHANGE_LABELS = mapOf(
    "p" to "过去式",
    "d" to "过去分词",
    "i" to "现在分词",
    "3" to "第三人称单数",
    "r" to "比较级",
    "t" to "最高级",
    "s" to "复数",
    "0" to "原形"
)

/**
 * ECDICT 的 `exchange` 字段是 `p:resulted/s:results` 这种机器码。原样显示既占地方
 * 又看不懂；这里转成「词形：过去式 resulted · 复数 results」。不认识的 `1:` 等
 * 变换类型直接忽略；没有冒号的 `<small>`（导入词典自己的注释）保持原样。
 */
private fun humanizeExchange(raw: String): String? {
    val parts = raw.split('/').filter { it.isNotBlank() }
    if (parts.none { it.contains(':') }) return null
    val mapped = parts.mapNotNull { part ->
        val separator = part.indexOf(':')
        if (separator <= 0) return@mapNotNull null
        val label = EXCHANGE_LABELS[part.substring(0, separator).lowercase()] ?: return@mapNotNull null
        val value = part.substring(separator + 1).trim()
        if (value.isEmpty()) null else "$label $value"
    }
    return mapped.joinToString(" · ")
}

/**
 * 词条 HTML → 可直接分栏渲染的正文。
 *
 * @param word 卡片标题上的词；用来识别并丢掉重复的词头行。
 */
internal fun parseLookupBody(html: String, word: String): LookupBody {
    val normalized = normalizeDictionaryHtml(html)
    val meta = parseLookupMeta(normalized)
    val body = normalized.replace(META_COMMENT_REGEX, "")
    if (body.isBlank()) return LookupBody.Rich(null, meta, body)
    // 块级结构的词条整段交给 fromHtml：标记注释不能漏进正文（HTML 注释不保证被解析器忽略）。
    if (!hasOnlyInlineMarkup(body)) return LookupBody.Rich(null, meta, stripLookupMarkers(body))

    val lines = body.split(BR_REGEX).map { it.trim() }.filter { it.isNotEmpty() }
    var index = 0

    // 1. 重复的词头：卡片标题已经显示了，正文里再放一个加粗的词只会显得乱。
    if (lines.getOrNull(index)?.let { isDuplicateWordLine(it, word) } == true) index++

    // 2. 音标：提到标题下面，不再埋在正文第一行。
    val phonetic = lines.getOrNull(index)?.let(::phoneticOf)?.also { index++ }

    val senses = mutableListOf<LookupSense>()
    val definitions = mutableListOf<LookupDefinition>()
    val notes = mutableListOf<String>()
    var inDefinitions = false

    for (raw in lines.drop(index)) {
        var line = raw
        if (line.startsWith(LOOKUP_DEFINITION_MARKER, ignoreCase = true)) {
            inDefinitions = true
            line = line.substring(LOOKUP_DEFINITION_MARKER.length).trim()
            if (line.isEmpty()) continue
        }
        val small = SMALL_ONLY_REGEX.matchEntire(line)
        val italic = ITALIC_ONLY_REGEX.matchEntire(line)
        when {
            // 词形/形态注：整行 <small> 或 <i>，只保留里面的文字，样式由卡片统一给。
            small != null -> notes += small.groupValues[1]
            italic != null -> notes += italic.groupValues[2]
            inDefinitions -> definitions += splitDefinition(line)
            looksLikeEnglishDefinition(line) -> definitions += splitDefinition(line)
            else -> senses += splitSense(line)
        }
    }

    val sections = LookupBody.Sections(phonetic, meta, senses, definitions, notes)
    // 拆完什么都没有（例如只有词头）时退回整段渲染，别显示空卡片。
    return if (sections.isEmpty) {
        LookupBody.Rich(phonetic, meta, stripLookupMarkers(body))
    } else {
        sections
    }
}

private fun stripLookupMarkers(html: String): String =
    html.replace(LOOKUP_DEFINITION_MARKER, "", ignoreCase = true)

/**
 * 解析 `<!--ecdict|c=…|o=…|t=…|b=…|f=…-->`。
 * 未知 key 忽略；数值越界（0/负数/非数字）按缺失处理。
 */
internal fun parseLookupMeta(html: String): LookupMeta? {
    val raw = META_COMMENT_REGEX.find(html)?.groupValues?.get(1) ?: return null
    var collins: Int? = null
    var oxford = false
    val tags = mutableListOf<String>()
    var bnc: Int? = null
    var frq: Int? = null

    raw.split('|').forEach { part ->
        val separator = part.indexOf('=')
        if (separator <= 0) return@forEach
        val key = part.substring(0, separator).trim().lowercase()
        val value = part.substring(separator + 1).trim()
        when (key) {
            "c" -> collins = value.toIntOrNull()?.takeIf { it in 1..5 }
            "o" -> oxford = value == "1" || value.equals("true", ignoreCase = true)
            "t" -> tags += value.split(',', ' ', ';').filter { it.isNotBlank() }
            "b" -> bnc = value.toIntOrNull()?.takeIf { it > 0 }
            "f" -> frq = value.toIntOrNull()?.takeIf { it > 0 }
        }
    }

    return LookupMeta(collins, oxford, tags, bnc, frq).takeIf { !it.isEmpty }
}

/** 把行拆成「词性/领域标签 + 释义」。没有标签时 label 为 null。 */
internal fun splitSense(line: String): LookupSense {
    SENSE_LABEL_REGEX.matchEntire(line)?.let { match ->
        val label = match.groupValues[1]
        if (label.lowercase() in SENSE_LABELS) {
            return LookupSense("$label.", match.groupValues[2].trim())
        }
    }
    DOMAIN_LABEL_REGEX.matchEntire(line)?.let { match ->
        return LookupSense(match.groupValues[1], match.groupValues[2].trim())
    }
    return LookupSense(null, line)
}

/**
 * 没有 `<!--def-->` 标记时（导入的词典）的兜底：纯英文 + `n. ` 这类词性开头算英文释义。
 * 中文义项不满足（含 CJK），ECDICT 的旧资产也能大致分开。
 */
private fun looksLikeEnglishDefinition(line: String): Boolean {
    val text = plainText(line)
    if (text.isEmpty() || text.any(::isCjk)) return false
    return DEFINITION_REGEX.containsMatchIn(text)
}

/** 把英文释义拆成「词性前缀 + 正文」。前缀只认行首的纯 ASCII（`n. `），其余原样保留。 */
internal fun splitDefinition(line: String): LookupDefinition =
    DEFINITION_PREFIX_REGEX.matchEntire(line)?.let { match ->
        LookupDefinition(match.groupValues[1], match.groupValues[2].trim())
    } ?: LookupDefinition(null, line)

private fun isDuplicateWordLine(line: String, word: String): Boolean {
    val inner = BOLD_ONLY_REGEX.matchEntire(line)?.groupValues?.get(2) ?: return false
    val headword = plainText(inner)
    if (headword.isEmpty()) return false
    return headword.equals(word.trim(), ignoreCase = true)
}

/** 音标行（`<span style="color:#888">/ˈæpl/</span>` 或裸 `/…/`）。 */
private fun phoneticOf(line: String): String? {
    val inner = PHONETIC_SPAN_REGEX.matchEntire(line)?.groupValues?.get(1) ?: line
    val phonetic = plainText(inner)
    if (phonetic.length !in 2..40) return null
    if (phonetic.any(::isCjk)) return null
    val wrappedInSlashes = phonetic.startsWith("/") && phonetic.endsWith("/")
    val wrappedInBrackets = phonetic.startsWith("[") && phonetic.endsWith("]")
    if (!wrappedInSlashes && !wrappedInBrackets) return null
    if (phonetic.contains('<') || phonetic.contains('>')) return null
    return phonetic
}

/** 词条是否只由行内标签组成（拆行不会破坏结构）。 */
private fun hasOnlyInlineMarkup(html: String): Boolean {
    if (BLOCK_TAG_REGEX.containsMatchIn(html)) return false
    TAG_REGEX.findAll(html).forEach { match ->
        val tag = match.groupValues[1].lowercase()
        if (tag !in ALLOWED_INLINE_TAGS) return false
    }
    return true
}

/** 去掉标签与常见实体，用于比较/判定（不是通用 HTML 解析）。 */
internal fun plainText(html: String): String = html
    .replace(TAG_REGEX, "")
    .replace("&nbsp;", " ")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#x27;", "'")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("&amp;", "&")
    .trim()

private fun isCjk(c: Char): Boolean {
    val code = c.code
    return code in 0x2E80..0x2FFF ||   // CJK 部首/标点
        code in 0x3040..0x30FF ||      // 平假名/片假名
        code in 0x3400..0x4DBF ||      // CJK 扩展 A
        code in 0x4E00..0x9FFF ||      // CJK 基本区
        code in 0xF900..0xFAFF ||      // CJK 兼容表意
        code in 0xAC00..0xD7AF ||      // 谚文音节
        code in 0xFF00..0xFFEF         // 全角符号
}
