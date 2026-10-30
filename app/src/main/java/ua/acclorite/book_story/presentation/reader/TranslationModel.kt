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
 * 关键约束（设计 §7.3）：
 *  - **缓存优先**：打开对话框先读 translation 缓存；命中直接展示、不建网络请求。
 *    语法缓存并行预取，但**不**发请求 —— 只有切到语法标签才请求。
 *  - **同一 cacheKey 的并发请求去重**：`inFlight` 里已有活跃 Job 就不再发第二次。
 *    重复点标签、预取与请求撞车都走这条。
 *  - **只写完整结果**：流式中途取消/失败不调用 `put`；落库前再校验一次「这次 open 还是不是
 *    当前的、协程有没有被取消」，晚到的旧结果不能盖掉重新生成的新结果。
 *    键用的是打开那一刻的配置快照（[Session]），与本次 open 绑定。
 *  - **取消不留半截**：`dismiss()` 取消全部 Job 并递增 requestId；迟到的 delta
 *    既过不了 `isActive` 也过不了 requestId 校验，不会把关掉的对话框画回来。
 *  - **写缓存失败不能盖掉结果**：`put` 抛异常只记日志，译文/语法照常展示。
 */

package ua.acclorite.book_story.presentation.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import ua.acclorite.book_story.presentation.reader.model.AsyncGrammar
import ua.acclorite.book_story.presentation.reader.model.AsyncText
import ua.acclorite.book_story.presentation.reader.model.TranslationState
import ua.acclorite.book_story.presentation.reader.model.TranslationTab
import ua.acclorite.book_story.presentation.reader.model.parseGrammarAnalysis
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
     * 一次 open 的上下文。缓存键在这里固定：切标签、重新生成都用同一份，
     * 不会因为设置中途变化而算出与本次 open 不一致的键。
     *
     * 只有**目标语言**同时进了请求（`ai.translate(text, targetLanguage)`）；model/baseUrl 由
     * [AiTranslationRepository] 在请求开始时现取设置，所以「键与请求同一份配置」只对目标语言
     * 成立（对话框开着时改不了设置，实践上碰不到 app 内改模型的情况）。
     */
    private data class Session(
        val requestId: Long,
        val text: String,
        val bookId: Int,
        val nodeId: Int?,
        val config: AiRequestConfig,
        val translationKey: String,
        val grammarKey: String
    )

    private val _state = MutableStateFlow(TranslationState())
    val state: StateFlow<TranslationState> = _state.asStateFlow()

    /**
     * 语法 JSON 解析用的调度器。默认 [Dispatchers.Default]：解析不在主线程做。
     * 单测换成测试调度器 —— 真实线程上的解析让 `advanceUntilIdle` 看不到结果。
     */
    internal var parseDispatcher: CoroutineDispatcher = Dispatchers.Default

    private var session: Session? = null

    /** 同一 cacheKey 的并发请求去重（设计 §7.3）。 */
    private val inFlight = mutableMapOf<String, Job>()

    private var translationJob: Job? = null
    private var grammarProbeJob: Job? = null
    private var grammarJob: Job? = null

    /**
     * 打开对话框。
     *
     * @param text 双击时是整段 `NodeContent.Paragraph.text`；长按选区是选中的文字。
     * @param nodeId 段落节点 id；选区传 null。
     */
    fun open(text: String, bookId: Int, nodeId: Int?) {
        if (text.isBlank()) return

        cancelJobs()
        val config = configProvider.current()
        val session = Session(
            requestId = _state.value.requestId + 1,
            text = text,
            bookId = bookId,
            nodeId = nodeId,
            config = config,
            translationKey = cacheKey(text, TranslationKind.TRANSLATION, config),
            grammarKey = cacheKey(text, TranslationKind.GRAMMAR, config)
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
            targetLanguage = config.targetLanguage,
            targetLanguageName = config.targetLanguageName,
            model = config.model,
            grammarAvailable = config.grammarEnabled,
            requestId = session.requestId
        )

        startTranslation(session, force = false)
        startGrammarProbe(session)
    }

    /**
     * 切标签。语法标签「点击即分析」（设计 §8）：没有缓存就立刻请求，没有「开始分析」按钮。
     * 重复点击/请求进行中都不会发出第二个请求。
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
                if (current.grammar is AsyncGrammar.Success) return
                if (current.grammar is AsyncGrammar.Error) return
                if (grammarJob?.isActive == true) return
                startGrammarRequest(session, force = false)
            }
        }
    }

    /**
     * 重新生成 / 重试。
     *
     * 只重置指定标签：另一侧的译文/分析保持不变（用户可能正拿它对照）。
     * 仓库没有按单键删除的 API，重新请求后 `put` 直接覆盖同一键（`REPLACE`）。
     */
    fun regenerate(tab: TranslationTab) {
        val session = session ?: return
        if (!_state.value.visible) return

        when (tab) {
            TranslationTab.TRANSLATION -> {
                translationJob?.cancel()
                translationJob = null
                _state.value = _state.value.copy(translation = AsyncText.Loading)
                startTranslation(session, force = true)
            }

            TranslationTab.GRAMMAR -> {
                grammarJob?.cancel()
                grammarJob = null
                _state.value = _state.value.copy(grammar = AsyncGrammar.Loading)
                startGrammarRequest(session, force = true)
            }
        }
    }

    /** 关闭对话框：取消全部请求（已完成的结果已经在缓存里）。 */
    fun dismiss() {
        cancelJobs()
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
                    publish(session.requestId) {
                        it.copy(translation = AsyncText.Error(AiError.UNKNOWN, null))
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

    /** 只读缓存，不发请求：语法标签没被点开之前不产生费用（设计 §7.3）。 */
    private fun startGrammarProbe(session: Session) {
        if (!session.config.grammarEnabled) return

        val job = viewModelScope.launch {
            val cached = cachedPayload(session.grammarKey) ?: return@launch
            val result = grammarResult(cached.payload, fromCache = true)
            publish(session.requestId) { state ->
                // 用户已经切到语法标签并开始请求时，不能被预取结果覆盖。
                if (state.grammar is AsyncGrammar.Idle) state.copy(grammar = result) else state
            }
        }
        grammarProbeJob = job
    }

    private fun startGrammarRequest(session: Session, force: Boolean) {
        val key = session.grammarKey
        if (inFlight[key]?.isActive == true) return

        val job = viewModelScope.launch {
            try {
                if (!force) {
                    // 等预取落定：命中缓存时它已经把结果写进状态，这里不用再请求。
                    grammarProbeJob?.join()
                    val current = _state.value
                    if (current.requestId == session.requestId &&
                        current.grammar is AsyncGrammar.Success
                    ) {
                        return@launch
                    }

                    val cached = cachedPayload(key)
                    if (cached != null) {
                        val result = grammarResult(cached.payload, fromCache = true)
                        publish(session.requestId) { it.copy(grammar = result) }
                        return@launch
                    }
                }

                publish(session.requestId) { it.copy(grammar = AsyncGrammar.Loading) }
                val raw = ai.analyzeGrammar(session.text, session.config.targetLanguage)
                    .getOrElse { throw it }

                putCache(session, TranslationKind.GRAMMAR, key, raw)
                val result = grammarResult(raw, fromCache = false)
                publish(session.requestId) { it.copy(grammar = result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val mapped = mapAiException(e)
                logW(TAG, "Grammar analysis failed: ${mapped.error.name}")
                publish(session.requestId) {
                    it.copy(grammar = AsyncGrammar.Error(mapped.error, mapped.statusCode))
                }
            }
        }

        register(key, job)
        grammarJob = job
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

    /** 缓存读失败不该让整次请求失败：记日志后当作未命中。 */
    private suspend fun cachedPayload(key: String): CachedAiResult? = try {
        cache.get(key)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logW(TAG, "Could not read the AI cache: ${e.message}")
        null
    }

    /** 解析放在 [parseDispatcher]（默认 [Dispatchers.Default]）上：语法 JSON 不该在主线程解。 */
    private suspend fun grammarResult(payload: String, fromCache: Boolean) = AsyncGrammar.Success(
        analysis = withContext(parseDispatcher) { parseGrammarAnalysis(payload) },
        raw = payload,
        fromCache = fromCache
    )

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
        grammarProbeJob?.cancel()
        grammarJob?.cancel()
        translationJob = null
        grammarProbeJob = null
        grammarJob = null
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
