/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：收藏夹的状态机（单词列表 / 句子列表 / 句子详情）。
 *
 * 三处界面共用一个 Activity 作用域的实例：
 *  - 取词卡片里的五角星（阅读页）；
 *  - 语法分析结果后的五角星（阅读页翻译对话框）；
 *  - 收藏页本身。
 * 这样「在卡片里点一下收藏」之后，收藏页列表与另一处五角星都会立刻变成实心 ——
 * 靠的是仓库的 Flow，而不是在各处手动通知。
 *
 * 句子详情的译文是**按需**请求的：收藏时只保证有语法分析；如果当时用户没打开过翻译标签，
 * translation 就是空的，打开收藏页的句子时才补一次，成功后回填数据库，下次打开零请求。
 *
 * 与 TranslationModel 的区别：这里只需要「一句 → 一段译文」，不需要逐句切分、缓存键、
 * 双标签流式状态机，所以不把它硬套进 TranslationModel（那会为了一个句子引入整套段落状态）。
 */

package ua.acclorite.book_story.presentation.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ua.acclorite.book_story.core.log.logW
import ua.acclorite.book_story.data.ai.AiError
import ua.acclorite.book_story.data.ai.AiRequestConfigProvider
import ua.acclorite.book_story.data.ai.AiTranslationRepository
import ua.acclorite.book_story.data.ai.mapAiException
import ua.acclorite.book_story.domain.model.favorite.FavoriteSentence
import ua.acclorite.book_story.domain.model.favorite.FavoriteTab
import ua.acclorite.book_story.domain.model.favorite.FavoriteWord
import ua.acclorite.book_story.domain.model.favorite.FavoriteWordDraft
import ua.acclorite.book_story.domain.repository.FavoriteRepository
import javax.inject.Inject

private const val TAG = "FavoritesModel"

/** 句子详情里译文区的状态。 */
sealed interface FavoriteTranslation {
    data object Loading : FavoriteTranslation

    data class Streaming(val text: String) : FavoriteTranslation

    data class Success(val text: String) : FavoriteTranslation

    data class Error(val error: AiError, val statusCode: Int?) : FavoriteTranslation
}

/** 收藏页上打开的句子详情。 */
data class FavoriteSentenceDialogState(
    val favorite: FavoriteSentence,
    val translation: FavoriteTranslation
)

@HiltViewModel
class FavoritesModel @Inject constructor(
    private val favorites: FavoriteRepository,
    private val ai: AiTranslationRepository,
    private val configProvider: AiRequestConfigProvider
) : ViewModel() {

    /**
     * `WhileSubscribed`：离开收藏页后停止订阅，回到阅读页时不会为了两列列表常驻数据库游标。
     * 再次进入时重新读一次（数据量很小）。
     */
    val words: StateFlow<List<FavoriteWord>> = favorites
        .observeWords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sentences: StateFlow<List<FavoriteSentence>> = favorites
        .observeSentences()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _tab = MutableStateFlow(FavoriteTab.WORDS)
    val tab: StateFlow<FavoriteTab> = _tab.asStateFlow()

    private val _sentenceDialog = MutableStateFlow<FavoriteSentenceDialogState?>(null)
    val sentenceDialog: StateFlow<FavoriteSentenceDialogState?> = _sentenceDialog.asStateFlow()

    /**
     * 五角星的「先查再写」必须是原子的：连点两下时两个协程都读到「未收藏」，
     * 就会连续写两次而不是「收藏→取消」。所有 toggle 共用一把锁，开销可以忽略。
     */
    private val toggleMutex = Mutex()

    private var translationJob: Job? = null

    /** 当前打开着的句子（trim 后的键）。晚到的翻译结果靠它判断是否还属于当前详情。 */
    private var openSentenceKey: String? = null

    fun selectTab(tab: FavoriteTab) {
        _tab.value = tab
    }

    /** 供取词卡片订阅：当前词是否已收藏。 */
    fun observeWord(word: String): Flow<Boolean> = favorites.observeWord(word)

    /** 供语法分析结果订阅：当前句是否已收藏。 */
    fun observeSentence(sentence: String): Flow<Boolean> = favorites.observeSentence(sentence)

    /**
     * 收藏/取消收藏一个单词。
     *
     * 用「先查再写」而不是 upsert：五角星是切换语义，用户点第二下要的是删掉；
     * `REPLACE` 只会把它又写回去，看起来像点了没反应。
     */
    fun toggleWord(draft: FavoriteWordDraft) {
        if (draft.word.isBlank()) return
        viewModelScope.launch {
            toggleMutex.withLock {
                runCatching {
                    if (favorites.getWord(draft.word) != null) favorites.removeWord(draft.word)
                    else favorites.addWord(draft.word, draft.phonetic, draft.definition)
                }.onFailure { error ->
                    logW(TAG, "Could not toggle favorite word: ${error.message}")
                }
            }
        }
    }

    /**
     * 收藏/取消收藏一句语法分析。
     *
     * @param translation 单句段落的译文（若已请求过）；多句段落里传 null —— 那时翻译标签
     *   翻的是整段，不能当成这一句的译文。
     */
    fun toggleSentence(sentence: String, grammar: String?, translation: String?) {
        if (sentence.isBlank()) return
        viewModelScope.launch {
            toggleMutex.withLock {
                runCatching {
                    if (favorites.getSentence(sentence) != null) favorites.removeSentence(sentence)
                    else favorites.addSentence(sentence, translation, grammar)
                }.onFailure { error ->
                    logW(TAG, "Could not toggle favorite sentence: ${error.message}")
                }
            }
        }
    }

    /**
     * 先收藏、后分析完：把语法补进已有收藏。
     *
     * 只在句子确实还在收藏夹里时生效（DAO 返回受影响行数）；句子已被取消收藏就什么都不做。
     */
    fun updateSentenceGrammar(sentence: String, grammar: String) {
        if (sentence.isBlank() || grammar.isBlank()) return
        viewModelScope.launch {
            runCatching { favorites.updateSentenceGrammar(sentence, grammar) }
                .onFailure { error ->
                    logW(TAG, "Could not update favorite grammar: ${error.message}")
                }
        }
    }

    /** 打开收藏页上的一个句子：已有的译文直接显示，没有就补一次。 */
    fun openSentence(sentence: String) {
        translationJob?.cancel()
        val key = sentence.trim()
        openSentenceKey = key
        viewModelScope.launch {
            val favorite = favorites.getSentence(key) ?: return@launch
            if (openSentenceKey != key) return@launch

            val stored = favorite.translation?.takeIf { it.isNotBlank() }
            _sentenceDialog.value = FavoriteSentenceDialogState(
                favorite = favorite,
                translation = stored?.let { FavoriteTranslation.Success(it) }
                    ?: FavoriteTranslation.Loading
            )
            if (stored == null) startTranslation(favorite)
        }
    }

    /** 错误态的「重试」。 */
    fun retryTranslation() {
        val current = _sentenceDialog.value ?: return
        startTranslation(current.favorite)
    }

    /** 关闭详情：取消在途请求（已得到的译文已经回填数据库，不受影响）。 */
    fun closeSentence() {
        translationJob?.cancel()
        translationJob = null
        openSentenceKey = null
        _sentenceDialog.value = null
    }

    /** 详情里的「取消收藏」：删掉这一行并关闭。 */
    fun removeOpenSentence() {
        val current = _sentenceDialog.value ?: return
        val key = current.favorite.text
        translationJob?.cancel()
        translationJob = null
        viewModelScope.launch {
            runCatching { favorites.removeSentence(key) }
                .onFailure { error -> logW(TAG, "Could not remove favorite sentence: ${error.message}") }
            if (_sentenceDialog.value?.favorite?.text == key) {
                openSentenceKey = null
                _sentenceDialog.value = null
            }
        }
    }

    private fun startTranslation(favorite: FavoriteSentence) {
        val key = favorite.text
        translationJob?.cancel()
        translationJob = viewModelScope.launch {
            val config = configProvider.current()
            setTranslation(key, FavoriteTranslation.Loading)
            try {
                val builder = StringBuilder()
                ai.translate(
                    text = favorite.text,
                    targetLanguage = config.targetLanguage
                ).collect { delta ->
                    builder.append(delta)
                    setTranslation(key, FavoriteTranslation.Streaming(builder.toString()))
                }

                val result = builder.toString()
                if (result.isBlank()) {
                    // 与翻译对话框一致：空结果按失败处理，不能把空串写进收藏。
                    setTranslation(key, FavoriteTranslation.Error(AiError.EMPTY_RESPONSE, null))
                    return@launch
                }

                // 回填失败只记日志：译文仍然展示，用户下次打开会再请求一次。
                runCatching { favorites.updateSentenceTranslation(key, result) }
                    .onFailure { error -> logW(TAG, "Could not store the translation: ${error.message}") }

                _sentenceDialog.update { current ->
                    if (current?.favorite?.text != key) current
                    else current.copy(
                        favorite = current.favorite.copy(translation = result),
                        translation = FavoriteTranslation.Success(result)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 只记错误类别：第三方异常消息里可能带响应体片段。
                val mapped = mapAiException(e)
                logW(TAG, "Translation failed: ${mapped.error.name}")
                setTranslation(
                    key,
                    FavoriteTranslation.Error(mapped.error, mapped.statusCode)
                )
            }
        }
    }

    private fun setTranslation(key: String, translation: FavoriteTranslation) {
        _sentenceDialog.update { current ->
            if (current?.favorite?.text != key) current
            else current.copy(translation = translation)
        }
    }
}
