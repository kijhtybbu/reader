/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.settings.reader.dictionary

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ua.acclorite.book_story.R
import ua.acclorite.book_story.data.dictionary.DictionaryCatalog
import ua.acclorite.book_story.data.dictionary.DictionaryImporter
import ua.acclorite.book_story.data.dictionary.ImportState
import ua.acclorite.book_story.ui.common.components.common.LazyColumnWithScrollbar
import ua.acclorite.book_story.ui.common.helpers.LocalSettings
import ua.acclorite.book_story.ui.settings.components.SettingsSubcategoryNote
import ua.acclorite.book_story.ui.settings.components.SettingsSubcategoryTitle
import ua.acclorite.book_story.ui.settings.reader.dictionary.components.BuiltInDictionaryItem
import ua.acclorite.book_story.ui.settings.reader.dictionary.components.DictionaryImportActions
import ua.acclorite.book_story.ui.settings.reader.dictionary.components.DictionaryImportStatus
import ua.acclorite.book_story.ui.settings.reader.dictionary.components.ImportedDictionaryItem
import ua.acclorite.book_story.ui.theme.Spacing

@Composable
fun DictionaryManagerLayout(
    listState: LazyListState,
    paddingValues: PaddingValues
) {
    // fork: 用 applicationContext —— 导入/启用/删除都挂在页面级作用域上，捕获 Activity 会让
    // 一个几百 MB 的导入把已销毁的 Activity 一直吊住（旋转屏幕后尤其明显）。
    val context = LocalContext.current.applicationContext
    val settings = LocalSettings.current
    // 页面级作用域：只有真正离开设置页才会取消导入（见 DictionaryImportActions 的注释）。
    val scope = rememberCoroutineScope()

    val dictionaries by DictionaryCatalog.dictionaries.collectAsStateWithLifecycle()
    val importState by DictionaryImporter.state.collectAsStateWithLifecycle()
    val activeId = settings.activeDictionaryId.value

    // 上一次的「已导入 / 失败」提示不该在重新进入设置页时还挂着；正在进行的导入不受影响。
    LaunchedEffect(Unit) { DictionaryImporter.reset() }

    LazyColumnWithScrollbar(
        Modifier
            .fillMaxSize()
            .padding(top = paddingValues.calculateTopPadding()),
        state = listState
    ) {
        item {
            SettingsSubcategoryTitle(
                title = stringResource(id = R.string.dictionary_installed_title),
                modifier = Modifier.padding(top = Spacing.page, bottom = Spacing.s)
            )
        }

        items(dictionaries, key = { it.id }) { dictionary ->
            if (dictionary.builtIn) {
                BuiltInDictionaryItem(
                    dictionary = dictionary,
                    active = dictionary.id == activeId,
                    onSelect = { settings.activeDictionaryId.update(dictionary.id) }
                )
            } else {
                ImportedDictionaryItem(
                    dictionary = dictionary,
                    active = dictionary.id == activeId,
                    onSelect = { settings.activeDictionaryId.update(dictionary.id) },
                    onEnabledChange = { enabled ->
                        // 禁用当前词典后它就不再参与取词，优先词典回落内置，避免留一个「选着但不用」的状态。
                        if (!enabled && settings.activeDictionaryId.lastValue == dictionary.id) {
                            settings.activeDictionaryId.update(DictionaryCatalog.BUILT_IN_ID)
                        }
                        scope.launch {
                            DictionaryCatalog.setEnabled(context, dictionary.id, enabled)
                        }
                    },
                    onDelete = {
                        // 与用户字体同一顺序：先回落优先词典，再删文件/索引，界面不会闪一帧失效的选中项。
                        if (settings.activeDictionaryId.lastValue == dictionary.id) {
                            settings.activeDictionaryId.update(DictionaryCatalog.BUILT_IN_ID)
                        }
                        scope.launch { DictionaryCatalog.delete(context, dictionary.id) }
                    }
                )
            }
            HorizontalDivider()
        }

        item {
            DictionaryImportActions(
                importing = importState is ImportState.Copying || importState is ImportState.Validating,
                // 导入必须挂在页面作用域上：LazyColumn 会回收滚出屏幕的 item，item 内的
                // rememberCoroutineScope 随之取消，滚动列表就会静默中止导入。
                onImport = { uri, isMdx ->
                    scope.launch {
                        if (isMdx) DictionaryImporter.importMdx(context, uri)
                        else DictionaryImporter.importStarDict(context, uri)
                    }
                }
            )
        }

        item {
            DictionaryImportStatus(state = importState)
        }

        item {
            // 「单选」到底是唯一词典还是优先词典，光看单选控件看不出来 —— 写在这里。
            SettingsSubcategoryNote(text = stringResource(id = R.string.dictionary_priority_note))
        }

        item {
            SettingsSubcategoryNote(text = stringResource(id = R.string.dictionary_import_note))
        }
    }
}
