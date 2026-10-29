/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：导入字体文件 + 用户字体列表。
 *
 * 用全通配 MIME 的 OpenDocument 而不是字体 MIME 过滤：很多 ROM 的文件选择器把字体报成
 * application/octet-stream，按 MIME 过滤会让用户根本选不到自己的文件。格式校验放在
 * 读取之后（扩展名 + 魔数），错误信息具体到「.ttc 需要先导出单个 face」这种可操作的程度。
 *
 * 每个字体给出三行预览（中文 / 拉丁+数字 / 全角标点）：这是唯一能在选之前看出
 * 「这个字体到底有没有中文字形」的方式 —— 徽标只说明检测结果，预览说明实际观感。
 */

package ua.acclorite.book_story.ui.settings.reader.font.components

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ua.acclorite.book_story.R
import ua.acclorite.book_story.data.storage.font.FontRejection
import ua.acclorite.book_story.ui.common.components.common.IconButton
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.helpers.LocalSettings
import ua.acclorite.book_story.ui.common.helpers.noRippleClickable
import ua.acclorite.book_story.ui.common.helpers.showToast
import ua.acclorite.book_story.ui.reader.data.FontImportResult
import ua.acclorite.book_story.ui.reader.data.ReaderData
import ua.acclorite.book_story.ui.reader.data.UserFontRegistry
import ua.acclorite.book_story.ui.reader.model.UserFontEntry
import ua.acclorite.book_story.ui.reader.model.toFontWithName
import ua.acclorite.book_story.ui.reader.rememberFontFamily
import ua.acclorite.book_story.ui.settings.components.SettingsSubcategoryNote
import ua.acclorite.book_story.ui.settings.components.SettingsSubcategoryTitle
import ua.acclorite.book_story.ui.theme.Spacing

@Composable
fun UserFontOption() {
    val settings = LocalSettings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fonts = UserFontRegistry.fonts
    val selectedId = settings.fontFamily.value.id

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        scope.launch {
            when (val result = UserFontRegistry.importFont(context, uri)) {
                is FontImportResult.Imported -> {
                    context.getString(R.string.font_import_success, result.entry.name)
                        .showToast(context, longToast = true)
                }

                is FontImportResult.Rejected -> {
                    context.getString(result.reason.messageRes())
                        .showToast(context, longToast = true)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        SettingsSubcategoryTitle(
            title = stringResource(id = R.string.user_fonts_option),
            modifier = Modifier.padding(top = Spacing.s)
        )

        fonts.forEach { entry ->
            UserFontItem(
                entry = entry,
                selected = entry.id == selectedId,
                onSelect = {
                    settings.fontFamily.update(entry.toFontWithName())
                },
                onDelete = {
                    // fork: 先回落默认字体，再删文件/索引。反过来会让 SettingsManager 在下一次
                    // emit 时查不到当前字体，虽然也会回落，但会先闪一帧失效的 family。
                    // 回调里不能用 @Composable 的 .value，用 lastValue（当前 StateFlow 值）。
                    if (settings.fontFamily.lastValue.id == entry.id) {
                        settings.fontFamily.update(ReaderData.fonts[0])
                    }
                    scope.launch { UserFontRegistry.deleteFont(context, entry) }
                }
            )
            HorizontalDivider()
        }

        UserFontImportAction(
            enabled = fonts.size < UserFontRegistry.MAX_FONTS,
            onImport = {
                try {
                    importLauncher.launch(arrayOf("*/*"))
                } catch (e: Exception) {
                    e.printStackTrace()
                    context.getString(R.string.error_no_file_manager_app)
                        .showToast(context, longToast = false)
                }
            }
        )

        if (fonts.size >= UserFontRegistry.MAX_FONTS) {
            SettingsSubcategoryNote(text = stringResource(id = R.string.user_fonts_limit_note))
        }
    }
}

@Composable
private fun UserFontItem(
    entry: UserFontEntry,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val previewFamily = rememberFontFamily(
        font = entry.toFontWithName(),
        fontWeight = 400,
        italic = false
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable(onClick = onSelect)
            .padding(horizontal = Spacing.page, vertical = Spacing.s)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                StyledText(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 1
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    StyledText(
                        text = Formatter.formatShortFileSize(context, entry.size),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    StyledText(
                        text = stringResource(
                            id = if (entry.hasCjk) R.string.user_font_badge_cjk
                            else R.string.user_font_badge_no_cjk
                        ),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (entry.hasCjk) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    )
                    if (entry.variable) {
                        StyledText(
                            text = stringResource(id = R.string.user_font_badge_variable),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }

            IconButton(
                modifier = Modifier.size(24.dp),
                icon = Icons.Outlined.Delete,
                contentDescription = R.string.user_font_delete,
                disableOnClick = true,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onDelete
            )
        }

        Spacer(modifier = Modifier.height(Spacing.s))

        UserFontPreviewLine(
            text = stringResource(id = R.string.user_font_preview_cjk),
            fontFamily = previewFamily
        )
        UserFontPreviewLine(
            text = stringResource(id = R.string.user_font_preview_latin),
            fontFamily = previewFamily
        )
        UserFontPreviewLine(
            text = stringResource(id = R.string.user_font_preview_punctuation),
            fontFamily = previewFamily
        )
    }
}

@Composable
private fun UserFontPreviewLine(
    text: String,
    fontFamily: FontFamily
) {
    StyledText(
        text = text,
        modifier = Modifier.padding(vertical = Spacing.xs),
        style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = fontFamily,
            lineBreak = LineBreak.Paragraph
        )
    )
}

@Composable
private fun UserFontImportAction(
    enabled: Boolean,
    onImport: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable(enabled = enabled, onClick = onImport)
            .padding(horizontal = Spacing.page, vertical = Spacing.page),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m)
    ) {
        Icon(
            modifier = Modifier.size(24.dp),
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
        StyledText(
            text = stringResource(id = R.string.user_fonts_import),
            style = MaterialTheme.typography.labelLarge.copy(
                color = if (enabled) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        )
    }
}

@StringRes
private fun FontRejection.messageRes(): Int = when (this) {
    FontRejection.EMPTY_FILE -> R.string.font_import_error_empty
    FontRejection.TOO_LARGE -> R.string.font_import_error_too_large
    FontRejection.UNSUPPORTED_EXTENSION -> R.string.font_import_error_extension
    FontRejection.NOT_A_FONT -> R.string.font_import_error_not_font
    FontRejection.FONT_COLLECTION -> R.string.font_import_error_collection
    FontRejection.WOFF_REQUIRES_CONVERSION -> R.string.font_import_error_woff
    FontRejection.LIMIT_REACHED -> R.string.font_import_error_limit
    FontRejection.UNREADABLE -> R.string.font_import_error_unreadable
}
