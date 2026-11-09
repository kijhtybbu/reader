/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：收藏页（单词 / 句子两个标签）。
 *
 * 纯展示层：数据、请求、取消都由 [FavoritesModel] 管；这里只负责布局与交互回调。
 * 单词行点击把**这一行的窗口矩形**交给阅读页同一套 `DictionaryLookupModel`，
 * 所以弹出的就是平时那张贴词卡片（不是另做一套详情页）。
 *
 * 句子行不截断：列表里就是完整一句，太长自动换行；点击打开详情对话框，
 * 译文与语法分析一起显示。
 */

package ua.acclorite.book_story.ui.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import ua.acclorite.book_story.R
import ua.acclorite.book_story.domain.model.favorite.FavoriteSentence
import ua.acclorite.book_story.domain.model.favorite.FavoriteTab
import ua.acclorite.book_story.domain.model.favorite.FavoriteWord
import ua.acclorite.book_story.presentation.favorites.FavoriteSentenceDialogState
import ua.acclorite.book_story.presentation.reader.LookupState
import ua.acclorite.book_story.ui.common.components.common.IconButton
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.navigator.NavigatorBackIconButton
import ua.acclorite.book_story.ui.reader.ReaderLookupPopup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesContent(
    tab: FavoriteTab,
    words: List<FavoriteWord>,
    sentences: List<FavoriteSentence>,
    onSelectTab: (FavoriteTab) -> Unit,
    onBack: () -> Unit,
    onWordClick: (FavoriteWord, Rect?) -> Unit,
    onSpeakWord: (String) -> Unit,
    onSentenceClick: (FavoriteSentence) -> Unit,
    lookupState: LookupState,
    onLookupSelect: (Int) -> Unit,
    onLookupLink: (String) -> Unit,
    onLookupRetry: () -> Unit,
    onLookupDismiss: () -> Unit,
    onImportDictionary: () -> Unit,
    onExternalLookup: (String) -> Unit,
    onTtsUnavailable: () -> Unit,
    onAudioFile: suspend (dictId: String, path: String) -> File?,
    isLookupFavorite: Boolean,
    onToggleLookupFavorite: () -> Unit,
    sentenceDialog: FavoriteSentenceDialogState?,
    onRetryTranslation: () -> Unit,
    onCloseSentence: () -> Unit,
    onRemoveSentence: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    StyledText(text = stringResource(id = R.string.favorites_screen))
                },
                navigationIcon = {
                    NavigatorBackIconButton(navigateBack = onBack)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            FavoriteTabs(tab = tab, onSelectTab = onSelectTab)

            when (tab) {
                FavoriteTab.WORDS -> FavoriteWordList(
                    words = words,
                    onWordClick = onWordClick,
                    onSpeakWord = onSpeakWord
                )

                FavoriteTab.SENTENCES -> FavoriteSentenceList(
                    sentences = sentences,
                    onSentenceClick = onSentenceClick
                )
            }
        }
    }

    if (lookupState !is LookupState.Idle) {
        ReaderLookupPopup(
            state = lookupState,
            onSelect = onLookupSelect,
            onLookupLink = onLookupLink,
            onRetry = onLookupRetry,
            onDismiss = onLookupDismiss,
            onImportDictionary = onImportDictionary,
            onExternalLookup = onExternalLookup,
            onTtsUnavailable = onTtsUnavailable,
            onAudioFile = onAudioFile,
            isFavorite = isLookupFavorite,
            onToggleFavorite = onToggleLookupFavorite
        )
    }

    if (sentenceDialog != null) {
        FavoriteSentenceDialog(
            state = sentenceDialog,
            onRetry = onRetryTranslation,
            onDismiss = onCloseSentence,
            onRemove = onRemoveSentence
        )
    }
}

@Composable
private fun FavoriteTabs(tab: FavoriteTab, onSelectTab: (FavoriteTab) -> Unit) {
    val selectedIndex = if (tab == FavoriteTab.WORDS) 0 else 1

    PrimaryTabRow(
        selectedTabIndex = selectedIndex,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        divider = {},
        indicator = {
            TabRowDefaults.PrimaryIndicator(
                Modifier.tabIndicatorOffset(selectedIndex, matchContentSize = true)
            )
        }
    ) {
        Tab(
            selected = tab == FavoriteTab.WORDS,
            onClick = { onSelectTab(FavoriteTab.WORDS) },
            text = {
                StyledText(
                    text = stringResource(id = R.string.favorites_tab_words),
                    maxLines = 1
                )
            }
        )
        Tab(
            selected = tab == FavoriteTab.SENTENCES,
            onClick = { onSelectTab(FavoriteTab.SENTENCES) },
            text = {
                StyledText(
                    text = stringResource(id = R.string.favorites_tab_sentences),
                    maxLines = 1
                )
            }
        )
    }
}

@Composable
private fun FavoriteWordList(
    words: List<FavoriteWord>,
    onWordClick: (FavoriteWord, Rect?) -> Unit,
    onSpeakWord: (String) -> Unit
) {
    if (words.isEmpty()) {
        FavoriteEmptyPlaceholder(
            text = stringResource(id = R.string.favorites_empty_words)
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(words, key = { it.id }) { word ->
            FavoriteWordRow(
                word = word,
                onClick = onWordClick,
                onSpeak = onSpeakWord
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun FavoriteWordRow(
    word: FavoriteWord,
    onClick: (FavoriteWord, Rect?) -> Unit,
    onSpeak: (String) -> Unit
) {
    // 点击时把这一行的窗口矩形交给取词卡片：卡片贴在行下方展开，而不是跑到屏幕中央。
    // 测量完成前是 null（而不是 Rect.Zero）——传 Zero 会被当成真实锚点，卡片贴到左上角。
    var bounds by remember(word.id) { mutableStateOf<Rect?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                bounds = coordinates.boundsInWindow()
            }
            .clickable { onClick(word, bounds?.takeIf { it.width > 0f && it.height > 0f }) }
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                StyledText(
                    text = word.word,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                if (!word.phonetic.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    StyledText(
                        text = word.phonetic,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            StyledText(
                text = word.definition
                    ?: stringResource(id = R.string.favorites_word_no_definition),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
        IconButton(
            modifier = Modifier.size(22.dp),
            icon = Icons.AutoMirrored.Outlined.VolumeUp,
            contentDescription = R.string.lookup_popup_pronounce,
            disableOnClick = false,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            onSpeak(word.word)
        }
    }
}

@Composable
private fun FavoriteSentenceList(
    sentences: List<FavoriteSentence>,
    onSentenceClick: (FavoriteSentence) -> Unit
) {
    if (sentences.isEmpty()) {
        FavoriteEmptyPlaceholder(
            text = stringResource(id = R.string.favorites_empty_sentences)
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(sentences, key = { it.id }) { sentence ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSentenceClick(sentence) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // 不设 maxLines：句子再长也完整换行显示，不拿省略号截掉后半句。
                StyledText(
                    text = sentence.text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun FavoriteEmptyPlaceholder(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.StarBorder,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StyledText(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}
