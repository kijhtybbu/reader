/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoveUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.domain.model.library.Category
import ua.acclorite.book_story.presentation.library.LibraryEvent
import ua.acclorite.book_story.presentation.library.model.LibrarySortOrder
import ua.acclorite.book_story.presentation.library.model.SelectableBook
import ua.acclorite.book_story.ui.common.components.common.AnimatedVisibility
import ua.acclorite.book_story.ui.common.components.common.IconButton
import ua.acclorite.book_story.ui.common.components.common.SearchTextField
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.components.top_bar.TopAppBar
import ua.acclorite.book_story.ui.common.components.top_bar.TopAppBarData
import ua.acclorite.book_story.ui.navigator.NavigatorIconButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryTopBar(
    books: List<SelectableBook>,
    selectedItemsCount: Int,
    hasSelectedItems: Boolean,
    showBookCount: Boolean,
    showCategoryTabs: Boolean,
    showSearch: Boolean,
    searchQuery: String,
    bookCount: Int,
    focusRequester: FocusRequester,
    pagerState: PagerState,
    isLoading: Boolean,
    isRefreshing: Boolean,
    categories: List<Category>,
    showDefaultCategory: Boolean,
    perCategorySort: Boolean,
    sortOrder: LibrarySortOrder,
    searchVisibility: (LibraryEvent.OnSearchVisibility) -> Unit,
    requestFocus: (LibraryEvent.OnRequestFocus) -> Unit,
    searchQueryChange: (LibraryEvent.OnSearchQueryChange) -> Unit,
    search: (LibraryEvent.OnSearch) -> Unit,
    selectBooks: (LibraryEvent.OnSelectBooks) -> Unit,
    clearSelectedBooks: (LibraryEvent.OnClearSelectedBooks) -> Unit,
    showMoveDialog: (LibraryEvent.OnShowMoveDialog) -> Unit,
    showDeleteDialog: (LibraryEvent.OnShowDeleteDialog) -> Unit,
    showFilterBottomSheet: (LibraryEvent.OnShowFilterBottomSheet) -> Unit
) {
    val defaultCategory = stringResource(id = R.string.default_tab)
    val categoriesWithBooks = remember(
        books,
        categories,
        showDefaultCategory,
        defaultCategory
    ) {
        derivedStateOf {
            categories.filterNot { it.id == -1 }.map { category ->
                category to books.filter { it.data.categories.any { it == category.id } }
            }.toMutableList().apply {
                if (showDefaultCategory) {
                    val categoryIds = categories.map { it.id }.toSet()
                    add(
                        0,
                        Category(
                            id = -1,
                            title = defaultCategory
                        ) to books.filter { book ->
                            book.data.categories.none { category -> category in categoryIds }
                        }
                    )
                }
            }.toList()
        }
    }
    val currentCategory = remember(pagerState.currentPage, categoriesWithBooks) {
        derivedStateOf {
            categoriesWithBooks.value[pagerState.currentPage]
        }
    }

    // fork: 书架默认按「最近阅读」排序，但界面上没有任何地方说明这一点，用户看不出
    // 书为什么是这个顺序。规则与 LibraryPager.kt:88-110 保持一致：
    // 开启「按分类排序」时以当前分类自己的设置为准。
    val activeSortOrder = if (perCategorySort) currentCategory.value.first.sortOrder
    else sortOrder

    val animatedItemCountBackgroundColor = animateColorAsState(
        if (hasSelectedItems) MaterialTheme.colorScheme.surfaceContainerHighest
        else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
    )

    TopAppBar(
        scrollBehavior = null,
        isTopBarScrolled = hasSelectedItems,

        shownTopBar = when {
            hasSelectedItems -> 2
            showSearch -> 1
            else -> 0
        },
        topBars = listOf(
            TopAppBarData(
                contentID = 0,
                contentNavigationIcon = {},
                contentTitle = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StyledText(
                                text = if (showCategoryTabs) stringResource(id = R.string.library_screen)
                                else currentCategory.value.first.title
                            )

                            if (showBookCount) {
                                Spacer(modifier = Modifier.width(6.dp))
                                StyledText(
                                    text = if (showCategoryTabs) bookCount.toString()
                                    else currentCategory.value.second.size.toString(),
                                    modifier = Modifier
                                        .background(
                                            MaterialTheme.colorScheme.surfaceContainer,
                                            RoundedCornerShape(14.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = LocalTextStyle.current.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 16.sp
                                    )
                                )
                            }
                        }

                        // fork: 只在「最近阅读」时提示——它是默认排序，用户没主动选过；
                        // 其它排序是用户刚在筛选面板里改的，不需要再占一行。
                        if (activeSortOrder == LibrarySortOrder.LAST_READ) {
                            StyledText(
                                text = stringResource(id = R.string.sort_order_last_read),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                maxLines = 1
                            )
                        }
                    }
                },
                contentActions = {
                    IconButton(
                        icon = Icons.Default.Search,
                        contentDescription = R.string.search_content_desc,
                        disableOnClick = true,
                    ) {
                        searchVisibility(LibraryEvent.OnSearchVisibility(true))
                    }
                    IconButton(
                        icon = Icons.Default.FilterList,
                        contentDescription = R.string.filter_content_desc,
                        disableOnClick = false,
                    ) {
                        showFilterBottomSheet(LibraryEvent.OnShowFilterBottomSheet)
                    }
                    NavigatorIconButton()
                }
            ),

            TopAppBarData(
                contentID = 1,
                contentNavigationIcon = {
                    IconButton(
                        icon = Icons.AutoMirrored.Default.ArrowBack,
                        contentDescription = R.string.exit_search_content_desc,
                        disableOnClick = true
                    ) {
                        searchVisibility(LibraryEvent.OnSearchVisibility(false))
                    }
                },
                contentTitle = {
                    SearchTextField(
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .onGloballyPositioned {
                                requestFocus(LibraryEvent.OnRequestFocus)
                            },
                        initialQuery = searchQuery,
                        onQueryChange = {
                            searchQueryChange(LibraryEvent.OnSearchQueryChange(it))
                        },
                        onSearch = {
                            search(LibraryEvent.OnSearch)
                        }
                    )
                },
                contentActions = {
                    NavigatorIconButton()
                },
            ),

            TopAppBarData(
                contentID = 2,
                contentNavigationIcon = {
                    IconButton(
                        icon = Icons.Default.Clear,
                        contentDescription = R.string.clear_selected_items_content_desc,
                        disableOnClick = true
                    ) {
                        clearSelectedBooks(LibraryEvent.OnClearSelectedBooks)
                    }
                },
                contentTitle = {
                    StyledText(
                        text = stringResource(
                            id = R.string.selected_items_count_query,
                            selectedItemsCount.coerceAtLeast(1)
                        ),
                        maxLines = 1
                    )
                },
                contentActions = {
                    IconButton(
                        icon = Icons.Default.SelectAll,
                        contentDescription = R.string.select_all_books_content_desc,
                        disableOnClick = false,
                    ) {
                        selectBooks(
                            LibraryEvent.OnSelectBooks(
                                books = currentCategory.value.second
                            )
                        )
                    }
                    IconButton(
                        icon = Icons.Outlined.MoveUp,
                        contentDescription = R.string.move_books_content_desc,
                        enabled = !isLoading && !isRefreshing,
                        disableOnClick = false,
                    ) {
                        showMoveDialog(LibraryEvent.OnShowMoveDialog)
                    }
                    IconButton(
                        icon = Icons.Outlined.Delete,
                        contentDescription = R.string.delete_books_content_desc,
                        enabled = !isLoading && !isRefreshing,
                        disableOnClick = false
                    ) {
                        showDeleteDialog(LibraryEvent.OnShowDeleteDialog)
                    }
                }
            ),
        ),
        customContent = {
            AnimatedVisibility(
                visible = showCategoryTabs,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                LibraryTabs(
                    categoriesWithBooks = categoriesWithBooks.value,
                    pagerState = pagerState,
                    itemCountBackgroundColor = animatedItemCountBackgroundColor.value,
                    showBookCount = showBookCount
                )
            }
        }
    )
}