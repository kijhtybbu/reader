/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：贴词取词卡片（第二阶段 S5，设计 §5.2）。
 *
 * 位置由 [lookupPopupPosition] 算，不交给 `Popup(alignment, offset)`：
 * 后者在 RTL 下会把 offset.x 从右边量起，卡片直接跑到屏幕外。自己算还有一个好处 ——
 * [PopupPositionProvider.calculatePosition] 能拿到 `popupContentSize`，第一帧就摆在正确位置，
 * 不会先画在词下方再跳上去。
 *
 * 配色只用 `LocalReaderPalette`：夜间预设下卡片必须是阅读背景的色系，否则一开一关闪白。
 *
 * 卡片分三层，只有中间那层滚动：
 *  1. 固定头部：词头 + 音标 + 来源 + 词典元信息（柯林斯星级/牛津/考试标签/词频）+ 多词典 chip；
 *  2. 可滚动正文：义项 / 英文释义 / 词形附注；**富媒体词条不同**：正文是 WebView，自己不滚，
 *     改用 `weight(1f)` 占满剩余高度（见 `LookupCard(fixedHeightBody = true)`）——
 *     外面再套一个 `verticalScroll` 就会变成嵌套滚动，两者会抢手势；
 *  3. 固定按钮区（未找到、错误两种状态）：以前的实现把按钮放在同一个 `verticalScroll` 里，
 *     词条一长按钮就被滚出可见区，用户以为卡片没有按钮。
 *
 * 发音（设计 §3.7）：头部喇叭优先播词条自带录音，全部失败/没有录音才用系统 TTS；
 * 两条路的互斥、候选回落与「连点不叠加」都在 [DictionaryAudioController] 里。
 */

package ua.acclorite.book_story.ui.reader


import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.TweenSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import ua.acclorite.book_story.R
import ua.acclorite.book_story.presentation.reader.LookupState
import ua.acclorite.book_story.presentation.reader.anchor
import ua.acclorite.book_story.ui.common.components.common.IconButton
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.helpers.LocalSettings
import ua.acclorite.book_story.ui.common.helpers.showToast
import ua.acclorite.book_story.ui.theme.LocalReaderPalette
import ua.acclorite.book_story.ui.theme.Motion
import java.io.File
import java.text.NumberFormat
import kotlin.math.roundToInt

/** 卡片与屏幕边缘的最小距离。 */
private val PopupMargin = 12.dp

/** 卡片与词之间的距离。 */
private val PopupGap = 8.dp

private val PopupMaxWidth = 360.dp

/** 命中「未找到」时卡片也要有个像样的宽度，否则三个按钮会挤成一条竖线。 */
private val PopupMinWidth = 248.dp

/** 卡片最高占安全区高度的比例（设计 §5.2）。 */
internal const val PopupMaxHeightFraction = 0.45f

/** 卡片圆角。[Surface] 与「展开动画」的裁剪区必须用同一个形状，否则动画时能看见直角边。 */
private val CardShape = RoundedCornerShape(18.dp)

/**
 * 尺寸变化（结果到达、切词典 chip、富媒体正文量出高度）的过渡：容器在 180ms 内从当前尺寸
 * 长到目标尺寸，多出来的内容由 [CardShape] 裁掉（「卡片从贴词那侧展开」）。
 */
private val CardSizeAnimation: TweenSpec<IntSize> =
    Motion.base<IntSize>(easing = Motion.Emphasized)

/**
 * 富媒体正文在量出内容高度之前的高度。和 Loading 卡片（头部 + 一行提示）差不多高：正文一
 * 到位就直接长到最终高度，中间不会再有一次「先撑满/先塌一下」。
 */
private val MediaBodyPlaceholderHeight = 56.dp

/**
 * 富媒体正文量不出高度时的兜底：超过这个时间还没量到，就让正文吃满剩余高度（回到旧的
 * 「宁可全高」行为）。宁可卡片大一点，也不能把一篇词条关在一个 56dp 的小窗口里。
 */
private const val MediaBodyMeasureTimeoutMillis = 800L

/** 词性标签列的宽度：让所有义项的左边缘对齐（`n.` / `prep.` / `[网络]` 共用一列）。 */
private val SenseLabelWidth = 44.dp

/** 义项之间的行距。 */
private val SenseSpacing = 4.dp

/**
 * 取词卡片。
 *
 * @param onSelect 多词典命中里选第 index 条。
 * @param onLookupLink 点了正文里的 `juice://lookup/…` 链接。
 * @param onRetry 错误态重试。
 * @param onDismiss 外部点击/返回/关闭。
 * @param onImportDictionary 未找到时去导入词典。
 * @param onExternalLookup 未找到时回退到外部词典应用（S4 之前的行为）。
 * @param onAiExplain S6 的 AI 解释；为 null 时按钮显示为禁用态。
 * @param onAudioFile 把词条自带的发音资源落成文件（`DictionaryResourceStore.audioFile`）。
 *   默认实现恒返回 null（没有录音，一直走 TTS），单测与预览不必造资源仓库。
 */
@Composable
fun ReaderLookupPopup(
    state: LookupState,
    onSelect: (Int) -> Unit,
    onLookupLink: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onImportDictionary: () -> Unit,
    onExternalLookup: (String) -> Unit,
    onAiExplain: ((String) -> Unit)? = null,
    onTtsUnavailable: () -> Unit = {},
    onAudioFile: suspend (dictId: String, path: String) -> File? = { _, _ -> null }
) {
    val palette = LocalReaderPalette.current
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current

    // 安全区：窗口减去系统栏/导航栏/刘海挖孔。卡片尺寸和夹紧都要用它，否则贴顶的词会把
    // 卡片翻到状态栏或挖孔下面（Popup 自己的窗口不避开 inset）。
    // 左右按物理方向取（传 Ltr）：`getLeft/getRight` 会随 layoutDirection 互换，
    // 而这里是像素级的夹紧范围，不跟着 RTL 翻转（RTL 只影响卡片内容的排版）。
    val safeInsets = WindowInsets.safeDrawing
    val safeLeft = safeInsets.getLeft(density, LayoutDirection.Ltr)
    val safeTop = safeInsets.getTop(density)
    val safeRight = safeInsets.getRight(density, LayoutDirection.Ltr)
    val safeBottom = safeInsets.getBottom(density)
    val containerSize = windowInfo.containerSize
    val safeBounds = remember(containerSize, safeLeft, safeTop, safeRight, safeBottom) {
        IntRect(
            left = safeLeft,
            top = safeTop,
            right = (containerSize.width - safeRight).coerceAtLeast(safeLeft),
            bottom = (containerSize.height - safeBottom).coerceAtLeast(safeTop)
        )
    }

    val margin = with(density) { PopupMargin.roundToPx() }
    val gap = with(density) { PopupGap.roundToPx() }
    val anchor = state.anchor
    // 方向在「点击那一刻」就能算出来：只看词落在屏幕哪个位置，与卡片尺寸无关。所以卡片
    // 之后怎么长高，方向都不会变 —— 用户看到的始终是「朝一侧长出来」，而不是「先朝下再翻上去」。
    val placement = remember(anchor, safeBounds, margin, gap) {
        lookupPopupPlacement(anchor, safeBounds, margin, gap)
    }
    // 高度上限 = min(设计上限, 该侧真正剩下的空间)。因为卡片贴词那条边是固定的（见
    // [lookupPopupPosition]），上限必须按这一侧的空间算，否则长到头会被夹回去、词边就动了。
    val maxHeightPx = lookupPopupMaxHeightPx(safeBounds, placement)
    val maxHeight = with(density) { maxHeightPx.toDp() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = LocalSettings.current
    val accent = settings.dictionaryAudioAccent.value
    val speaker = remember(context) { WordSpeaker(context) }
    val audioPlayer = remember(context) { DictionaryAudioPlayer(context) }
    val audioUnavailableMessage = stringResource(id = R.string.lookup_popup_audio_unavailable)
    // 回调每帧都可能是新实例（卡片跟着阅读进度重组），用 State 包一层，控制器只建一次。
    val currentOnTtsUnavailable = rememberUpdatedState(onTtsUnavailable)
    val currentOnAudioFile = rememberUpdatedState(onAudioFile)
    val audio = remember(scope, speaker, audioPlayer, audioUnavailableMessage, context) {
        DictionaryAudioController(
            scope = scope,
            audio = audioPlayer,
            speech = speaker,
            onSpeechUnavailable = { currentOnTtsUnavailable.value() },
            onAudioUnavailable = {
                audioUnavailableMessage.showToast(context, longToast = false)
            }
        )
    }
    // 卡片一关（或换词/换命中的词典）就停：继续播上一张卡片的录音是明确的 bug。
    DisposableEffect(audio) {
        onDispose {
            audio.release()
            speaker.shutdown()
        }
    }
    val currentWord = when (state) {
        is LookupState.Loading -> state.word
        is LookupState.Success -> state.word
        is LookupState.NotFound -> state.word
        is LookupState.Error -> state.word
        LookupState.Idle -> ""
    }
    val selectedIndex = (state as? LookupState.Success)?.selectedIndex ?: -1
    LaunchedEffect(currentWord, selectedIndex) { audio.stop() }
    // 非高亮词条（加载中/未找到/错误）的喇叭：直接走 TTS，与旧行为一致。
    val onSpeak = {
        audio.speak(currentWord)
    }

    // 链接样式与回调给正文的每一行共用一份：`remember` 里捕获的是稳定的 State 对象，
    // 行内容不变时不会因为父级重组而重新解析 HTML。
    val currentOnLookupLink = rememberUpdatedState(onLookupLink)
    val linkStyles = remember(palette.accent) {
        TextLinkStyles(SpanStyle(color = palette.accent, textDecoration = TextDecoration.Underline))
    }

    // 卡片尺寸要自己量：弹窗是全屏的（见下面的说明），`PopupPositionProvider` 拿到的
    // `popupContentSize` 是全屏尺寸，不能再用来算卡片位置。
    // 量到的是**动画中的当前尺寸**（见下面的 `animateContentSize`）：贴着词的那条边在整个
    // 长高过程中固定不动，只有远端在动。
    //
    // 位置在 **layout 阶段**算（`Modifier.offset { }` 的 lambda 版）：动画期间尺寸每帧都在变，
    // 走 state 会每帧重组整张卡片（里面还有 WebView），lambda 版只触发重排。
    val cardSize = remember { mutableStateOf(IntSize.Zero) }
    // 第一帧还没量到尺寸，没法算位置，先透明；量到之后再淡入，避免卡片先在左上角闪一下。
    var measured by remember { mutableStateOf(false) }

    Popup(
        // 弹窗占满整个窗口，内部再摆卡片。为什么不用「贴卡的 wrap-content 弹窗 + 外部点击关闭」：
        // 长按取词时选区手柄是**独立的 Popup 窗口**，且带着 `WATCH_OUTSIDE_TOUCH` 叠在卡片之上，
        // 会把 ACTION_OUTSIDE 截走，卡片外点了没反应（真机复现：长按取词后卡片关不掉）。
        // 全屏窗口把「外部点击」变成窗口内事件，不再依赖 WindowManager 的跨窗口分发。
        popupPositionProvider = remember {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize
                ): IntOffset = IntOffset.Zero
            }
        },
        onDismissRequest = onDismiss,
        // 刻意不 focusable：focusable 弹窗在开/关时会抢/还焦点，Compose 的 SelectionManager
        // 每次拿到焦点都会把选区工具栏 showMenu 再报一次，长按取词就变成「关掉→自己弹回来」
        // 的死循环。不抢焦点时返回键用 BackHandler 接（见下）。
        properties = PopupProperties(focusable = false, dismissOnBackPress = false)
    ) {
        // 窗口不 focusable，返回键到 Activity 的 dispatcher；这个 BackHandler 比阅读页的
        // 后注册，所以卡片开着时先关卡片。
        BackHandler(enabled = true) { onDismiss() }
        Box(Modifier.fillMaxSize()) {
            // 透明遮罩：点卡片以外的任何位置都关卡片。
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(onDismiss) {
                        detectTapGestures { onDismiss() }
                    }
            )
            // 尺寸变化用「容器裁剪展开」表现：子内容一开始就按**最终尺寸**排版并摆放，容器从
            // 当前尺寸平滑长到目标尺寸，多出来的部分被卡片圆角裁掉 —— 看上去就是卡片从贴词
            // 那侧把内容展开。
            //
            // 为什么不用 `heightIn(max = 动画值)`：那样每一帧都会把新高度交给子内容，富媒体的
            // WebView 会跟着每帧重排（文字跳动/白闪），长词条还会在中途冒出滚动条再消失。
            // 现在的写法子内容只测量一次，`animateContentSize` 只动容器。
            //
            // 顺序有讲究：`clip` 必须在 `animateContentSize` **外面**，裁剪区才会跟着动画
            // 尺寸走；`onSizeChanged` 也在它外面，量到的才是动画中的尺寸（位置据此每帧重算）。
            //
            // alignment 取**贴词那一侧**：`animateContentSize` 把已经按最终尺寸排好的内容对齐
            // 到容器的贴词边，于是内容里紧挨着词的那部分从第一帧起就在最终位置上，长高只是
            // 「把远端放出来」—— 动画全程内容和词的相对位置都不动，不会出现文字被推着跑。
            Box(
                modifier = Modifier
                    .offset {
                        if (measured) {
                            lookupPopupPosition(anchor, safeBounds, cardSize.value, margin, gap)
                        } else {
                            IntOffset.Zero
                        }
                    }
                    .heightIn(max = maxHeight)
                    // 阴影必须画在 clip 之外：Material 的 elevation 是圆角轮廓**外面**的一圈，
                    // 与卡片同尺寸的 clip 会把它整圈剪掉（只剩一张没有投影的平面）。
                    .shadow(elevation = 10.dp, shape = CardShape)
                    .clip(CardShape)
                    .onSizeChanged {
                        cardSize.value = it
                        measured = true
                    }
                    // 吞掉点击的节点必须和**可见区域**同尺寸（跟着动画走）。放进 animateContentSize
                    // 里的话它的边界是内容的最终尺寸，动画期间卡片外那条空白会吃掉本该关闭卡片的点击。
                    .pointerInput(Unit) {
                        detectTapGestures { }
                    }
                    .animateContentSize(
                        animationSpec = CardSizeAnimation,
                        alignment = if (placement.below) {
                            Alignment.TopCenter
                        } else {
                            Alignment.BottomCenter
                        }
                    )
                    .alpha(if (measured) 1f else 0f)
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(min = PopupMinWidth, max = PopupMaxWidth),
                    shape = CardShape,
                    color = palette.bar,
                    contentColor = palette.onBar,
                    // 阴影由外层 shadow 画（见上），这里不能再画一遍。
                    shadowElevation = 0.dp
                ) {
                    when (state) {
                        is LookupState.Loading -> LoadingCard(state, onSpeak)
                        is LookupState.Success -> SuccessCard(
                            state = state,
                            linkStyles = linkStyles,
                            onLookupLink = currentOnLookupLink,
                            onSelect = onSelect,
                            accent = accent,
                            audio = audio,
                            audioFile = { dictId, path -> currentOnAudioFile.value(dictId, path) }
                        )

                        is LookupState.NotFound -> NotFoundCard(
                            state = state,
                            onImportDictionary = onImportDictionary,
                            onExternalLookup = onExternalLookup,
                            onAiExplain = onAiExplain,
                            onSpeak = onSpeak
                        )

                        is LookupState.Error -> ErrorCard(state, onRetry, onSpeak)
                        LookupState.Idle -> Unit
                    }
                }
            }
        }
    }
}

/**
 * 卡片骨架：固定头部 + 正文 + 固定按钮区。
 *
 * 正文默认用 `weight(1f, fill = false)`：内容短时卡片跟着内容收缩，内容超过最大高度时
 * 只有正文滚动，头部与按钮始终留在原位。`fill = false` 是关键 —— 默认的 `fill = true`
 * 会把卡片永远撑到最大高度。
 *
 * @param fixedHeightBody 正文自己负责滚动（富媒体词条的 WebView）。此时：
 *  - 正文高度由它自己决定（WebView 量出的内容高度，见 [DictionaryArticleWebView] 的
 *    `onContentHeight`），放不下时才被剩余空间夹住；
 *  - **不**套 `verticalScroll`：WebView 内部已经在滚，外面再套一层就变成嵌套滚动，
 *    两层会抢手势（滑一下跳两屏）。头尾仍然固定。
 */
@Composable
private fun LookupCard(
    header: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    fixedHeightBody: Boolean = false
) {
    val palette = LocalReaderPalette.current
    Column(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
            content = header
        )
        HorizontalDivider(color = palette.onBar.copy(alpha = 0.10f))
        Column(
            modifier = Modifier
                // fill = false 是关键：正文按自己的高度排（短词条卡片就矮），只有放不下时才
                // 被剩余空间夹住。富媒体正文也一样 —— 它的高度由量出来的内容高度决定，
                // 所以卡片是「内容多高就多高，最多到上限」，不是一上来就撑满。
                .weight(1f, fill = false)
                .padding(horizontal = 18.dp, vertical = 12.dp)
                .then(
                    if (fixedHeightBody) Modifier
                    else Modifier.verticalScroll(rememberScrollState())
                ),
            content = body
        )
        if (footer != null) {
            HorizontalDivider(color = palette.onBar.copy(alpha = 0.10f))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                content = footer
            )
        }
    }
}

@Composable
private fun LoadingCard(state: LookupState.Loading, onSpeak: () -> Unit) {
    LookupCard(
        header = { WordHeader(word = state.word, onSpeak = onSpeak) },
        body = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = LocalReaderPalette.current.accent,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                MutedText(stringResource(id = R.string.lookup_popup_loading))
            }
        }
    )
}

@Composable
private fun SuccessCard(
    state: LookupState.Success,
    linkStyles: TextLinkStyles,
    onLookupLink: State<(String) -> Unit>,
    onSelect: (Int) -> Unit,
    accent: DictionaryAudioAccent,
    audio: DictionaryAudioController,
    audioFile: suspend (dictId: String, path: String) -> File?
) {
    val selected = state.entries.getOrNull(state.selectedIndex)
    // 解析结果只跟词条 HTML、词头、词典有无资源包有关；切 chip 时才重新解析一次。
    val body = remember(state.html, state.word, state.hasResources, selected?.dictId) {
        parseLookupBody(
            html = state.html,
            word = state.word,
            hasResources = state.hasResources,
            dictId = selected?.dictId.orEmpty()
        )
    }
    val media = body as? LookupBody.Media
    // 富媒体正文的高度：WebView 加载完成后用 `contentHeight` 量一次（见 DictionaryArticleWebView
    // 的 onContentHeight），量到之前先按占位高度显示。
    // 只跟词走、不跟命中的词典走：切 chip 时上一条的高度先留一会儿，比「先塌下去再长起来」稳。
    var mediaBodyHeightPx by remember(state.word) { mutableStateOf(0) }
    // 量不出来的兜底（见 MediaBodyMeasureTimeoutMillis）：正文吃满剩余高度，而不是停在占位高度。
    var mediaBodyUnmeasured by remember(state.word) { mutableStateOf(false) }
    LaunchedEffect(state.word, media != null) {
        if (media == null) return@LaunchedEffect
        mediaBodyUnmeasured = false
        delay(MediaBodyMeasureTimeoutMillis)
        mediaBodyUnmeasured = mediaBodyHeightPx <= 0
    }
    // 口音偏好只决定「先试哪一条」，其余候选跟在后面：单条失败不等于要立刻回落 TTS。
    val playbackOrder = remember(media?.audio, accent) {
        media?.let { audioPlaybackOrder(it.audio, accent) }.orEmpty()
    }
    val speak = {
        if (playbackOrder.isEmpty()) {
            audio.speak(state.word)
        } else {
            audio.play(state.word, playbackOrder) { path ->
                audioFile(selected?.dictId.orEmpty(), path)
            }
        }
    }
    // 正文里点了 `dict-audio://…`：播**点的那一条**，不是头部喇叭按口音选的那条。
    val playAudio = { path: String ->
        audio.play(state.word, listOf(path)) { candidate ->
            audioFile(selected?.dictId.orEmpty(), candidate)
        }
    }

    LookupCard(
        fixedHeightBody = media != null,
        header = {
            WordHeader(
                word = state.word,
                phonetic = body.phonetic,
                source = selected?.dictName,
                meta = body.meta,
                onSpeak = speak
            )
            if (state.entries.size > 1) {
                // 候选词条和上方的词典元信息都是胶囊，挤在一起会混成一片：
                // 拉开间距并用一条发丝线分组。
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = LocalReaderPalette.current.onBar.copy(alpha = 0.06f))
                Spacer(Modifier.height(10.dp))
                EntryChips(state = state, onSelect = onSelect)
            }
        },
        body = {
            when (body) {
                is LookupBody.Sections -> SectionsBody(
                    body = body,
                    linkStyles = linkStyles,
                    onLookupLink = onLookupLink
                )

                is LookupBody.Rich -> HtmlParagraph(
                    html = body.html,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = LocalReaderPalette.current.onBar
                    ),
                    linkStyles = linkStyles,
                    onLookupLink = onLookupLink
                )

                is LookupBody.Media -> DictionaryArticleWebView(
                    // 高度 = 量出来的内容高度；放不下时由卡片高度上限夹住，WebView 自己滚
                    // （`fixedHeightBody = true` 已去掉外层滚动）。
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            when {
                                mediaBodyHeightPx > 0 -> Modifier.height(
                                    with(LocalDensity.current) { mediaBodyHeightPx.toDp() }
                                )
                                // 量不出来：吃满剩余高度（旧的「宁可全高」行为）。
                                mediaBodyUnmeasured -> Modifier.fillMaxHeight()
                                else -> Modifier.height(MediaBodyPlaceholderHeight)
                            }
                        ),
                    html = body.html,
                    dictId = selected?.dictId.orEmpty(),
                    onLookupLink = { word -> onLookupLink.value(word) },
                    onPlayAudio = playAudio,
                    onContentHeight = { px -> if (px > 0) mediaBodyHeightPx = px }
                )
            }
        }
    )
}

@Composable
private fun NotFoundCard(
    state: LookupState.NotFound,
    onImportDictionary: () -> Unit,
    onExternalLookup: (String) -> Unit,
    onAiExplain: ((String) -> Unit)?,
    onSpeak: () -> Unit
) {
    LookupCard(
        header = { WordHeader(word = state.word, onSpeak = onSpeak) },
        body = { MutedText(stringResource(id = R.string.lookup_popup_not_found)) },
        // 按钮固定在底部：词条再长（或者字数再多）也不会被滚走。
        footer = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                TextButton(onClick = onImportDictionary) {
                    StyledText(text = stringResource(id = R.string.lookup_popup_import_dictionary))
                }
                // S6 才接 AI：现在永远是禁用态，但按钮先占位，用户知道后面有这条路。
                TextButton(
                    onClick = { onAiExplain?.invoke(state.word) },
                    enabled = onAiExplain != null
                ) {
                    StyledText(text = stringResource(id = R.string.lookup_popup_ai_explain))
                }
                TextButton(onClick = { onExternalLookup(state.word) }) {
                    StyledText(text = stringResource(id = R.string.lookup_popup_external))
                }
            }
        }
    )
}

@Composable
private fun ErrorCard(state: LookupState.Error, onRetry: () -> Unit, onSpeak: () -> Unit) {
    LookupCard(
        header = { WordHeader(word = state.word, onSpeak = onSpeak) },
        body = { MutedText(stringResource(id = R.string.lookup_popup_error)) },
        footer = {
            TextButton(onClick = onRetry) {
                StyledText(text = stringResource(id = R.string.retry))
            }
        }
    )
}

/** 多词典/多义项命中时的词条选择器：一行放不下就换行，不做横向滚动（滚动会藏起候选）。 */
@Composable
private fun EntryChips(state: LookupState.Success, onSelect: (Int) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        state.entries.forEachIndexed { index, entry ->
            val duplicateWord = state.entries.count { it.word == entry.word } > 1
            val duplicateWordSameDict = state.entries.count {
                it.word == entry.word && it.dictId == entry.dictId
            } > 1
            val preview = entry.preview.lineSequence().firstOrNull().orEmpty().trim()
            val label = when {
                // 同一部词典里同词多义项（常见于导入的 OALD）：用首行释义区分。
                duplicateWordSameDict && preview.isNotEmpty() ->
                    "${entry.word} · ${preview.take(24)}"

                duplicateWordSameDict -> "${entry.word} · #${index + 1}"
                duplicateWord -> "${entry.word} · ${entry.dictName}"
                else -> entry.word
            }
            EntryChip(
                label = label,
                selected = index == state.selectedIndex,
                onClick = { onSelect(index) }
            )
        }
    }
}

/**
 * 词头：词 + 音标 + 来源 + 词典元信息。
 *
 * 音标在 ECDICT 词条里本来是正文第一行的一个灰色 span，跟释义挤在一起；解析出来放在
 * 词头下面，卡片的第一眼就是「词 · 怎么读 · 多重要」。
 */
@Composable
private fun WordHeader(
    word: String,
    phonetic: String? = null,
    source: String? = null,
    meta: LookupMeta? = null,
    onSpeak: () -> Unit
) {
    val palette = LocalReaderPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            StyledText(
                text = word,
                style = MaterialTheme.typography.titleLarge.copy(
                    color = palette.onBar,
                    fontWeight = FontWeight.SemiBold
                )
            )
            val subtitle = listOfNotNull(
                phonetic,
                source?.let { stringResource(id = R.string.lookup_popup_source, it) }
            )
            if (subtitle.isNotEmpty()) {
                StyledText(
                    text = subtitle.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium.copy(color = palette.onBarMuted)
                )
            }
            if (meta != null && !meta.isEmpty) {
                Spacer(Modifier.height(7.dp))
                MetaBadges(meta)
            }
        }
        IconButton(
            modifier = Modifier.size(22.dp),
            icon = Icons.Outlined.VolumeUp,
            contentDescription = R.string.lookup_popup_pronounce,
            disableOnClick = false,
            color = palette.onBarMuted,
            onClick = onSpeak
        )
    }
}

/** 词典元信息小标签：牛津 3000、考试标签、词频、柯林斯星级。 */
@Composable
private fun MetaBadges(meta: LookupMeta) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (meta.oxford) {
            MetaBadge(stringResource(id = R.string.lookup_badge_oxford))
        }
        // 考试标签最多四个：abandon 有 6 个（gk/cet4/cet6/ky/toefl/gre），全列会把头部撑成三行。
        meta.tags
            .sortedBy { tag -> TAG_PRIORITY.indexOf(tag.lowercase()).let { if (it < 0) Int.MAX_VALUE else it } }
            .take(MAX_TAG_BADGES)
            .forEach { tag ->
                tagLabel(tag)?.let { MetaBadge(it) }
            }
        meta.frequencyRank?.let { rank ->
            MetaBadge(
                stringResource(
                    id = R.string.lookup_badge_frequency,
                    NumberFormat.getIntegerInstance().format(rank)
                )
            )
        }
        meta.collins?.let { stars ->
            MetaBadge("★".repeat(stars))
        }
    }
}

/** 考试标签的展示优先级：国内常见的四级/六级/考研在前。 */
private val TAG_PRIORITY = listOf("cet4", "cet6", "ky", "toefl", "ielts", "gre", "gk", "zk")

private const val MAX_TAG_BADGES = 4

@Composable
private fun MetaBadge(text: String) {
    val palette = LocalReaderPalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(palette.accent.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        StyledText(
            text = text,
            maxLines = 1,
            style = MaterialTheme.typography.labelSmall.copy(
                color = palette.accent,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

/** 考试标签 → 本地化文案。未知标签不显示（数据里可能出现新标签）。 */
@Composable
private fun tagLabel(tag: String): String? = when (tag.lowercase()) {
    "zk" -> stringResource(id = R.string.lookup_tag_zk)
    "gk" -> stringResource(id = R.string.lookup_tag_gk)
    "cet4" -> stringResource(id = R.string.lookup_tag_cet4)
    "cet6" -> stringResource(id = R.string.lookup_tag_cet6)
    "ky" -> stringResource(id = R.string.lookup_tag_ky)
    "toefl" -> stringResource(id = R.string.lookup_tag_toefl)
    "ielts" -> stringResource(id = R.string.lookup_tag_ielts)
    "gre" -> stringResource(id = R.string.lookup_tag_gre)
    else -> null
}

/** 结构化正文：义项 → 英文释义 → 词形附注。 */
@Composable
private fun SectionsBody(
    body: LookupBody.Sections,
    linkStyles: TextLinkStyles,
    onLookupLink: State<(String) -> Unit>
) {
    val palette = LocalReaderPalette.current
    Column(Modifier.fillMaxWidth()) {
        body.senses.forEach { sense ->
            SenseRow(sense = sense, linkStyles = linkStyles, onLookupLink = onLookupLink)
        }
        if (body.definitions.isNotEmpty()) {
            if (body.senses.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = palette.onBar.copy(alpha = 0.08f))
                Spacer(Modifier.height(8.dp))
            }
            StyledText(
                text = stringResource(id = R.string.lookup_popup_definitions),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = palette.onBarMuted,
                    fontWeight = FontWeight.Medium
                )
            )
            Spacer(Modifier.height(4.dp))
            body.definitions.forEach { definition ->
                Row(Modifier.fillMaxWidth()) {
                    if (definition.pos != null) {
                        // 浅灰底 + 粗体的小徽标：一眼能看出是词性，不是释义正文。
                        DefinitionPosBadge(definition.pos)
                    } else {
                        StyledText(
                            text = "·",
                            style = MaterialTheme.typography.bodySmall.copy(color = palette.onBarMuted)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    HtmlParagraph(
                        modifier = Modifier.weight(1f),
                        html = definition.html,
                        style = MaterialTheme.typography.bodySmall.copy(color = palette.onBarMuted),
                        linkStyles = linkStyles,
                        onLookupLink = onLookupLink
                    )
                }
            }
        }
        if (body.notes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            body.notes.forEach { note ->
                HtmlParagraph(
                    html = note,
                    style = MaterialTheme.typography.labelMedium.copy(color = palette.onBarMuted),
                    linkStyles = linkStyles,
                    onLookupLink = onLookupLink
                )
            }
        }
    }
}

/**
 * 词条候选 chip。不用 Material3 的 `FilterChip`：它固定 32dp 高，5 个前缀候选换行后
 * 就把固定头部撑掉半个卡片；这里做成 ~24dp 的紧凑胶囊，换行也不至于挤走正文。
 */
@Composable
private fun EntryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalReaderPalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) {
                    palette.accent.copy(alpha = 0.18f)
                } else {
                    palette.onBar.copy(alpha = 0.06f)
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        StyledText(
            text = label,
            maxLines = 1,
            style = MaterialTheme.typography.labelMedium.copy(
                color = if (selected) palette.accent else palette.onBarMuted,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            )
        )
    }
}

/** 英文释义的词性徽标（`n.` / `v.` / `a.` …）：浅灰底 + 粗体，宽度不超过常见缩写。 */
@Composable
private fun DefinitionPosBadge(pos: String) {
    val palette = LocalReaderPalette.current
    Box(
        modifier = Modifier
            .widthIn(min = 26.dp)
            .padding(top = 1.dp, end = 6.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(palette.onBar.copy(alpha = 0.08f))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
            StyledText(
                text = pos,
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = palette.onBarMuted,
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
    }
}

/** 一条义项：左侧固定宽度的词性/领域标签 + 右侧释义（左边缘对齐）。 */
@Composable
private fun SenseRow(
    sense: LookupSense,
    linkStyles: TextLinkStyles,
    onLookupLink: State<(String) -> Unit>
) {
    val palette = LocalReaderPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SenseSpacing / 2),
        verticalAlignment = Alignment.Top
    ) {
        if (sense.label != null) {
            StyledText(
                modifier = Modifier
                    // 只设下限：`[美国英语]` 这类长领域标签不能截断，词性缩写仍然对齐。
                    .widthIn(min = SenseLabelWidth)
                    .padding(top = 2.dp, end = 6.dp),
                text = sense.label,
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = palette.accent,
                    fontWeight = FontWeight.Medium
                )
            )
        }
        HtmlParagraph(
            modifier = Modifier.weight(1f),
            html = sense.html,
            style = MaterialTheme.typography.bodyMedium.copy(color = palette.onBar),
            linkStyles = linkStyles,
            onLookupLink = onLookupLink
        )
    }
}

/**
 * 一行词条 HTML → 可点击的 `AnnotatedString`。
 *
 * `remember` 只按 HTML 与链接样式失效；回调通过 [rememberUpdatedState] 的稳定 State 读取，
 * 所以父级重组不会让整段正文重新解析。
 */
@Composable
private fun HtmlParagraph(
    html: String,
    style: TextStyle,
    linkStyles: TextLinkStyles,
    onLookupLink: State<(String) -> Unit>,
    modifier: Modifier = Modifier
) {
    val annotated = remember(html, linkStyles) {
        // 只闭包住稳定的 State 对象，点击时再读 `.value`：回调换了也不会拿到旧的。
        parseLookupHtml(html = html, linkStyles = linkStyles) { url ->
            onLookupLink.value(url)
        }
    }
    if (annotated != null) {
        StyledText(modifier = modifier, text = annotated, style = style)
    } else {
        // 解析失败退化成纯文本，不留空行。
        StyledText(modifier = modifier, text = plainText(html), style = style)
    }
}

@Composable
private fun MutedText(text: String) {
    StyledText(
        modifier = Modifier.fillMaxWidth(),
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(
            color = LocalReaderPalette.current.onBarMuted
        )
    )
}

/**
 * 词条 HTML → AnnotatedString。`juice://lookup/` 交给 [onLink] 继续查，其余链接
 * （外部 URL）在解析阶段就被吞掉：卡片里点外链会把用户带出阅读页，本期不需要。
 *
 * @return null 表示解析失败，调用方退化成纯文本预览。
 */
private fun parseLookupHtml(
    html: String,
    linkStyles: TextLinkStyles,
    onLink: (String) -> Unit
): AnnotatedString? {
    if (html.isBlank()) return null
    return try {
        AnnotatedString.fromHtml(
            htmlString = html,
            linkStyles = linkStyles,
            linkInteractionListener = { link ->
                val url = (link as? LinkAnnotation.Url)?.url
                if (url != null) lookupWordFromLink(url)?.let(onLink)
            }
        )
    } catch (e: Exception) {
        null
    }
}

/**
 * 卡片朝哪一侧展开，以及那一侧还剩多少空间（px）。
 *
 * 只取决于**词在屏幕上的位置**，与卡片尺寸无关 —— 这是「点击那一刻就定方向」的前提：
 * 卡片之后怎么长高，方向都不会变，所以看到的是「朝一侧长出来」，不是「先朝下再翻上去」。
 *
 * 竖屏尺寸下（安全区高 ≥ 2000 px）两侧取大者总大于 45% 的高度上限（[PopupMaxHeightFraction]），
 * 上限先起作用、贴词那条边不会被夹。横屏或分屏时安全区只有 ~1000 px，45% 比该侧剩余空间还大，
 * 此时 [lookupPopupMaxHeightPx] 会按该侧空间收紧 —— 卡片变矮（正文可滚），贴词边仍然不动。
 */
internal data class LookupPopupPlacement(
    /** 卡片在词的下方（true）还是上方（false）。 */
    val below: Boolean,
    /** 该侧还剩多少可用高度（px），已扣掉边距与贴词间隙。 */
    val availableSpace: Int
)

internal fun lookupPopupPlacement(
    anchor: Rect?,
    safeBounds: IntRect,
    margin: Int,
    gap: Int
): LookupPopupPlacement {
    if (anchor == null) {
        // 没有锚点（整词卡片之类）：居中显示，方向不影响结果。
        return LookupPopupPlacement(below = true, availableSpace = safeBounds.height - margin * 2)
    }
    val spaceBelow = safeBounds.bottom - margin - (anchor.bottom.roundToInt() + gap)
    val spaceAbove = (anchor.top.roundToInt() - gap) - (safeBounds.top + margin)
    // 相等时朝下：正文是从词往下读的。
    return if (spaceBelow >= spaceAbove) {
        LookupPopupPlacement(below = true, availableSpace = spaceBelow)
    } else {
        LookupPopupPlacement(below = false, availableSpace = spaceAbove)
    }
}

/**
 * 卡片高度上限（px）= min(设计上限, 该侧剩余空间)。
 *
 * 因为贴词那条边是钉住的，上限必须按**这一侧**的空间算：否则卡片长到一半被屏幕边缘夹回来，
 * 钉住的边就动了（这正是「跳一下」的来源之一）。
 */
internal fun lookupPopupMaxHeightPx(
    safeBounds: IntRect,
    placement: LookupPopupPlacement
): Int {
    val cap = (safeBounds.height * PopupMaxHeightFraction).roundToInt()
    return if (placement.availableSpace > 0) minOf(cap, placement.availableSpace) else cap
}

/**
 * 卡片位置。
 *
 * **贴词的那条边固定**，另一条边跟着高度走：
 *  - 在词下方 → 顶边钉在「词底 + 间隙」，卡片只能向下长；
 *  - 在词上方 → 底边钉在「词顶 − 间隙」，卡片只能向上长。
 *
 * 这就是「结果到达后卡片按内容高度调整」不跳的原因：调整时动的永远是远离词的那条边，
 * 而它旁边是空白的正文，用户视线所在的词一侧丝毫不动。
 *
 * 方向由 [lookupPopupPlacement] 决定（只按空间大小），不按「这次放不放得下」判断 ——
 * 后者会让卡片随内容高度翻面。
 */
internal fun lookupPopupPosition(
    anchor: Rect?,
    safeBounds: IntRect,
    popupSize: IntSize,
    margin: Int,
    gap: Int
): IntOffset {
    val minX = safeBounds.left + margin
    val maxX = (safeBounds.right - popupSize.width - margin).coerceAtLeast(minX)
    val minY = safeBounds.top + margin
    val maxY = (safeBounds.bottom - popupSize.height - margin).coerceAtLeast(minY)

    if (anchor == null) {
        return IntOffset(
            x = ((safeBounds.left + safeBounds.right - popupSize.width) / 2).coerceIn(minX, maxX),
            y = ((safeBounds.top + safeBounds.bottom - popupSize.height) / 2).coerceIn(minY, maxY)
        )
    }

    val x = (anchor.center.x - popupSize.width / 2f).roundToInt().coerceIn(minX, maxX)
    val placement = lookupPopupPlacement(anchor, safeBounds, margin, gap)
    val y = if (placement.below) {
        (anchor.bottom.roundToInt() + gap).coerceIn(minY, maxY)
    } else {
        (anchor.top.roundToInt() - gap - popupSize.height).coerceIn(minY, maxY)
    }
    return IntOffset(x, y)
}
