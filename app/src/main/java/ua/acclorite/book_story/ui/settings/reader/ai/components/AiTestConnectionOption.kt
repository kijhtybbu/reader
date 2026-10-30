/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：「测试连接」按钮与错误文案（第二阶段 S6，设计 §9）。
 *
 * 数据层只给 [AiError] + HTTP 状态码，这里映射成字符串资源 —— 换语言不改网络逻辑，
 * 测试也能只断言枚举/状态码。Key 与响应体不会出现在任何错误文案里。
 */

package ua.acclorite.book_story.ui.settings.reader.ai.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.acclorite.book_story.R
import ua.acclorite.book_story.data.ai.AiError
import ua.acclorite.book_story.presentation.settings.AiSettingsModel
import ua.acclorite.book_story.presentation.settings.AiTestState
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.theme.Spacing

@Composable
fun AiTestConnectionOption(model: AiSettingsModel) {
    val state by model.testState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.page, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = {
                // 先把失焦提交（Base URL/模型）触发掉，测试才会用到刚输入的值。
                focusManager.clearFocus()
                model.testConnection()
            },
            enabled = state != AiTestState.Testing
        ) {
            if (state == AiTestState.Testing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                StyledText(text = stringResource(id = R.string.ai_test_connection))
            }
        }

        when (val current = state) {
            AiTestState.Idle -> Unit
            AiTestState.Testing -> StyledText(
                text = stringResource(id = R.string.ai_test_testing),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            AiTestState.Success -> StyledText(
                text = stringResource(id = R.string.ai_test_success),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.primary
                )
            )

            is AiTestState.Error -> StyledText(
                text = stringResource(
                    id = R.string.ai_test_failed,
                    current.message()
                ),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.error
                )
            )
        }
    }
}

@Composable
private fun AiTestState.Error.message(): String {
    val base = stringResource(id = error.messageRes())
    return statusCode?.let {
        stringResource(id = R.string.ai_error_with_status, base, it)
    } ?: base
}

@StringRes
internal fun AiError.messageRes(): Int = when (this) {
    AiError.NO_KEY -> R.string.ai_error_no_key
    AiError.INVALID_KEY -> R.string.ai_error_invalid_key
    AiError.RATE_LIMIT -> R.string.ai_error_rate_limit
    AiError.NOT_FOUND -> R.string.ai_error_not_found
    AiError.TIMEOUT -> R.string.ai_error_timeout
    AiError.NETWORK -> R.string.ai_error_network
    AiError.SERVER -> R.string.ai_error_server
    AiError.CANCELLED -> R.string.ai_error_cancelled
    AiError.UNKNOWN -> R.string.ai_error_unknown
}
