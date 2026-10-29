/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import ua.acclorite.book_story.presentation.reader.ReaderEvent
import ua.acclorite.book_story.ui.common.components.common.LazyColumnWithScrollbar
import ua.acclorite.book_story.ui.common.components.modal_bottom_sheet.ModalBottomSheet
import ua.acclorite.book_story.ui.settings.appearance.colors.ColorsSubcategory
import ua.acclorite.book_story.ui.settings.reader.headers.HeadersSubcategory
import ua.acclorite.book_story.ui.settings.reader.font.FontSubcategory
import ua.acclorite.book_story.ui.settings.reader.images.ImagesSubcategory
import ua.acclorite.book_story.ui.settings.reader.misc.MiscSubcategory
import ua.acclorite.book_story.ui.settings.reader.padding.PaddingSubcategory
import ua.acclorite.book_story.ui.settings.reader.progress.ProgressSubcategory
import ua.acclorite.book_story.ui.settings.reader.reading_mode.ReadingModeSubcategory
import ua.acclorite.book_story.ui.settings.reader.reading_speed.ReadingSpeedSubcategory
import ua.acclorite.book_story.ui.settings.reader.system.SystemSubcategory
import ua.acclorite.book_story.ui.settings.reader.translator.TranslatorSubcategory
import ua.acclorite.book_story.ui.theme.Motion

private var initialPage = 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsBottomSheet(
    menuVisibility: (ReaderEvent.OnMenuVisibility) -> Unit,
    dismissBottomSheet: (ReaderEvent.OnDismissBottomSheet) -> Unit
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage) { 3 }
    DisposableEffect(Unit) { onDispose { initialPage = pagerState.currentPage } }

    val animatedScrimColor by animateColorAsState(
        // fork: 排版页也透明，让面板上方的正文不被蒙暗（scrim 画在面板之外，
        // 它不会盖住面板内的样例预览，但会压暗上方正文）；颜色页本来就是透明的
        targetValue = if (pagerState.currentPage == 0 || pagerState.currentPage == 2) {
            Color.Transparent
        } else {
            BottomSheetDefaults.ScrimColor
        },
        animationSpec = Motion.long()
    )
    val animatedHeight by animateFloatAsState(
        // fork: 排版页压到 0.62，多露出一点上方正文（真正的预览在面板顶部固定）
        targetValue = if (pagerState.currentPage == 2) 0.6f else 0.62f,
        animationSpec = Motion.long()
    )

    LaunchedEffect(pagerState.currentPage) {
        menuVisibility(
            ReaderEvent.OnMenuVisibility(
                show = pagerState.currentPage != 2,
                saveCheckpoint = false
            )
        )
    }

    ModalBottomSheet(
        hasFixedHeight = true,
        scrimColor = animatedScrimColor,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(animatedHeight),
        dragHandle = {},
        onDismissRequest = {
            dismissBottomSheet(ReaderEvent.OnDismissBottomSheet)
        },
        sheetGesturesEnabled = false
    ) {
        ReaderSettingsBottomSheetTabRow(
            currentPage = pagerState.currentPage,
            scrollToPage = {
                scope.launch {
                    pagerState.animateScrollToPage(it)
                }
            }
        )

        HorizontalPager(state = pagerState) { page ->
            when (page) {
                0 -> {
                    // 预览固定在顶部：否则滚到字距/缩进/对齐时预览已经滚出屏幕，
                    // 调这些项就完全没有反馈
                    Column(Modifier.fillMaxSize()) {
                        ReaderTypographyPreview()
                        LazyColumnWithScrollbar(Modifier.fillMaxSize()) {
                        // 常驻高频区：字号/行距/段距/字距/缩进/对齐/字体
                        ReaderTypographyQuickSettings()
                        // 字体子页只保留「字重/粗体字重/斜体」，避免同一屏出现重复控件；
                        // TextSubcategory 的四项已全部上移，不再重复渲染
                        FontSubcategory(
                            titleColor = { MaterialTheme.colorScheme.onSurface },
                            includeFamily = false,
                            includeSize = false,
                            includeLetterSpacing = false
                        )
                        ImagesSubcategory(
                            titleColor = { MaterialTheme.colorScheme.onSurface }
                        )
                        HeadersSubcategory(
                            titleColor = { MaterialTheme.colorScheme.onSurface },
                            backgroundColor = { MaterialTheme.colorScheme.surfaceContainer }
                        )
                        ProgressSubcategory(
                            titleColor = { MaterialTheme.colorScheme.onSurface }
                        )
                        TranslatorSubcategory(
                            titleColor = { MaterialTheme.colorScheme.onSurface },
                            showDivider = false
                        )
                        }
                    }
                }

                1 -> {
                    LazyColumnWithScrollbar(Modifier.fillMaxSize()) {
                        ReadingModeSubcategory(
                            titleColor = { MaterialTheme.colorScheme.onSurface }
                        )
                        PaddingSubcategory(
                            titleColor = { MaterialTheme.colorScheme.onSurface }
                        )
                        SystemSubcategory(
                            titleColor = { MaterialTheme.colorScheme.onSurface }
                        )
                        ReadingSpeedSubcategory(
                            titleColor = { MaterialTheme.colorScheme.onSurface }
                        )
                        MiscSubcategory(
                            titleColor = { MaterialTheme.colorScheme.onSurface },
                            showDivider = false
                        )
                    }
                }

                2 -> {
                    LazyColumnWithScrollbar(Modifier.fillMaxSize()) {
                        ColorsSubcategory(
                            showTitle = false,
                            showDivider = false,
                            backgroundColor = { MaterialTheme.colorScheme.surfaceContainer }
                        )
                    }
                }
            }
        }
    }
}