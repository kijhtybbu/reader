/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.presentation.reader

import android.content.pm.ActivityInfo
import android.os.Parcelable
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.parcelize.Parcelize
import ua.acclorite.book_story.core.helpers.calculateProgress
import ua.acclorite.book_story.domain.model.book.cleanTitle
import ua.acclorite.book_story.presentation.navigator.Screen
import ua.acclorite.book_story.presentation.reader.model.ReaderColorEffects
import ua.acclorite.book_story.presentation.reader.model.ReaderProgressCount
import ua.acclorite.book_story.presentation.reader.model.ReaderTextAlignment
import ua.acclorite.book_story.presentation.settings.SettingsModel
import ua.acclorite.book_story.ui.common.helpers.LocalActivity
import ua.acclorite.book_story.ui.common.helpers.LocalSettings
import ua.acclorite.book_story.ui.common.helpers.setBrightness
import ua.acclorite.book_story.ui.reader.ReaderContent
import ua.acclorite.book_story.ui.reader.ReaderEffects
import ua.acclorite.book_story.ui.reader.readerParagraphTextStyle
import ua.acclorite.book_story.ui.reader.rememberFontFamily
import ua.acclorite.book_story.ui.reader.translator.LocalBookTranslator
import ua.acclorite.book_story.ui.reader.translator.rememberBookTranslator
import ua.acclorite.book_story.ui.reader.typography.LocalReaderTypography
import ua.acclorite.book_story.ui.reader.typography.rememberBookReaderTypography

@Parcelize
data class ReaderScreen(val bookId: Int) : Screen, Parcelable {

    companion object {
        const val CHAPTERS_DRAWER = "chapters_drawer"
        const val SETTINGS_BOTTOM_SHEET = "settings_bottom_sheet"
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content() {
        val screenModel = hiltViewModel<ReaderModel>()
        val settingsModel = hiltViewModel<SettingsModel>()
        val settings = LocalSettings.current

        val state = screenModel.state.collectAsStateWithLifecycle()
        val settingsState = settingsModel.state.collectAsStateWithLifecycle()

        // fork: 当前生效的排版（全局或本书）。12 个排版项从这里读，其余设置仍走全局。
        // 包裹 ReaderContent 后，底部面板里的 12 个控件也自动编辑同一套值。
        val typography = rememberBookReaderTypography(state.value.book.uuid)
        // fork: 翻译器行为按书、默认关闭。旧全局设置不再参与。
        val translator = rememberBookTranslator(state.value.book.uuid)

        val activity = LocalActivity.current
        val density = LocalDensity.current
        val nestedScrollConnection = remember {
            derivedStateOf {
                object : NestedScrollConnection {
                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource
                    ): Offset {
                        consumed.y.let { velocity ->
                            if (velocity in -70f..70f) return@let
                            if (!state.value.showMenu) return@let
                            if (state.value.lockMenu) return@let
                            if (!settings.hideBarsOnFastScroll.lastValue) return@let

                            screenModel.onEvent(
                                ReaderEvent.OnMenuVisibility(
                                    show = false,
                                    saveCheckpoint = false
                                )
                            )
                        }
                        return super.onPostScroll(consumed, available, source)
                    }
                }
            }
        }

        val backgroundColor = animateColorAsState(
            targetValue = settingsState.value.selectedColorPreset.backgroundColor
        )
        val fontColor = animateColorAsState(
            targetValue = settingsState.value.selectedColorPreset.fontColor
        )
        val sidePadding = remember(typography.values.sidePadding) {
            (typography.values.sidePadding * 3).dp
        }
        val verticalPadding = remember(typography.values.verticalPadding) {
            (typography.values.verticalPadding * 4.5f).dp
        }
        val paragraphHeight = remember(
            typography.values.paragraphHeight,
            typography.values.lineHeight
        ) {
            ((typography.values.paragraphHeight * 3).dp).coerceAtLeast(
                with(density) { typography.values.lineHeight.sp.toDp().value * 0.5f }.dp
            )
        }
        val perceptionExpanderPadding = remember(
            sidePadding,
            settings.perceptionExpanderPadding.value
        ) {
            sidePadding + (settings.perceptionExpanderPadding.lastValue * 8).dp
        }
        val perceptionExpanderThickness = remember(
            settings.perceptionExpanderThickness.value
        ) {
            (settings.perceptionExpanderThickness.lastValue * 0.25f).dp
        }
        val horizontalLimiterHeight = remember(
            settings.horizontalLimiterHeight.value
        ) {
            (settings.horizontalLimiterHeight.lastValue * 20).dp
        }
        val horizontalLimiterRulerThickness = remember(
            settings.horizontalLimiterRulerThickness.value
        ) {
            (settings.horizontalLimiterRulerThickness.lastValue * 0.25f).dp
        }
        val horizontalAlignment = remember(typography.values.textAlignment) {
            when (typography.values.textAlignment) {
                ReaderTextAlignment.START, ReaderTextAlignment.JUSTIFY -> Alignment.Start
                ReaderTextAlignment.CENTER -> Alignment.CenterHorizontally
                ReaderTextAlignment.END -> Alignment.End
            }
        }
        val imagesWidth = remember(settings.imagesWidth.value) {
            settings.imagesWidth.lastValue.coerceAtLeast(0.01f)
        }
        val imagesCornersRoundness = remember(
            settings.imagesCornersRoundness.value,
            settings.imagesWidth.value
        ) {
            (settings.imagesCornersRoundness.lastValue * 3 * imagesWidth).dp
        }
        val imagesColorEffects = remember(
            settings.imagesColorEffects.value,
            fontColor.value,
            backgroundColor.value
        ) {
            when (settings.imagesColorEffects.lastValue) {
                ReaderColorEffects.OFF -> null

                ReaderColorEffects.GRAYSCALE -> ColorFilter.colorMatrix(
                    ColorMatrix().apply { setToSaturation(0f) }
                )

                ReaderColorEffects.FONT -> ColorFilter.tint(
                    color = fontColor.value,
                    blendMode = BlendMode.Color
                )

                ReaderColorEffects.BACKGROUND -> ColorFilter.tint(
                    color = backgroundColor.value,
                    blendMode = BlendMode.Color
                )
            }
        }
        val progressBarPadding = remember(settings.progressBarPadding.value) {
            (settings.progressBarPadding.lastValue * 3).dp
        }
        val progressBarFontSize = remember(settings.progressBarFontSize.value) {
            (settings.progressBarFontSize.lastValue * 2).sp
        }

        // fork: 用户字体的 FontFamily 由 (font, weight, italic) 在后台解析；内置字体直接返回。
        // 这里用它作为 remember 的 key，Typeface 建好后会触发一次重组（并重新分页）。
        val resolvedFontFamily = rememberFontFamily(
            font = typography.values.fontFamily,
            fontWeight = typography.values.fontThickness,
            italic = typography.values.italic
        )

        // fork: 正文样式抽到 readerParagraphTextStyle，与排版面板的样例预览共用同一份实现，
        // 避免预览显示书里并不存在的效果（曾漏掉斜体与「居中/右对齐取消缩进」）
        val paragraphTextStyle = remember(
            typography.values,
            fontColor.value,
            resolvedFontFamily
        ) {
            readerParagraphTextStyle(
                fontFamily = resolvedFontFamily,
                fontWeight = FontWeight(typography.values.fontThickness),
                fontSizeSp = typography.values.fontSize,
                lineHeightDeltaSp = typography.values.lineHeight,
                letterSpacingHundredths = typography.values.letterSpacing,
                paragraphIndentationSetting = typography.values.paragraphIndentation,
                textAlignment = typography.values.textAlignment,
                italic = typography.values.italic,
                // fork: 用户字体没有 italic 字型，Android 也不做伪斜体，用几何倾斜合成
                synthesizeItalic = typography.values.fontFamily.userEntry != null,
                color = fontColor.value
            )
        }

        val headerTextStyles = settings.headers.map { header ->
            val fontSize = header.fontSize.value
            val fontWeight = header.fontWeight.value
            val textAlignment = header.textAlignment.value

            remember(paragraphTextStyle, fontSize, fontWeight, textAlignment) {
                paragraphTextStyle.copy(
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.2f).sp,
                    fontWeight = FontWeight(fontWeight),
                    fontStyle = FontStyle.Normal,
                    // fork: 标题永远不斜体；清掉正文为合成斜体加的 skew，否则标题也会被拉斜
                    textGeometricTransform = null,
                    textAlign = textAlignment.textAlignment,
                    textIndent = if (textAlignment.textAlignment == TextAlign.Center) {
                        TextIndent.None
                    } else paragraphTextStyle.textIndent
                )
            }
        }

        val layoutDirection = LocalLayoutDirection.current
        val cutoutInsets = WindowInsets.displayCutout
        val systemBarsInsets = WindowInsets.systemBarsIgnoringVisibility

        val cutoutInsetsPadding = remember(settings.cutoutPadding.value) {
            derivedStateOf {
                cutoutInsets.asPaddingValues(density = density).run {
                    if (settings.cutoutPadding.lastValue) PaddingValues(
                        top = calculateTopPadding(),
                        start = calculateStartPadding(layoutDirection),
                        end = calculateEndPadding(layoutDirection),
                        bottom = calculateBottomPadding()
                    ) else PaddingValues(0.dp)
                }
            }
        }
        val systemBarsInsetsPadding = remember(settings.fullscreen.value) {
            derivedStateOf {
                systemBarsInsets.asPaddingValues(density = density).run {
                    if (!settings.fullscreen.lastValue) PaddingValues(
                        top = calculateTopPadding(),
                        start = calculateStartPadding(layoutDirection),
                        end = calculateEndPadding(layoutDirection),
                        bottom = calculateBottomPadding()
                    ) else PaddingValues(0.dp)
                }
            }
        }
        val contentPadding = remember(
            cutoutInsetsPadding.value,
            systemBarsInsetsPadding.value
        ) {
            PaddingValues(
                top = systemBarsInsetsPadding.value.calculateTopPadding().run {
                    if (equals(0.dp)) return@run cutoutInsetsPadding.value
                        .calculateTopPadding()
                    this
                },
                start = systemBarsInsetsPadding.value.calculateStartPadding(layoutDirection).run {
                    if (equals(0.dp)) return@run cutoutInsetsPadding.value
                        .calculateStartPadding(layoutDirection)
                    this
                },
                end = systemBarsInsetsPadding.value.calculateEndPadding(layoutDirection).run {
                    if (equals(0.dp)) return@run cutoutInsetsPadding.value
                        .calculateEndPadding(layoutDirection)
                    this
                },
                bottom = systemBarsInsetsPadding.value.calculateBottomPadding().run {
                    if (equals(0.dp)) return@run cutoutInsetsPadding.value
                        .calculateBottomPadding()
                    this
                }
            )
        }
        val bottomBarPadding = remember(settings.bottomBarPadding.value) {
            (settings.bottomBarPadding.lastValue * 4f).dp
        }

        val currentChapter =
            remember(state.value.book.progress.currentOffset, state.value.chapters) {
                derivedStateOf {
                    state.value.chapters.findLast {
                        it.position.isCurrent(state.value.book.progress.currentOffset)
                    }
                }
            }
        val currentChapterString =
            remember(state.value.book.progress.currentOffset, state.value.chapters) {
                var str = ""
                state.value.chapters.filter {
                    it.position.isCurrent(state.value.book.progress.currentOffset)
                }.also { currentChapters ->
                    currentChapters.forEachIndexed { index, chapter ->
                        str += chapter.cleanTitle
                        if (index != currentChapters.lastIndex) str += " • "
                    }
                }
                str.ifBlank { null }
            }
        val currentChapterProgress =
            remember(state.value.book.progress.currentOffset, currentChapter.value) {
                currentChapter.value?.position?.progress(state.value.book.progress.currentOffset)
                    ?: 0.0f
            }

        val bookPercentage = state.value.book.progress.getPercentage()
        val bookProgress = remember(
            bookPercentage,
            state.value.book.progress,
            settings.progressCount.value
        ) {
            when (settings.progressCount.lastValue) {
                ReaderProgressCount.PERCENTAGE -> {
                    "${bookPercentage.calculateProgress(2)}%"
                }

                ReaderProgressCount.QUANTITY -> {
                    val current =
                        (state.value.book.progress.currentNodeId.id + 1).coerceAtMost(state.value.book.progress.totalNodes)
                    "$current / ${state.value.book.progress.totalNodes}"
                }

                ReaderProgressCount.CHARACTERS -> {
                    val total = state.value.book.progress.totalLength.coerceAtLeast(0)
                    val current = state.value.book.progress.currentOffset.coerceIn(0, total)
                    "$current / $total"
                }
            }
        }
        val chapterProgress = remember(
            currentChapter.value,
            currentChapterProgress,
            state.value.book.progress,
            state.value.chapters,
            settings.progressCount.value
        ) {
            val chapter = currentChapter.value ?: return@remember ""
            when (settings.progressCount.lastValue) {
                ReaderProgressCount.PERCENTAGE -> {
                    " (${currentChapterProgress.calculateProgress(2)}%)"
                }

                ReaderProgressCount.QUANTITY -> {
                    val startNodeId = chapter.linkId.id
                    val endNodeId = state.value.chapters.firstOrNull {
                        it.position.chapterOffset == chapter.position.endChapterOffset &&
                                it.linkId.id > startNodeId
                    }?.linkId?.id ?: state.value.book.progress.totalNodes
                    val total = (endNodeId - startNodeId).coerceAtLeast(0)
                    val current = (state.value.book.progress.currentNodeId.id - startNodeId + 1)
                        .coerceIn(0, total)
                    " ($current / $total)"
                }

                ReaderProgressCount.CHARACTERS -> {
                    val currentIndexInChapter =
                        (state.value.book.progress.currentOffset - chapter.position.chapterOffset).coerceAtLeast(
                            0
                        )
                    val chapterLength =
                        (chapter.position.endChapterOffset - chapter.position.chapterOffset).coerceAtLeast(
                            1
                        )
                    " ($currentIndexInChapter / $chapterLength)"
                }
            }
        }
        val progress = remember(bookProgress, chapterProgress) {
            "${bookProgress}${chapterProgress}"
        }

        LaunchedEffect(Unit) {
            screenModel.init(bookId = bookId)
        }
        LaunchedEffect(settings.fullscreen.value) {
            screenModel.onEvent(
                ReaderEvent.OnMenuVisibility(
                    show = state.value.showMenu,
                    saveCheckpoint = false
                )
            )
        }

        DisposableEffect(settings.screenOrientation.value) {
            activity.requestedOrientation = settings.screenOrientation.lastValue.code
            onDispose {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        DisposableEffect(
            settings.screenBrightness.value,
            settings.customScreenBrightness.value
        ) {
            when (settings.customScreenBrightness.lastValue) {
                true -> activity.setBrightness(brightness = settings.screenBrightness.lastValue)
                false -> activity.setBrightness(brightness = null)
            }

            onDispose {
                activity.setBrightness(brightness = null)
            }
        }
        DisposableEffect(settings.keepScreenOn.value) {
            when (settings.keepScreenOn.lastValue) {
                true -> activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                false -> activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                screenModel.clearAsync()
                WindowCompat.getInsetsController(
                    activity.window,
                    activity.window.decorView
                ).show(WindowInsetsCompat.Type.systemBars())
            }
        }

        ReaderEffects(
            effects = screenModel.effects,
            book = state.value.book,
            fullscreen = settings.fullscreen.value
        )

        // fork: 把当前生效排版覆盖到整个阅读内容（正文 + 字体/文本/边距控件 + 样例预览）。
        CompositionLocalProvider(
            LocalReaderTypography provides typography,
            LocalBookTranslator provides translator
        ) {
            ReaderContent(
                layout = settings.readerLayout.value,
                pagedDirection = settings.pagedDirection.value,
                pagedAnimation = settings.pagedAnimation.value,
                tapZones = settings.tapZones.value,
                layoutState = state.value.layoutState,
                nodes = state.value.nodes,
                chapters = state.value.chapters,
                book = state.value.book,
                paginating = state.value.paginating,
                scrollTarget = state.value.scrollTarget,
                bottomSheet = state.value.bottomSheet,
                drawer = state.value.drawer,
                currentChapter = currentChapter.value,
                currentChapterString = currentChapterString,
                currentChapterProgress = currentChapterProgress,
                chaptersEmpty = state.value.chapters.isEmpty(),
                nestedScrollConnection = nestedScrollConnection.value,
                fastColorPresetChange = settings.fastColorPresetChange.value,
                perceptionExpander = settings.perceptionExpander.value,
                perceptionExpanderPadding = perceptionExpanderPadding,
                perceptionExpanderThickness = perceptionExpanderThickness,
                horizontalLimiter = settings.horizontalLimiter.value,
                horizontalLimiterHeight = horizontalLimiterHeight,
                horizontalLimiterVerticalOffset = settings.horizontalLimiterVerticalOffset.value,
                horizontalLimiterRuler = settings.horizontalLimiterRuler.value,
                horizontalLimiterRulerThickness = horizontalLimiterRulerThickness,
                horizontalLimiterDimming = settings.horizontalLimiterDimming.value,
                checkpoints = state.value.checkpoints,
                showMenu = state.value.showMenu,
                lockMenu = state.value.lockMenu,
                contentPadding = contentPadding,
                verticalPadding = verticalPadding,
                highlightedReading = settings.highlightedReading.value,
                highlightedReadingThickness = settings.highlightedReadingThickness.value,
                progress = progress,
                progressBar = settings.progressBar.value,
                progressBarPadding = progressBarPadding,
                progressBarAlignment = settings.progressBarAlignment.value,
                progressBarFontSize = progressBarFontSize,
                paragraphTextStyle = paragraphTextStyle,
                boldFontThickness = typography.values.boldFontThickness,
                header1TextStyle = headerTextStyles[0],
                header2TextStyle = headerTextStyles[1],
                header3TextStyle = headerTextStyles[2],
                header4TextStyle = headerTextStyles[3],
                header5TextStyle = headerTextStyles[4],
                header6TextStyle = headerTextStyles[5],
                paragraphHeight = paragraphHeight,
                sidePadding = sidePadding,
                bottomBarPadding = bottomBarPadding,
                backgroundColor = backgroundColor.value,
                fontColor = fontColor.value,
                images = settings.images.value,
                imagesCaptions = settings.imagesCaptions.value,
                imagesCornersRoundness = imagesCornersRoundness,
                imagesAlignment = settings.imagesAlignment.value,
                imagesWidth = imagesWidth,
                imagesColorEffects = imagesColorEffects,
                imagesOriginalSize = settings.imagesOriginalSize.value,
                horizontalAlignment = horizontalAlignment,
                doubleClickTranslation = translator.values.doubleClickTranslation,
                lookupGesture = translator.values.lookupGesture,
                switchColorPreset = settingsModel::onEvent,
                leave = screenModel::onEvent,
                restoreCheckpoint = screenModel::onEvent,
                scroll = screenModel::onEvent,
                menuVisibility = screenModel::onEvent,
                retry = screenModel::onEvent,
                openShareApp = screenModel::onEvent,
                openWebBrowser = screenModel::onEvent,
                openTranslator = screenModel::onEvent,
                openDictionary = screenModel::onEvent,
                scrollToChapter = screenModel::onEvent,
                showSettingsBottomSheet = screenModel::onEvent,
                changeCharset = screenModel::onEvent,
                dismissBottomSheet = screenModel::onEvent,
                showChaptersDrawer = screenModel::onEvent,
                dismissDrawer = screenModel::onEvent,
                updateProgress = screenModel::onEvent,
                paginateForward = screenModel::onEvent,
                paginateBackward = screenModel::onEvent,
                scrollConsumed = screenModel::onEvent,
                navigateBack = screenModel::onEvent,
                navigateToBookInfo = screenModel::onEvent,
                loadNodesBefore = screenModel::loadNodesBefore
            )
        }
    }
}
