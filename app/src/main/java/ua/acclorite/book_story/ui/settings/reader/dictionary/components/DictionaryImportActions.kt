/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：词典导入入口（第二阶段 S4）。
 *
 * MDX 正文用全通配 MIME 的 OpenDocument（很多 ROM 把 `.mdx` 报成 application/octet-stream，
 * 按扩展名过滤会让用户选不到文件）；MDX 文件夹（含 `.mdd` 资源包）与 StarDict 用
 * OpenDocumentTree 选整个目录 —— 资源包是 `.mdx` 的兄弟文件，单文件授权拿不到。
 * 导入过程中三个入口都禁用，[DictionaryImporter] 内部还有 Mutex 兜底。
 *
 * 这里**不**取 `rememberCoroutineScope()`：本组件是 `LazyColumn` 的一个 item，滚出屏幕就会被
 * 回收、作用域随之取消，导入会静默中止。协程由调用方（页面级作用域）通过 [onImport] 启动。
 */

package ua.acclorite.book_story.ui.settings.reader.dictionary.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.helpers.noRippleClickable
import ua.acclorite.book_story.ui.common.helpers.showToast
import ua.acclorite.book_story.ui.theme.Spacing

/** 导入入口的种类：决定用哪个 SAF 合约、调用 [DictionaryImporter] 的哪条路径。 */
enum class ImportKind {
    /** 单个 `.mdx` 文件（纯文本词典，没有资源包）。 */
    MDX_FILE,

    /** 包含 `.mdx` 与同级 `.mdd` / `.N.mdd` 的目录。 */
    MDX_FOLDER,

    /** 包含 `.ifo` / `.idx` / `.dict` 的目录。 */
    STARDICT_FOLDER
}

@Composable
fun DictionaryImportActions(
    importing: Boolean,
    /** `(选中的 Uri, 导入入口种类)`，协程由调用方的页面作用域启动。 */
    onImport: (Uri, ImportKind) -> Unit
) {
    val context = LocalContext.current

    val mdxLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onImport(uri, ImportKind.MDX_FILE)
    }

    val mdxFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onImport(uri, ImportKind.MDX_FOLDER)
    }

    val starDictLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onImport(uri, ImportKind.STARDICT_FOLDER)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        DictionaryImportAction(
            icon = Icons.Outlined.Description,
            title = R.string.dictionary_import_mdx,
            enabled = !importing,
            onClick = {
                try {
                    mdxLauncher.launch(arrayOf("*/*"))
                } catch (e: Exception) {
                    e.printStackTrace()
                    context.getString(R.string.error_no_file_manager_app)
                        .showToast(context, longToast = false)
                }
            }
        )
        DictionaryImportAction(
            icon = Icons.Outlined.Collections,
            title = R.string.dictionary_import_mdx_folder,
            enabled = !importing,
            onClick = {
                try {
                    mdxFolderLauncher.launch(null)
                } catch (e: Exception) {
                    e.printStackTrace()
                    context.getString(R.string.error_no_file_manager_app)
                        .showToast(context, longToast = false)
                }
            }
        )
        DictionaryImportAction(
            icon = Icons.Outlined.FolderOpen,
            title = R.string.dictionary_import_stardict,
            enabled = !importing,
            onClick = {
                try {
                    starDictLauncher.launch(null)
                } catch (e: Exception) {
                    e.printStackTrace()
                    context.getString(R.string.error_no_file_manager_app)
                        .showToast(context, longToast = false)
                }
            }
        )
    }
}

@Composable
private fun DictionaryImportAction(
    icon: ImageVector,
    @StringRes title: Int,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.page, vertical = Spacing.page),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m)
    ) {
        Icon(
            modifier = Modifier.size(24.dp),
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
        StyledText(
            text = stringResource(id = title),
            style = MaterialTheme.typography.labelLarge.copy(
                color = if (enabled) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        )
    }
}
