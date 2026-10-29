/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.domain.model.book.Book
import ua.acclorite.book_story.domain.model.book.Chapter
import ua.acclorite.book_story.presentation.reader.ReaderEvent
import ua.acclorite.book_story.presentation.settings.SettingsEvent
import ua.acclorite.book_story.ui.common.components.common.IconButton
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.helpers.LocalActivity
import ua.acclorite.book_story.ui.common.helpers.noRippleClickable
import ua.acclorite.book_story.ui.theme.LocalReaderPalette

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReaderTopBar(
    book: Book,
    currentChapter: Chapter?,
    currentChapterString: String?,
    currentChapterProgress: Float,
    chaptersEmpty: Boolean,
    fastColorPresetChange: Boolean,
    menuShowing: Boolean,
    lockMenu: Boolean,
    leave: (ReaderEvent.OnLeave) -> Unit,
    switchColorPreset: (SettingsEvent.OnSwitchColorPreset) -> Unit,
    showSettingsBottomSheet: (ReaderEvent.OnShowSettingsBottomSheet) -> Unit,
    showChaptersDrawer: (ReaderEvent.OnShowChaptersDrawer) -> Unit,
    navigateToBookInfo: (ReaderEvent.OnNavigateToBookInfo) -> Unit,
    navigateBack: (ReaderEvent.OnNavigateBack) -> Unit
) {
    val activity = LocalActivity.current
    val animatedChapterProgress = animateFloatAsState(
        targetValue = currentChapterProgress
    )

    Column(
        Modifier
            .fillMaxWidth()
            .background(LocalReaderPalette.current.bar)
            .readerColorPresetChange(
                colorPresetChangeEnabled = fastColorPresetChange,
                menuShowing = menuShowing,
                switchColorPreset = switchColorPreset
            )
    ) {
        TopAppBar(
            navigationIcon = {
                IconButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = R.string.go_back_content_desc,
                    disableOnClick = true
                ) {
                    leave(
                        ReaderEvent.OnLeave(
                            navigate = {
                                navigateBack(ReaderEvent.OnNavigateBack)
                            }
                        )
                    )
                }
            },
            title = {
                StyledText(
                    text = book.title,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .noRippleClickable(
                            enabled = !lockMenu,
                            onClick = {
                                leave(
                                    ReaderEvent.OnLeave(
                                        navigate = {
                                            navigateToBookInfo(
                                                ReaderEvent.OnNavigateToBookInfo(
                                                    changePath = false
                                                )
                                            )
                                        }
                                    )
                                )
                            }
                        ),
                    style = LocalTextStyle.current.copy(
                        fontSize = 20.sp,
                        lineHeight = 20.sp,
                        color = LocalReaderPalette.current.onBar
                    ),
                    maxLines = 1
                )
            },
            subtitle = {
                StyledText(
                    text = currentChapterString
                        ?: activity.getString(R.string.no_chapters),
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = LocalReaderPalette.current.onBarMuted
                    ),
                    maxLines = 1
                )
            },
            actions = {
                if (!chaptersEmpty) {
                    IconButton(
                        icon = Icons.Rounded.Menu,
                        contentDescription = R.string.chapters_content_desc,
                        disableOnClick = false,
                        enabled = !lockMenu
                    ) {
                        showChaptersDrawer(ReaderEvent.OnShowChaptersDrawer)
                    }
                }

                IconButton(
                    icon = Icons.Default.Settings,
                    contentDescription = R.string.open_reader_settings_content_desc,
                    disableOnClick = false,
                    enabled = !lockMenu
                ) {
                    showSettingsBottomSheet(ReaderEvent.OnShowSettingsBottomSheet)
                }
            },
            // fork: 必须显式给内容色。默认取的是 App 主题的 onSurfaceVariant，
            // 而栏底已换成预设派生的 palette.bar —— 混搭时图标对比度会掉到 1.3:1。
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                navigationIconContentColor = LocalReaderPalette.current.onBar,
                actionIconContentColor = LocalReaderPalette.current.onBar,
                titleContentColor = LocalReaderPalette.current.onBar
            )
        )

        if (currentChapter != null) {
            LinearProgressIndicator(
                progress = { animatedChapterProgress.value },
                modifier = Modifier.fillMaxWidth(),
                color = LocalReaderPalette.current.accent,
                trackColor = LocalReaderPalette.current.onBarMuted.copy(0.25f)
            )
        }
    }
}