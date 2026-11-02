package com.example.myapplication111.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

enum class Screen { MAIN, SETTINGS, FAVORITES, FAVORITE_DETAIL, FAVORITE_EDIT, VOCABULARY }

@Composable
fun AppNavigation() {
    // 用屏幕枚举管理导航，可容纳更多页面
    var currentScreen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    // 状态提升：文本内容保存在导航层，rememberSaveable 保证切换页面/配置变化后仍保留
    var textContent by rememberSaveable { mutableStateOf("") }
    // 当前选中的收藏项 id，用于传递给详情页
    var selectedFavoriteId by rememberSaveable { mutableStateOf<String?>(null) }

    // 子页面按返回键回到上一级（编辑页回播放页、播放页回收藏列表），而不是退出 App
    BackHandler(enabled = currentScreen != Screen.MAIN) {
        currentScreen = when (currentScreen) {
            Screen.FAVORITE_EDIT -> Screen.FAVORITE_DETAIL
            Screen.FAVORITE_DETAIL -> Screen.FAVORITES
            else -> Screen.MAIN
        }
    }

    when (currentScreen) {
        Screen.MAIN -> MainScreen(
            onOpenSettings = { currentScreen = Screen.SETTINGS },
            onOpenFavorites = { currentScreen = Screen.FAVORITES },
            onOpenVocabulary = { currentScreen = Screen.VOCABULARY },
            textContent = textContent,
            onTextChange = { textContent = it }
        )
        Screen.SETTINGS -> SettingsScreen(onBack = { currentScreen = Screen.MAIN })
        Screen.FAVORITES -> FavoritesScreen(
            onBack = { currentScreen = Screen.MAIN },
            onOpenDetail = { id ->
                selectedFavoriteId = id
                currentScreen = Screen.FAVORITE_DETAIL
            }
        )
        // 播放页只负责播：逐句高光 + 进度条，右上角编辑按钮进入编辑页
        Screen.FAVORITE_DETAIL -> FavoritePlayerScreen(
            favoriteId = selectedFavoriteId,
            onBack = { currentScreen = Screen.FAVORITES },
            onEdit = { currentScreen = Screen.FAVORITE_EDIT }
        )
        Screen.FAVORITE_EDIT -> FavoriteEditScreen(
            favoriteId = selectedFavoriteId,
            onBack = { currentScreen = Screen.FAVORITE_DETAIL }
        )
        Screen.VOCABULARY -> VocabularyScreen(onBack = { currentScreen = Screen.MAIN })
    }
}
