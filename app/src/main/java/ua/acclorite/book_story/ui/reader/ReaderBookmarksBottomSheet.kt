/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：书签列表。
 *
 * 面板自身保持 App 主题（不透明 ModalBottomSheet），与排版面板一致；
 * 不要用 LocalReaderPalette —— 那会让面板随阅读背景变色，反而看不清。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.domain.model.bookmark.Bookmark
import ua.acclorite.book_story.ui.common.components.common.IconButton
import ua.acclorite.book_story.ui.common.components.common.LazyColumnWithScrollbar
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderBookmarksBottomSheet(
    bookmarks: List<Bookmark>,
    onJump: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.l)
        ) {
            StyledText(
                text = stringResource(id = R.string.bookmarks_option),
                modifier = Modifier.padding(horizontal = Spacing.page, vertical = Spacing.s),
                style = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                )
            )

            if (bookmarks.isEmpty()) {
                StyledText(
                    text = stringResource(id = R.string.bookmarks_empty),
                    modifier = Modifier.padding(horizontal = Spacing.page, vertical = Spacing.xl),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            } else {
                LazyColumnWithScrollbar(Modifier.fillMaxWidth()) {
                    items(bookmarks.size) { index ->
                        val bookmark = bookmarks[index]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onJump(bookmark) }
                                .padding(horizontal = Spacing.page, vertical = Spacing.m),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.m)
                        ) {
                            Column(Modifier.weight(1f)) {
                                StyledText(
                                    text = bookmark.chapterTitle,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (bookmark.snippet.isNotBlank()) {
                                    StyledText(
                                        text = bookmark.snippet,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(
                                icon = Icons.Outlined.Delete,
                                contentDescription = R.string.delete_bookmark_content_desc,
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                disableOnClick = false
                            ) {
                                onDelete(bookmark)
                            }
                        }
                    }
                }
            }
        }
    }
}
