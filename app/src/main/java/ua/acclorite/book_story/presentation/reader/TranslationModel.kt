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
            _state.value = current.copy(selectedSentenceIndex = null, grammar = AsyncGrammar.Idle)
            return
        }

        val stored = grammarResults[grammarKey(session, current.sentences[index])]
        _state.value = current.copy(
            selectedSentenceIndex = index,
            grammar = stored ?: AsyncGrammar.Idle
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
                inFlight[grammarKey(session, current.sentences[index])]?.cancel()
                _state.value = current.copy(grammar = AsyncGrammar.Loading)
                startGrammarRequest(session, index, force = true)
            }
        }
    }

    /** 关闭对话框：取消全部请求（已完成的结果已经在缓存里）。 */
    fun dismiss() {
        cancelJobs()
        grammarResults.clear()
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
                ai.translate(session.text, session.config.targetLanguage).collect { delta ->
                    builder.append(delta)
                    val text = builder.toString()
                    publish(session.requestId) { it.copy(translation = AsyncText.Streaming(text)) }
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
     * 完成后 `put` Markdown 原文，并留一份在 [grammarResults] 里。
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
                val markdown = ai.analyzeGrammar(sentence, session.config.targetLanguage)
                    .getOrElse { throw it }

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
        if (result is AsyncGrammar.Success) grammarResults[key] = result
        publish(session.requestId) { state ->
            val selected = state.selectedSentenceIndex
            if (selected == null || state.sentences.getOrNull(selected) != sentence) {
                state
            } else {
                state.copy(grammar = result)
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
