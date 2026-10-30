/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import ua.acclorite.book_story.R
import ua.acclorite.book_story.domain.model.book.Book
import ua.acclorite.book_story.domain.model.book.Node
import ua.acclorite.book_story.presentation.reader.ReaderEvent
import ua.acclorite.book_story.presentation.reader.ScrollTarget
import ua.acclorite.book_story.ui.common.components.common.AnimatedVisibility
import ua.acclorite.book_story.ui.common.components.common.LazyColumnWithScrollbar
import ua.acclorite.book_story.ui.common.components.common.SelectionContainer
import ua.acclorite.book_story.ui.common.components.common.SpacedItem
import ua.acclorite.book_story.ui.common.components.progress_indicator.CircularProgressIndicator
import ua.acclorite.book_story.ui.common.helpers.LocalActivity
import ua.acclorite.book_story.ui.common.helpers.noRippleClickable
import ua.acclorite.book_story.ui.common.helpers.showToast
import ua.acclorite.book_story.ui.reader.model.ReaderNode
import ua.acclorite.book_story.ui.theme.DefaultTransition
import ua.acclorite.book_story.ui.theme.model.HorizontalAlignment
import ua.acclorite.book_story.ui.theme.Motion

private const val PRELOAD_THRESHOLD = 30

@OptIn(FlowPreview::class)
@SuppressLint("FrequentlyChangingValue")
@Composable
fun ReaderLayoutContinuous(
    book: Book,
    nodes: List<Node>,
    paginating: Boolean,
    scrollTarget: ScrollTarget?,
    contentPadding: PaddingValues,
    verticalPadding: Dp,
    highlightedReading: Boolean,
    highlightedReadingThickness: Int,
    progress: String,
    progressBar: Boolean,
    progressBarPadding: Dp,
    progressBarAlignment: HorizontalAlignment,
    progressBarFontSize: TextUnit,
    paragraphTextStyle: TextStyle,
    boldFontThickness: Int,
    header1TextStyle: TextStyle,
    header2TextStyle: TextStyle,
    header3TextStyle: TextStyle,
    header4TextStyle: TextStyle,
    header5TextStyle: TextStyle,
    header6TextStyle: TextStyle,
    paragraphHeight: Dp,
    sidePadding: Dp,
    backgroundColor: Color,
    fontColor: Color,
    images: Boolean,
    imagesCaptions: Boolean,
    imagesCornersRoundness: Dp,
    imagesAlignment: HorizontalAlignment,
    imagesWidth: Float,
    imagesOriginalSize: Boolean,
    imagesColorEffects: ColorFilter?,
    horizontalAlignment: Alignment.Horizontal,
    doubleClickTranslation: Boolean,
    lookup: ReaderLookupActions,
    // fork: 双击段落与选区「翻译」共用同一个 TranslationModel（S8）。
    translation: ReaderTranslationActions,
    showMenu: Boolean,
    menuVisibility: (ReaderEvent.OnMenuVisibility) -> Unit,
    openShareApp: (ReaderEvent.OnOpenShareApp) -> Unit,
    openWebBrowser: (ReaderEvent.OnOpenWebBrowser) -> Unit,
    openDictionary: (ReaderEvent.OnOpenDictionary) -> Unit,
    updateProgress: (ReaderEvent.OnUpdateProgress) -> Unit,
    paginateForward: (ReaderEvent.OnPaginateForward) -> Unit,
    paginateBackward: (ReaderEvent.OnPaginateBackward) -> Unit,
    scrollConsumed: (ReaderEvent.OnScrollConsumed) -> Unit
) {
    val activity = LocalActivity.current
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val layoutDirection = LocalLayoutDirection.current
    val listState = rememberLazyListState()
    val textMeasurer = rememberTextMeasurer()
    // fork: 取词注册表 + 内容层的坐标（把点击点换算成窗口坐标）。
    val textLayouts = remember { ReaderTextLayoutRegistry() }
    var contentCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val isInitialLoading = remember(book.uuid) { mutableStateOf(true) }
    val initialScrollTarget = remember(book.uuid) {
        ScrollTarget(
            nodeId = book.progress.currentNodeId,
            nodeIntraOffset = book.progress.currentNodeIntraOffset
        )
    }
    val readerNodes = rememberReaderNodes(
        nodes = nodes,
        paragraphTextStyle = paragraphTextStyle,
        boldFontThickness = boldFontThickness,
        header1TextStyle = header1TextStyle,
        header2TextStyle = header2TextStyle,
        header3TextStyle = header3TextStyle,
        header4TextStyle = header4TextStyle,
        header5TextStyle = header5TextStyle,
        header6TextStyle = header6TextStyle,
        highlightedReading = highlightedReading,
        highlightedReadingThickness = highlightedReadingThickness
    )
    val reachedFirst = remember(readerNodes) {
        if (readerNodes.isEmpty()) return@remember false
        readerNodes.first().node.id.id <= 0
    }
    val reachedLast = remember(readerNodes, book.progress.totalNodes) {
        if (readerNodes.isEmpty() || book.progress.totalNodes <= 0) return@remember false
        readerNodes.last().node.id.id >= (book.progress.totalNodes - 1)
    }
    val constraints = remember(density, windowInfo, contentPadding, sidePadding) {
        Constraints(
            maxWidth = with(density) {
                windowInfo.containerSize.width.minus(
                    contentPadding.calculateLeftPadding(layoutDirection).toPx()
                ).minus(
                    contentPadding.calculateRightPadding(layoutDirection).toPx()
                ).minus(
                    sidePadding.toPx() * 2
                )
            }.toInt()
        )
    }

    val contentAlpha by animateFloatAsState(
        targetValue = if (scrollTarget != null || isInitialLoading.value) 0f else 1f,
        animationSpec = Motion.short(),
        label = "continuous_reader_fade"
    )

    // Target scrolling (initial load & programmatic jumps)
    LaunchedEffect(
        scrollTarget,
        paginating,
        readerNodes,
        textMeasurer,
        paragraphTextStyle,
        header1TextStyle,
        header2TextStyle,
        header3TextStyle,
        header4TextStyle,
        header5TextStyle,
        header6TextStyle,
        constraints
    ) {
        val target = scrollTarget
            ?: initialScrollTarget.takeIf { isInitialLoading.value }
            ?: return@LaunchedEffect
        if (readerNodes.isEmpty()) return@LaunchedEffect

        val index = readerNodes.indexOfFirst { it.node.id == target.nodeId }
        if (index == -1) {
            if (scrollTarget == null && !paginating) {
                if (target.nodeId.id < readerNodes.first().node.id.id) {
                    paginateBackward(ReaderEvent.OnPaginateBackward(readerNodes.first().node.id))
                } else if (target.nodeId.id > readerNodes.last().node.id.id) {
                    paginateForward(ReaderEvent.OnPaginateForward(readerNodes.last().node.id))
                }
            }
            return@LaunchedEffect
        }
        val readerNode = readerNodes[index]
        val offset = readerNode.getNodeVerticalOffsetPx(
            nodeIntraOffset = target.nodeIntraOffset,
            textMeasurer = textMeasurer,
            constraints = constraints,
            paragraphTextStyle = paragraphTextStyle,
            header1TextStyle = header1TextStyle,
            header2TextStyle = header2TextStyle,
            header3TextStyle = header3TextStyle,
            header4TextStyle = header4TextStyle,
            header5TextStyle = header5TextStyle,
            header6TextStyle = header6TextStyle
        )

        listState.scrollToItem(
            index = index,
            scrollOffset = offset
        )
        isInitialLoading.value = false
        if (scrollTarget != null) scrollConsumed(ReaderEvent.OnScrollConsumed)
    }

    val currentScrollTarget by rememberUpdatedState(scrollTarget)
    val currentReaderNodes by rememberUpdatedState(readerNodes)
    val currentPaginating by rememberUpdatedState(paginating)

    // Progress tracking
    LaunchedEffect(listState) {
        snapshotFlow {
            Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.distinctUntilChanged().debounce(200)
            .collectLatest { (itemIndex, itemOffset) ->
                if (currentScrollTarget != null || isInitialLoading.value) return@collectLatest
                val nodesList = currentReaderNodes
                val currentNode = nodesList.getOrNull(itemIndex) ?: return@collectLatest

                val currentNodeId = currentNode.node.id
                val currentNodeIntraOffset = currentNode.getNodeIntraOffset(
                    nodeVerticalOffsetPx = itemOffset,
                    textMeasurer = textMeasurer,
                    constraints = constraints,
                    paragraphTextStyle = paragraphTextStyle,
                    header1TextStyle = header1TextStyle,
                    header2TextStyle = header2TextStyle,
                    header3TextStyle = header3TextStyle,
                    header4TextStyle = header4TextStyle,
                    header5TextStyle = header5TextStyle,
                    header6TextStyle = header6TextStyle
                )

                updateProgress(ReaderEvent.OnUpdateProgress(currentNodeId, currentNodeIntraOffset))
            }
    }

    // fork: 滚动就关卡片 —— 卡片贴着的是滚动前的词，跟着滚会贴到别的字上。
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { lookup.onDismiss() }
    }

    // Pagination
    LaunchedEffect(listState, paginating) {
        snapshotFlow {
            listState.firstVisibleItemIndex
        }.distinctUntilChanged()
            .collectLatest { itemIndex ->
                if (currentPaginating || currentScrollTarget != null || isInitialLoading.value) return@collectLatest
                val nodesList = currentReaderNodes
                if (nodesList.isEmpty()) return@collectLatest

                if (itemIndex < PRELOAD_THRESHOLD && !reachedFirst) {
                    val firstNode = nodesList.firstOrNull() ?: return@collectLatest
                    paginateBackward(ReaderEvent.OnPaginateBackward(firstNode.node.id))
                } else if (itemIndex > nodesList.size - PRELOAD_THRESHOLD && !reachedLast) {
                    val lastNode = nodesList.lastOrNull() ?: return@collectLatest
                    paginateForward(ReaderEvent.OnPaginateForward(lastNode.node.id))
                }
            }
    }

    SelectionContainer(
        onCopyRequested = {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                activity.getString(R.string.copied)
                    .showToast(context = activity, longToast = false)
            }
        },
        onShareRequested = { textToShare ->
            openShareApp(
                ReaderEvent.OnOpenShareApp(
                    textToShare = textToShare
                )
            )
        },
        onWebSearchRequested = { textToSearch ->
            openWebBrowser(
                ReaderEvent.OnOpenWebBrowser(
                    textToSearch = textToSearch
                )
            )
        },
        // fork: 选区「翻译」走应用内对话框（S8），不再是外部翻译应用；
        // 外部 Intent 只保留为对话框错误态的「用其他应用翻译」。
        onTranslateRequested = { textToTranslate ->
            translation.onTranslate(textToTranslate, null)
        },
        // fork: 工具栏「词典」改为查应用内词典（S4 之前是外部 Intent）。
        // 外部 Intent 退到卡片未找到时的「用其他应用查询」，不再是默认动作。
        // 多词选区只查第一个词：整串短语查不到，会把原本可用的「查词」变成「未找到」。
        onDictionaryRequested = { textToDefine ->
            val word = firstLookupWord(textToDefine)
            if (word != null) {
                lookup.onWord(word, null)
            } else {
                openDictionary(ReaderEvent.OnOpenDictionary(textToDefine))
            }
        },
        onSelectionSettled = rememberLookupSelectionHandler(lookup, textLayouts)
    ) { toolbarHidden ->
        Column(
            Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .then(
                    if (!isInitialLoading.value && toolbarHidden) {
                        // fork: 连续布局唯一的正文手势处理器（原来这里是 noRippleClickable）。
                        // 段落不再各挂一个检测器：多一套就会吞掉单击，取词与翻页/菜单只能二选一。
                        Modifier
                            .onGloballyPositioned { contentCoordinates = it }
                            .pointerInput(lookup, showMenu, doubleClickTranslation, translation) {
                                detectTapGestures(
                                    // 非 null 时单击会等双击超时（~300ms）；设置里关掉双击翻译时
                                    // 必须传 null，否则每次切菜单都慢一拍。
                                    onDoubleTap = if (!doubleClickTranslation) null else { offset ->
                                        val window = contentCoordinates
                                            ?.takeIf { it.isAttached }
                                            ?.localToWindow(offset)
                                        val nodeId = window?.let { textLayouts.hitTest(it)?.nodeId }
                                        // 用 rememberUpdatedState 的那份：分页追加节点后旧的
                                        // readerNodes 已经过期，双击会翻到不存在或重复的段落。
                                        val paragraph = nodeId?.let { id ->
                                            currentReaderNodes.firstOrNull { it.node.id == id }
                                                as? ReaderNode.Paragraph
                                        }
                                        if (paragraph != null) {
                                            // fork: 整段正文 + 节点 id（不是分页切片）。
                                            translation.onTranslate(
                                                paragraph.wholeParagraphText(),
                                                paragraph.node.id.id
                                            )
                                        }
                                    },
                                    onTap = { offset ->
                                        // 菜单开着时，点任意位置先收起菜单（与 ReaderLayoutPaged 一致）。
                                        // 必须排在取词前面：否则菜单没关、卡片又弹出来了。
                                        if (showMenu) {
                                            menuVisibility(ReaderEvent.OnMenuVisibility(false, true))
                                            lookup.onDismiss()
                                            return@detectTapGestures
                                        }
                                        val window = contentCoordinates
                                            ?.takeIf { it.isAttached }
                                            ?.localToWindow(offset)
                                        val word = if (lookup.singleTapEnabled && window != null) {
                                            textLayouts.hitTest(window)
                                        } else null
                                        if (word != null) {
                                            // 命中单词：取词并吃掉这次点击；空白区照旧切菜单。
                                            lookup.onWord(word.word, word.rect)
                                        } else {
                                            menuVisibility(
                                                ReaderEvent.OnMenuVisibility(
                                                    show = !showMenu,
                                                    saveCheckpoint = true
                                                )
                                            )
                                        }
                                    }
                                )
                            }
                    } else Modifier
                )
                .padding(contentPadding)
                .padding(vertical = verticalPadding)
        ) {
            LazyColumnWithScrollbar(
                state = listState,
                enableScrollbar = false,
                parentModifier = Modifier
                    .weight(1f)
                    .graphicsLayer { alpha = contentAlpha },
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !isInitialLoading.value,
                contentPadding = PaddingValues(
                    top = (WindowInsets.displayCutout.asPaddingValues()
                        .calculateTopPadding() + paragraphHeight)
                        .coerceAtLeast(18.dp),
                    bottom = (WindowInsets.displayCutout.asPaddingValues()
                        .calculateBottomPadding() + paragraphHeight)
                        .coerceAtLeast(18.dp),
                )
            ) {
                itemsIndexed(
                    readerNodes,
                    key = { _, readerNode -> readerNode.node.id.id }
                ) { index, readerNode ->
                    Column {
                        AnimatedVisibility(
                            visible = index == 0 && !reachedFirst,
                            enter = fadeIn(Motion.instant()),
                            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = paragraphHeight),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    color = fontColor,
                                    trackColor = fontColor.copy(0.3f)
                                )
                            }
                        }

                        when {
                            readerNode is ReaderNode.Image && !images -> {}
                            else -> SpacedItem(
                                space = index != readerNodes.lastIndex,
                                spacing = paragraphHeight
                            ) {
                                ReaderLayoutContent(
                                    readerNode = readerNode,
                                    paragraphTextStyle = paragraphTextStyle,
                                    header1TextStyle = header1TextStyle,
                                    header2TextStyle = header2TextStyle,
                                    header3TextStyle = header3TextStyle,
                                    header4TextStyle = header4TextStyle,
                                    header5TextStyle = header5TextStyle,
                                    header6TextStyle = header6TextStyle,
                                    imagesCaptions = imagesCaptions,
                                    imagesCornersRoundness = imagesCornersRoundness,
                                    imagesAlignment = imagesAlignment,
                                    imagesWidth = imagesWidth,
                                    imagesColorEffects = imagesColorEffects,
                                    imagesOriginalSize = imagesOriginalSize,
                                    fontColor = fontColor,
                                    horizontalAlignment = horizontalAlignment,
                                    sidePadding = sidePadding,
                                    paragraphHeight = paragraphHeight,
                                    textLayouts = textLayouts
                                )
                            }
                        }

                        if (index == readerNodes.lastIndex && !reachedLast) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = paragraphHeight),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    color = fontColor,
                                    trackColor = fontColor.copy(0.3f)
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = !showMenu && progressBar,
                enter = slideInVertically { it } + expandVertically(),
                exit = slideOutVertically { it } + shrinkVertically()
            ) {
                ReaderProgressBar(
                    progress = progress,
                    progressBarPadding = progressBarPadding,
                    progressBarAlignment = progressBarAlignment,
                    progressBarFontSize = progressBarFontSize,
                    fontColor = fontColor,
                    sidePadding = sidePadding
                )
            }
        }
    }

    DefaultTransition(
        visible = isInitialLoading.value
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .noRippleClickable() {},
        )
    }
}
