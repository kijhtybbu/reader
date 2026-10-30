/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：推理模型的「思考强度」（OpenAI 兼容的 `reasoning_effort` 请求字段）。
 *
 * 为什么需要它：推理模型（DeepSeek V4 / o 系列 / gpt-5 等）在写正文之前会先输出
 * `delta.reasoning_content`。实测（OpenCode Zen + deepseek-v4.1-flash）一次语法分析：
 * 第一段正文要等 **35 秒**，期间推了 **8608 个字符**的思考；翻译也要 5.8 秒 / 1509 字。
 * 思考强度调到 `low` 能明显缩短这段等待（代价是分析略浅）。
 *
 * [OFF] 发的是 `"minimal"` —— OpenAI 兼容里「尽量不思考」的取值（gpt-5 系的小值档）；
 * 有的兼容端点（Gemini 兼容层、部分代理、Ollama）既不认这个字段也不认这个值，会直接 400，
 * 那种情况下选 [DEFAULT]（不发送该字段）即可。
 */

package ua.acclorite.book_story.data.ai

enum class AiReasoningEffort(
    /** 发给 `reasoning_effort` 的值；null = 不发送该字段。 */
    val apiValue: String?
) {
    /** 不发送该字段，完全交给模型自身的默认值（最保守，兼容性最好）。 */
    DEFAULT(null),

    /**
     * 关闭思考：发 `"none"`。
     *
     * 实测（OpenCode Zen + deepseek-v4.1-flash）：`"none"` 真的把思考关掉了 ——
     * `reasoning_content` 0 字符、第一段正文 0.8–3.4 秒（默认档 35–42 秒）。
     * 注意 `"minimal"` 在该网关被**静默忽略**（思考量回到默认档），所以这里用 `"none"`；
     * 不认这个值的端点选 [DEFAULT]（不发送字段），否则可能 400。
     */
    OFF("none"),

    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        /**
         * 解析设置里存的字符串。认不出的存量值回落 [OFF]：本机默认就是「不思考」，
         * 一个脏值不该把请求悄悄切回更慢的档位。
         */
        fun fromName(name: String?): AiReasoningEffort =
            entries.firstOrNull { it.name == name } ?: OFF
    }
}
