/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：系统没有英语 TTS 语音包时的可操作提示。
 */

package ua.acclorite.book_story.ui.reader

import android.content.Context
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ua.acclorite.book_story.R
import ua.acclorite.book_story.ui.common.components.common.StyledText

/** 优先打开当前 TTS 引擎的语音包安装页；没有该 Activity 时退到系统 TTS 设置。 */
internal fun openTtsVoiceInstaller(context: Context) {
    val candidates = listOf(
        Intent("android.speech.tts.engine.INSTALL_TTS_DATA"),
        Intent("com.android.settings.TTS_SETTINGS"),
        Intent(android.provider.Settings.ACTION_SETTINGS)
    )
    candidates.firstOrNull { context.packageManager.resolveActivity(it, 0) != null }
        ?.let { intent ->
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
}

@Composable
fun TtsUnavailableDialog(
    onDismiss: () -> Unit,
    onOpenInstaller: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { StyledText(text = stringResource(id = R.string.lookup_popup_tts_unavailable_title)) },
        text = { StyledText(text = stringResource(id = R.string.lookup_popup_tts_unavailable)) },
        confirmButton = {
            TextButton(onClick = onOpenInstaller) {
                StyledText(text = stringResource(id = R.string.lookup_popup_tts_install_data))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                StyledText(text = stringResource(id = R.string.cancel))
            }
        }
    )
}
