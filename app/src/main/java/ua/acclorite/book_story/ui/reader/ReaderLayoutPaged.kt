/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import ua.acclorite.book_story.R
import ua.acclorite.book_story.domain.model.book.Book
import ua.acclorite.book_story.domain.model.book.Node
import ua.acclorite.book_story.domain.model.book.NodeContent
import ua.acclorite.book_story.domain.model.book.NodeId
import ua.acclorite.book_story.presentation.reader.ReaderEvent
import ua.acclorite.book_story.presentation.reader.ScrollTarget
import ua.acclorite.book_story.presentation.reader.model.ReaderPagedAnimation
import ua.acclorite.book_story.presentation.reader.model.pagerPageTransform
import ua.acclorite.book_story.presentation.reader.model.ReaderPagedDirection
import ua.acclorite.book_story.presentation.reader.model.ReaderTapAction
import ua.acclorite.book_story.presentation.reader.model.ReaderTapZones
import ua.acclorite.book_story.presentation.reader.model.tapActionFor
import ua.acclorite.book_story.ui.common.components.common.SelectionContainer
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.helpers.LocalActivity
import ua.acclorite.book_story.ui.common.helpers.noRippleClickable
import ua.acclorite.book_story.ui.common.helpers.showToast
import ua.acclorite.book_story.ui.reader.model.ReaderNode
import ua.acclorite.book_story.ui.reader.paged.ReaderPage
import ua.acclorite.book_story.ui.reader.paged.ReaderPageConfig
import ua.acclorite.book_story.ui.reader.paged.ReaderPagePosition
import ua.acclorite.book_story.ui.reader.paged.ReaderTextMeasurement
import ua.acclorite.book_story.ui.reader.paged.findReaderPage
import ua.acclorite.book_story.ui.reader.paged.paginateReaderPages
import ua.acclorite.book_story.ui.reader.paged.loadReaderPageWindow
import ua.acclorite.book_story.ui.theme.model.HorizontalAlignment
import kotlin.math.ceil
import ua.acclorite.book_story.ui.theme.Motion

@Composable
fun ReaderLayoutPaged(
    direction: ReaderPagedDirection,
    animation: ReaderPagedAnimation,
    tapZones: ReaderTapZones,
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
    doubleClickTranslation: Boolean,
    showMenu: Boolean,
    menuVisibility: (ReaderEvent.OnMenuVisibility) -> Unit,
    openShareApp: (ReaderEvent.OnOpenShareApp) -> Unit,
    openWebBrowser: (ReaderEvent.OnOpenWebBrowser) -> Unit,
    openTranslator: (ReaderEvent.OnOpenTranslator) -> Unit,
    openDictionary: (ReaderEvent.OnOpenDictionary) -> Unit,
    updateProgress: (ReaderEvent.OnUpdateProgress) -> Unit,
    paginateForward: (ReaderEvent.OnPaginateForward) -> Unit,
    paginateBackward: (ReaderEvent.OnPaginateBackward) -> Unit,
    scrollConsumed: (ReaderEvent.OnScrollConsumed) -> Unit,
    loadNodesBefore: suspend (NodeId, Int) -> List<Node>
) {
    val activity = LocalActivity.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    var pageNodes by remember(book.uuid) { mutableStateOf(emptyList<Node>()) }
    var windowInput by remember(book.uuid) {
        mutableStateOf<Pair<List<Node>, Boolean>?>(null)
    }
    val windowReady = windowInput?.first == nodes && windowInput?.second == images &&
        pageNodes.isNotEmpty()

    LaunchedEffect(book.uuid, nodes, images) {
        pageNodes = withContext(Dispatchers.IO) {
            loadReaderPageWindow(nodes, pageNodes, images, loadNodesBefore)
        }
        windowInput = nodes to images
    }

    val readerNodes = rememberReaderNodes(
        pageNodes, paragraphTextStyle, boldFontThickness,
        header1TextStyle, header2TextStyle, header3TextStyle,
        header4TextStyle, header5TextStyle, header6TextStyle,
        highlightedReading, highlightedReadingThickness
    )
    val reachedFirst = pageNodes.firstOrNull()?.id?.id == 0
    val reachedLast = nodes.lastOrNull()?.id?.id == book.progress.totalNodes - 1
    var anchorNodeId by rememberSaveable(book.uuid) { mutableIntStateOf(book.progress.currentNodeId.id) }
    var anchorOffset by rememberSaveable(book.uuid) { mutableIntStateOf(book.progress.currentNodeIntraOffset) }
    var pendingTurn by remember { mutableIntStateOf(0) }

    SelectionContainer(
        onCopyRequested = {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                activity.getString(R.string.copied).showToast(activity, longToast = false)
            }
        },
        onShareRequested = { openShareApp(ReaderEvent.OnOpenShareApp(it)) },
        onWebSearchRequested = { openWebBrowser(ReaderEvent.OnOpenWebBrowser(it)) },
        onTranslateRequested = { openTranslator(ReaderEvent.OnOpenTranslator(it, false)) },
        onDictionaryRequested = { openDictionary(ReaderEvent.OnOpenDictionary(it)) }
    ) { toolbarHidden ->
        Column(
            Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(contentPadding)
                .padding(vertical = verticalPadding)
        ) {
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 18.dp)
                    .clipToBounds()
            ) {
                // fork: 点击分区。挂在内容区而不是外层 Column，这样坐标就是正文区坐标，
                // 也不会和上下栏的留白混在一起。只处理「点」，拖拽由 Pager 自己消费。
                val tapScope = rememberCoroutineScope()
                var pendingTapTarget by remember { mutableStateOf<Int?>(null) }
                val width = (constraints.maxWidth - with(density) { sidePadding.roundToPx() * 2 })
                    .coerceAtLeast(0)
                val height = constraints.maxHeight
                val config = ReaderPageConfig(
                    width = width,
                    height = height,
                    gap = with(density) { paragraphHeight.roundToPx() },
                    separatorHeight = with(density) { 3.dp.roundToPx() },
                    paragraphStyle = paragraphTextStyle.copy(
                        color = Color.Unspecified, textDirection = TextDirection.Content
                    ),
                    headerStyles = listOf(
                        header1TextStyle, header2TextStyle, header3TextStyle,
                        header4TextStyle, header5TextStyle, header6TextStyle
                    ).map { it.copy(color = Color.Unspecified, textDirection = TextDirection.Content) },
                    images = images,
                    imagesCaptions = imagesCaptions,
                    imagesOriginalSize = imagesOriginalSize,
                    imagesWidth = imagesWidth,
                    density = density
                )
                var pages by remember { mutableStateOf(emptyList<ReaderPage>()) }
                var reflowing by remember { mutableStateOf(true) }
                var previousInput by remember {
                    mutableStateOf<Pair<List<ReaderNode>, ReaderPageConfig>?>(null)
                }
                val leadingPages = if (reachedFirst) 0 else 1
                val trailingPages = if (reachedLast) 0 else 1
                val pagerState = rememberPagerState {
                    if (pages.isEmpty()) 0 else pages.size + leadingPages + trailingPages
                }

                val contentAlpha by animateFloatAsState(
                    targetValue = if (!windowReady || reflowing || scrollTarget != null ||
                        pages.getOrNull(pagerState.currentPage - leadingPages) == null
                    ) 0f else 1f,
                    animationSpec = Motion.short(),
                    label = "paged_reader_fade"
                )

                LaunchedEffect(config, readerNodes, nodes, windowReady, paginating, reachedLast, scrollTarget, fontFamilyResolver, layoutDirection) {
                    if (width <= 0 || height <= 0 || nodes.isEmpty()) return@LaunchedEffect
                    if (!windowReady || pageNodes.lastOrNull()?.id != nodes.lastOrNull()?.id) {
                        reflowing = true
                        return@LaunchedEffect
                    }
                    if (scrollTarget == null && pendingTurn == 0 &&
                        previousInput?.first === readerNodes && previousInput?.second == config
                    ) {
                        reflowing = false
                        return@LaunchedEffect
                    }
                    reflowing = true
                    previousInput = null
                    if (scrollTarget != null && pageNodes.none { it.id == scrollTarget.nodeId }) {
                        return@LaunchedEffect
                    }
                    val requestedTarget = scrollTarget?.let { ReaderPagePosition(it.nodeId, it.nodeIntraOffset) }
                        ?: ReaderPagePosition(
                            NodeId(anchorNodeId.coerceIn(pageNodes.first().id.id, pageNodes.last().id.id)),
                            anchorOffset
                        )
                    val target = if (!images && readerNodes.any {
                            it.node.id == requestedTarget.nodeId && it is ReaderNode.Image
                        }
                    ) {
                        val nearby = readerNodes.firstOrNull {
                            it.node.id.id > requestedTarget.nodeId.id && it !is ReaderNode.Image
                        } ?: readerNodes.lastOrNull {
                            it.node.id.id < requestedTarget.nodeId.id && it !is ReaderNode.Image
                        }
                        nearby?.let { ReaderPagePosition(it.node.id, 0) } ?: requestedTarget
                    } else requestedTarget
                    val nextPages = withContext(Dispatchers.Default) {
                        val measurer = TextMeasurer(fontFamilyResolver, density, layoutDirection, cacheSize = 0)
                        paginateReaderPages(readerNodes, config) { text, style ->
                            val result = measurer.measure(
                                text = text,
                                style = style,
                                constraints = Constraints(minWidth = width, maxWidth = width)
                            )
                            ReaderTextMeasurement(
                                height = result.size.height,
                                lineEnds = List(result.lineCount) { result.getLineEnd(it, visibleEnd = false) },
                                lineBottoms = List(result.lineCount) { ceil(result.getLineBottom(it)).toInt() }
                            )
                        }.let { calculated ->
                            if (reachedLast) calculated else calculated.dropLast(1)
                        }
                    }
                    val lastItem = nextPages.lastOrNull()?.items?.lastOrNull()
                    val targetBeyondPages = lastItem == null ||
                        target.nodeId.id > lastItem.node.node.id.id ||
                        (target.nodeId == lastItem.node.node.id && target.offset >= lastItem.endOffset)
                    if (!reachedLast && targetBeyondPages) {
                        if (!paginating) paginateForward(ReaderEvent.OnPaginateForward(nodes.last().id))
                        return@LaunchedEffect
                    }
                    pages = nextPages
                    if (nextPages.isNotEmpty()) {
                        val pageIndex = (findReaderPage(nextPages, target) + pendingTurn)
                            .coerceIn(nextPages.indices)
                        withFrameNanos { }
                        pagerState.scrollToPage(pageIndex + leadingPages)
                        val position = nextPages[pageIndex].position
                        anchorNodeId = position.nodeId.id
                        anchorOffset = position.offset
                    }
                    pendingTurn = 0
                    previousInput = readerNodes to config
                    if (scrollTarget != null) scrollConsumed(ReaderEvent.OnScrollConsumed)
                    reflowing = false
                }

                LaunchedEffect(pagerState, pages, nodes, windowReady, paginating, scrollTarget, reachedFirst, reachedLast) {
                    snapshotFlow { pagerState.settledPage to reflowing }
                        .distinctUntilChanged()
                        .collect { (index, loading) ->
                            pendingTapTarget = null
                            if (loading || !windowReady || scrollTarget != null || nodes.isEmpty()) return@collect
                            if (pages.isEmpty()) {
                                if (!paginating && !reachedLast) {
                                    paginateForward(ReaderEvent.OnPaginateForward(nodes.last().id))
                                }
                                return@collect
                            }
                            val page = pages.getOrNull(index - leadingPages)
                            if (page == null) {
                                pendingTurn = if (index < leadingPages) -1 else 1
                                if (!paginating) {
                                    if (pendingTurn < 0 && !reachedFirst) {
                                        paginateBackward(ReaderEvent.OnPaginateBackward(nodes.first().id))
                                    } else if (pendingTurn > 0 && !reachedLast) {
                                        paginateForward(ReaderEvent.OnPaginateForward(nodes.last().id))
                                    }
                                }
                                return@collect
                            }
                            anchorNodeId = page.position.nodeId.id
                            anchorOffset = page.position.offset
                            updateProgress(
                                ReaderEvent.OnUpdateProgress(
                                    currentNodeId = page.position.nodeId,
                                    currentNodeIntraOffset = page.position.offset,
                                    currentNodeOffset = page.items.first().node.node.position.offset
                                )
                            )
                            if (!paginating) {
                                val firstIndex = nodes.indexOfFirst { it.id == page.items.first().node.node.id }
                                val lastIndex = nodes.indexOfFirst { it.id == page.items.last().node.node.id }
                                if (firstIndex < 20 && !reachedFirst) {
                                    paginateBackward(ReaderEvent.OnPaginateBackward(nodes.first().id))
                                } else if ((lastIndex >= nodes.size - 20 || pages.indexOf(page) >= pages.size - 2) && !reachedLast) {
                                    paginateForward(ReaderEvent.OnPaginateForward(nodes.last().id))
                                }
                            }
                        }
                }

                if (pages.isNotEmpty()) {
                    val pageContent: @Composable PagerScope.(Int) -> Unit = { index ->
                        val page = pages.getOrNull(index - leadingPages)
                        if (page == null) {
                            Box(Modifier.fillMaxSize())
                        } else {
                            // fork: 翻页动画。约定见 ReaderPagedAnimation.kt：
                            // offsetDistance = page - currentPage - currentPageOffsetFraction（正数在前方）
                            val reversed = if (direction.vertical) {
                                direction.reversed
                            } else {
                                direction.reversed xor (layoutDirection == LayoutDirection.Rtl)
                            }
                            val offsetDistance = index - pagerState.currentPage -
                                pagerState.currentPageOffsetFraction
                            key(page) {
                                Box(
                                    Modifier.graphicsLayer {
                                        val pageSize = if (direction.vertical) size.height else size.width
                                        val transform = pagerPageTransform(
                                            animation = animation,
                                            offsetDistance = offsetDistance,
                                            pageSize = pageSize,
                                            reversed = reversed
                                        )
                                        if (direction.vertical) {
                                            translationY = transform.translation
                                        } else {
                                            translationX = transform.translation
                                        }
                                        alpha = transform.alpha
                                    }
                                ) {
                                ReaderPagedPage(
                                    page = page,
                                    sidePadding = sidePadding,
                                    fontColor = fontColor,
                                    imagesCornersRoundness = imagesCornersRoundness,
                                    imagesAlignment = imagesAlignment,
                                    imagesColorEffects = imagesColorEffects,
                                    toolbarHidden = toolbarHidden
                                )
                                }
                            }
                        }
                    }
                    val pagerModifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = contentAlpha }
                        .then(
                            if (toolbarHidden) {
                                Modifier.pointerInput(tapZones, showMenu, doubleClickTranslation) {
                                    detectTapGestures(
                                        // 关键：onDoubleTap 非 null 时 detectTapGestures 会把单击
                                        // 延后到双击超时（~300ms）再回调。默认关闭双击翻译，
                                        // 所以这里必须传 null，否则每次翻页都慢一拍。
                                        onDoubleTap = if (!doubleClickTranslation) null else { offset ->
                                            // fork: 双击翻译原来挂在段落自己的 combinedClickable 上，
                                            // 那是个「第二套点击检测器」，会把单击吞掉，导致正文上的
                                            // 分区点击失效。现在统一在这里处理。
                                            if (doubleClickTranslation) {
                                                val page = pages.getOrNull(
                                                    pagerState.currentPage - leadingPages
                                                )
                                                val node = page?.let { readerNodeAtY(it, offset.y) }
                                                if (node is ReaderNode.Paragraph) {
                                                    openTranslator(
                                                        ReaderEvent.OnOpenTranslator(
                                                            textToTranslate = node.text.text,
                                                            translateWholeParagraph = true
                                                        )
                                                    )
                                                }
                                            }
                                        },
                                        onTap = { offset ->
                                        // 菜单开着时，点任意位置先收起菜单（与 ReaderLayoutContinuous 一致）
                                        if (showMenu) {
                                            menuVisibility(ReaderEvent.OnMenuVisibility(false, true))
                                            return@detectTapGestures
                                        }
                                        val action = tapActionFor(
                                            zones = tapZones,
                                            x = (offset.x / size.width).coerceIn(0f, 1f),
                                            y = (offset.y / size.height).coerceIn(0f, 1f)
                                        )
                                        when (action) {
                                            ReaderTapAction.MENU ->
                                                menuVisibility(ReaderEvent.OnMenuVisibility(!showMenu, true))
                                            ReaderTapAction.NONE -> Unit
                                            ReaderTapAction.NEXT, ReaderTapAction.PREVIOUS -> {
                                                // 连点两次时，第二次从「已排队的目标」继续，
                                                // 而不是从 currentPage 重新算（否则两次点击只翻一页）
                                                val delta = if (action == ReaderTapAction.NEXT) 1 else -1
                                                // 目标距当前页超过 1 页说明上一次连点被用户滚动打断了，丢弃
                                                val from = pendingTapTarget
                                                    ?.takeIf { kotlin.math.abs(it - pagerState.currentPage) <= 1 }
                                                    ?: pagerState.currentPage
                                                val target = (from + delta)
                                                    .coerceIn(0, (pagerState.pageCount - 1).coerceAtLeast(0))
                                                pendingTapTarget = target
                                                tapScope.launch {
                                                    pagerState.animateScrollToPage(target)
                                                }
                                            }
                                        }
                                        }
                                    )
                                }
                            } else {
                                Modifier
                            }
                        )
                    val scrollEnabled = windowReady && !reflowing && toolbarHidden && scrollTarget == null

                    if (direction.vertical) {
                        VerticalPager(
                            state = pagerState,
                            modifier = pagerModifier,
                            reverseLayout = direction.reversed,
                            horizontalAlignment = Alignment.Start,
                            userScrollEnabled = scrollEnabled,
                            pageContent = pageContent
                        )
                    } else {
                        HorizontalPager(
                            state = pagerState,
                            modifier = pagerModifier,
                            reverseLayout = direction.reversed xor (layoutDirection == LayoutDirection.Rtl),
                            verticalAlignment = Alignment.Top,
                            userScrollEnabled = scrollEnabled,
                            pageContent = pageContent
                        )
                    }
                }
            }

            if (progressBar) {
                Box(Modifier.alpha(if (showMenu) 0f else 1f)) {
                    ReaderProgressBar(
                        progress, progressBarPadding, progressBarAlignment,
                        progressBarFontSize, fontColor, sidePadding
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderPagedPage(
    page: ReaderPage,
    sidePadding: Dp,
    fontColor: Color,
    imagesCornersRoundness: Dp,
    imagesAlignment: HorizontalAlignment,
    imagesColorEffects: ColorFilter?,
    toolbarHidden: Boolean
) {
    val density = LocalDensity.current
    Column(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .padding(horizontal = sidePadding)
    ) {
        page.items.forEach { item ->
            if (item.spaceBefore > 0) {
                Spacer(Modifier.height(with(density) { item.spaceBefore.toDp() }))
            }
            val height = with(density) { item.height.toDp() }
            when {
                item.text != null -> StyledText(
                    text = item.text,
                    style = requireNotNull(item.style).copy(color = fontColor),
                    // fork: 去掉逐项点击检测器（见 ReaderLayoutContentParagraph 的说明），
                    // 单击与双击统一交给 Pager 上那一个检测器
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height),
                    overflow = TextOverflow.Clip
                )

                item.node is ReaderNode.Image -> Box(
                    Modifier.fillMaxWidth().height(height),
                    contentAlignment = imagesAlignment.alignment
                ) {
                    AsyncImage(
                        model = item.node.content,
                        contentDescription = null,
                        modifier = Modifier
                            .size(with(density) { item.width.toDp() }, height)
                            .clip(RoundedCornerShape(imagesCornersRoundness))
                            .background(fontColor.copy(0.3f)),
                        contentScale = ContentScale.Fit,
                        colorFilter = imagesColorEffects
                    )
                }

                else -> HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    thickness = height,
                    color = fontColor.copy(0.3f)
                )
            }
        }
    }
}