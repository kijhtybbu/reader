/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import ua.acclorite.book_story.core.BottomSheet
import ua.acclorite.book_story.core.Drawer
import ua.acclorite.book_story.domain.model.book.Book
import ua.acclorite.book_story.domain.model.book.Chapter
import ua.acclorite.book_story.domain.model.book.Node
import ua.acclorite.book_story.domain.model.book.NodeId
import ua.acclorite.book_story.presentation.reader.LayoutState
import ua.acclorite.book_story.presentation.reader.ReaderEvent
import ua.acclorite.book_story.presentation.reader.ScrollTarget
import ua.acclorite.book_story.presentation.reader.model.Checkpoint
import ua.acclorite.book_story.presentation.reader.model.ReaderLayout
import ua.acclorite.book_story.presentation.reader.model.ReaderPagedAnimation
import ua.acclorite.book_story.presentation.reader.model.ReaderPagedDirection
import ua.acclorite.book_story.presentation.reader.model.ReaderTapZones
import ua.acclorite.book_story.presentation.settings.SettingsEvent
import ua.acclorite.book_story.ui.theme.model.HorizontalAlignment
import ua.acclorite.book_story.ui.theme.LocalReaderPalette
import ua.acclorite.book_story.ui.theme.readerPalette
import ua.acclorite.book_story.ui.theme.SystemBarsAppearanceOverride
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import ua.acclorite.book_story.domain.model.book.NodeContent
import ua.acclorite.book_story.domain.model.bookmark.Bookmark
import ua.acclorite.book_story.domain.model.bookmark.bookmarkForNode
import ua.acclorite.book_story.presentation.reader.BookmarkViewModel
import ua.acclorite.book_story.presentation.reader.DictionaryLookupModel
import ua.acclorite.book_story.presentation.reader.LookupState
import ua.acclorite.book_story.presentation.reader.TranslationModel
import ua.acclorite.book_story.presentation.reader.anchor
import ua.acclorite.book_story.presentation.reader.model.ReaderLookupGesture
import ua.acclorite.book_story.presentation.settings.DictionaryManagerScreen
import ua.acclorite.book_story.ui.navigator.LocalNavigator

@Composable
fun ReaderContent(
    layout: ReaderLayout,
    pagedDirection: ReaderPagedDirection,
    pagedAnimation: ReaderPagedAnimation,
    tapZones: ReaderTapZones,
    layoutState: LayoutState,
    nodes: List<Node>,
    chapters: List<Chapter>,
    book: Book,
    paginating: Boolean,
    scrollTarget: ScrollTarget?,
    bottomSheet: BottomSheet?,
    drawer: Drawer?,
    currentChapter: Chapter?,
    currentChapterString: String?,
    currentChapterProgress: Float,
    chaptersEmpty: Boolean,
    nestedScrollConnection: NestedScrollConnection,
    fastColorPresetChange: Boolean,
    perceptionExpander: Boolean,
    perceptionExpanderPadding: Dp,
    perceptionExpanderThickness: Dp,
    horizontalLimiter: Boolean,
    horizontalLimiterHeight: Dp,
    horizontalLimiterVerticalOffset: Float,
    horizontalLimiterRulerThickness: Dp,
    horizontalLimiterRuler: Boolean,
    horizontalLimiterDimming: Float,
    checkpoints: List<Checkpoint>,
    showMenu: Boolean,
    lockMenu: Boolean,
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
    bottomBarPadding: Dp,
    backgroundColor: Color,
    fontColor: Color,
    images: Boolean,
    imagesCaptions: Boolean,
    imagesCornersRoundness: Dp,
    imagesAlignment: HorizontalAlignment,
    imagesWidth: Float,
    imagesColorEffects: ColorFilter?,
    imagesOriginalSize: Boolean,
    horizontalAlignment: Alignment.Horizontal,
    doubleClickTranslation: Boolean,
    lookupGesture: ReaderLookupGesture,
    switchColorPreset: (SettingsEvent.OnSwitchColorPreset) -> Unit,
    menuVisibility: (ReaderEvent.OnMenuVisibility) -> Unit,
    retry: (ReaderEvent.OnRetry) -> Unit,
    leave: (ReaderEvent.OnLeave) -> Unit,
    restoreCheckpoint: (ReaderEvent.OnRestoreCheckpoint) -> Unit,
    // fork: 放宽为任意 ReaderEvent —— 书签跳转用的是 OnScrollToPosition，
    // 没必要为它再加一条贯穿四层的参数
    scroll: (ReaderEvent) -> Unit,
    openShareApp: (ReaderEvent.OnOpenShareApp) -> Unit,
    openWebBrowser: (ReaderEvent.OnOpenWebBrowser) -> Unit,
    openTranslator: (ReaderEvent.OnOpenTranslator) -> Unit,
    openDictionary: (ReaderEvent.OnOpenDictionary) -> Unit,
    scrollToChapter: (ReaderEvent.OnScrollToChapter) -> Unit,
    showSettingsBottomSheet: (ReaderEvent.OnShowSettingsBottomSheet) -> Unit,
    changeCharset: (ReaderEvent.OnChangeCharset) -> Unit,
    dismissBottomSheet: (ReaderEvent.OnDismissBottomSheet) -> Unit,
    showChaptersDrawer: (ReaderEvent.OnShowChaptersDrawer) -> Unit,
    dismissDrawer: (ReaderEvent.OnDismissDrawer) -> Unit,
    updateProgress: (ReaderEvent.OnUpdateProgress) -> Unit,
    paginateForward: (ReaderEvent.OnPaginateForward) -> Unit,
    paginateBackward: (ReaderEvent.OnPaginateBackward) -> Unit,
    scrollConsumed: (ReaderEvent.OnScrollConsumed) -> Unit,
    navigateToBookInfo: (ReaderEvent.OnNavigateToBookInfo) -> Unit,
    navigateBack: (ReaderEvent.OnNavigateBack) -> Unit,
    loadNodesBefore: suspend (NodeId, Int) -> List<Node>
) {
    // fork: palette 必须在这里提供 —— ReaderBottomSheet 与 ReaderScaffold 是兄弟节点，
    // 只包在 Scaffold 里会让底部面板拿到 ReaderPalette.Default（浅色），深色预设下正好是本次要修的 bug。
    // MaterialTheme 的读取不能放进 remember 的 lambda（非 @Composable 作用域）。
    // fork: 书签。图标指示与去重都按 nodeId（段落）判断，跳转仍然用精确的 nodeIntraOffset。
    val bookmarkViewModel: BookmarkViewModel = hiltViewModel()
    val bookmarks by bookmarkViewModel.bookmarks.collectAsStateWithLifecycle()
    LaunchedEffect(book.id) { bookmarkViewModel.load(book.id) }
    // fork: 用 rememberSaveable —— 普通 remember 会把面板状态丢掉，旋转屏幕时面板自己关掉
    var showBookmarks by rememberSaveable { mutableStateOf(false) }

    val currentNodeId = book.progress.currentNodeId
    val currentNodeOffset = book.progress.currentNodeIntraOffset
    val currentBookmark = bookmarkForNode(bookmarks, currentNodeId.id)
    // 摘录：从当前这一行（段内偏移）开始取若干字；只看段首的话，段落中间的书签会显示成无关的文字
    val currentSnippet = remember(nodes, currentNodeId, currentNodeOffset) {
        nodes.firstOrNull { it.id == currentNodeId }
            ?.content
            ?.let { it as? NodeContent.Paragraph }
            ?.text
            ?.drop(currentNodeOffset)
            ?.take(60)
            ?.trim()
            .orEmpty()
    }

    val accentSeed = MaterialTheme.colorScheme.primary
    val palette = remember(backgroundColor, fontColor, accentSeed) {
        readerPalette(backgroundColor, fontColor, accentSeed)
    }

    // fork: 取词卡片。状态与回调都在这里收口，两种布局只负责「点到哪个词」。
    val lookupModel: DictionaryLookupModel = hiltViewModel()
    val lookupState by lookupModel.state.collectAsStateWithLifecycle()
    // 手指按下就亮高亮：卡片要等双击判定，这段等待里必须给用户「点到了」的反馈。
    val lookupPreviewAnchor by lookupModel.previewAnchor.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var ttsUnavailable by remember { mutableStateOf(false) }
    val navigator = LocalNavigator.current
    val lookup = remember(lookupGesture, lookupModel) {
        ReaderLookupActions(
            gesture = lookupGesture,
            onWord = { word, anchor -> lookupModel.lookup(word, anchor) },
            onPreview = { anchor -> lookupModel.preview(anchor) },
            onPreviewDismiss = { lookupModel.clearPreview() },
            onDismiss = { lookupModel.dismiss() }
        )
    }
    // fork: 翻译对话框（S8）。双击段落与选区工具栏「翻译」共用这一个模型：
    // 缓存优先、同一 cacheKey 去重、关闭取消都在模型里。
    val translationModel: TranslationModel = hiltViewModel()
    val translationState by translationModel.state.collectAsStateWithLifecycle()
    val translation = remember(translationModel, book.id) {
        ReaderTranslationActions { text, nodeId ->
            translationModel.open(text = text, bookId = book.id, nodeId = nodeId)
        }
    }

    // 切菜单/跳转都要先关卡片：它是贴在某一段文字上的，位置一变就贴错了。
    val menuVisibilityDismissingLookup = remember(menuVisibility, lookupModel) {
        { event: ReaderEvent.OnMenuVisibility ->
            lookupModel.dismiss()
            menuVisibility(event)
        }
    }
    val scrollDismissingLookup = remember(scroll, lookupModel) {
        { event: ReaderEvent ->
            lookupModel.dismiss()
            scroll(event)
        }
    }

    // 阅读页的系统栏区域被 palette.bar 覆盖，图标明暗必须跟着它走，否则默认配置下
    // （深色系统 + 默认浅色预设）会出现浅底 + 浅图标
    SystemBarsAppearanceOverride(lightBars = palette.bar.luminance() > 0.5f)

    if (showBookmarks) {
        ReaderBookmarksBottomSheet(
            bookmarks = bookmarks,
            onJump = { bookmark ->
                showBookmarks = false
                scroll(
                    ReaderEvent.OnScrollToPosition(
                        nodeId = NodeId(bookmark.nodeId),
                        nodeIntraOffset = bookmark.nodeIntraOffset
                    )
                )
            },
            onDelete = { bookmarkViewModel.delete(it.id) },
            onDismiss = { showBookmarks = false }
        )
    }

    CompositionLocalProvider(LocalReaderPalette provides palette) {
        ReaderBottomSheet(
            bottomSheet = bottomSheet,
            book = book,
            menuVisibility = menuVisibility,
            changeCharset = changeCharset,
            dismissBottomSheet = dismissBottomSheet
        )

        when (layoutState) {
            is LayoutState.Loading -> {
                ReaderLoadingPlaceholder(navigateBack)
            }

            is LayoutState.Parsing -> {
                ReaderParsingPlaceholder(layoutState, navigateBack)
            }

            is LayoutState.Saving -> {
                ReaderSavingPlaceholder(navigateBack)
            }

            is LayoutState.Error -> {
                ReaderErrorPlaceholder(layoutState, retry, navigateBack)
            }

            is LayoutState.Showing -> {
                ReaderScaffold(
                    layout = layout,
                    pagedDirection = pagedDirection,
                    pagedAnimation = pagedAnimation,
                    tapZones = tapZones,
                    bookmarkState = bookmarkButtonState(currentBookmark, currentNodeOffset),
                    onToggleBookmark = {
                        bookmarkViewModel.toggle(
                            Bookmark(
                                bookId = book.id,
                                nodeId = currentNodeId.id,
                                nodeIntraOffset = currentNodeOffset,
                                chapterTitle = currentChapterString ?: "",
                                snippet = currentSnippet,
                                createdAt = System.currentTimeMillis()
                            )
                        )
                    },
                    onShowBookmarks = { showBookmarks = true },
                    nodes = nodes,
                    book = book,
                    paginating = paginating,
                    scrollTarget = scrollTarget,
                    currentChapter = currentChapter,
                    currentChapterString = currentChapterString,
                    chaptersEmpty = chaptersEmpty,
                    nestedScrollConnection = nestedScrollConnection,
                    fastColorPresetChange = fastColorPresetChange,
                    perceptionExpander = perceptionExpander,
                    perceptionExpanderPadding = perceptionExpanderPadding,
                    perceptionExpanderThickness = perceptionExpanderThickness,
                    horizontalLimiter = horizontalLimiter,
                    horizontalLimiterHeight = horizontalLimiterHeight,
                    horizontalLimiterVerticalOffset = horizontalLimiterVerticalOffset,
                    horizontalLimiterRuler = horizontalLimiterRuler,
                    horizontalLimiterRulerThickness = horizontalLimiterRulerThickness,
                    horizontalLimiterDimming = horizontalLimiterDimming,
                    currentChapterProgress = currentChapterProgress,
                    checkpoints = checkpoints,
                    showMenu = showMenu,
                    lockMenu = lockMenu,
                    contentPadding = contentPadding,
                    verticalPadding = verticalPadding,
                    highlightedReading = highlightedReading,
                    highlightedReadingThickness = highlightedReadingThickness,
                    progress = progress,
                    progressBar = progressBar,
                    progressBarPadding = progressBarPadding,
                    progressBarAlignment = progressBarAlignment,
                    progressBarFontSize = progressBarFontSize,
                    paragraphTextStyle = paragraphTextStyle,
                    boldFontThickness = boldFontThickness,
                    header1TextStyle = header1TextStyle,
                    header2TextStyle = header2TextStyle,
                    header3TextStyle = header3TextStyle,
                    header4TextStyle = header4TextStyle,
                    header5TextStyle = header5TextStyle,
                    header6TextStyle = header6TextStyle,
                    paragraphHeight = paragraphHeight,
                    sidePadding = sidePadding,
                    bottomBarPadding = bottomBarPadding,
                    backgroundColor = backgroundColor,
                    fontColor = fontColor,
                    images = images,
                    imagesCaptions = imagesCaptions,
                    imagesCornersRoundness = imagesCornersRoundness,
                    imagesAlignment = imagesAlignment,
                    imagesWidth = imagesWidth,
                    imagesColorEffects = imagesColorEffects,
                    imagesOriginalSize = imagesOriginalSize,
                    horizontalAlignment = horizontalAlignment,
                    doubleClickTranslation = doubleClickTranslation,
                    lookupAnchor = lookupPreviewAnchor ?: lookupState.anchor,
                    lookup = lookup,
                    translation = translation,
                    switchColorPreset = switchColorPreset,
                    menuVisibility = menuVisibilityDismissingLookup,
                    leave = leave,
                    restoreCheckpoint = restoreCheckpoint,
                    scroll = scrollDismissingLookup,
                    openShareApp = openShareApp,
                    openWebBrowser = openWebBrowser,
                    openDictionary = openDictionary,
                    showSettingsBottomSheet = showSettingsBottomSheet,
                    showChaptersDrawer = showChaptersDrawer,
                    updateProgress = updateProgress,
                    paginateForward = paginateForward,
                    paginateBackward = paginateBackward,
                    scrollConsumed = scrollConsumed,
                    navigateBack = navigateBack,
                    navigateToBookInfo = navigateToBookInfo,
                    loadNodesBefore = loadNodesBefore
                )
            }
        }

        ReaderDrawer(
            drawer = drawer,
            chapters = chapters,
            currentChapter = currentChapter,
            currentChapterProgress = currentChapterProgress,
            scrollToChapter = scrollToChapter,
            dismissDrawer = dismissDrawer
        )

        if (lookupState !is LookupState.Idle) {
            ReaderLookupPopup(
                state = lookupState,
                onSelect = lookupModel::select,
                onLookupLink = { word -> lookupModel.lookup(word, lookupState.anchor) },
                onRetry = lookupModel::retry,
                onDismiss = lookupModel::dismiss,
                onImportDictionary = {
                    lookupModel.dismiss()
                    navigator.push(DictionaryManagerScreen)
                },
                // S4 之前工具栏「词典」走的就是这个外部 Intent；现在它是未找到时的回退。
                onExternalLookup = { word ->
                    lookupModel.dismiss()
                    openDictionary(ReaderEvent.OnOpenDictionary(word))
                },
                onTtsUnavailable = { ttsUnavailable = true }
            )
        }

        if (translationState.visible) {
            ReaderTranslationDialog(
                state = translationState,
                onSelectTab = translationModel::selectTab,
                onSelectSentence = translationModel::selectSentence,
                onRegenerate = translationModel::regenerate,
                onDismiss = translationModel::dismiss,
                onShare = { text -> openShareApp(ReaderEvent.OnOpenShareApp(text)) },
                // S8 之前双击段落走的就是这个外部 Intent；现在它只是错误态的退路。
                onExternalTranslate = { text ->
                    openTranslator(ReaderEvent.OnOpenTranslator(text))
                }
            )
        }

        if (ttsUnavailable) {
            TtsUnavailableDialog(
                onDismiss = { ttsUnavailable = false },
                onOpenInstaller = {
                    ttsUnavailable = false
                    openTtsVoiceInstaller(context)
                }
            )
        }

        ReaderBackHandler(
            lookupOpen = lookupState !is LookupState.Idle,
            dismissLookup = lookupModel::dismiss,
            leave = leave,
            navigateBack = navigateBack
        )
    }
}
