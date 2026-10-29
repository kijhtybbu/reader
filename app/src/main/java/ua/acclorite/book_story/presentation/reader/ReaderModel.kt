/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.presentation.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.acclorite.book_story.core.log.logW
import ua.acclorite.book_story.domain.model.book.Node
import ua.acclorite.book_story.domain.model.book.NodeId
import ua.acclorite.book_story.domain.use_case.book.GetBookContentNodeByOffsetUseCase
import ua.acclorite.book_story.domain.use_case.book.GetBookContentState
import ua.acclorite.book_story.domain.use_case.book.GetBookContentUseCase
import ua.acclorite.book_story.domain.use_case.book.GetBookUseCase
import ua.acclorite.book_story.domain.use_case.book.UpdateBookUseCase
import ua.acclorite.book_story.domain.use_case.history.GetHistoryForBookUseCase
import ua.acclorite.book_story.presentation.history.HistoryScreen
import ua.acclorite.book_story.presentation.library.LibraryScreen
import ua.acclorite.book_story.presentation.reader.model.Checkpoint
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

private const val TAG = "ReaderModel"

/**
 * fork: 「重新加载节点窗口」的触发信号。
 *
 * 必须是 SharedFlow 而不是 StateFlow：StateFlow 会合并相等值，而跳转目标完全可能等于
 * 当前值 —— 从远处跳回 NodeId(0)（书签、目录、检查点都走这条路径），或检查点恰好就是
 * 当前位置时，`update { NodeId(0) }` 什么都不发射，[GetBookContentUseCase] 里的
 * `nodeId.collectLatest` 就不会重发 Nodes，被 `combined.drop(100)` 逐出的节点永远回不到
 * `ReaderState.nodes`；分页布局的 `pageNodes.none { it.id == scrollTarget.nodeId }` 于是
 * 永久成立，页面停在 alpha 0、scrollEnabled=false、scrollConsumed 不回调 —— 空白且不响应。
 * SharedFlow 不比较值，同一个目标发几次就是几次重新加载。
 *
 * replay = 1：保留「打开阅读页即有值」的语义（OnLoadContent 先 emit 再 collect），
 * 重试（OnRetry → 重新 collect）时也能立刻拿到当前目标。
 * extraBufferCapacity = 1 + DROP_OLDEST：连续跳转时不阻塞调用者；只有最新目标有意义，
 * 中间目标被丢弃是正确行为。
 */
internal fun newPaginationNodeRequests(): MutableSharedFlow<NodeId> =
    MutableSharedFlow(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

@HiltViewModel
class ReaderModel @Inject constructor(
    private val updateBookUseCase: UpdateBookUseCase,
    private val getBookContentUseCase: GetBookContentUseCase,
    private val getBookUseCase: GetBookUseCase,
    private val getHistoryForBookUseCase: GetHistoryForBookUseCase,
    private val getBookContentNodeByOffsetUseCase: GetBookContentNodeByOffsetUseCase
) : ViewModel() {

    private val mutex = Mutex()

    private val _state = MutableStateFlow(ReaderState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ReaderEffect>()
    val effects = _effects.asSharedFlow()

    private val paginationNodeRequests = newPaginationNodeRequests()

    private var scrollJob: Job? = null
    private val eventStack = mutableListOf<Job>()

    fun onEvent(event: ReaderEvent) {
        viewModelScope.launch {
            when (event) {
                is ReaderEvent.OnLoadContent -> {
                    withContext(Dispatchers.IO) {
                        val lastOpened = getHistoryForBookUseCase(
                            _state.value.book.id
                        )?.time
                        _state.update {
                            it.copy(
                                book = it.book.copy(
                                    lastOpened = lastOpened
                                )
                            )
                        }
                        updateBookUseCase(_state.value.book)
                        paginationNodeRequests.emit(_state.value.book.progress.currentNodeId)

                        LibraryScreen.refreshListChannel.trySend(0)
                        HistoryScreen.refreshListChannel.trySend(0)

                        getBookContentUseCase(
                            _state.value.book,
                            paginationNodeRequests
                        ).collect { state ->
                            when (state) {
                                is GetBookContentState.Loading -> {
                                    _state.update {
                                        it.copy(
                                            layoutState = LayoutState.Loading
                                        )
                                    }
                                    delay(500)
                                }

                                is GetBookContentState.Parsing -> {
                                    _state.update {
                                        it.copy(
                                            layoutState = LayoutState.Parsing(
                                                progress = state.progress
                                            )
                                        )
                                    }
                                }

                                is GetBookContentState.Saving -> {
                                    _state.update {
                                        it.copy(
                                            layoutState = LayoutState.Saving
                                        )
                                    }
                                }

                                is GetBookContentState.Success -> {
                                    _effects.emit(ReaderEffect.OnSystemBarsVisibility(show = null))

                                    getBookUseCase(_state.value.book.id)?.let { book ->
                                        _state.update {
                                            it.copy(
                                                book = book
                                            )
                                        }
                                    }

                                    LibraryScreen.refreshListChannel.trySend(0)
                                    HistoryScreen.refreshListChannel.trySend(0)
                                }

                                is GetBookContentState.Nodes -> {
                                    _state.update {
                                        it.copy(
                                            nodes = state.nodes,
                                            layoutState = LayoutState.Showing,
                                            paginating = false
                                        )
                                    }
                                }

                                is GetBookContentState.Chapters -> {
                                    _state.update {
                                        it.copy(
                                            chapters = state.chapters
                                        )
                                    }
                                }

                                is GetBookContentState.Error -> {
                                    _state.update {
                                        it.copy(
                                            layoutState = LayoutState.Error(
                                                message = state.message,
                                                action = state.action
                                            )
                                        )
                                    }
                                    _effects.emit(ReaderEffect.OnSystemBarsVisibility(show = true))
                                    return@collect
                                }
                            }
                        }
                    }
                }

                is ReaderEvent.OnRetry -> {
                    if (_state.value.book.id == -1) return@launch
                    init(_state.value.book.id)
                }

                is ReaderEvent.OnPaginateForward -> {
                    if (_state.value.paginating) return@launch
                    _state.update { it.copy(paginating = true) }
                    withContext(Dispatchers.IO) {
                        val newNodes = getBookContentUseCase.getNodesAfter(
                            book = _state.value.book,
                            nodeId = event.lastNodeId,
                            limit = 100
                        )
                        if (newNodes.isNotEmpty()) {
                            _state.update { currentState ->
                                val combined = currentState.nodes + newNodes
                                val finalNodes = if (combined.size > 500) {
                                    combined.drop(100)
                                } else {
                                    combined
                                }
                                currentState.copy(
                                    nodes = finalNodes,
                                    paginating = false
                                )
                            }
                        } else {
                            _state.update { it.copy(paginating = false) }
                        }
                    }
                }

                is ReaderEvent.OnPaginateBackward -> {
                    if (_state.value.paginating) return@launch
                    _state.update { it.copy(paginating = true) }
                    withContext(Dispatchers.IO) {
                        val newNodes = getBookContentUseCase.getNodesBefore(
                            book = _state.value.book,
                            nodeId = event.firstNodeId,
                            limit = 100
                        )
                        if (newNodes.isNotEmpty()) {
                            _state.update { currentState ->
                                val combined = newNodes + currentState.nodes
                                val finalNodes = if (combined.size > 500) {
                                    combined.dropLast(100)
                                } else {
                                    combined
                                }
                                currentState.copy(
                                    nodes = finalNodes,
                                    paginating = false
                                )
                            }
                        } else {
                            _state.update { it.copy(paginating = false) }
                        }
                    }
                }

                is ReaderEvent.OnUpdateProgress -> {
                    val nodeOffset = event.currentNodeOffset
                        ?: _state.value.nodes.find { it.id == event.currentNodeId }?.position?.offset
                        ?: return@launch
                    _state.update {
                        it.copy(
                            book = it.book.copy(
                                progress = it.book.progress.copy(
                                    currentNodeId = event.currentNodeId,
                                    currentNodeIntraOffset = event.currentNodeIntraOffset,
                                    currentOffset = nodeOffset + event.currentNodeIntraOffset
                                )
                            )
                        )
                    }
                    updateBookUseCase(_state.value.book)

                    LibraryScreen.refreshListChannel.trySend(300)
                    HistoryScreen.refreshListChannel.trySend(300)
                }

                is ReaderEvent.OnMenuVisibility -> {
                    withContext(Dispatchers.Default) {
                        if (_state.value.lockMenu) return@withContext

                        _effects.emit(
                            ReaderEffect.OnSystemBarsVisibility(
                                show = if (event.show) true
                                else null
                            )
                        )

                        if (event.saveCheckpoint && event.show) {
                            val currentProgress = _state.value.book.progress
                            val checkpoints = _state.value.checkpoints.toMutableList()
                            checkpoints.removeIf {
                                it.offset == currentProgress.currentOffset
                            }
                            checkpoints.add(
                                Checkpoint(
                                    nodeId = currentProgress.currentNodeId,
                                    nodeIntraOffset = currentProgress.currentNodeIntraOffset,
                                    offset = currentProgress.currentOffset
                                )
                            )

                            _state.update {
                                it.copy(checkpoints = checkpoints)
                            }
                        }

                        _state.update {
                            it.copy(showMenu = event.show)
                        }
                    }
                }

                is ReaderEvent.OnScrollToPosition -> {
                    // fork: 书签里存的 nodeId 是上一次解析分配的，重新解析时节点 id 从 0 重排，而书签不做重映射。
                    // 越界的 id 永远不会出现在 nodes 里，ReaderLayoutPaged 会一直等目标节点
                    // （空白页、scrollConsumed 不回调），所以直接丢弃这次跳转，也不落库。
                    val totalNodes = _state.value.book.progress.totalNodes
                    if (event.nodeId.id !in 0 until totalNodes) {
                        logW(
                            TAG,
                            "Ignoring scroll target [${event.nodeId.id}] outside 0 until $totalNodes"
                        )
                        return@launch
                    }

                    // fork: 与 OnRestoreCheckpoint 一致 —— 除了设 scrollTarget，还必须向
                    // paginationNodeRequests 发一次目标节点，否则 GetBookContentUseCase 不会重新加载节点窗口：
                    // 目标节点（例如 ±200 节点之外的旧书签）永远不会出现在 nodes 里，
                    // 分页模式就停在 reflowing/currentScrollTarget 上（空白页、contentAlpha=0、
                    // scrollConsumed 永不回调），lazy 模式则只能盲滑。
                    withContext(Dispatchers.Default) {
                        _state.update {
                            // 目标节点若已在当前窗口内，可算出绝对偏移；窗口外的偏移无从得知，
                            // 先保留旧值，等重排后阅读页发 OnUpdateProgress 时校正
                            val nodeOffset = it.nodes
                                .find { node -> node.id == event.nodeId }
                                ?.position?.offset

                            it.copy(
                                scrollTarget = ScrollTarget(
                                    nodeId = event.nodeId,
                                    nodeIntraOffset = event.nodeIntraOffset
                                ),
                                book = it.book.copy(
                                    progress = it.book.progress.copy(
                                        // fork: 已知局限：目标节点不在已加载窗口内时，下面拿不到它的绝对
                                        // offset，于是 currentNodeId 已指向目标、currentOffset 还是旧的。
                                        // 这一瞬间书架上的百分比可能不准；重排后阅读页发 OnUpdateProgress
                                        // 会把它改对，所以这里不再额外算一次。
                                        currentNodeId = event.nodeId,
                                        currentNodeIntraOffset = event.nodeIntraOffset,
                                        currentOffset = nodeOffset
                                            ?.plus(event.nodeIntraOffset)
                                            ?: it.book.progress.currentOffset
                                    )
                                )
                            )
                        }
                        // 跳转当下就落库：否则万一在这之前离开阅读页，下次打开还是旧位置
                        paginationNodeRequests.emit(event.nodeId)
                        updateBookUseCase(_state.value.book)

                        LibraryScreen.refreshListChannel.trySend(300)
                        HistoryScreen.refreshListChannel.trySend(300)
                    }
                }

                is ReaderEvent.OnScrollToChapter -> {
                    withContext(Dispatchers.Default) {
                        val chapter = event.chapter
                        val targetOffset = chapter.position.chapterOffset
                        val targetNodeId = NodeId(chapter.linkId.id)

                        _state.update {
                            it.copy(
                                scrollTarget = ScrollTarget(
                                    nodeId = targetNodeId,
                                    nodeIntraOffset = 0
                                ),
                                book = it.book.copy(
                                    progress = it.book.progress.copy(
                                        currentNodeId = targetNodeId,
                                        currentNodeIntraOffset = 0,
                                        currentOffset = targetOffset
                                    )
                                )
                            )
                        }
                        paginationNodeRequests.emit(targetNodeId)
                        updateBookUseCase(_state.value.book)

                        LibraryScreen.refreshListChannel.trySend(300)
                        HistoryScreen.refreshListChannel.trySend(300)
                    }
                }

                is ReaderEvent.OnScroll -> {
                    scrollJob?.cancel()
                    scrollJob = viewModelScope.launch(Dispatchers.Default) {
                        val totalLength = _state.value.book.progress.totalLength
                        if (totalLength <= 0) return@launch
                        val targetOffset = (event.progress.coerceIn(0f, 1f) * totalLength).toInt()
                        val node = _state.value.nodes.find {
                            targetOffset in it.position.offset until it.position.endOffset
                        } ?: getBookContentNodeByOffsetUseCase(_state.value.book, targetOffset)
                        ?: _state.value.nodes.firstOrNull() ?: return@launch

                        val intraOffset = (targetOffset - node.position.offset).coerceAtLeast(0)
                        _state.update {
                            it.copy(
                                scrollTarget = ScrollTarget(
                                    nodeId = node.id,
                                    nodeIntraOffset = intraOffset
                                ),
                                book = it.book.copy(
                                    progress = it.book.progress.copy(
                                        currentNodeId = node.id,
                                        currentNodeIntraOffset = intraOffset,
                                        currentOffset = targetOffset
                                    )
                                )
                            )
                        }
                        paginationNodeRequests.emit(node.id)
                        updateBookUseCase(_state.value.book)

                        LibraryScreen.refreshListChannel.trySend(300)
                        HistoryScreen.refreshListChannel.trySend(300)
                    }
                }

                is ReaderEvent.OnRestoreCheckpoint -> {
                    withContext(Dispatchers.Default) {
                        _state.update {
                            val checkpoints = it.checkpoints.toMutableList()
                            if (checkpoints.size > 1) checkpoints.remove(event.checkpoint)

                            it.copy(
                                checkpoints = checkpoints,
                                scrollTarget = ScrollTarget(
                                    nodeId = event.checkpoint.nodeId,
                                    nodeIntraOffset = event.checkpoint.nodeIntraOffset
                                ),
                                book = it.book.copy(
                                    progress = it.book.progress.copy(
                                        currentNodeId = event.checkpoint.nodeId,
                                        currentNodeIntraOffset = event.checkpoint.nodeIntraOffset,
                                        currentOffset = event.checkpoint.offset
                                    )
                                )
                            )
                        }
                        paginationNodeRequests.emit(event.checkpoint.nodeId)
                        updateBookUseCase(_state.value.book)

                        LibraryScreen.refreshListChannel.trySend(300)
                        HistoryScreen.refreshListChannel.trySend(300)
                    }
                }

                is ReaderEvent.OnScrollConsumed -> {
                    _state.update {
                        it.copy(scrollTarget = null)
                    }
                }

                is ReaderEvent.OnLeave -> {
                    _state.update {
                        it.copy(
                            lockMenu = true
                        )
                    }

                    if (_state.value.layoutState is LayoutState.Showing) {
                        updateBookUseCase(_state.value.book)

                        LibraryScreen.refreshListChannel.trySend(0)
                        HistoryScreen.refreshListChannel.trySend(0)
                    }

                    event.navigate()
                }

                is ReaderEvent.OnOpenTranslator -> {
                    _effects.emit(
                        ReaderEffect.OnOpenTranslator(
                            textToTranslate = event.textToTranslate,
                            translateWholeParagraph = event.translateWholeParagraph
                        )
                    )
                }

                is ReaderEvent.OnOpenShareApp -> {
                    _effects.emit(
                        ReaderEffect.OnOpenShareApp(
                            textToShare = event.textToShare
                        )
                    )
                }

                is ReaderEvent.OnOpenWebBrowser -> {
                    _effects.emit(
                        ReaderEffect.OnOpenWebBrowser(
                            textToSearch = event.textToSearch
                        )
                    )
                }

                is ReaderEvent.OnOpenDictionary -> {
                    _effects.emit(
                        ReaderEffect.OnOpenDictionary(
                            textToDefine = event.textToDefine
                        )
                    )
                }

                is ReaderEvent.OnShowSettingsBottomSheet -> {
                    _state.update {
                        it.copy(
                            bottomSheet = ReaderScreen.SETTINGS_BOTTOM_SHEET,
                            drawer = null
                        )
                    }
                }

                is ReaderEvent.OnDismissBottomSheet -> {
                    _state.update {
                        it.copy(
                            bottomSheet = null
                        )
                    }
                }

                is ReaderEvent.OnShowChaptersDrawer -> {
                    _state.update {
                        it.copy(
                            drawer = ReaderScreen.CHAPTERS_DRAWER,
                            bottomSheet = null
                        )
                    }
                }

                is ReaderEvent.OnDismissDrawer -> {
                    _state.update {
                        it.copy(
                            drawer = null
                        )
                    }
                }

                is ReaderEvent.OnNavigateBack -> {
                    _effects.emit(ReaderEffect.OnNavigateBack)
                }

                is ReaderEvent.OnNavigateToBookInfo -> {
                    _effects.emit(ReaderEffect.OnNavigateToBookInfo(event.changePath))
                }
            }
        }.also { eventStack.add(it) }
    }

    suspend fun loadNodesBefore(nodeId: NodeId, limit: Int): List<Node> {
        return withContext(Dispatchers.IO) {
            getBookContentUseCase.getNodesBefore(_state.value.book, nodeId, limit)
        }
    }

    fun init(bookId: Int) {
        viewModelScope.launch(Dispatchers.Default) {
            val book = getBookUseCase(bookId)

            if (book == null) {
                _effects.emit(ReaderEffect.OnNavigateBack)
                return@launch
            }

            clear()

            _state.update {
                ReaderState(
                    book = book,
                    scrollTarget = ScrollTarget(
                        nodeId = book.progress.currentNodeId,
                        nodeIntraOffset = book.progress.currentNodeIntraOffset
                    )
                )
            }

            onEvent(ReaderEvent.OnLoadContent)
        }
    }

    fun clearAsync() {
        viewModelScope.launch {
            eventStack.forEach { job ->
                job.cancel()
            }
            _state.update { ReaderState() }
        }
    }

    suspend fun clear() {
        eventStack.forEach { job ->
            job.cancel()
            job.join()
        }
        eventStack.clear()
        _state.update { ReaderState() }
    }

    private suspend inline fun <T> MutableStateFlow<T>.update(function: (T) -> T) {
        mutex.withLock {
            coroutineContext.ensureActive()
            this.value = function(this.value)
        }
    }
}
