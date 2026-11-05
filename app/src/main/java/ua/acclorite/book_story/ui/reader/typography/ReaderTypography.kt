/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：当前生效排版的组合层入口。
 *
 * 全局设置页与阅读器底部面板复用同一批 12 个控件，它们不再直接读 SettingsManager，
 * 而是读 LocalReaderTypography：
 *  - ProvideSettings 在 App 根部注入「全局态」；
 *  - ReaderScreen 用 rememberBookReaderTypography(book.uuid) 覆盖成「本书态」。
 * 这样「当前在编辑哪一套」只有一个来源，后续新增排版项不会漏接。
 *
 * 差异标记（modifiedFields）只在本书态非空；全局态永远为空，所以全局设置页
 * 不需要任何条件判断就不会显示标记。
 */

package ua.acclorite.book_story.ui.reader.typography

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.acclorite.book_story.data.settings.SettingsManager
import ua.acclorite.book_story.data.typography.ReaderTypographyField
import ua.acclorite.book_story.data.typography.ReaderTypographyMode
import ua.acclorite.book_story.data.typography.ReaderTypographyResolver
import ua.acclorite.book_story.data.typography.ReaderTypographyStore
import ua.acclorite.book_story.data.typography.ReaderTypographyValues
import ua.acclorite.book_story.presentation.reader.model.ReaderTextAlignment
import ua.acclorite.book_story.ui.common.helpers.LocalSettings
import ua.acclorite.book_story.ui.reader.data.FontRegistry
import ua.acclorite.book_story.ui.reader.model.FontWithName

/**
 * 存储本身由 ProvideSettings 注入；放在 CompositionLocal 里是为了让阅读页、
 * 字体删除清理等 UI 不需要各自注入一次 Hilt。
 */
val LocalReaderTypographyStore = staticCompositionLocalOf<ReaderTypographyStore> {
    error("No reader typography store provided")
}

// 用 compositionLocalOf 而不是 staticCompositionLocalOf：排版状态每次拖动都会变，
// static 版本会让 Provider 之下整棵子树重组（根部注入时等于整个 App）。
val LocalReaderTypography = compositionLocalOf<ReaderTypographyState> {
    error("No reader typography provided")
}

@Immutable
data class ReaderTypographyState(
    val mode: ReaderTypographyMode,
    val values: ReaderTypographyValues,
    val modifiedFields: Set<ReaderTypographyField>,
    val hasBookRecord: Boolean,
    val actions: ReaderTypographyActions
) {
    fun isModified(field: ReaderTypographyField): Boolean = field in modifiedFields
}

@Stable
interface ReaderTypographyActions {
    fun setFontFamily(font: FontWithName)
    fun setFontThickness(thickness: Int)
    fun setBoldFontThickness(thickness: Int)
    fun setItalic(italic: Boolean)
    fun setFontSize(size: Int)
    fun setLineHeight(height: Int)
    fun setParagraphHeight(height: Int)
    fun setLetterSpacing(spacing: Int)
    fun setParagraphIndentation(indentation: Int)
    fun setTextAlignment(alignment: ReaderTextAlignment)
    fun setSidePadding(padding: Int)
    fun setVerticalPadding(padding: Int)

    /** 切换当前书使用全局还是本书。全局态下无意义。 */
    fun setMode(mode: ReaderTypographyMode)

    /** 删除本书记录并回到全局。全局态下无意义。 */
    fun resetToGlobal()
}

/**
 * 全局态：写入 SettingsManager，行为与改动前完全一致。
 */
@Stable
private class GlobalTypographyActions(
    private val settings: SettingsManager
) : ReaderTypographyActions {
    override fun setFontFamily(font: FontWithName) = settings.fontFamily.update(font)
    override fun setFontThickness(thickness: Int) = settings.fontThickness.update(thickness)
    override fun setBoldFontThickness(thickness: Int) = settings.boldFontThickness.update(thickness)
    override fun setItalic(italic: Boolean) = settings.italic.update(italic)
    override fun setFontSize(size: Int) = settings.fontSize.update(size)
    override fun setLineHeight(height: Int) = settings.lineHeight.update(height)
    override fun setParagraphHeight(height: Int) = settings.paragraphHeight.update(height)
    override fun setLetterSpacing(spacing: Int) = settings.letterSpacing.update(spacing)
    override fun setParagraphIndentation(indentation: Int) =
        settings.paragraphIndentation.update(indentation)

    override fun setTextAlignment(alignment: ReaderTextAlignment) =
        settings.textAlignment.update(alignment)

    override fun setSidePadding(padding: Int) = settings.sidePadding.update(padding)
    override fun setVerticalPadding(padding: Int) = settings.verticalPadding.update(padding)

    // 全局设置页没有「作用域」概念，这两项不会被调用；保持 no-op 而不是抛异常，
    // 避免将来把选择器误复用到全局页时崩溃。
    override fun setMode(mode: ReaderTypographyMode) = Unit
    override fun resetToGlobal() = Unit
}

/**
 * 本书态：写入 ReaderTypographyStore。
 *
 * [globalSnapshot] 用 lastValue 在回调时读取，而不是构造时抄一份 —— 切换开关
 * 必须快照「此刻」的全局设置，否则用户先改全局再切本书会拿到旧值。
 */
@Stable
private class BookTypographyActions(
    private val store: ReaderTypographyStore,
    private val bookUuid: String,
    private val globalSnapshot: () -> ReaderTypographyValues
) : ReaderTypographyActions {
    override fun setFontFamily(font: FontWithName) =
        store.update(bookUuid) { it.copy(fontFamilyId = font.id) }

    override fun setFontThickness(thickness: Int) =
        store.update(bookUuid) { it.copy(fontThickness = thickness) }

    override fun setBoldFontThickness(thickness: Int) =
        store.update(bookUuid) { it.copy(boldFontThickness = thickness) }

    override fun setItalic(italic: Boolean) =
        store.update(bookUuid) { it.copy(italic = italic) }

    override fun setFontSize(size: Int) =
        store.update(bookUuid) { it.copy(fontSize = size) }

    override fun setLineHeight(height: Int) =
        store.update(bookUuid) { it.copy(lineHeight = height) }

    override fun setParagraphHeight(height: Int) =
        store.update(bookUuid) { it.copy(paragraphHeight = height) }

    override fun setLetterSpacing(spacing: Int) =
        store.update(bookUuid) { it.copy(letterSpacing = spacing) }

    override fun setParagraphIndentation(indentation: Int) =
        store.update(bookUuid) { it.copy(paragraphIndentation = indentation) }

    override fun setTextAlignment(alignment: ReaderTextAlignment) =
        store.update(bookUuid) { it.copy(textAlignment = alignment) }

    override fun setSidePadding(padding: Int) =
        store.update(bookUuid) { it.copy(sidePadding = padding) }

    override fun setVerticalPadding(padding: Int) =
        store.update(bookUuid) { it.copy(verticalPadding = padding) }

    override fun setMode(mode: ReaderTypographyMode) {
        when (mode) {
            ReaderTypographyMode.BOOK -> store.enable(bookUuid, globalSnapshot())
            ReaderTypographyMode.GLOBAL -> store.disable(bookUuid)
        }
    }

    override fun resetToGlobal() = store.reset(bookUuid)
}

/**
 * 阅读页的作用域动作：按当前模式把写入分别送到全局设置或本书记录。
 *
 * 不能直接复用 BookTypographyActions：在「全局」模式下拖滑块必须编辑全局，
 * 而不是悄无声息地创建一条本书记录并把这本书切到本书模式。
 */
@Stable
private class BookScopedTypographyActions(
    settings: SettingsManager,
    private val store: ReaderTypographyStore,
    private val bookUuid: String,
    /**
     * 读「此刻」的模式，而不是构图时捕获的布尔值。
     *
     * 切换作用域时 store 的内存状态是同步更新的；用动态读取可以让「切到本书后立刻拖滑块」
     * 也写进本书记录，而不是因为旧闭包还认为在全局模式而改掉全局设置。
     */
    private val isBookMode: () -> Boolean,
    private val globalSnapshot: () -> ReaderTypographyValues
) : ReaderTypographyActions {
    private val global = GlobalTypographyActions(settings)
    private val book = BookTypographyActions(store, bookUuid, globalSnapshot)
    private val values: ReaderTypographyActions
        get() = if (isBookMode()) book else global

    override fun setFontFamily(font: FontWithName) = values.setFontFamily(font)
    override fun setFontThickness(thickness: Int) = values.setFontThickness(thickness)
    override fun setBoldFontThickness(thickness: Int) = values.setBoldFontThickness(thickness)
    override fun setItalic(italic: Boolean) = values.setItalic(italic)
    override fun setFontSize(size: Int) = values.setFontSize(size)
    override fun setLineHeight(height: Int) = values.setLineHeight(height)
    override fun setParagraphHeight(height: Int) = values.setParagraphHeight(height)
    override fun setLetterSpacing(spacing: Int) = values.setLetterSpacing(spacing)
    override fun setParagraphIndentation(indentation: Int) =
        values.setParagraphIndentation(indentation)

    override fun setTextAlignment(alignment: ReaderTextAlignment) =
        values.setTextAlignment(alignment)

    override fun setSidePadding(padding: Int) = values.setSidePadding(padding)
    override fun setVerticalPadding(padding: Int) = values.setVerticalPadding(padding)

    // 模式切换必须由这里处理：values 委托只能改当前作用域里的值。
    override fun setMode(mode: ReaderTypographyMode) {
        when (mode) {
            // 已有记录直接复用记忆值；第一次启用时用当前全局值快照。
            ReaderTypographyMode.BOOK -> store.enable(bookUuid, globalSnapshot())
            ReaderTypographyMode.GLOBAL -> store.disable(bookUuid)
        }
    }

    override fun resetToGlobal() = store.reset(bookUuid)
}

/**
 * 全局态。只在 ProvideSettings 里调用一次，作为整个 App 的默认值。
 */
@Composable
fun rememberGlobalReaderTypography(settings: SettingsManager): ReaderTypographyState {
    val values = readGlobalValues(settings)
    val actions = remember(settings) { GlobalTypographyActions(settings) }

    return remember(values, actions) {
        ReaderTypographyState(
            mode = ReaderTypographyMode.GLOBAL,
            values = values,
            modifiedFields = emptySet(),
            hasBookRecord = false,
            actions = actions
        )
    }
}

/**
 * 本书态。由 ReaderScreen 调用，并用 CompositionLocalProvider 覆盖到正文与底部面板。
 *
 * 没有记录时返回全局值（mode=GLOBAL、hasBookRecord=false），所以第一次打开一本书
 * 的观感与改动前完全一致。
 */
@Composable
fun rememberBookReaderTypography(bookUuid: String): ReaderTypographyState {
    val settings = LocalSettings.current
    val store = LocalReaderTypographyStore.current
    val records by store.records.collectAsStateWithLifecycle()

    val global = readGlobalValues(settings)
    val record = records[bookUuid]

    // 字体列表参与 remember key：导入/删除字体后需要重新解析记录里的字体 id。
    // 找不到 id 时返回 null（而不是默认字体），让解析层明确回落全局字体。
    val availableFonts = FontRegistry.fonts
    val resolveFont: (String) -> FontWithName? = { fontId ->
        availableFonts.find { it.id == fontId }
    }
    val values = remember(global, record, availableFonts) {
        ReaderTypographyResolver.resolve(global, record, resolveFont)
    }
    val modifiedFields = remember(global, record, availableFonts) {
        ReaderTypographyResolver.modifiedFields(global, record, resolveFont)
    }
    val bookMode = record?.enabled == true
    val actions = remember(store, bookUuid, settings) {
        BookScopedTypographyActions(
            settings = settings,
            store = store,
            bookUuid = bookUuid,
            isBookMode = { store.records.value[bookUuid]?.enabled == true },
            globalSnapshot = { readGlobalValuesBlocking(settings) }
        )
    }

    return remember(values, modifiedFields, record, actions) {
        ReaderTypographyState(
            mode = if (bookMode) {
                ReaderTypographyMode.BOOK
            } else {
                ReaderTypographyMode.GLOBAL
            },
            values = values,
            modifiedFields = modifiedFields,
            hasBookRecord = record != null,
            actions = actions
        )
    }
}

/**
 * 组合读取：这些是 @Composable getter，读 `.value` 才会在设置变化时触发重组。
 */
@Composable
private fun readGlobalValues(settings: SettingsManager): ReaderTypographyValues =
    ReaderTypographyValues(
        fontFamily = settings.fontFamily.value,
        fontThickness = settings.fontThickness.value,
        boldFontThickness = settings.boldFontThickness.value,
        italic = settings.italic.value,
        fontSize = settings.fontSize.value,
        lineHeight = settings.lineHeight.value,
        paragraphHeight = settings.paragraphHeight.value,
        letterSpacing = settings.letterSpacing.value,
        paragraphIndentation = settings.paragraphIndentation.value,
        textAlignment = settings.textAlignment.value,
        sidePadding = settings.sidePadding.value,
        verticalPadding = settings.verticalPadding.value
    )

/**
 * 非组合读取：用于开关切换时给 store 快照全局值（回调里不能读 @Composable getter）。
 */
private fun readGlobalValuesBlocking(settings: SettingsManager): ReaderTypographyValues =
    ReaderTypographyValues(
        fontFamily = settings.fontFamily.lastValue,
        fontThickness = settings.fontThickness.lastValue,
        boldFontThickness = settings.boldFontThickness.lastValue,
        italic = settings.italic.lastValue,
        fontSize = settings.fontSize.lastValue,
        lineHeight = settings.lineHeight.lastValue,
        paragraphHeight = settings.paragraphHeight.lastValue,
        letterSpacing = settings.letterSpacing.lastValue,
        paragraphIndentation = settings.paragraphIndentation.lastValue,
        textAlignment = settings.textAlignment.lastValue,
        sidePadding = settings.sidePadding.lastValue,
        verticalPadding = settings.verticalPadding.lastValue
    )
