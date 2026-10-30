/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:Suppress("DEPRECATION")

package ua.acclorite.book_story.ui.common.components.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import ua.acclorite.book_story.R


private const val MENU_ITEM_COPY = 0
private const val MENU_ITEM_SHARE = 1
private const val MENU_ITEM_WEB = 2
private const val MENU_ITEM_TRANSLATE = 3
private const val MENU_ITEM_DICTIONARY = 4

/**
 * Text ActionMode callback.
 * Used in pair with [SelectionToolbar]. Follow [TextToolbar] for more info.
 */
private class TextActionModeCallback(
    private val context: Context,
    var rect: Rect = Rect.Zero,
    var onCopyRequested: (() -> Unit)? = null,
    var onShareRequested: (() -> Unit)? = null,
    var onWebSearchRequested: (() -> Unit)? = null,
    var onTranslateRequested: (() -> Unit)? = null,
    var onDictionaryRequested: (() -> Unit)? = null
) : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        requireNotNull(menu)
        requireNotNull(mode)

        onCopyRequested?.let {
            menu.add(0, MENU_ITEM_COPY, 0, context.getString(R.string.copy))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }

        onShareRequested?.let {
            menu.add(0, MENU_ITEM_SHARE, 1, context.getString(R.string.share))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }

        onWebSearchRequested?.let {
            menu.add(0, MENU_ITEM_WEB, 2, context.getString(R.string.web_search))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }

        onTranslateRequested?.let {
            menu.add(0, MENU_ITEM_TRANSLATE, 3, context.getString(R.string.translate))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }

        onDictionaryRequested?.let {
            menu.add(0, MENU_ITEM_DICTIONARY, 4, context.getString(R.string.dictionary))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }

        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        return false
    }

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        when (item!!.itemId) {
            MENU_ITEM_COPY -> onCopyRequested?.invoke()
            MENU_ITEM_SHARE -> onShareRequested?.invoke()
            MENU_ITEM_WEB -> onWebSearchRequested?.invoke()
            MENU_ITEM_TRANSLATE -> onTranslateRequested?.invoke()
            MENU_ITEM_DICTIONARY -> onDictionaryRequested?.invoke()
            else -> return false
        }
        mode?.finish()
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode?) {}
}

/**
 * Floating [TextActionModeCallback].
 */
private class FloatingTextActionModeCallback(
    val callback: TextActionModeCallback
) : ActionMode.Callback2() {
    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return callback.onActionItemClicked(mode, item)
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        return callback.onCreateActionMode(mode, menu)
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        return callback.onPrepareActionMode(mode, menu)
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        callback.onDestroyActionMode(mode)
    }

    override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: android.graphics.Rect?) {
        val rect = callback.rect
        outRect?.set(
            rect.left.toInt(),
            rect.top.toInt(),
            rect.right.toInt(),
            rect.bottom.toInt()
        )
    }
}

/**
 * Selection Toolbar.
 * Used in pair with [SelectionContainer] to display custom toolbar.
 */
private class SelectionToolbar(
    private val view: View,
    context: Context,
    private val onCopyRequest: (() -> Unit)?,
    private val onShareRequest: ((String) -> Unit)?,
    private val onWebSearchRequest: ((String) -> Unit)?,
    private val onTranslateRequest: ((String) -> Unit)?,
    private val onDictionaryRequest: ((String) -> Unit)?,
    private val onSelectionSettled: ((Rect, () -> Unit) -> Unit)?
) : TextToolbar {
    private var actionMode: ActionMode? = null
    private val callback = TextActionModeCallback(context = context)

    val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override var status: TextToolbarStatus by mutableStateOf(TextToolbarStatus.Hidden)

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        callback.rect = rect
        // fork: 选区落定（工具栏即将弹出）时上报一次。rect 已经是**窗口坐标**：同一个 rect
        // 会原样交给 `FloatingTextActionModeCallback.onGetContentRect`，而 ActionMode 的
        // `onGetContentRect` 契约就是 view（= root/window）坐标。所以这里**不能**再叠加一次
        // `localToWindow`，否则窗口有 inset 时选区会被算成偏移后的位置。
        // 不在这里读剪贴板：Android 13+ 每次写剪贴板都会弹系统提示，选区每动一下弹一次不能接受。
        // 第二个参数是「收起工具栏」：系统 ActionMode 是触摸模态窗口，卡片弹出后不收起它，
        // 卡片外的点击会被它吞掉。工具栏实例只有这里持有，所以由这里交给回调。
        onSelectionSettled?.invoke(rect) { hide() }
        callback.onCopyRequested = {
            onCopyRequested?.invoke()
            onCopyRequest?.invoke()
        }
        callback.onShareRequested = {
            val previousClipboard = clipboardManager.primaryClip
            onCopyRequested?.invoke()
            val currentClipboard = clipboardManager.text

            onShareRequest?.invoke(currentClipboard.toString())

            if (previousClipboard != null) {
                clipboardManager.setPrimaryClip(
                    previousClipboard
                )
            } else {
                clipboardManager.setPrimaryClip(ClipData.newPlainText(null, " "))
            }
        }
        callback.onWebSearchRequested = {
            val previousClipboard = clipboardManager.primaryClip
            onCopyRequested?.invoke()
            val currentClipboard = clipboardManager.text

            onWebSearchRequest?.invoke(currentClipboard.toString())

            if (previousClipboard != null) {
                clipboardManager.setPrimaryClip(
                    previousClipboard
                )
            } else {
                clipboardManager.setPrimaryClip(ClipData.newPlainText(null, " "))
            }
        }
        callback.onTranslateRequested = {
            val previousClipboard = clipboardManager.primaryClip
            onCopyRequested?.invoke()
            val currentClipboard = clipboardManager.text

            onTranslateRequest?.invoke(currentClipboard.toString())

            if (previousClipboard != null) {
                clipboardManager.setPrimaryClip(
                    previousClipboard
                )
            } else {
                clipboardManager.setPrimaryClip(ClipData.newPlainText(null, " "))
            }
        }
        callback.onDictionaryRequested = {
            val previousClipboard = clipboardManager.primaryClip
            onCopyRequested?.invoke()
            val currentClipboard = clipboardManager.text

            onDictionaryRequest?.invoke(currentClipboard.toString())

            if (previousClipboard != null) {
                clipboardManager.setPrimaryClip(
                    previousClipboard
                )
            } else {
                clipboardManager.setPrimaryClip(ClipData.newPlainText(null, " "))
            }
        }

        if (actionMode == null) {
            status = TextToolbarStatus.Shown
            actionMode = view.startActionMode(
                FloatingTextActionModeCallback(callback),
                ActionMode.TYPE_FLOATING
            )
        } else {
            actionMode?.invalidate()
        }
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
        actionMode?.finish()
        actionMode = null
    }
}

/**
 * Selection container.
 *
 * @param onCopyRequested Callback for when the copy option is clicked.
 * @param onTranslateRequested Callback for when the translate option is clicked.
 * @param onDictionaryRequested Callback for when the dictionary option is clicked.
 * @param onSelectionSettled fork（S5）：选区落定时回调。第一个参数是选区在**窗口坐标**里的
 *   矩形（与交给 ActionMode `onGetContentRect` 的是同一个 rect，不再二次换算）；第二个参数把
 *   选区工具栏（ActionMode）收起来，取词卡片弹出前调用 —— 否则触摸模态的工具栏会吞掉卡片外的
 *   点击。拖动选择手柄时会被连续调用，需要防抖的调用方自己处理（阅读页是 350ms）。
 * @param content Selection container content.
 */
@Composable
fun SelectionContainer(
    onCopyRequested: (() -> Unit),
    onShareRequested: ((String) -> Unit),
    onWebSearchRequested: ((String) -> Unit),
    onTranslateRequested: ((String) -> Unit),
    onDictionaryRequested: ((String) -> Unit),
    onSelectionSettled: ((Rect, () -> Unit) -> Unit)? = null,
    content: @Composable (toolbarHidden: Boolean) -> Unit
) {
    val view = LocalView.current
    val context = LocalContext.current

    val currentOnSelectionSettled = rememberUpdatedState(onSelectionSettled)

    val selectionToolbar = remember {
        SelectionToolbar(
            view = view,
            context = context,

            onCopyRequest = {
                onCopyRequested()
            },
            onShareRequest = {
                onShareRequested(it)
            },
            onWebSearchRequest = {
                onWebSearchRequested(it)
            },
            onTranslateRequest = {
                onTranslateRequested(it)
            },
            onDictionaryRequest = {
                onDictionaryRequested(it)
            },
            onSelectionSettled = { rect, hideToolbar ->
                currentOnSelectionSettled.value?.invoke(rect, hideToolbar)
            }
        )
    }
    val isToolbarHidden = remember(selectionToolbar.status) {
        derivedStateOf {
            selectionToolbar.status == TextToolbarStatus.Hidden
        }
    }

    CompositionLocalProvider(
        LocalTextToolbar provides selectionToolbar
    ) {
        SelectionContainer(modifier = Modifier) {
            content(isToolbarHidden.value)
        }
    }
}