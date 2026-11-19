/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：翻译/语法对话框的状态机（第二阶段 S8，设计 §7.3/§8）。
 *
 * 与 ReaderModel 分开的理由和 DictionaryLookupModel 一样：翻译是旁挂功能，
 * 不该进阅读进度状态机；对话框关掉就该把请求取消干净。
 *
 * 关键约束（设计 §7.3/§8.2）：
 *  - **缓存优先**：打开对话框先读 translation 缓存；命中直接展示、不建网络请求。
 *  - **两个标签都流式**：译文与语法分析都是 SSE 逐块产出，边到边更新 `AsyncText.Streaming` /
 *    `AsyncGrammar.Streaming`；**只有完整结果才落库**（空结果算失败，`EMPTY_RESPONSE`）。
 *  - **语法逐句**：`open` 只切句不请求；两句以上时切到语法标签也不请求，用户点中哪句才分析哪句。
 *    结果按 `translationCacheKey(sentence, GRAMMAR, …)` 缓存，本次打开期间还多留一份在
 *    [grammarResults] 里，收起再展开不用重新请求。
 *  - **同一 cacheKey 的并发请求去重**：`inFlight` 里已有活跃 Job 就不再发第二次。
 *  - **迟到的结果不覆盖新选择**：发布前校验「还是不是这次 open」+「这一句还是不是选中的那句」。
 *    切换句子不取消上一句的在途请求（跑完会进缓存，切回去直接显示），靠这两条校验丢弃它。
 *  - **只写完整结果**：请求失败/取消不调用 `put`；落库前再校验一次「这次 open 还是不是
 *    当前的、协程有没有被取消」，晚到的旧结果不能盖掉重新分析的新结果。
 *  - **取消不留半截**：`dismiss()` 取消全部 Job 并递增 requestId。
 *  - **写缓存失败不能盖掉结果**：`put` 抛异常只记日志，译文/语法照常展示。
 */

package ua.acclorite.book_story.presentation.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.acclorite.book_story.core.log.logW
import ua.acclorite.book_story.data.ai.AI_PROMPT_VERSION
import ua.acclorite.book_story.data.ai.AiError
import ua.acclorite.book_story.data.ai.AiRequestConfig
import ua.acclorite.book_story.data.ai.AiRequestConfigProvider
import ua.acclorite.book_story.data.ai.AiTranslationRepository
import ua.acclorite.book_story.data.ai.cache.CachedAiResult
import ua.acclorite.book_story.data.ai.cache.TranslationCacheRepository
import ua.acclorite.book_story.data.ai.cache.TranslationKind
import ua.acclorite.book_story.data.ai.cache.translationCacheKey
import ua.acclorite.book_story.data.ai.mapAiException
import ua.acclorite.book_story.data.ai.splitIntoSentences
import ua.acclorite.book_story.presentation.reader.model.AsyncGrammar
import ua.acclorite.book_story.presentation.reader.model.AsyncText
import ua.acclorite.book_story.presentation.reader.model.TranslationState
import ua.acclorite.book_story.presentation.reader.model.TranslationTab
import javax.inject.Inject

private const val TAG = "TranslationModel"

/** 长按选区没有段落节点；缓存表的 `nodeId` 非空，用 -1 表示「不是段落」（不参与缓存键）。 */
private const val UNKNOWN_NODE_ID = -1

/**
 * 语法流式输出的发布步长（字符）。
 *
 * 语法结果是 Markdown，每变一次就要重新解析 + 重排版；一个 token 一次推送（~10–50 次/秒）
 * 在低端机上能明显拖慢对话框。攒够这么多个字符才推一次中间态，**最终结果总会推送**。
 * 按长度而不按时间节流：单测跑在虚拟时间上，长度阈值才可复现。
 */
private const val GRAMMAR_STREAM_PUBLISH_STEP = 32

@HiltViewModel
class TranslationModel @Inject constructor(
    private val ai: AiTranslationRepository,
    private val cache: TranslationCacheRepository,
    private val configProvider: AiRequestConfigProvider
) : ViewModel() {

    /**
     * 一次 open 的上下文。翻译缓存键在这里固定：切标签、重新生成都用同一份，
     * 不会因为设置中途变化而算出与本次 open 不一致的键。语法键按句现算（[grammarKey]）。
     *
     * 只有**目标语言**同时进了请求（`ai.translate(text, targetLanguage)`）；model/baseUrl 由
     * [AiTranslationRepository] 在请求开始时现取设置，所以「键与请求同一份配置」只对目标语言
     * 成立（对话框开着时改不了设置，实践上碰不到 app 内改模型的情况）。
     */
    private data class Session(
        val requestId: Long,
        val text: String,
        val sentences: List<String>,
        val bookId: Int,
        val nodeId: Int?,
        val config: AiRequestConfig,
        val translationKey: String
    )

    private val _state = MutableStateFlow(TranslationState())
    val state: StateFlow<TranslationState> = _state.asStateFlow()

    private var session: Session? = null

    /** 同一 cacheKey 的并发请求去重（设计 §7.3）。 */
    private val inFlight = mutableMapOf<String, Job>()

    /**
     * 本次打开期间已经拿到的逐句语法结果，键是这一句的 grammar cacheKey。
     * 不放进 [TranslationState]：UI 只展示当前展开的那一句，其余句子的结果只是缓存。
     */
    private val grammarResults = mutableMapOf<String, AsyncGrammar.Success>()

    /**
     * 还在流式输出的句子片段（键同上）。
     *
     * 只为了「写到一半时收起再展开还能接着看」：收起的时候状态回到 Idle，而请求照旧在跑，
     * 片段下一块才会再推给状态 —— 没有这一份就会短暂空白。写完（成功/失败）就从这里删掉：
     * 成功结果进 [grammarResults]，失败不缓存。
     */
    private val grammarStreaming = mutableMapOf<String, String>()

    private var translationJob: Job? = null

    /**
     * 打开对话框。
     *
     * @param text 双击时是整段 `NodeContent.Paragraph.text`；长按选区是选中的文字。
     * @param nodeId 段落节点 id；选区传 null。
     */
    fun open(text: String, bookId: Int, nodeId: Int?) {
        if (text.isBlank()) return

        cancelJobs()
        grammarResults.clear()
        grammarStreaming.clear()
        val config = configProvider.current()
        val sentences = splitIntoSentences(text)
        val session = Session(
            requestId = _state.value.requestId + 1,
            text = text,
            sentences = sentences,
            bookId = bookId,
            nodeId = nodeId,
            config = config,
            translationKey = cacheKey(text, TranslationKind.TRANSLATION, config)
        )
        this.session = session
        _state.value = TranslationState(
            visible = true,
            originalText = text,
            bookId = bookId,
            nodeId = nodeId,
            activeTab = TranslationTab.TRANSLATION,
            translation = AsyncText.Loading,
            grammar = AsyncGrammar.Idle,
            sentences = sentences,
            selectedSentenceIndex = null,
            targetLanguage = config.targetLanguage,
            targetLanguageName = config.targetLanguageName,
            model = config.model,
            grammarAvailable = config.grammarEnabled,
            requestId = session.requestId
        )

        startTranslation(session, force = false)
    }

    /**
     * 切标签。翻译标签「打开即翻译」不变。
     *
     * 语法标签不再「切到就分析」（设计 §8.2）：整段可能有好几句，一次分析整段只能得到笼统说明。
     * 只有**恰好一句**时等价于用户点了第一句，才自动开始；两句以上等用户选（见 [selectSentence]）。
     */
    fun selectTab(tab: TranslationTab) {
        val session = session ?: return
        val current = _state.value
        if (!current.visible) return
        // 设置里关掉语法标签时 UI 不显示它；这里是程序化调用的兜底，不发请求也不切标签。
        if (tab == TranslationTab.GRAMMAR && !current.grammarAvailable) return
        if (current.activeTab != tab) _state.value = current.copy(activeTab = tab)

        when (tab) {
            TranslationTab.TRANSLATION -> {
                if (current.translation is AsyncText.Success) return
                if (current.translation is AsyncText.Error) return
                if (translationJob?.isActive == true) return
                startTranslation(session, force = false)
            }

            TranslationTab.GRAMMAR -> {
                val latest = _state.value
                if (latest.selectedSentenceIndex != null) return
                if (latest.sentences.size == 1) selectSentence(0)
            }
        }
    }

    /**
     * 语法标签里点一句句子。
     *
     * - 点的就是当前展开的那句 → 收起（`selectedSentenceIndex = null`，语法状态回到 Idle）。
     *   不取消在途请求：它跑完会进缓存，重新展开时直接显示。
     * - 点的是另一句 → 展开它，收起上一句；本次打开已经算过就立刻显示，否则发请求（先读缓存）。
     *
     * 上一句的在途请求不会被取消，但它的结果只会写进缓存，不会覆盖当前展开的这句
     * （[publishGrammar] 会校验选中的还是不是那一句）。
     */
    fun selectSentence(index: Int) {
        val session = session ?: return
        val current = _state.value
        if (!current.visible) return
        if (!current.grammarAvailable) return
        if (index !in current.sentences.indices) return

        if (current.selectedSentenceIndex == index) {
            _state.value = current.copy(
                selectedSentenceIndex = null,
                grammar = AsyncGrammar.Idle,
                // 收起就没有「正在分析」的句子了，上一句的推理字数提示跟着清掉。
                thinkingChars = 0
            )
            return
        }

        val key = grammarKey(session, current.sentences[index])
        val stored = grammarResults[key]
        _state.value = current.copy(
            selectedSentenceIndex = index,
            // 请求还在跑、结果还没写完时先接上已到达的片段：收起再展开不该是一块空白。
            grammar = stored
                ?: grammarStreaming[key]?.let { AsyncGrammar.Streaming(it) }
                ?: AsyncGrammar.Idle,
            // 切到另一句：上一条请求的推理字数不属于这一句。
            thinkingChars = 0
        )
        if (stored == null) startGrammarRequest(session, index, force = false)
    }

    /**
     * 重新生成 / 重试。
     *
     * 只重置指定标签：另一侧的译文/分析保持不变（用户可能正拿它对照）。
     * 仓库没有按单键删除的 API，重新请求后 `put` 直接覆盖同一键（`REPLACE`）。
     */
    fun regenerate(tab: TranslationTab) {
        val session = session ?: return
        val current = _state.value
        if (!current.visible) return

        when (tab) {
            TranslationTab.TRANSLATION -> {
                translationJob?.cancel()
                translationJob = null
                _state.value = current.copy(translation = AsyncText.Loading)
                startTranslation(session, force = true)
            }

            TranslationTab.GRAMMAR -> {
                val index = current.selectedSentenceIndex
                if (index == null) {
                    // 只有一句时没有可点的句子列表，等价于重新分析那一句。
                    if (current.sentences.size == 1) selectSentence(0)
                    return
                }
                // 取消这一句的在途请求：重新分析必须让新结果落地，不能被旧请求先写进缓存。
                // 同时丢掉上一轮的片段，否则中途收起再展开会看到旧的一半。
                val key = grammarKey(session, current.sentences[index])
                inFlight[key]?.cancel()
                grammarStreaming.remove(key)
                _state.value = current.copy(grammar = AsyncGrammar.Loading)
                startGrammarRequest(session, index, force = true)
            }
        }
    }

    /**
     * 本次打开期间已经分析过的某句语法（Markdown 原文）；没分析过返回 null。
     *
     * 收藏句子时用它保住「已经分析过、但当前没展开」的那份结果：UI 状态里只有展开句的语法，
     * 收起或切到另一句之后旧结果就只剩下这里一份。
     */
    fun grammarFor(sentence: String): String? =
        session?.let { s -> grammarResults[grammarKey(s, sentence)]?.text }

    /** 关闭对话框：取消全部请求（已完成的结果已经在缓存里）。 */
    fun dismiss() {
        cancelJobs()
        grammarResults.clear()
        grammarStreaming.clear()
        session = null
        _state.value = TranslationState(requestId = _state.value.requestId + 1)
    }

    /* ------ Translation ---------------------------------- */

    private fun startTranslation(session: Session, force: Boolean) {
        val key = session.translationKey
        if (inFlight[key]?.isActive == true) return

        val job = viewModelScope.launch {
            try {
                if (!force) {
                    val cached = cachedPayload(key)
                    if (cached != null) {
                        publish(session.requestId) {
                            it.copy(translation = AsyncText.Success(cached.payload, fromCache = true))
                        }
                        return@launch
                    }
                }

                publish(session.requestId) { it.copy(translation = AsyncText.Loading) }
                val builder = StringBuilder()
                ai.translate(
                    text = session.text,
                    targetLanguage = session.config.targetLanguage,
                    onThinking = { chars -> publishThinking(session.requestId, chars) }
                ).collect { delta ->
                    builder.append(delta)
                    val text = builder.toString()
                    // 正文开始了：思考阶段的字数不再有意义，顺手清零（同一个状态字段两处共用）。
                    publish(session.requestId) {
                        it.copy(translation = AsyncText.Streaming(text), thinkingChars = 0)
                    }
                }

                val result = builder.toString()
                if (result.isBlank()) {
                    // 空结果按失败处理：不能把空串当译文写进缓存，否则下次命中一张白纸。
                    // 用独立错误类别，UI 才能说“模型没有返回内容”而不是“未知错误”。
                    publish(session.requestId) {
                        it.copy(translation = AsyncText.Error(AiError.EMPTY_RESPONSE, null))
                    }
                    return@launch
                }

                // 先落库再展示：写库失败只记日志，不能把已经拿到的译文藏起来。
                putCache(session, TranslationKind.TRANSLATION, key, result)
                publish(session.requestId) {
                    it.copy(translation = AsyncText.Success(result, fromCache = false))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 只记错误类别：第三方异常的 message 里可能带响应体片段，正文不该进日志。
                val mapped = mapAiException(e)
                logW(TAG, "Translation failed: ${mapped.error.name}")
                publish(session.requestId) {
                    it.copy(translation = AsyncText.Error(mapped.error, mapped.statusCode))
                }
            }
        }

        register(key, job)
        translationJob = job
    }

    /* ------ Grammar -------------------------------------- */

    /**
     * 分析第 [index] 句。先读这一句的缓存（`force` 时跳过），未命中才请求；
     * 请求是流式的：中间态推 [AsyncGrammar.Streaming]（按 [GRAMMAR_STREAM_PUBLISH_STEP]
     * 攒块），全部到齐后 `put` Markdown 原文并推 [AsyncGrammar.Success]。
     */
    private fun startGrammarRequest(session: Session, index: Int, force: Boolean) {
        val sentence = session.sentences.getOrNull(index) ?: return
        val key = grammarKey(session, sentence)
        if (inFlight[key]?.isActive == true) return

        val job = viewModelScope.launch {
            try {
                if (!force) {
                    val cached = cachedPayload(key)
                    if (cached != null) {
                        publishGrammar(
                            session = session,
                            sentence = sentence,
                            key = key,
                            result = AsyncGrammar.Success(cached.payload, fromCache = true)
                        )
                        return@launch
                    }
                }

                publishGrammar(session, sentence, key, AsyncGrammar.Loading)
                val builder = StringBuilder()
                var publishedLength = 0
                ai.analyzeGrammar(
                    text = sentence,
                    targetLanguage = session.config.targetLanguage,
                    // fork: 只推「当前展开的就是这一句」的推理进度。切到别的句子后，上一条
                    // 请求还在跑，它的字数不能跑到新展开的那句头上。
                    onThinking = { chars ->
                        if (_state.value.selectedSentenceIndex
                                ?.let(session.sentences::getOrNull) == sentence
                        ) {
                            publishThinking(session.requestId, chars)
                        }
                    }
                ).collect { delta ->
                    builder.append(delta)
                    // 第一块立刻渲染（`publishedLength == 0`）：Markdown 重排再贵也贵不过让用户
                    // 对着「分析中」干等；之后才按步长攒块。
                    if (publishedLength == 0 ||
                        builder.length - publishedLength >= GRAMMAR_STREAM_PUBLISH_STEP
                    ) {
                        publishedLength = builder.length
                        publishGrammar(
                            session = session,
                            sentence = sentence,
                            key = key,
                            result = AsyncGrammar.Streaming(builder.toString())
                        )
                    }
                }

                val markdown = builder.toString()
                if (markdown.isBlank()) {
                    // 空结果按失败处理：不能把空串当分析结果写进缓存，否则下次命中一片空白。
                    // 与译文同一条路径（`AsyncText` 侧也在状态机里判空）。
                    publishGrammar(
                        session = session,
                        sentence = sentence,
                        key = key,
                        result = AsyncGrammar.Error(AiError.EMPTY_RESPONSE, null)
                    )
                    return@launch
                }

                putCache(session, TranslationKind.GRAMMAR, key, markdown)
                publishGrammar(
                    session = session,
                    sentence = sentence,
                    key = key,
                    result = AsyncGrammar.Success(markdown, fromCache = false)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val mapped = mapAiException(e)
                logW(TAG, "Grammar analysis failed: ${mapped.error.name}")
                publishGrammar(
                    session = session,
                    sentence = sentence,
                    key = key,
                    result = AsyncGrammar.Error(mapped.error, mapped.statusCode)
                )
            }
        }

        register(key, job)
    }

    /**
     * 发布一句的结果。
     *
     * 成功结果先写进 [grammarResults]（这一句没被展开也留着，重新展开直接显示）；
     * 流式片段写进 [grammarStreaming]（只为了续显，不是结果）；写完就从片段表里删掉。
     * 状态只在「这次 open 还是当前的」且「展开的还是这一句」时才更新 —— 切换句子之后
     * 上一句迟到的结果只能进缓存，不能把当前这句的 loading/结果盖掉。
     */
    private fun CoroutineScope.publishGrammar(
        session: Session,
        sentence: String,
        key: String,
        result: AsyncGrammar
    ) {
        if (!isActive) return
        when (result) {
            is AsyncGrammar.Streaming -> grammarStreaming[key] = result.text
            is AsyncGrammar.Success -> {
                grammarResults[key] = result
                grammarStreaming.remove(key)
            }
            is AsyncGrammar.Error -> grammarStreaming.remove(key)
            AsyncGrammar.Idle, AsyncGrammar.Loading -> Unit
        }
        publish(session.requestId) { state ->
            val selected = state.selectedSentenceIndex
            if (selected == null || state.sentences.getOrNull(selected) != sentence) {
                state
            } else {
                // fork: 这一句已经有了实际输出（流式/完成/失败），推理阶段的字数提示就该消失；
                // 不重置的话上一句的 `thinkingChars` 会残留在新展开的那句上。
                val resetThinking = result is AsyncGrammar.Streaming ||
                    result is AsyncGrammar.Success ||
                    result is AsyncGrammar.Error
                state.copy(
                    grammar = result,
                    thinkingChars = if (resetThinking) 0 else state.thinkingChars
                )
            }
        }
    }

    /* ------ Cache / helpers ------------------------------ */

    private fun cacheKey(text: String, kind: TranslationKind, config: AiRequestConfig): String =
        translationCacheKey(
            text = text,
            kind = kind,
            targetLanguage = config.targetLanguageName,
            model = config.model,
            baseUrl = config.baseUrl
        )

    /** 语法缓存按**句子**算键：同一段里点第二句不会命中第一句的分析结果。 */
    private fun grammarKey(session: Session, sentence: String): String =
        cacheKey(sentence, TranslationKind.GRAMMAR, session.config)

    /** 缓存读失败不该让整次请求失败：记日志后当作未命中。 */
    private suspend fun cachedPayload(key: String): CachedAiResult? = try {
        cache.get(key)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logW(TAG, "Could not read the AI cache: ${e.message}")
        null
    }

    /**
     * 写完整结果。
     *
     * 写之前再确认一次「这次 open 还是当前的、跑它的协程没被取消」：仓库内部用 `NonCancellable`
     * 包住写库，被取消的旧请求如果把结果落库，会盖掉「重新生成」刚写进去的新译文。
     * 写失败只记日志，不影响展示。
     */
    private suspend fun putCache(
        session: Session,
        kind: TranslationKind,
        key: String,
        payload: String
    ) {
        if (!currentCoroutineContext().isActive) {
            logW(TAG, "Skipped caching a result from a cancelled request")
            return
        }
        if (_state.value.requestId != session.requestId) {
            logW(TAG, "Skipped caching a result from a stale request")
            return
        }

        try {
            cache.put(
                key = key,
                kind = kind,
                bookId = session.bookId,
                nodeId = session.nodeId ?: UNKNOWN_NODE_ID,
                payload = payload,
                model = session.config.model,
                baseUrl = session.config.baseUrl,
                targetLanguage = session.config.targetLanguageName,
                promptVersion = AI_PROMPT_VERSION,
                now = System.currentTimeMillis()
            )
        } catch (e: CancellationException) {
            logW(TAG, "AI cache write was cancelled")
        } catch (e: Exception) {
            logW(TAG, "Could not cache the AI result: ${e.message}")
        }
    }

    /** 登记到去重表；完成（含取消）后只清理自己那一格，避免删掉后来的同名请求。 */
    private fun register(key: String, job: Job) {
        inFlight[key] = job
        job.invokeOnCompletion {
            if (inFlight[key] === job) inFlight.remove(key)
        }
    }

    private fun cancelJobs() {
        translationJob?.cancel()
        translationJob = null
        // 先拷贝再取消：完成回调会改 inFlight，边遍历边改会抛 ConcurrentModificationException。
        inFlight.values.toList().forEach { it.cancel() }
        inFlight.clear()
    }

    /**
     * 推理阶段的进度。只是一个「还活着」的提示：不改 translation/grammar 的状态机，
     * 也不落库；正文一到（[AsyncText.Streaming] / [AsyncGrammar.Streaming]）就清零。
     */
    private fun publishThinking(requestId: Long, chars: Int) {
        if (_state.value.requestId != requestId) return
        if (_state.value.thinkingChars == chars) return
        _state.value = _state.value.copy(thinkingChars = chars)
    }

    /**
     * 只在自己还属于当前这次 open、且没被取消时写状态。取消后协程可能还会跑完最后几行，
     * 不拦住的话关掉的对话框会自己弹回来。
     */
    private fun CoroutineScope.publish(
        requestId: Long,
        transform: (TranslationState) -> TranslationState
    ) {
        if (!isActive) return
        if (_state.value.requestId != requestId) return
        _state.value = transform(_state.value)
    }
}
