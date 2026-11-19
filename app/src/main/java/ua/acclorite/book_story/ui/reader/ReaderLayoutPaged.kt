/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
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
import kotlinx.coroutines.withTimeoutOrNull
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
import ua.acclorite.book_story.presentation.reader.model.pageDeltaFor
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

/**
 * 屏幕左右两侧「按下即翻页」的带宽（占正文区宽度）。
 *
 * 这个范围内若分区判定是「上一页/下一页」，就跳过双击判定（`detectTapGestures` 在
 * `onDoubleTap != null` 时会把单击压后 ~200ms）；其它分区（顶部菜单带、CENTER_ONLY 空白区）
 * 照旧等双击。10% 是用户实测手感给出的值，再宽会吃掉正文左侧的取词点击。
 */
private const val EDGE_FAST_PAGE_FRACTION = 0.10f

/** 横向坐标是否落在左右「快速翻页」带内（带内翻页由快速路径负责，外层不再重复翻）。 */
private fun inFastPageBand(x: Float, width: Float): Boolean =
    width > 0f &&
        (x <= width * EDGE_FAST_PAGE_FRACTION || x >= width * (1f - EDGE_FAST_PAGE_FRACTION))

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
    scrollConsumed: (ReaderEvent.OnScrollConsumed) -> Unit,
    loadNodesBefore: suspend (NodeId, Int) -> List<Node>
) {
    val activity = LocalActivity.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // fork: pager 的实际反向状态（下面 Vertical/HorizontalPager 的 reverseLayout）。
    // 点击分区要拿同一个值去算页码增量 —— 反向布局下页码向左递增，
    // 不翻转 delta 就会出现「点左边翻到上一页」（见 pageDeltaFor）。
    val reversed = direction.reversed xor (!direction.vertical && layoutDirection == LayoutDirection.Rtl)
    val fontFamilyResolver = LocalFontFamilyResolver.current
    // fork: 取词的文本块注册表。只在点击时读，不需要触发重组，所以是普通对象而不是 snapshot state。
    val textLayouts = remember { ReaderTextLayoutRegistry() }
    var pagerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
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
        // fork: 选区「翻译」走应用内对话框（S8），不再是外部翻译应用；
        // 外部 Intent 只保留为对话框错误态的「用其他应用翻译」。
        onTranslateRequested = { translation.onTranslate(it, null) },
        // fork: 工具栏「词典」改为查应用内词典（S4 之前是外部 Intent）。
        // 外部 Intent 退到卡片未找到时的「用其他应用查询」，不再是默认动作。
        // 多词选区只查第一个词：整串短语查不到，会把原本可用的「查词」变成「未找到」。
        onDictionaryRequested = { textToDefine ->
            val word = firstLookupWord(textToDefine)
            if (word != null) lookup.onWord(word, null)
            else openDictionary(ReaderEvent.OnOpenDictionary(textToDefine))
        },
        // fork: 长按取词接管长按时关掉正文选区（见 ReaderLookupActions.selectionEnabled）。
        selectionEnabled = lookup.selectionEnabled
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
                            // 翻页/重排后卡片贴着的是上一页的词，先关掉。
                            lookup.onDismiss()
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
                    // fork: 翻页。点击分区的 onTap 与「边缘快速翻页」共用这一份，
                    // 两处各写一遍迟早会出现「双击后第二下不接着排队」的偏差。
                    fun turnPage(action: ReaderTapAction) {
                        if (action != ReaderTapAction.NEXT && action != ReaderTapAction.PREVIOUS) return
                        // 连点两次时，第二次从「已排队的目标」继续，而不是从 currentPage 重新算
                        // （否则两次点击只翻一页）
                        // fork: 分区 → 语义动作（tapActionFor）→ 页码增量（pageDeltaFor）。
                        // 反向阅读方向下翻转的是 delta，不是分区。
                        val delta = pageDeltaFor(action, reversed = reversed)
                        // 目标距当前页超过 1 页说明上一次连点被用户滚动打断了，丢弃
                        val from = pendingTapTarget
                            ?.takeIf { kotlin.math.abs(it - pagerState.currentPage) <= 1 }
                            ?: pagerState.currentPage
                        val target = (from + delta)
                            .coerceIn(0, (pagerState.pageCount - 1).coerceAtLeast(0))
                        pendingTapTarget = target
                        tapScope.launch {
                            // fork: 默认 spring 要 ~400ms 才落定，点击翻页显得拖沓；
                            // 用 200ms tween，配合双击判定窗口缩短，整体更跟手。
                            pagerState.animateScrollToPage(
                                page = target,
                                animationSpec = tween(
                                    durationMillis = Motion.Base,
                                    easing = Motion.Standard
                                )
                            )
                        }
                    }

                    val pageContent: @Composable PagerScope.(Int) -> Unit = { index ->
                        val page = pages.getOrNull(index - leadingPages)
                        if (page == null) {
                            Box(Modifier.fillMaxSize())
                        } else {
                            // fork: 翻页动画。约定见 ReaderPagedAnimation.kt：
                            // offsetDistance = page - currentPage - currentPageOffsetFraction（正数在前方）
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
                                    textLayouts = textLayouts,
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
                        .onGloballyPositioned { pagerCoordinates = it }
                        .graphicsLayer { alpha = contentAlpha }
                        .then(
                            if (toolbarHidden) {
                                // fork: `leadingPages` / `reversed` 是每次组合重算的普通值，
                                // 必须进 key —— 否则翻到首页/末页、或切换阅读方向后，
                                // 闭包里仍是旧的页偏移与翻页方向。
                                Modifier.pointerInput(
                                    tapZones,
                                    showMenu,
                                    doubleClickTranslation,
                                    lookup,
                                    translation,
                                    leadingPages,
                                    reversed
                                ) {
                                    detectTapGestures(
                                        // fork: 手指按下就先高亮命中的词。单击要等双击判定
                                        // （~300ms）才会回调 onTap，没有这个预览用户会觉得「点了没反应」。
                                        onPress = press@{ offset ->
                                            if (!lookup.lookupEnabled || showMenu) return@press
                                            val word = wordAtPageOffset(
                                                page = pages.getOrNull(pagerState.currentPage - leadingPages),
                                                tapInPager = offset,
                                                pagerOrigin = pagerCoordinates
                                                    ?.takeIf { it.isAttached }
                                                    ?.localToWindow(Offset.Zero),
                                                textLayouts = textLayouts
                                            )
                                            if (word != null) {
                                                lookup.onPreview(word.rect)
                                                // 单击模式：抬起后 onTap 会落地成卡片，高亮保持到那时；
                                                // 长按模式：短按只是翻页、没有卡片，抬起就撤掉预览
                                                // （长按真的取词时卡片自己带锚点，撤掉预览不影响高亮）。
                                                val released = tryAwaitRelease()
                                                if (!released || lookup.longPressEnabled) {
                                                    lookup.onPreviewDismiss()
                                                }
                                            }
                                        },
                                        // fork: 长按取词。走与单击同一个注册表命中（锚点就是卡片用的
                                        // 精确词矩形），不再借 SelectionContainer 的选区：Compose 的
                                        // WordAdjustment 与注册表的词边界不是一个坐标系（两端对齐还会
                                        // 偏一个词），会出现「选区高亮 + 卡片高亮」两块区间，拖动选择
                                        // 手柄与 ActionMode 重报还会把卡片顶回来。
                                        onLongPress = if (!lookup.longPressEnabled) null else longPress@{ offset ->
                                            val word = wordAtPageOffset(
                                                page = pages.getOrNull(pagerState.currentPage - leadingPages),
                                                tapInPager = offset,
                                                pagerOrigin = pagerCoordinates
                                                    ?.takeIf { it.isAttached }
                                                    ?.localToWindow(Offset.Zero),
                                                textLayouts = textLayouts
                                            ) ?: return@longPress
                                            lookup.onWord(word.word, word.rect)
                                        },
                                        // 关键：onDoubleTap 非 null 时 detectTapGestures 会把单击
                                        // 延后到双击超时（~300ms）再回调。设置里关掉双击翻译时
                                        // 必须传 null，否则每次翻页都慢一拍。
                                        onDoubleTap = if (!doubleClickTranslation) null else { offset ->
                                            // fork: 双击翻译原来挂在段落自己的 combinedClickable 上，
                                            // 那是个「第二套点击检测器」，会把单击吞掉，导致正文上的
                                            // 分区点击失效。现在统一在这里处理。
                                            // fork: 预览高亮无条件先撤掉 —— 包括「快速翻页带」里的那次双击。
                                            // 带内虽然不弹翻译，但 `SINGLE_TAP` 模式下按下时已经亮了高亮，
                                            // 不撤就会一直卡在屏幕上。
                                            lookup.onDismiss()
                                            // 左右 10% 的快速翻页带里不认双击：那里第一下就已经翻页了，
                                            // 再弹翻译只会让人以为点错了。
                                            if (!inFastPageBand(offset.x, size.width.toFloat())) {
                                                val page = pages.getOrNull(
                                                    pagerState.currentPage - leadingPages
                                                )
                                                val node = page?.let { readerNodeAtY(it, offset.y) }
                                                if (node is ReaderNode.Paragraph) {
                                                    // fork: 整段正文 + 节点 id（不是分页切片），
                                                    // 缓存优先、去重、取消都在 TranslationModel 里。
                                                    translation.onTranslate(
                                                        node.wholeParagraphText(),
                                                        node.node.id.id
                                                    )
                                                }
                                            }
                                        },
                                        onTap = { offset ->
                                        // 菜单开着时，点任意位置先收起菜单（与 ReaderLayoutContinuous 一致）
                                        if (showMenu) {
                                            menuVisibility(ReaderEvent.OnMenuVisibility(false, true))
                                            lookup.onDismiss()
                                            return@detectTapGestures
                                        }
                                        // fork: 取词优先。命中单词就弹卡片并吃掉这次点击；
                                        // 点空白（页脚、行尾、段间空隙）照旧走下面的分区逻辑，
                                        // 所以「翻页/呼菜单」不会因为开了取词就失效。
                                        if (lookup.singleTapEnabled) {
                                            val pageIndex = pagerState.currentPage - leadingPages
                                            val word = wordAtPageOffset(
                                                page = pages.getOrNull(pageIndex),
                                                tapInPager = offset,
                                                pagerOrigin = pagerCoordinates
                                                    ?.takeIf { it.isAttached }
                                                    ?.localToWindow(Offset.Zero),
                                                textLayouts = textLayouts
                                            )
                                            if (word != null) {
                                                lookup.onWord(word.word, word.rect)
                                                return@detectTapGestures
                                            }
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
                                            // 边缘带的翻页由下面的快速路径负责（它在抬手时就翻了），
                                            // 这里再翻一次就是「点一下翻两页」。
                                            ReaderTapAction.NEXT, ReaderTapAction.PREVIOUS ->
                                                if (!inFastPageBand(offset.x, size.width.toFloat())) {
                                                    turnPage(action)
                                                }
                                        }
                                        }
                                    )
                                }
                                // fork: 边缘快速翻页。左右各 10% 的点击**不等双击判定**
                                // （`detectTapGestures` 的 onDoubleTap 非 null 时，单击要等
                                // ~200ms 才知道不是双击），按下即翻。
                                // 位置放在 tap 检测器之后 = 修改器链更内层 = Main pass 先执行，
                                // 消费掉 down 后外层的 `awaitFirstDown()` 收不到这次手势，
                                // 所以只翻一次页（不会被外层的 onTap 再翻一次）。
                                // 只接管「翻页」动作：Kindle 的顶部菜单带、TOP_BOTTOM 的中间带、
                                // CENTER_ONLY 的空白区仍然走原来那条「等双击」的路。
                                // fork: key 里必须带上 `leadingPages` / `reversed` / `lookup`：
                                // 页码换算与 `turnPage` 都读它们，取值变了就得重建手势处理器。
                                .pointerInput(tapZones, showMenu, leadingPages, reversed, lookup) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        if (showMenu) return@awaitEachGesture
                                        val width = size.width.toFloat()
                                        val height = size.height.toFloat()
                                        if (width <= 0f || height <= 0f) return@awaitEachGesture
                                        val edge = width * EDGE_FAST_PAGE_FRACTION
                                        if (down.position.x > edge && down.position.x < width - edge) {
                                            return@awaitEachGesture
                                        }
                                        val action = tapActionFor(
                                            zones = tapZones,
                                            x = (down.position.x / width).coerceIn(0f, 1f),
                                            y = (down.position.y / height).coerceIn(0f, 1f)
                                        )
                                        if (action != ReaderTapAction.NEXT &&
                                            action != ReaderTapAction.PREVIOUS
                                        ) {
                                            return@awaitEachGesture
                                        }
                                        val word = wordAtPageOffset(
                                            page = pages.getOrNull(pagerState.currentPage - leadingPages),
                                            tapInPager = down.position,
                                            pagerOrigin = pagerCoordinates
                                                ?.takeIf { it.isAttached }
                                                ?.localToWindow(Offset.Zero),
                                            textLayouts = textLayouts
                                        )

                                        if (word == null) {
                                            // 空白（页边距、行尾、段间空隙、页脚）：整条手势归这里。
                                            // 消费 down 让外层 `detectTapGestures` 收不到，松手即翻。
                                            down.consume()
                                            // 拖动/滑动返回 null —— 这一下不是点击，交给 Pager 自己翻。
                                            if (waitForUpOrCancellation() == null) {
                                                return@awaitEachGesture
                                            }
                                            turnPage(action)
                                            return@awaitEachGesture
                                        }

                                        // 命中了单词：`SINGLE_TAP` 模式下这次点击属于取词，直接放手。
                                        if (word != null && lookup.singleTapEnabled) return@awaitEachGesture

                                        // 其余取词模式（`LONG_PRESS` / `OFF`）下，边缘带里的单击既不是
                                        // 取词也不是选文本（那要长按），没理由等双击窗口：
                                        // down 不消费，长按留给外层（`onLongPress` 取词 / 选文本），
                                        // 在长按超时内抬手就算单击 → 立刻翻页。
                                        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                            waitForUpOrCancellation()
                                        } ?: return@awaitEachGesture
                                        up.consume()
                                        turnPage(action)
                                    }
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
                            reverseLayout = reversed,
                            horizontalAlignment = Alignment.Start,
                            userScrollEnabled = scrollEnabled,
                            pageContent = pageContent
                        )
                    } else {
                        HorizontalPager(
                            state = pagerState,
                            modifier = pagerModifier,
                            reverseLayout = reversed,
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
    textLayouts: ReaderTextLayoutRegistry,
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
                item.text != null -> {
                    // fork: 每个页项注册自己的 TextLayoutResult + 窗口矩形。
                    // 同一段文本被切成两页时 startOffset 不同，所以 key 要带上它。
                    val textLayout = rememberReaderTextLayoutHandle(
                        registry = textLayouts,
                        nodeId = item.node.node.id,
                        startOffset = item.startOffset
                    )
                    StyledText(
                        text = item.text,
                        style = requireNotNull(item.style).copy(color = fontColor),
                        // fork: 去掉逐项点击检测器（见 ReaderLayoutContentParagraph 的说明），
                        // 单击与双击统一交给 Pager 上那一个检测器
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(height)
                            .then(textLayout.positionModifier),
                        overflow = TextOverflow.Clip,
                        onTextLayout = textLayout::onTextLayout
                    )
                }

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
/**
 * Pager 局部点击坐标 → 词命中。
 *
 * 先用 [readerPageItemAtY] 定位页项（空白区/页脚直接 null），再用该页项注册的
 * `TextLayoutResult` 取词。`tapInPager` 加 pager 原点是窗口坐标 —— 注册表存的是窗口矩形，
 * 滚动/翻页动画里也能对上。
 */
private fun wordAtPageOffset(
    page: ReaderPage?,
    tapInPager: Offset,
    pagerOrigin: Offset?,
    textLayouts: ReaderTextLayoutRegistry
): WordHit? {
    if (page == null || pagerOrigin == null) return null
    val itemHit = readerPageItemAtY(page, tapInPager.y) ?: return null
    // 落在段前空隙里（localY < 0）不算命中：那里没有字。
    if (itemHit.localY < 0f) return null
    val item = itemHit.item
    if (item.text == null) return null
    val entry = textLayouts.entry(
        ReaderTextLayoutKey(nodeId = item.node.node.id.id, startOffset = item.startOffset)
    ) ?: return null
    return entry.wordAt(pagerOrigin + tapInPager)
}
