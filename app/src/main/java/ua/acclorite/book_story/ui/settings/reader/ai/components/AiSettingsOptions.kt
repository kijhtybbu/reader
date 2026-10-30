/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：AI 设置页的输入项（第二阶段 S6，设计 §9）。
 *
 * Base URL/模型用「失焦时提交」而不是每敲一个字写一次 DataStore；Key 用显式「保存」按钮，
 * 避免把半截 Key 存进加密存储。Key 输入框默认是密码框，**保存成功后**才清空并显示「已保存」——
 * 失败时保留用户打的 Key。
 *
 * Base URL 指向本机服务（设计 §6.3 的回环地址）时允许不填 Key：这些服务不校验鉴权，
 * 输入框旁边会说明可以留空；公网地址仍然必须有 Key。
 */

package ua.acclorite.book_story.ui.settings.reader.ai.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.acclorite.book_story.R
import ua.acclorite.book_story.data.ai.AiReasoningEffort
import ua.acclorite.book_story.data.ai.AiTargetLanguage
import ua.acclorite.book_story.data.ai.DEFAULT_AI_MODEL
import ua.acclorite.book_story.data.ai.isLoopbackAiBaseUrl
import ua.acclorite.book_story.data.ai.normalizeAiBaseUrl
import ua.acclorite.book_story.presentation.settings.AiSettingsModel
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.components.settings.ChipsWithTitle
import ua.acclorite.book_story.ui.common.components.settings.SwitchWithTitle
import ua.acclorite.book_story.ui.common.helpers.LocalSettings
import ua.acclorite.book_story.ui.common.model.ListItem
import ua.acclorite.book_story.ui.settings.components.SettingsSubcategoryNote
import ua.acclorite.book_story.ui.settings.components.SettingsSubcategoryTitle
import ua.acclorite.book_story.ui.settings.reader.ai.titleRes
import ua.acclorite.book_story.ui.theme.Spacing

private val MODEL_PRESETS = listOf(
    "gpt-4o-mini",
    "gpt-4o",
    "deepseek-chat",
    "gemini-2.0-flash",
    "llama3.1"
)

/**
 * Base URL 与 model 必须成对匹配：只换 model 不换 Base URL 是最常见的 404/400 来源。
 * 这里给常用服务一次性写入两者；自定义服务仍可在下方分别填写。
 */
private data class ProviderPreset(
    val label: String,
    val baseUrl: String,
    val model: String
)

private val PROVIDER_PRESETS = listOf(
    ProviderPreset("OpenAI", "https://api.openai.com/v1/", "gpt-4o-mini"),
    ProviderPreset("DeepSeek", "https://api.deepseek.com/v1/", "deepseek-chat"),
    ProviderPreset(
        "Gemini",
        "https://generativelanguage.googleapis.com/v1beta/openai/",
        "gemini-2.0-flash"
    ),
    ProviderPreset("OpenRouter", "https://openrouter.ai/api/v1/", "openai/gpt-4o-mini"),
    ProviderPreset("OpenCode Go", "https://opencode.ai/zen/go/v1/", "deepseek-v4.1-flash"),
    ProviderPreset("Ollama", "http://127.0.0.1:11434/v1/", "llama3.1")
)

@Composable
fun AiProviderPresetOption() {
    val settings = LocalSettings.current
    val currentBase = settings.aiBaseUrl.value
    val currentModel = settings.aiModel.value

    ChipsWithTitle(
        title = stringResource(id = R.string.ai_provider_presets),
        chips = PROVIDER_PRESETS.map { preset ->
            ListItem(
                item = preset,
                title = preset.label,
                selected = preset.baseUrl == currentBase && preset.model == currentModel
            )
        },
        onClick = { preset ->
            settings.aiBaseUrl.update(preset.baseUrl)
            settings.aiModel.update(preset.model)
        }
    )
}

@Composable
fun AiBaseUrlOption() {
    val settings = LocalSettings.current

    AiTextSetting(
        title = stringResource(id = R.string.ai_base_url),
        description = stringResource(id = R.string.ai_base_url_desc),
        initialValue = settings.aiBaseUrl.value,
        keyboardType = KeyboardType.Uri,
        onCommit = { raw ->
            val normalized = normalizeAiBaseUrl(raw)
            settings.aiBaseUrl.update(normalized)
            normalized
        }
    )
}

@Composable
fun AiModelOption() {
    val settings = LocalSettings.current

    AiTextSetting(
        title = stringResource(id = R.string.ai_model),
        description = stringResource(id = R.string.ai_model_desc),
        initialValue = settings.aiModel.value,
        keyboardType = KeyboardType.Text,
        presets = MODEL_PRESETS,
        onCommit = { raw ->
            val normalized = raw.trim().ifBlank { DEFAULT_AI_MODEL }
            settings.aiModel.update(normalized)
            normalized
        }
    )
}

@Composable
fun AiApiKeyOption(model: AiSettingsModel) {
    val settings = LocalSettings.current
    val hasKey by model.hasKey.collectAsStateWithLifecycle()
    val keyError by model.keyError.collectAsStateWithLifecycle()
    val keySaves by model.keySaves.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    // 本机服务（Ollama 等）不校验 Key，留空是有效配置；公网地址仍然必须有 Key。
    val keyOptional = isLoopbackAiBaseUrl(settings.aiBaseUrl.value)

    // 不 rememberSaveable：明文 Key 不能进 saved instance state（进程重建时会落盘）。
    var value by remember { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }

    // 只在保存/清除成功后才清空输入框：失败时保留用户打的 Key，不用重打一遍。
    LaunchedEffect(keySaves) {
        if (keySaves > 0) value = ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.page, vertical = Spacing.s)
    ) {
        SettingsSubcategoryTitle(
            title = stringResource(id = R.string.ai_api_key),
            padding = 0.dp
        )
        Spacer(modifier = Modifier.height(Spacing.s))

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = { StyledText(text = stringResource(id = R.string.ai_api_key_placeholder)) },
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) {
                            Icons.Outlined.VisibilityOff
                        } else {
                            Icons.Outlined.Visibility
                        },
                        contentDescription = stringResource(
                            id = if (visible) R.string.ai_api_key_hide else R.string.ai_api_key_show
                        )
                    )
                }
            },
            supportingText = {
                val showSaved = hasKey && value.isBlank() && !keyError
                StyledText(
                    text = stringResource(
                        id = when {
                            keyError -> R.string.ai_api_key_save_failed
                            showSaved -> R.string.ai_api_key_saved
                            keyOptional -> R.string.ai_api_key_local_note
                            else -> R.string.ai_api_key_desc
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = when {
                            keyError -> MaterialTheme.colorScheme.error
                            showSaved -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                )
            }
        )

        Row(
            modifier = Modifier.padding(top = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    // 先让 Base URL 的失焦提交落地（同「测试连接」），保存的 Key 才对应刚输入的地址。
                    focusManager.clearFocus()
                    model.saveKey(value)
                },
                // 本机服务上「不填 Key」本身就是一个有效选择（已存 Key 时用「清除 Key」）。
                enabled = value.isNotBlank() || (keyOptional && !hasKey)
            ) {
                StyledText(text = stringResource(id = R.string.ai_api_key_save))
            }

            if (hasKey) {
                TextButton(onClick = { model.clearKey() }) {
                    StyledText(text = stringResource(id = R.string.ai_api_key_clear))
                }
            }
        }
    }
}

@Composable
fun AiTargetLanguageOption() {
    val settings = LocalSettings.current

    ChipsWithTitle(
        title = stringResource(id = R.string.ai_target_language),
        chips = AiTargetLanguage.entries.map { language ->
            ListItem(
                item = language,
                title = stringResource(language.titleRes()),
                selected = language == settings.aiTargetLanguage.value
            )
        },
        onClick = { settings.aiTargetLanguage.update(it) }
    )
}

/**
 * 思考强度（`reasoning_effort`）。
 *
 * 推理模型先输出 `delta.reasoning_content` 再写正文：实测语法分析第一段正文要等 35 秒
 * （8.6k 字思考）。默认 `LOW`；换成不认这个字段的端点时选「默认」= 不发送。
 */
@Composable
fun AiReasoningEffortOption() {
    val settings = LocalSettings.current

    ChipsWithTitle(
        title = stringResource(id = R.string.ai_reasoning_effort),
        chips = AiReasoningEffort.entries.map { effort ->
            ListItem(
                item = effort,
                title = stringResource(effort.titleRes()),
                selected = effort == settings.aiReasoningEffort.value
            )
        },
        onClick = { settings.aiReasoningEffort.update(it) }
    )

    SettingsSubcategoryNote(text = stringResource(id = R.string.ai_reasoning_effort_desc))
}

@Composable
fun AiGrammarOption() {
    val settings = LocalSettings.current

    SwitchWithTitle(
        selected = settings.aiGrammarEnabled.value,
        title = stringResource(id = R.string.ai_grammar_enabled),
        description = stringResource(id = R.string.ai_grammar_enabled_desc),
        onClick = {
            settings.aiGrammarEnabled.update(!settings.aiGrammarEnabled.lastValue)
        }
    )
}

@Composable
private fun AiTextSetting(
    title: String,
    description: String,
    initialValue: String,
    keyboardType: KeyboardType,
    presets: List<String> = emptyList(),
    /** 提交并返回真正生效的值：输入框用它回填，避免显示空串而实际用着默认值。 */
    onCommit: (String) -> String
) {
    val focusManager = LocalFocusManager.current
    var value by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    val latestValue by rememberUpdatedState(value)

    // 返回键可能不会触发失焦；页面销毁时补一次提交，避免刚输入的地址/模型丢失。
    DisposableEffect(Unit) {
        onDispose {
            if (latestValue != initialValue) onCommit(latestValue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.page, vertical = Spacing.s)
    ) {
        SettingsSubcategoryTitle(title = title, padding = 0.dp)
        Spacer(modifier = Modifier.height(Spacing.s))

        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused && value != initialValue) value = onCommit(value)
                },
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            supportingText = {
                StyledText(
                    text = description,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        )

        if (presets.isNotEmpty()) {
            ChipsWithTitle(
                title = stringResource(id = R.string.ai_model_presets),
                chips = presets.map { preset ->
                    ListItem(
                        item = preset,
                        title = preset,
                        selected = preset == value
                    )
                },
                horizontalPadding = 0.dp,
                verticalPadding = 0.dp,
                onClick = { preset ->
                    value = onCommit(preset)
                }
            )
        }
    }
}
