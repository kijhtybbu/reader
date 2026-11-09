/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：收藏页入口（主页面右上角菜单里的「收藏」）。
 *
 * 这里把三个 ViewModel 拼在一起：
 *  - [FavoritesModel]：收藏列表、标签、句子详情与补译文；
 *  - [DictionaryLookupModel]：单词行点击后复用阅读页同一套取词卡片；
 *  - [WordSpeaker]：单词行的发音按钮（系统 TTS，没有语音包时弹安装提示）。
 *
 * `LocalReaderPalette` 用 App 主题颜色派生后注入收藏页：取词卡片与句子详情都读它，
 * 不注入的话会拿到 `ReaderPalette.Default`，深色主题下弹出一块亮白卡片。
 */

package ua.acclorite.book_story.presentation.favorites

import android.content.Intent
import android.os.Parcelable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf
import kotlinx.parcelize.Parcelize
import ua.acclorite.book_story.R
import ua.acclorite.book_story.presentation.navigator.Screen
import ua.acclorite.book_story.presentation.reader.DictionaryLookupModel
import ua.acclorite.book_story.presentation.reader.LookupState
import ua.acclorite.book_story.presentation.settings.DictionaryManagerScreen
import ua.acclorite.book_story.ui.common.helpers.LocalActivity
import ua.acclorite.book_story.ui.common.helpers.launchActivity
import ua.acclorite.book_story.ui.common.helpers.showToast
import ua.acclorite.book_story.ui.favorites.FavoritesContent
import ua.acclorite.book_story.ui.navigator.LocalNavigator
import ua.acclorite.book_story.ui.reader.TtsUnavailableDialog
import ua.acclorite.book_story.ui.reader.WordSpeaker
import ua.acclorite.book_story.ui.reader.favoriteWordDraft
import ua.acclorite.book_story.ui.reader.openTtsVoiceInstaller
import ua.acclorite.book_story.ui.theme.LocalReaderPalette
import ua.acclorite.book_story.ui.theme.readerPalette

@Parcelize
object FavoritesScreen : Screen, Parcelable {

    @Composable
    override fun Content() {
        val model: FavoritesModel = hiltViewModel()
        val lookupModel: DictionaryLookupModel = hiltViewModel()
        val navigator = LocalNavigator.current
        val activity = LocalActivity.current
        val context = LocalContext.current

        val words by model.words.collectAsStateWithLifecycle()
        val sentences by model.sentences.collectAsStateWithLifecycle()
        val tab by model.tab.collectAsStateWithLifecycle()
        val sentenceDialog by model.sentenceDialog.collectAsStateWithLifecycle()
        val lookupState by lookupModel.state.collectAsStateWithLifecycle()

        var ttsUnavailable by remember { mutableStateOf(false) }
        val speaker = remember(context) { WordSpeaker(context) }
        DisposableEffect(speaker) {
            onDispose {
                speaker.shutdown()
                // 收藏页退出时关掉取词卡片/句子详情：两个模型都是 Activity 作用域，
                // 不主动收会把「离开前开着的弹层」带回下一次进入。
                lookupModel.dismiss()
                model.closeSentence()
            }
        }

        // 取词卡片头部五角星：订阅当前命中词。词换了就换一条 Flow。
        val lookupWord = (lookupState as? LookupState.Success)?.word
        val lookupFavoriteFlow = remember(lookupWord) {
            if (lookupWord.isNullOrBlank()) flowOf(false) else model.observeWord(lookupWord)
        }
        val lookupFavorite by lookupFavoriteFlow.collectAsStateWithLifecycle(initialValue = false)

        val surface = MaterialTheme.colorScheme.surface
        val onSurface = MaterialTheme.colorScheme.onSurface
        val primary = MaterialTheme.colorScheme.primary
        val palette = remember(surface, onSurface, primary) {
            readerPalette(
                background = surface,
                onBackground = onSurface,
                accentSeed = primary
            )
        }

        CompositionLocalProvider(LocalReaderPalette provides palette) {
            FavoritesContent(
                tab = tab,
                words = words,
                sentences = sentences,
                onSelectTab = model::selectTab,
                onBack = { navigator.pop() },
                onWordClick = { word, bounds -> lookupModel.lookup(word.word, bounds) },
                onSpeakWord = { word -> speaker.speak(word) { ttsUnavailable = true } },
                onSentenceClick = { sentence -> model.openSentence(sentence.text) },
                lookupState = lookupState,
                onLookupSelect = lookupModel::select,
                onLookupLink = { word -> lookupModel.lookup(word, lookupState.anchorOrNull()) },
                onLookupRetry = lookupModel::retry,
                onLookupDismiss = lookupModel::dismiss,
                onImportDictionary = {
                    lookupModel.dismiss()
                    navigator.push(DictionaryManagerScreen)
                },
                // 与阅读页未找到卡片的「用其他应用查询」一致：ACTION_PROCESS_TEXT，失败再开 onelook。
                onExternalLookup = { word ->
                    lookupModel.dismiss()
                    run {
                        val dictionaryIntent = Intent().apply {
                            type = "text/plain"
                            action = Intent.ACTION_PROCESS_TEXT
                            putExtra(Intent.EXTRA_PROCESS_TEXT, word.trim())
                            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                        }
                        val browserIntent = Intent(
                            Intent.ACTION_VIEW,
                            "https://www.onelook.com/?w=${word.trim().replace(" ", "+")}".toUri()
                        )
                        dictionaryIntent.launchActivity(
                            activity = activity,
                            createChooser = true,
                            success = { return@run }
                        )
                        browserIntent.launchActivity(
                            activity = activity,
                            success = { return@run }
                        )
                        activity.getString(R.string.error_no_dictionary)
                            .showToast(context = activity, longToast = false)
                    }
                },
                onTtsUnavailable = { ttsUnavailable = true },
                onAudioFile = { dictId, path -> lookupModel.audioFile(context, dictId, path) },
                isLookupFavorite = lookupFavorite,
                onToggleLookupFavorite = {
                    (lookupState as? LookupState.Success)?.let { success ->
                        model.toggleWord(favoriteWordDraft(success))
                    }
                },
                sentenceDialog = sentenceDialog,
                onRetryTranslation = model::retryTranslation,
                onCloseSentence = model::closeSentence,
                onRemoveSentence = model::removeOpenSentence
            )
        }

        if (ttsUnavailable) {
            TtsUnavailableDialog(
                onDismiss = { ttsUnavailable = false },
                onOpenInstaller = {
                    ttsUnavailable = false
                    openTtsVoiceInstaller(context)
                }
            )
        }
    }
}

/** 取词卡片的锚点：只有 Success 才带；其余状态没有可读的锚点，居中即可。 */
private fun LookupState.anchorOrNull() = (this as? LookupState.Success)?.anchor
